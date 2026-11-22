package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.syskata.api.foundation.ApiFailure;

@ApplicationScoped
public class LabService {
  @Inject ContentCatalog catalog;
  @Inject AgroalDataSource database;

  @ConfigProperty(name = "syskata.labs.worker-subject", defaultValue = "unconfigured")
  String worker;

  JsonNode create(Connection c, UUID learner, JsonNode body) throws Exception {
    UUID session = UUID.fromString(body.path("session_id").asText());
    var state = new AttemptState(c, learner, session);
    UUID attempt = UUID.fromString(body.path("attempt_id").asText());
    state.open(c, attempt, body);
    var question = state.current().path("question");
    if (!question.path("type").asText().equals("lab_task")) throw new ApiFailure(409, "ineligible");
    var bundle = catalog.pinned(state.version);
    String scenario =
        LabProfile.scenario(question, bundle.assessment(question.path("id").asText()));
    UUID instance = UUID.fromString(body.path("exercise_instance_id").asText());
    try (var q =
            LabSql.query(
                c,
                "SELECT id FROM lab_sessions WHERE instance_id=? AND (status NOT IN ('closed','expired','infrastructure_error') OR NOT cleanup_complete)",
                instance);
        var r = q.executeQuery()) {
      if (r.next()) throw new ApiFailure(409, "ineligible");
    }
    UUID id = UUID.randomUUID();
    LabSql.update(
        c,
        "INSERT INTO lab_sessions(id,learner_id,session_id,instance_id,status,scenario) VALUES (?,?,?,?,'provisioning',?)",
        id,
        learner,
        session,
        instance,
        scenario);
    LabState.enqueue(
        c,
        learner,
        "provision",
        UUID.randomUUID(),
        JsonData.tree(
            Map.of(
                "key",
                id.toString(),
                "id",
                id.toString(),
                "subject",
                learner.toString(),
                "scenario",
                scenario)));
    state.save(c);
    return LabRecord.owned(c, learner, id).response(state.revision);
  }

  JsonNode change(Connection c, UUID learner, UUID id, JsonNode body, boolean reset)
      throws Exception {
    var lab = LabRecord.owned(c, learner, id);
    var state = new AttemptState(c, learner, lab.session());
    // Closing a completed checkpoint remains possible, but still checks its revision.
    if (body.path("session_revision").asLong() != state.revision)
      throw new ApiFailure(409, "stale_revision", state.revision);
    lab.generation(body.path("generation").asLong());
    if (unchanged(lab, state, body, reset)) return lab.response(state.revision);
    LabState.cancel(c, lab, "superseded");
    LabSql.update(
        c,
        "UPDATE attempts SET status='cancelled' WHERE instance_id=? AND status='open'",
        lab.instance());
    long generation = lab.generation() + (reset ? 1 : 0);
    LabSql.update(
        c,
        "UPDATE lab_sessions SET generation=?,status=?,cleanup_complete=false WHERE id=?",
        generation,
        reset ? "resetting" : "closing",
        id);
    UUID job = UUID.randomUUID();
    var payload =
        JsonData.tree(
            Map.of(
                "id",
                id.toString(),
                "key",
                job.toString(),
                "subject",
                learner.toString(),
                "revision",
                lab.remoteRevision(),
                "generation",
                lab.generation()));
    LabState.enqueue(c, learner, reset ? "reset" : "close", job, payload);
    state.save(c);
    return LabRecord.owned(c, learner, id).response(state.revision);
  }

  private boolean unchanged(LabRecord lab, AttemptState state, JsonNode body, boolean reset) {
    if (reset) {
      state.validate(body);
      lab.ready();
      if (lab.remoteRevision() == 0) throw new ApiFailure(409, "operation_pending");
      return false;
    }
    return java.util.Set.of("closed", "closing").contains(lab.status());
  }

  JsonNode assess(Connection c, UUID learner, UUID id, JsonNode body) throws Exception {
    if (worker.equals("unconfigured")) throw new ApiFailure(503, "infrastructure_unavailable");
    var lab = LabRecord.owned(c, learner, id);
    var state = new AttemptState(c, learner, lab.session());
    lab.generation(body.path("generation").asLong());
    lab.ready();
    UUID attempt = UUID.fromString(body.path("attempt_id").asText());
    JsonNode current = state.open(c, attempt, body);
    if (!current.path("exercise_instance_id").asText().equals(lab.instance().toString()))
      throw new ApiFailure(409, "result_mismatch");
    Bundle bundle = catalog.pinned(state.version);
    LabProfile.scenario(
        state.current().path("question"),
        bundle.assessment(state.current().path("question").path("id").asText()));
    UUID assessment = UUID.randomUUID();
    JsonNode dispatch = LabState.dispatch(lab, state, attempt, assessment, bundle);
    LabSql.update(
        c,
        "INSERT INTO assessments(id,lab_id,instance_id,session_id,learner_id,attempt_id,generation,assigned_worker,status,dispatch) VALUES (?,?,?,?,?,?,?,?,'pending',?::jsonb)",
        assessment,
        id,
        lab.instance(),
        lab.session(),
        learner,
        attempt,
        lab.generation(),
        worker,
        dispatch.toString());
    LabSql.update(c, "UPDATE attempts SET status='awaiting_assessment' WHERE id=?", attempt);
    LabState.enqueue(c, learner, "assess", assessment, dispatch);
    state.save(c);
    return JsonData.tree(
        Map.of(
            "assessment_id",
            assessment.toString(),
            "lab_session_id",
            id.toString(),
            "generation",
            lab.generation(),
            "status",
            "pending",
            "status_url",
            "/v1/assessments/" + assessment,
            "session_revision",
            state.revision));
  }

  public JsonNode read(UUID learner, UUID id, boolean assessment) throws Exception {
    try (var c = database.getConnection()) {
      c.setAutoCommit(false);
      LearningStore.lockLearner(c, learner);
      JsonNode result;
      if (assessment) result = assessment(c, learner, id);
      else {
        var lab = LabRecord.owned(c, learner, id);
        var state = new AttemptState(c, learner, lab.session());
        expire(c, lab, state);
        result = LabRecord.owned(c, learner, id).response(state.revision);
      }
      c.commit();
      return result;
    }
  }

  static void expire(Connection c, LabRecord lab, AttemptState state) throws Exception {
    if (!lab.expired() || java.util.Set.of("closed", "expired", "closing").contains(lab.status()))
      return;
    LabState.cancel(c, lab, "expired");
    LabSql.update(c, "UPDATE lab_sessions SET status='expired' WHERE id=?", lab.id());
    // Expiry invalidates an open attempt as well as a frozen assessment.
    LabSql.update(
        c,
        "UPDATE attempts SET status='cancelled' WHERE instance_id=? AND status='open'",
        lab.instance());
    state.save(c);
  }

  private JsonNode assessment(Connection c, UUID learner, UUID id) throws Exception {
    try (var q =
            LabSql.query(
                c,
                "SELECT lab_id,attempt_id,generation,status,result FROM assessments WHERE id=? AND learner_id=?",
                id,
                learner);
        var r = q.executeQuery()) {
      if (!r.next()) throw new ApiFailure(404, "not_found");
      var lab = LabRecord.owned(c, learner, r.getObject(1, UUID.class));
      var state = new AttemptState(c, learner, lab.session());
      expire(c, lab, state);
      String status = r.getString(4);
      if (status.equals("pending") && lab.expired()) status = "expired";
      var result = JsonData.object();
      result.put("assessment_id", id.toString());
      result.put("lab_session_id", lab.id().toString());
      result.put("attempt_id", r.getString(2));
      result.put("generation", r.getLong(3));
      result.put("status", status);
      if (status.equals("completed")) result.set("result", JsonData.parse(r.getString(5)));
      else if (!status.equals("pending"))
        result.set(
            "error",
            LabState.error(
                status.equals("infrastructure_error")
                    ? "infrastructure_unavailable"
                    : status.equals("expired") ? "expired" : "generation_mismatch"));
      return result;
    }
  }
}
