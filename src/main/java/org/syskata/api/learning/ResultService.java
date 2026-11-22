package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.syskata.api.foundation.Accounts;
import org.syskata.api.foundation.ApiFailure;

@ApplicationScoped
public class ResultService {
  @Inject AgroalDataSource database;
  @Inject ContentCatalog catalog;
  @Inject Accounts accounts;

  public JsonNode receive(UUID id, String worker, JsonNode body) throws Exception {
    try (var c = database.getConnection()) {
      c.setAutoCommit(false);
      try {
        UUID learner = owner(c, id, worker);
        LearningStore.lockLearner(c, learner);
        try (var q = LabSql.query(c, "SELECT issuer,subject FROM learners WHERE id=?", learner);
            var r = q.executeQuery()) {
          r.next();
          accounts.ensureNotDeleted(r.getString(1), r.getString(2));
        }
        JsonNode receipt = apply(c, learner, id, body);
        c.commit();
        return receipt;
      } catch (Exception failure) {
        c.rollback();
        throw failure;
      }
    }
  }

  private UUID owner(Connection c, UUID id, String worker) throws Exception {
    try (var q =
            LabSql.query(c, "SELECT learner_id,assigned_worker FROM assessments WHERE id=?", id);
        var r = q.executeQuery()) {
      if (!r.next()) throw new ApiFailure(404, "not_found");
      if (!r.getString(2).equals(worker)) throw new ApiFailure(403, "forbidden");
      return r.getObject(1, UUID.class);
    }
  }

  private JsonNode apply(Connection c, UUID learner, UUID id, JsonNode body) throws Exception {
    try (var q =
            LabSql.query(
                c,
                "SELECT lab_id,session_id,attempt_id,generation,status,dispatch,callback,created_at FROM assessments WHERE id=?",
                id);
        var r = q.executeQuery()) {
      r.next();
      var state = new AttemptState(c, learner, r.getObject(2, UUID.class));
      var lab = LabRecord.owned(c, learner, r.getObject(1, UUID.class));
      var dispatch = JsonData.parse(r.getString(6));
      Bundle bundle = catalog.pinned(dispatch.path("versions").path("bundle_version").asText());
      JsonNode question;
      try (var instance =
              LabSql.query(
                  c, "SELECT presentation FROM exercise_instances WHERE id=?", lab.instance());
          var row = instance.executeQuery()) {
        row.next();
        question = JsonData.parse(row.getString(1));
      }
      TrustedResult.validate(body, dispatch, bundle.assessment(question.path("id").asText()));
      if (r.getString(7) != null) {
        if (!JsonData.parse(r.getString(7)).equals(body))
          throw new ApiFailure(409, "result_conflict");
        return receipt(id, "duplicate");
      }
      validateTime(body, lab, r.getTimestamp(8).toInstant());
      String disposition = disposition(lab, r.getLong(4), r.getString(5));
      if (disposition.equals("applied")) {
        UUID attempt = r.getObject(3, UUID.class);
        validateCurrent(c, state, lab, attempt);
        applyCurrent(c, state, bundle, id, attempt, body);
      } else {
        LabSql.update(
            c,
            "UPDATE assessments SET status=? WHERE id=? AND status='pending'",
            disposition.equals("ignored_expired") ? "expired" : "superseded",
            id);
        LabSql.update(
            c,
            "UPDATE attempts SET status='cancelled' WHERE id=? AND status='awaiting_assessment'",
            r.getObject(3, UUID.class));
      }
      LabSql.update(
          c,
          "UPDATE assessments SET callback=?::jsonb,disposition=? WHERE id=?",
          body.toString(),
          disposition,
          id);
      return receipt(id, disposition);
    }
  }

  private void validateTime(JsonNode body, LabRecord lab, Instant created) {
    Instant observed = TrustedResult.observed(body);
    if (lab.expiry() == null
        || observed.isBefore(created)
        || observed.isAfter(Instant.now())
        || !observed.isBefore(lab.expiry())) throw new ApiFailure(409, "result_mismatch");
  }

  private void validateCurrent(Connection c, AttemptState state, LabRecord lab, UUID attempt)
      throws Exception {
    if (!state.status.equals("active")
        || !state.current().path("exercise_instance_id").asText().equals(lab.instance().toString()))
      throw new ApiFailure(409, "result_mismatch");
    if (!state
        .attempt(c, attempt)
        .path("attempt")
        .path("status")
        .asText()
        .equals("awaiting_assessment")) throw new ApiFailure(409, "result_mismatch");
  }

  private String disposition(LabRecord lab, long generation, String status) {
    if (java.util.Set.of("superseded", "infrastructure_error").contains(status)
        || lab.generation() != generation
        || java.util.Set.of("closing", "closed", "resetting").contains(lab.status()))
      return "ignored_superseded";
    if (status.equals("expired") || expired(lab)) return "ignored_expired";
    if (!status.equals("pending") || !lab.status().equals("ready"))
      throw new ApiFailure(409, "result_conflict");
    return "applied";
  }

  private boolean expired(LabRecord lab) {
    return lab.expired() || lab.status().equals("expired");
  }

  private void applyCurrent(
      Connection c, AttemptState state, Bundle bundle, UUID id, UUID attempt, JsonNode body)
      throws Exception {
    if (body.path("outcome").asText().equals("infrastructure_error")) {
      LabSql.update(c, "UPDATE assessments SET status='infrastructure_error' WHERE id=?", id);
      LabSql.update(c, "UPDATE attempts SET status='cancelled' WHERE id=?", attempt);
      state.save(c);
    } else {
      JsonNode result = LabGrade.apply(c, state, bundle, attempt, body);
      LabSql.update(
          c,
          "UPDATE assessments SET status='completed',result=?::jsonb WHERE id=?",
          result.toString(),
          id);
    }
  }

  private JsonNode receipt(UUID id, String disposition) {
    return JsonData.tree(Map.of("assessment_id", id.toString(), "disposition", disposition));
  }
}
