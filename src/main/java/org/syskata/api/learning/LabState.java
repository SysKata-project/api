package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

final class LabState {
  private LabState() {}

  static void enqueue(Connection c, UUID learner, String operation, UUID id, JsonNode payload)
      throws SQLException {
    LabSql.update(
        c,
        "INSERT INTO outbox_jobs(id,learner_id,operation,deduplication_key,payload) VALUES (?,?,?,?,?::jsonb)",
        id,
        learner,
        operation,
        id.toString(),
        payload.toString());
  }

  static void cancel(Connection c, LabRecord lab, String status) throws SQLException {
    LabSql.update(
        c,
        "UPDATE attempts SET status='cancelled' WHERE id IN (SELECT attempt_id FROM assessments WHERE lab_id=? AND status='pending') AND status='awaiting_assessment'",
        lab.id());
    LabSql.update(
        c, "UPDATE assessments SET status=? WHERE lab_id=? AND status='pending'", status, lab.id());
  }

  static ObjectNode error(String code) {
    var result = JsonData.object();
    result.put("code", code);
    result.put("message", code.replace('_', ' '));
    result.put("retryable", code.equals("infrastructure_unavailable"));
    result.put("correlation_id", UUID.randomUUID().toString());
    result.put("recovery", "refresh_state");
    result.putNull("current_revision");
    return result;
  }

  static ObjectNode dispatch(
      LabRecord lab, AttemptState state, UUID attempt, UUID id, Bundle bundle) {
    var question = state.current().path("question");
    var assessment = bundle.assessment(question.path("id").asText());
    var result = JsonData.object();
    result.put("id", id.toString());
    result.put("labId", lab.id().toString());
    result.put("subject", lab.learner().toString());
    result.put("generation", lab.generation());
    result.put("sessionId", lab.session().toString());
    result.put("instanceId", lab.instance().toString());
    result.put("attemptId", attempt.toString());
    result.set("versions", bundle.versions());
    result.set("exerciseRevision", question.path("revision"));
    result.set("evaluator", assessment.path("evaluator"));
    result.set("evaluatorVersion", assessment.path("evaluator_version"));
    return result;
  }
}
