package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/** Confirmed evidence and completion rewards in the grading transaction. */
final class GradeProgress {
  private GradeProgress() {}

  static void evidence(
      Connection c,
      UUID learner,
      JsonNode question,
      boolean correct,
      boolean assisted,
      Instant time)
      throws SQLException {
    try (var q =
        c.prepareStatement(
            "INSERT INTO skill_progress(learner_id,skill_id,independent_successes,assisted_successes,mistakes,updated_at) VALUES (?,?,?,?,?,?::timestamptz) ON CONFLICT (learner_id,skill_id) DO UPDATE SET independent_successes=skill_progress.independent_successes+EXCLUDED.independent_successes,assisted_successes=skill_progress.assisted_successes+EXCLUDED.assisted_successes,mistakes=skill_progress.mistakes+EXCLUDED.mistakes,updated_at=EXCLUDED.updated_at")) {
      for (String skill : JsonData.strings(question.path("skill_ids"))) {
        q.setObject(1, learner);
        q.setString(2, skill);
        q.setInt(3, correct && !assisted ? 1 : 0);
        q.setInt(4, correct && assisted ? 1 : 0);
        q.setInt(5, correct ? 0 : 1);
        q.setString(6, time.toString());
        q.executeUpdate();
      }
    }
  }

  static ArrayNode reward(Connection c, AttemptState state, Instant time) throws SQLException {
    ArrayNode rewards = JsonData.object().putArray("rewards");
    if (!state.status.equals("completed") || state.plan.path("mode").asText().equals("review"))
      return rewards;
    String lesson = state.plan.path("lesson_id").asText();
    PracticeDays days = PracticeDays.load(c, state.learner, time);
    String timezone = days.zone();
    String day = days.date();
    try (var q =
        c.prepareStatement(
            "INSERT INTO lesson_completion_events VALUES (?,?,?,?::timestamptz,?,?::date)")) {
      q.setObject(1, state.session);
      q.setObject(2, state.learner);
      q.setString(3, lesson);
      q.setString(4, time.toString());
      q.setString(5, timezone);
      q.setString(6, day);
      q.executeUpdate();
    }
    UUID event = UUID.randomUUID();
    try (var q =
        c.prepareStatement(
            "INSERT INTO reward_events(id,learner_id,kind,activity_id,timezone,xp,created_at) VALUES (?,?,'lesson',?,?,10,?::timestamptz) ON CONFLICT DO NOTHING")) {
      q.setObject(1, event);
      q.setObject(2, state.learner);
      q.setString(3, lesson);
      q.setString(4, timezone);
      q.setString(5, time.toString());
      if (q.executeUpdate() == 1) {
        var reward = rewards.addObject();
        reward.put("event_id", event.toString());
        reward.put("type", "lesson");
        reward.put("subject_id", lesson);
        reward.put("xp", 10);
        reward.put("credited_date", day);
        reward.put("timezone", timezone);
      }
    }
    return rewards;
  }

  static ObjectNode snapshot(Connection c, UUID learner, Instant time) throws SQLException {
    ObjectNode result = JsonData.object();
    PracticeDays days = PracticeDays.load(c, learner, time);
    result.put("confirmed_at", time.toString());
    try (var q =
        c.prepareStatement(
            "UPDATE learners SET progress_revision=progress_revision+1 WHERE id=? RETURNING progress_revision")) {
      q.setObject(1, learner);
      try (var rows = q.executeQuery()) {
        rows.next();
        result.put("revision", rows.getLong(1));
      }
    }
    var lessons = result.putArray("completed_lesson_ids");
    var checkpoints = result.putArray("completed_checkpoint_ids");
    long xp = 0;
    try (var q =
        c.prepareStatement(
            "SELECT kind,activity_id,xp FROM reward_events WHERE learner_id=? ORDER BY kind,activity_id,id")) {
      q.setObject(1, learner);
      try (var rows = q.executeQuery()) {
        while (rows.next()) {
          xp += rows.getLong(3);
          if (rows.getString(1).equals("lesson")) lessons.add(rows.getString(2));
          if (rows.getString(1).equals("checkpoint")) checkpoints.add(rows.getString(2));
        }
      }
    }
    result.put("total_xp", xp);
    var skills = result.putArray("skills");
    try (var q =
        c.prepareStatement(
            "SELECT skill_id,independent_successes,assisted_successes,mistakes FROM skill_progress WHERE learner_id=? ORDER BY skill_id")) {
      q.setObject(1, learner);
      try (var rows = q.executeQuery()) {
        while (rows.next()) {
          var skill = skills.addObject();
          skill.put("skill_id", rows.getString(1));
          skill.put("independent_successes", rows.getLong(2));
          skill.put("assisted_successes", rows.getLong(3));
          skill.put("mistakes", rows.getLong(4));
        }
      }
    }
    days.append(result);
    ReviewSummary.append(c, learner, result);
    return result;
  }
}
