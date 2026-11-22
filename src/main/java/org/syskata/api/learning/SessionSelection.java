package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.syskata.api.foundation.ApiFailure;

public final class SessionSelection {
  private SessionSelection() {}

  public static ObjectNode choose(Connection c, UUID learner, Bundle bundle, JsonNode request)
      throws SQLException {
    String journey = request.path("journey_id").asText();
    String mode = request.path("mode").asText();
    JourneyState state = new JourneyState(c, learner);
    if (mode.equals("review") || mode.equals("auto")) {
      ObjectNode review = review(c, learner, bundle, journey, state);
      if (review != null) return review;
      if (mode.equals("review")) throw new ApiFailure(409, "ineligible");
    }
    return nextLesson(bundle, request, state, journey, mode);
  }

  private static ObjectNode nextLesson(
      Bundle bundle, JsonNode request, JourneyState state, String journey, String mode) {
    for (JsonNode lesson : bundle.lessons(journey)) {
      boolean requested =
          mode.equals("lesson")
              ? lesson.path("id").equals(request.path("lesson_id"))
              : !state.completed(lesson.path("id").asText());
      if (requested && state.unmet(bundle, journey, lesson).isEmpty())
        return lesson(bundle, lesson);
    }
    throw new ApiFailure(409, "ineligible");
  }

  private static ObjectNode lesson(Bundle bundle, JsonNode lesson) {
    ObjectNode plan = JsonData.object();
    plan.put("mode", "lesson");
    plan.set("lesson_id", lesson.path("id"));
    var pending = plan.putArray("pending_objective_ids");
    lesson.path("objectives").forEach(o -> pending.add(o.path("id").asText()));
    plan.set("exercise_queue", JsonData.tree(exercises(bundle, lesson)));
    plan.putArray("remediation_objective_ids");
    plan.putArray("due_skill_ids");
    return plan;
  }

  private static List<String> exercises(Bundle bundle, JsonNode lesson) {
    return JsonData.strings(lesson.path("exercise_ids")).stream().sorted().toList();
  }

  private static ObjectNode review(
      Connection c, UUID learner, Bundle bundle, String journey, JourneyState state)
      throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT skill_id,due_at FROM review_schedule WHERE learner_id=? AND due_at<=?::timestamptz ORDER BY due_at,skill_id")) {
      q.setObject(1, learner);
      q.setString(2, java.time.Instant.now().toString());
      try (var rows = q.executeQuery()) {
        while (rows.next()) {
          String skill = rows.getString(1);
          List<JsonNode> eligible = new ArrayList<>();
          for (JsonNode lesson : bundle.lessons(journey)) {
            if (!state.unmet(bundle, journey, lesson).isEmpty()) continue;
            for (String id : exercises(bundle, lesson)) {
              JsonNode exercise = bundle.entity(id, "exercise");
              if (JsonData.strings(exercise.path("skill_ids")).contains(skill))
                eligible.add(exercise);
            }
          }
          if (!eligible.isEmpty())
            return reviewPlan(skill, rows.getTimestamp(2).toInstant().toString(), eligible);
        }
      }
    }
    return null;
  }

  private static ObjectNode reviewPlan(String skill, String due, List<JsonNode> eligible) {
    eligible.sort(Comparator.comparing(e -> e.path("id").asText()));
    JsonNode exercise = eligible.getFirst();
    ObjectNode plan = JsonData.object();
    plan.put("mode", "review");
    plan.putNull("lesson_id");
    plan.set("pending_objective_ids", exercise.path("objective_ids"));
    plan.putArray("exercise_queue").add(exercise.path("id").asText());
    plan.putArray("remediation_objective_ids");
    plan.putArray("due_skill_ids").add(skill);
    plan.put("review_due_at", due);
    return plan;
  }
}
