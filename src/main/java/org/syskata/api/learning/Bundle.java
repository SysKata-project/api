package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.syskata.api.foundation.ApiFailure;

/** An immutable validated catalog. Never return trusted entities at the HTTP boundary. */
public final class Bundle {
  private final JsonNode manifest;
  private final Map<String, JsonNode> publicEntities;
  private final Map<String, JsonNode> trustedEntities;

  Bundle(JsonNode manifest, JsonNode learner, JsonNode trusted) {
    this.manifest = manifest.deepCopy();
    publicEntities = index(learner);
    trustedEntities = index(trusted);
  }

  private static Map<String, JsonNode> index(JsonNode catalog) {
    Map<String, JsonNode> result = new LinkedHashMap<>();
    catalog
        .path("entities")
        .forEach(entity -> result.put(entity.path("id").asText(), entity.deepCopy()));
    return result;
  }

  public String version() {
    return manifest.path("bundle_version").asText();
  }

  public JsonNode manifest() {
    return manifest.deepCopy();
  }

  public JsonNode versions() {
    return JsonData.tree(
        Map.of("schema_version", "1.0.0", "bundle_version", version(), "policy_version", "1.0.0"));
  }

  public JsonNode entity(String id, String kind) {
    JsonNode entity = publicEntities.get(id);
    if (entity == null || !entity.path("kind").asText().equals(kind))
      throw new ApiFailure(404, "not_found");
    return entity.deepCopy();
  }

  public List<JsonNode> entities(String kind) {
    return publicEntities.values().stream()
        .filter(e -> e.path("kind").asText().equals(kind))
        .<JsonNode>map(JsonNode::deepCopy)
        .toList();
  }

  JsonNode trusted(String id) {
    return trustedEntities.get(id).deepCopy();
  }

  public JsonNode assessment(String id) {
    return trustedEntities.get(id).path("assessment").deepCopy();
  }

  public List<JsonNode> units(String journey) {
    return JsonData.strings(entity(journey, "journey").path("unit_ids")).stream()
        .map(id -> entity(id, "unit"))
        .toList();
  }

  public List<JsonNode> lessons(String journey) {
    return units(journey).stream()
        .flatMap(unit -> JsonData.strings(unit.path("lesson_ids")).stream())
        .map(id -> entity(id, "lesson"))
        .toList();
  }
}
