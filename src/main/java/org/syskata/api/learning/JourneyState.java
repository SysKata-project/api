package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class JourneyState {
  private final Set<String> completed = new HashSet<>();
  private final Set<String> skills = new HashSet<>();

  public JourneyState(Connection c, UUID learner) throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT activity_id FROM reward_events WHERE learner_id=? AND kind IN ('lesson','checkpoint')")) {
      q.setObject(1, learner);
      try (var rows = q.executeQuery()) {
        while (rows.next()) completed.add(rows.getString(1));
      }
    }
    try (var q =
        c.prepareStatement(
            "SELECT skill_id FROM skill_progress WHERE learner_id=? AND independent_successes+assisted_successes>0")) {
      q.setObject(1, learner);
      try (var rows = q.executeQuery()) {
        while (rows.next()) skills.add(rows.getString(1));
      }
    }
  }

  public boolean completed(String id) {
    return completed.contains(id);
  }

  public List<String> unmet(Bundle bundle, String journey, JsonNode lesson) {
    Set<String> missing = new LinkedHashSet<>();
    prerequisites(bundle, bundle.entity(journey, "journey"), missing);
    for (JsonNode unit : bundle.units(journey)) {
      if (JsonData.strings(unit.path("lesson_ids")).contains(lesson.path("id").asText()))
        prerequisites(bundle, unit, missing);
    }
    prerequisites(bundle, lesson, missing);
    for (JsonNode objective : lesson.path("objectives")) {
      for (String skill : JsonData.strings(objective.path("skill_ids"))) {
        prerequisites(bundle, bundle.entity(skill, "skill"), missing);
      }
    }
    return List.copyOf(missing);
  }

  private void prerequisites(Bundle bundle, JsonNode entity, Set<String> missing) {
    String kind = entity.path("kind").asText();
    for (String id : JsonData.strings(entity.path("prerequisites"))) {
      JsonNode prerequisite = bundle.entity(id, kind);
      if (!satisfied(bundle, prerequisite)) missing.add(id);
      prerequisites(bundle, prerequisite, missing);
    }
  }

  private boolean satisfied(Bundle bundle, JsonNode entity) {
    return switch (entity.path("kind").asText()) {
      case "lesson" -> completed.contains(entity.path("id").asText());
      case "skill" -> skills.contains(entity.path("id").asText());
      case "unit" ->
          JsonData.strings(entity.path("lesson_ids")).stream().allMatch(completed::contains);
      case "journey" ->
          bundle.lessons(entity.path("id").asText()).stream()
              .allMatch(l -> completed.contains(l.path("id").asText()));
      default -> false;
    };
  }

  public JsonNode path(Bundle bundle, String journey) {
    List<JsonNode> nodes = new ArrayList<>();
    for (JsonNode lesson : bundle.lessons(journey)) {
      List<String> missing = unmet(bundle, journey, lesson);
      String status =
          completed(lesson.path("id").asText())
              ? "completed"
              : missing.isEmpty() ? "available" : "locked";
      nodes.add(
          JsonData.tree(
              Map.of("lesson", lesson, "status", status, "unmet_prerequisite_ids", missing)));
    }
    return JsonData.tree(
        Map.of(
            "versions",
            bundle.versions(),
            "journey",
            bundle.entity(journey, "journey"),
            "units",
            bundle.units(journey),
            "lessons",
            nodes,
            "references",
            bundle.entities("reference")));
  }
}
