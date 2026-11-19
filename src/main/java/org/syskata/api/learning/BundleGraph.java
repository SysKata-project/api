package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class BundleGraph {
  private BundleGraph() {}

  static void validate(JsonNode catalog) {
    Map<String, JsonNode> entities = new HashMap<>();
    Map<String, JsonNode> objectives = new HashMap<>();
    BundleReader.require(catalog.path("entities").isArray() && !catalog.path("entities").isEmpty());
    for (JsonNode entity : catalog.path("entities")) {
      String id = entity.path("id").asText();
      BundleReader.require(id.matches("[a-z][a-z0-9]*([.-][a-z0-9]+)*"));
      BundleReader.require(entity.path("revision").asInt() > 0);
      BundleReader.require(entities.put(id, entity) == null);
      for (JsonNode objective : entity.path("objectives")) {
        BundleReader.require(objectives.put(objective.path("id").asText(), objective) == null);
      }
    }
    BundleReader.require(java.util.Collections.disjoint(entities.keySet(), objectives.keySet()));
    for (JsonNode entity : entities.values()) {
      links(entities, entity.path("prerequisites"), entity.path("kind").asText());
      links(entities, entity.path("unit_ids"), "unit");
      links(entities, entity.path("lesson_ids"), "lesson");
      links(entities, entity.path("exercise_ids"), "exercise");
      links(entities, entity.path("skill_ids"), "skill");
      links(entities, entity.path("source").path("reference_ids"), "reference");
      for (String id : JsonData.strings(entity.path("objective_ids"))) {
        BundleReader.require(objectives.containsKey(id));
        BundleReader.require(
            JsonData.strings(entity.path("skill_ids"))
                .containsAll(JsonData.strings(objectives.get(id).path("skill_ids"))));
      }
      if (entity.path("kind").asText().equals("lesson")) coverage(entity, entities);
      acyclic(entity.path("id").asText(), entities, new HashSet<>(), new HashSet<>());
    }
    objectives.values().forEach(o -> links(entities, o.path("skill_ids"), "skill"));
  }

  private static void links(Map<String, JsonNode> entities, JsonNode ids, String kind) {
    for (String id : JsonData.strings(ids)) {
      BundleReader.require(entities.containsKey(id));
      BundleReader.require(entities.get(id).path("kind").asText().equals(kind));
    }
  }

  private static void coverage(JsonNode lesson, Map<String, JsonNode> entities) {
    Set<String> owned = new HashSet<>();
    lesson.path("objectives").forEach(o -> owned.add(o.path("id").asText()));
    Set<String> covered = new HashSet<>();
    for (String id : JsonData.strings(lesson.path("exercise_ids"))) {
      var targets = JsonData.strings(entities.get(id).path("objective_ids"));
      BundleReader.require(owned.containsAll(targets));
      covered.addAll(targets);
    }
    BundleReader.require(!owned.isEmpty() && covered.containsAll(owned));
  }

  private static void acyclic(
      String id, Map<String, JsonNode> entities, Set<String> active, Set<String> done) {
    if (done.contains(id)) return;
    BundleReader.require(active.add(id));
    for (String prerequisite : JsonData.strings(entities.get(id).path("prerequisites"))) {
      acyclic(prerequisite, entities, active, done);
    }
    active.remove(id);
    done.add(id);
  }
}
