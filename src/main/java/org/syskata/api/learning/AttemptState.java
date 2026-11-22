package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.syskata.api.foundation.ApiFailure;

/** Session lock is held through grade, progress and replay commit. Learner lock comes first. */
final class AttemptState {
  final UUID learner;
  final UUID session;
  final ObjectNode plan;
  final String version;
  long revision;
  String status;

  AttemptState(Connection c, UUID learner, UUID session) throws SQLException {
    this.learner = learner;
    this.session = session;
    try (var q =
        c.prepareStatement(
            "SELECT selection_state,bundle_version,revision,status FROM learning_sessions WHERE id=? AND learner_id=? FOR UPDATE")) {
      q.setObject(1, session);
      q.setObject(2, learner);
      try (var rows = q.executeQuery()) {
        if (!rows.next()) throw new ApiFailure(404, "not_found");
        plan = (ObjectNode) JsonData.parse(rows.getString(1));
        version = rows.getString(2);
        revision = rows.getLong(3);
        status = rows.getString(4);
      }
    }
  }

  ObjectNode response() {
    return (ObjectNode) plan.path("response");
  }

  JsonNode current() {
    return response().path("current_instance");
  }

  void validate(JsonNode body) {
    if (body.path("session_revision").asLong() != revision)
      throw new ApiFailure(409, "stale_revision", revision);
    if (!status.equals("active")) throw new ApiFailure(409, "attempt_closed");
    if (plan.path("mode").asText().equals("review")) throw new ApiFailure(409, "ineligible");
    validateInstance(body);
  }

  private void validateInstance(JsonNode body) {
    if (body.has("exercise_instance_id")
        && !body.path("exercise_instance_id").equals(current().path("exercise_instance_id")))
      throw new ApiFailure(409, "result_mismatch", revision);
  }

  void save(Connection c) throws SQLException {
    revision++;
    response().put("revision", revision);
    response().put("status", status);
    try (var q =
        c.prepareStatement(
            "UPDATE learning_sessions SET selection_state=?::jsonb,revision=?,status=? WHERE id=? AND learner_id=?")) {
      q.setString(1, plan.toString());
      q.setLong(2, revision);
      q.setString(3, status);
      q.setObject(4, session);
      q.setObject(5, learner);
      q.executeUpdate();
    }
  }

  ObjectNode attempt(Connection c, UUID id) throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT id,instance_id,status,hints,revealed,result FROM attempts WHERE id=? AND session_id=? AND learner_id=?")) {
      q.setObject(1, id);
      q.setObject(2, session);
      q.setObject(3, learner);
      try (var rows = q.executeQuery()) {
        if (!rows.next()) throw new ApiFailure(404, "not_found");
        ObjectNode detail = JsonData.object();
        var attempt = detail.putObject("attempt");
        attempt.put("attempt_id", rows.getString(1));
        attempt.put("exercise_instance_id", rows.getString(2));
        String state = rows.getString(3);
        attempt.put("status", state.equals("graded") ? "submitted" : state);
        var assistance = attempt.putObject("assistance");
        assistance.set("hint_ids", JsonData.parse(rows.getString(4)));
        assistance.put("revealed", rows.getBoolean(5));
        detail.set(
            "result",
            rows.getString(6) == null ? JsonData.tree(null) : JsonData.parse(rows.getString(6)));
        detail.putNull("assessment_id");
        detail.put("session_revision", revision);
        return detail;
      }
    }
  }

  JsonNode open(Connection c, UUID id, JsonNode body) throws SQLException {
    JsonNode attempt = attempt(c, id).path("attempt");
    if (!attempt.path("status").asText().equals("open"))
      throw new ApiFailure(409, "attempt_closed");
    validate(body);
    if (!attempt.path("exercise_instance_id").equals(current().path("exercise_instance_id")))
      throw new ApiFailure(409, "result_mismatch", revision);
    return attempt;
  }
}
