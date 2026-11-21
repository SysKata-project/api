package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.syskata.api.foundation.ApiFailure;

/** Hash-pinned artifacts from the content compiler; no network access or caller-defined paths. */
public final class BundleReader {
  private static final Map<String, String> FIELDS =
      Map.of(
          "reference", "title url baseline reviewed_on",
          "skill", "title description prerequisites source",
          "journey", "title locale prerequisites unit_ids source runtime",
          "unit", "title prerequisites lesson_ids source",
          "lesson",
              "title prerequisites objectives exercise_ids estimated_minutes explanation_markdown review_prompt source",
          "exercise",
              "type skill_ids objective_ids prompt source options steps runtime reset_behavior");

  private BundleReader() {}

  public static Bundle read(Path directory, String hash) throws IOException {
    // Real checkpoint adapters remain gated on labs #3; drafts still fail closed.
    return read(directory, hash, TextGrading.CAPABILITIES, false);
  }

  static Bundle read(Path directory, String hash, Set<String> capabilities, boolean fixture)
      throws IOException {
    byte[] bytes = Files.readAllBytes(directory.resolve("manifest.json"));
    require(JsonData.hash(bytes).equals(hash));
    JsonNode manifest = content(bytes);
    require(manifest.path("manifest_version").isIntegralNumber());
    require(manifest.path("manifest_version").asLong() == 1);
    require(manifest.path("schema_version").asText().equals("1.0.0"));
    require(manifest.path("release_eligible").isBoolean());
    require(fixture || manifest.path("release_eligible").asBoolean());
    require(manifest.path("bundle_version").asText().matches("[0-9]+\\.[0-9]+\\.[0-9]+"));
    require(manifest.path("source_commit").asText().matches("[0-9a-f]{40}"));
    require(manifest.path("artifacts").size() == 2);
    JsonNode learner = artifact(directory, manifest, "learner.json", "learner");
    JsonNode trusted = artifact(directory, manifest, "assessment.json", "trusted");
    for (String field : new String[] {"bundle_version", "schema_version"}) {
      require(manifest.path(field).equals(learner.path(field)));
      require(manifest.path(field).equals(trusted.path(field)));
    }
    require(projection(trusted).equals(learner));
    BundleGraph.validate(trusted);
    Set<String> required = new HashSet<>();
    require(manifest.path("evaluator_requirements").isArray());
    for (JsonNode requirement : manifest.path("evaluator_requirements")) {
      require(required.add(capability(requirement, "version")));
    }
    Set<String> actual = new HashSet<>();
    for (JsonNode entity : trusted.path("entities")) {
      if (entity.path("kind").asText().equals("exercise")) {
        JsonNode assessment = entity.path("assessment");
        actual.add(capability(assessment, "evaluator_version"));
        assessment
            .path("checks")
            .forEach(check -> actual.add(capability(check, "evaluator_version")));
      }
    }
    require(required.equals(actual));
    require(capabilities.containsAll(required));
    return new Bundle(manifest, learner, trusted);
  }

  private static String capability(JsonNode requirement, String versionField) {
    JsonNode version = requirement.path(versionField);
    require(version.isIntegralNumber() && version.canConvertToLong());
    require(version.asLong() > 0 && version.asLong() <= 9007199254740991L);
    String evaluator = requirement.path("evaluator").asText();
    require(evaluator.matches("[a-z][a-z0-9]*([.-][a-z0-9]+)*"));
    return evaluator + ":" + version.asLong();
  }

  private static JsonNode artifact(Path directory, JsonNode manifest, String name, String audience)
      throws IOException {
    byte[] bytes = Files.readAllBytes(directory.resolve(name));
    JsonNode info = manifest.path("artifacts").path(name);
    require(info.path("audience").asText().equals(audience));
    require(info.path("bytes").asLong() == bytes.length);
    require(info.path("sha256").asText().equals(JsonData.hash(bytes)));
    return content(bytes);
  }

  private static JsonNode content(byte[] bytes) {
    try {
      return JsonData.parse(new String(bytes, StandardCharsets.UTF_8));
    } catch (ApiFailure failure) {
      throw new ApiFailure(503, "unsupported_content");
    }
  }

  static JsonNode projection(JsonNode trusted) {
    ObjectNode learner = JsonData.object();
    learner.set("schema_version", trusted.path("schema_version"));
    learner.set("bundle_version", trusted.path("bundle_version"));
    var entities = learner.putArray("entities");
    for (JsonNode entity : trusted.path("entities")) {
      String kind = entity.path("kind").asText();
      require(FIELDS.containsKey(kind));
      ObjectNode output = entities.addObject();
      for (String field : ("kind id revision " + FIELDS.get(kind)).split(" ")) {
        if (entity.has(field)) output.set(field, entity.path(field));
      }
      if (kind.equals("exercise")) {
        var hints = output.putArray("hint_ids");
        entity.path("hints").forEach(h -> hints.add(h.path("id").asText()));
      }
    }
    return learner;
  }

  static void require(boolean condition) {
    if (!condition) throw new ApiFailure(503, "unsupported_content");
  }
}
