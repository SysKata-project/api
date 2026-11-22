package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.syskata.api.foundation.ApiFailure;

@ApplicationScoped
public class AttemptService {
  private final ContentCatalog catalog;
  private final AgroalDataSource database;

  public AttemptService(ContentCatalog catalog, AgroalDataSource database) {
    this.catalog = catalog;
    this.database = database;
  }

  public JsonNode read(UUID learner, UUID session, UUID attempt) throws Exception {
    try (var c = database.getConnection()) {
      c.setAutoCommit(false);
      JsonNode result = new AttemptState(c, learner, session).attempt(c, attempt);
      c.commit();
      return result;
    }
  }

  JsonNode start(Connection c, UUID learner, UUID session, JsonNode body) throws Exception {
    AttemptState state = new AttemptState(c, learner, session);
    state.validate(body);
    try (var q =
        c.prepareStatement(
            "SELECT id FROM attempts WHERE session_id=? AND status IN ('open','awaiting_assessment')")) {
      q.setObject(1, session);
      try (var rows = q.executeQuery()) {
        if (rows.next())
          return started(state, state.attempt(c, rows.getObject(1, UUID.class)).path("attempt"));
      }
    }
    UUID attempt = UUID.randomUUID();
    try (var q =
        c.prepareStatement(
            "INSERT INTO attempts(id,instance_id,session_id,learner_id,status) VALUES (?,?,?,?,'open')")) {
      q.setObject(1, attempt);
      q.setObject(2, UUID.fromString(body.path("exercise_instance_id").asText()));
      q.setObject(3, session);
      q.setObject(4, learner);
      q.executeUpdate();
    }
    state.save(c);
    return started(state, state.attempt(c, attempt).path("attempt"));
  }

  private JsonNode started(AttemptState state, JsonNode attempt) {
    return JsonData.tree(Map.of("attempt", attempt, "session_revision", state.revision));
  }

  JsonNode assistance(
      Connection c, UUID learner, UUID session, UUID id, JsonNode body, boolean reveal)
      throws Exception {
    AttemptState state = new AttemptState(c, learner, session);
    JsonNode attempt = state.open(c, id, body);
    JsonNode trusted =
        catalog.pinned(state.version).trusted(state.current().path("question").path("id").asText());
    String text = assistanceText(trusted, body, reveal);
    ObjectNode assistance = (ObjectNode) attempt.path("assistance");
    var hints = JsonData.strings(assistance.path("hint_ids"));
    boolean changed =
        reveal
            ? !assistance.path("revealed").asBoolean()
            : !hints.contains(body.path("hint_id").asText());
    if (changed) {
      if (reveal) assistance.put("revealed", true);
      else {
        hints.add(body.path("hint_id").asText());
        assistance.set("hint_ids", JsonData.tree(hints));
      }
      try (var q =
          c.prepareStatement(
              "UPDATE attempts SET hints=?::jsonb,revealed=?,assisted=true WHERE id=?")) {
        q.setString(1, assistance.path("hint_ids").toString());
        q.setBoolean(2, assistance.path("revealed").asBoolean());
        q.setObject(3, id);
        q.executeUpdate();
      }
      state.save(c);
    }
    return JsonData.tree(
        Map.of(
            "attempt_id",
            id.toString(),
            "assistance",
            assistance,
            "text_markdown",
            text,
            "session_revision",
            state.revision));
  }

  private String assistanceText(JsonNode trusted, JsonNode body, boolean reveal) {
    if (reveal) return trusted.path("assessment").path("reveal_markdown").asText();
    for (JsonNode hint : trusted.path("hints"))
      if (hint.path("id").equals(body.path("hint_id"))) return hint.path("text").asText();
    throw new ApiFailure(400, "invalid_request");
  }

  JsonNode submit(Connection c, UUID learner, UUID session, UUID id, JsonNode body)
      throws Exception {
    AttemptState state = new AttemptState(c, learner, session);
    JsonNode attempt = state.open(c, id, body);
    Bundle bundle = catalog.pinned(state.version);
    JsonNode question = state.current().path("question");
    JsonNode assessment = bundle.assessment(question.path("id").asText());
    boolean correct = TextGrading.evaluate(question, assessment, body.path("answer"));
    JsonNode assistance = attempt.path("assistance");
    boolean assisted =
        assistance.path("revealed").asBoolean() || !assistance.path("hint_ids").isEmpty();
    Instant time = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    ObjectNode result = result(bundle, question, assessment, attempt, correct, assisted);
    GradeProgress.evidence(c, learner, question, correct, assisted, time);
    var reviewRewards = ReviewProgress.apply(c, state, question, correct, assisted, time);
    String next = AttemptAdvance.apply(c, state, bundle, correct);
    reviewRewards.addAll(GradeProgress.reward(c, state, time));
    result.set("rewards", reviewRewards);
    result.set("progress", GradeProgress.snapshot(c, learner, time));
    state.save(c);
    result.put("session_revision", state.revision);
    result.set(
        "next_action", JsonData.tree(Map.of("type", next, "session_id", session.toString())));
    persist(c, id, body.path("answer"), result, time);
    return result;
  }

  private ObjectNode result(
      Bundle bundle,
      JsonNode question,
      JsonNode assessment,
      JsonNode attempt,
      boolean correct,
      boolean assisted) {
    ObjectNode result = JsonData.object();
    for (String field : new String[] {"attempt_id", "exercise_instance_id", "assistance"})
      result.set(field, attempt.path(field));
    result.put("outcome", correct ? "correct" : "incorrect");
    result.put("evidence", assisted ? "assisted" : "independent");
    result.set("versions", bundle.versions());
    result.set("exercise_revision", question.path("revision"));
    result.set("evaluator", assessment.path("evaluator"));
    result.set("evaluator_version", assessment.path("evaluator_version"));
    var criteria = result.putArray("criteria");
    for (JsonNode feedback : assessment.path("feedback")) {
      ObjectNode criterion = feedback.deepCopy();
      criterion.put("passed", correct);
      if (correct) criterion.putNull("misconception_code");
      criteria.add(criterion);
    }
    result.set("references", JsonData.tree(bundle.entities("reference")));
    return result;
  }

  private void persist(Connection c, UUID id, JsonNode answer, JsonNode result, Instant time)
      throws SQLException {
    try (var q =
        c.prepareStatement(
            "UPDATE attempts SET status='graded',answer=?::jsonb,result=?::jsonb,submitted_at=?::timestamptz WHERE id=? AND status='open'")) {
      q.setString(1, answer.toString());
      q.setString(2, result.toString());
      q.setString(3, time.toString());
      q.setObject(4, id);
      if (q.executeUpdate() != 1) throw new ApiFailure(409, "attempt_closed");
    }
  }
}
