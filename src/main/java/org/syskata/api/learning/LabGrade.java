package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class LabGrade {
  private LabGrade() {}

  static JsonNode apply(
      Connection c, AttemptState state, Bundle bundle, UUID attempt, JsonNode trusted)
      throws Exception {
    JsonNode question = state.current().path("question");
    JsonNode detail = state.attempt(c, attempt).path("attempt");
    JsonNode assistance = detail.path("assistance");
    boolean assisted =
        assistance.path("revealed").asBoolean() || !assistance.path("hint_ids").isEmpty();
    Map<String, Boolean> outcomes = new HashMap<>();
    trusted
        .path("criteria")
        .forEach(x -> outcomes.put(x.path("criterion_id").asText(), x.path("passed").asBoolean()));
    boolean correct = outcomes.values().stream().allMatch(Boolean::booleanValue);
    var result = JsonData.object();
    for (String field :
        new String[] {
          "attempt_id",
          "exercise_instance_id",
          "versions",
          "exercise_revision",
          "evaluator",
          "evaluator_version"
        }) result.set(field, trusted.path(field));
    result.set("assistance", assistance);
    result.put("evidence", assisted ? "assisted" : "independent");
    result.put("outcome", correct ? "correct" : "incorrect");
    var criteria = result.putArray("criteria");
    for (var feedback : bundle.assessment(question.path("id").asText()).path("feedback")) {
      ObjectNode criterion = feedback.deepCopy();
      boolean passed = outcomes.get(feedback.path("criterion_id").asText());
      criterion.put("passed", passed);
      if (passed) criterion.putNull("misconception_code");
      criteria.add(criterion);
    }
    result.set("references", JsonData.tree(bundle.entities("reference")));
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    GradeProgress.evidence(c, state.learner, question, correct, assisted, now);
    var reviewRewards = ReviewProgress.apply(c, state, question, correct, assisted, now);
    String next = AttemptAdvance.apply(c, state, bundle, correct);
    var rewards = GradeProgress.reward(c, state, now);
    rewards.addAll(reviewRewards);
    if (correct) {
      JsonNode checkpoint = checkpoint(c, state.learner, question.path("id").asText(), now);
      if (checkpoint != null) rewards.add(checkpoint);
    }
    result.set("rewards", rewards);
    result.set("progress", GradeProgress.snapshot(c, state.learner, now));
    state.save(c);
    result.put("session_revision", state.revision);
    result.set(
        "next_action", JsonData.tree(Map.of("type", next, "session_id", state.session.toString())));
    LabSql.update(
        c,
        "UPDATE attempts SET status='graded',result=?::jsonb,submitted_at=?::timestamptz WHERE id=? AND status='awaiting_assessment'",
        result.toString(),
        now.toString(),
        attempt);
    return result;
  }

  private static JsonNode checkpoint(Connection c, UUID learner, String checkpoint, Instant time)
      throws Exception {
    String zone = PracticeDays.load(c, learner, time).zone();
    UUID id = UUID.randomUUID();
    try (var q =
        LabSql.query(
            c,
            "INSERT INTO reward_events(id,learner_id,kind,activity_id,timezone,xp,created_at) VALUES (?,?,'checkpoint',?,?,25,?::timestamptz) ON CONFLICT DO NOTHING",
            id,
            learner,
            checkpoint,
            zone,
            time.toString())) {
      if (q.executeUpdate() == 0) return null;
    }
    return JsonData.tree(
        Map.of(
            "event_id",
            id.toString(),
            "type",
            "checkpoint",
            "subject_id",
            checkpoint,
            "xp",
            25,
            "credited_date",
            time.atZone(ZoneId.of(zone)).toLocalDate().toString(),
            "timezone",
            zone));
  }
}
