package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class AttemptAdvance {
  private AttemptAdvance() {}

  static String apply(Connection c, AttemptState state, Bundle bundle, boolean correct)
      throws Exception {
    JsonNode question = state.current().path("question");
    List<String> pending = JsonData.strings(state.plan.path("pending_objective_ids"));
    List<String> remediation = JsonData.strings(state.plan.path("remediation_objective_ids"));
    List<String> objectives = JsonData.strings(question.path("objective_ids"));
    if (correct) {
      pending.removeAll(objectives);
      remediation.removeAll(objectives);
    } else {
      for (String objective : objectives)
        if (pending.contains(objective) && !remediation.contains(objective))
          remediation.add(objective);
    }
    state.plan.set("pending_objective_ids", JsonData.tree(pending));
    state.plan.set("remediation_objective_ids", JsonData.tree(remediation));
    state.response().set("pending_objective_ids", JsonData.tree(pending));
    state.response().putNull("current_attempt");
    if (pending.isEmpty()) {
      state.status = "completed";
      state.response().putNull("current_instance");
      state.plan.putArray("exercise_queue");
      return "session_complete";
    }
    List<String> queue = JsonData.strings(state.plan.path("exercise_queue"));
    queue.removeFirst();
    // Skip work whose objectives are already satisfied; preserve remaining first-pass order.
    queue.removeIf(id -> disjoint(bundle.entity(id, "exercise"), pending));
    boolean remediate = queue.isEmpty();
    if (remediate) queue.add(alternative(bundle, state, question, remediation.getFirst()));
    state.plan.set("exercise_queue", JsonData.tree(queue));
    String next = queue.getFirst();
    var current = state.response().putObject("current_instance");
    UUID instance = UUID.randomUUID();
    current.put("exercise_instance_id", instance.toString());
    current.set("question", bundle.entity(next, "exercise"));
    current.set("evaluator", bundle.assessment(next).path("evaluator"));
    current.set("evaluator_version", bundle.assessment(next).path("evaluator_version"));
    LearningService.saveInstance(c, state.learner, state.session, instance, current);
    return remediate ? "remediate" : "continue";
  }

  private static boolean disjoint(JsonNode exercise, List<String> pending) {
    return JsonData.strings(exercise.path("objective_ids")).stream().noneMatch(pending::contains);
  }

  private static String alternative(
      Bundle bundle, AttemptState state, JsonNode previous, String objective) {
    if (state.plan.path("mode").asText().equals("review")) return previous.path("id").asText();
    List<String> choices =
        new ArrayList<>(
            JsonData.strings(
                bundle
                    .entity(state.plan.path("lesson_id").asText(), "lesson")
                    .path("exercise_ids")));
    choices.sort(String::compareTo);
    String fallback = previous.path("id").asText();
    for (String id : choices) {
      if (JsonData.strings(bundle.entity(id, "exercise").path("objective_ids"))
          .contains(objective)) {
        fallback = id;
        if (!id.equals(previous.path("id").asText())) return id;
      }
    }
    return fallback;
  }
}
