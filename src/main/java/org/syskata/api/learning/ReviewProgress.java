package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Version 1.0.0 elapsed-time review policy, inside the learner-locked grade transaction. */
final class ReviewProgress {
  private ReviewProgress() {}

  static ArrayNode apply(
      Connection c,
      AttemptState state,
      JsonNode question,
      boolean correct,
      boolean assisted,
      Instant time)
      throws SQLException {
    ArrayNode rewards = JsonData.object().putArray("rewards");
    var due = JsonData.strings(state.plan.path("due_skill_ids"));
    var failed = JsonData.strings(state.plan.path("failed_review_skill_ids"));
    for (String skill : JsonData.strings(question.path("skill_ids"))) {
      boolean scheduled = due.contains(skill);
      boolean completed = correct && scheduled && claim(c, state, skill);
      schedule(c, state, skill, correct, assisted, completed, !failed.contains(skill), time);
      if (!correct && scheduled && !failed.contains(skill)) failed.add(skill);
      if (completed) {
        JsonNode reward = reward(c, state.learner, skill, time);
        if (reward != null) rewards.add(reward);
        due.remove(skill);
      }
    }
    state.plan.set("due_skill_ids", JsonData.tree(due));
    state.plan.set("failed_review_skill_ids", JsonData.tree(failed));
    return rewards;
  }

  private static boolean claim(Connection c, AttemptState state, String skill) throws SQLException {
    try (var q =
        LabSql.query(
            c,
            "INSERT INTO review_completion_events(learner_id,skill_id,due_at,session_id) VALUES (?,?,?::timestamptz,?) ON CONFLICT DO NOTHING",
            state.learner,
            skill,
            state.plan.path("review_due_at").asText(),
            state.session)) {
      return q.executeUpdate() == 1;
    }
  }

  private static void schedule(
      Connection c,
      AttemptState state,
      String skill,
      boolean correct,
      boolean assisted,
      boolean completed,
      boolean clean,
      Instant time)
      throws SQLException {
    int interval = 0;
    boolean originalDue = false;
    try (var q =
            LabSql.query(
                c,
                "SELECT interval_days,due_at FROM review_schedule WHERE learner_id=? AND skill_id=?",
                state.learner,
                skill);
        var r = q.executeQuery()) {
      if (r.next()) {
        interval = r.getInt(1);
        originalDue =
            r.getTimestamp(2)
                .toInstant()
                .toString()
                .equals(state.plan.path("review_due_at").asText());
      }
    }
    int days = ReviewPolicy.interval(interval, correct, assisted, completed, originalDue && clean);
    if (days == 0) return;
    LabSql.update(
        c,
        "INSERT INTO review_schedule(learner_id,skill_id,due_at,interval_days) VALUES (?,?,?::timestamptz,?) ON CONFLICT (learner_id,skill_id) DO UPDATE SET due_at=EXCLUDED.due_at,interval_days=EXCLUDED.interval_days,policy_version='1.0.0'",
        state.learner,
        skill,
        time.plus(days, ChronoUnit.DAYS).toString(),
        days);
  }

  static JsonNode reward(Connection c, UUID learner, String skill, Instant time)
      throws SQLException {
    PracticeDays days = PracticeDays.load(c, learner, time);
    try (var q =
            LabSql.query(
                c,
                "SELECT 1 FROM reward_events WHERE learner_id=? AND kind='review' AND activity_id=? AND created_at>=?::timestamptz",
                learner,
                skill,
                days.guardStart().toString());
        var r = q.executeQuery()) {
      if (r.next()) return null;
    }
    UUID id = UUID.randomUUID();
    try (var q =
        LabSql.query(
            c,
            "INSERT INTO reward_events(id,learner_id,kind,activity_id,local_day,timezone,xp,created_at,practice_day_start) VALUES (?,?,'review',?,?::date,?,5,?::timestamptz,?::timestamptz) ON CONFLICT DO NOTHING",
            id,
            learner,
            skill,
            days.date(),
            days.zone(),
            time.toString(),
            days.dayStart(time).toString())) {
      if (q.executeUpdate() == 0) return null;
    }
    var result = JsonData.object();
    result.put("event_id", id.toString());
    result.put("type", "review");
    result.put("subject_id", skill);
    result.put("xp", 5);
    result.put("credited_date", days.date());
    result.put("timezone", days.zone());
    return result;
  }
}
