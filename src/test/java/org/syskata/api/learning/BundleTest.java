package org.syskata.api.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.syskata.api.foundation.ApiFailure;

class BundleTest {
  static final Set<String> CAPABILITIES =
      Set.of("choice.exact:1", "order.exact:1", "command.literal:1");
  @TempDir Path temp;

  static Path directory(String version) {
    return Path.of("src/test/resources/content", version);
  }

  static Bundle fixture(String version) throws Exception {
    Path dir = directory(version);
    return BundleReader.read(
        dir, JsonData.hash(Files.readAllBytes(dir.resolve("manifest.json"))), CAPABILITIES, true);
  }

  @Test
  void pilotProjectionAndCapabilities() throws Exception {
    Bundle bundle = fixture("0.3.0");
    assertEquals(7, bundle.lessons("journey.openshift").size());
    assertEquals(28, bundle.entities("exercise").size());
    for (JsonNode question : bundle.entities("exercise")) {
      assertFalse(question.has("assessment"));
      assertFalse(question.has("hints"));
      assertFalse(question.has("reveal"));
      assertTrue(question.has("hint_ids"));
    }
    assertThrows(ApiFailure.class, () -> bundle.entity("oc-orientation", "unit"));
    assertThrows(ApiFailure.class, () -> bundle.entity("missing", "lesson"));
    Path dir = directory("0.3.0");
    String hash = JsonData.hash(Files.readAllBytes(dir.resolve("manifest.json")));
    assertThrows(ApiFailure.class, () -> BundleReader.read(dir, hash));
    assertThrows(ApiFailure.class, () -> BundleReader.read(dir, "wrong", CAPABILITIES, true));
    assertThrows(ApiFailure.class, () -> BundleReader.read(dir, hash, Set.of(), true));
    JsonNode copy = bundle.entity("oc-orientation", "lesson");
    ((ObjectNode) copy).put("title", "changed");
    assertFalse(bundle.entity("oc-orientation", "lesson").path("title").asText().equals("changed"));
  }

  private ObjectNode copy() throws Exception {
    for (String file : new String[] {"manifest.json", "learner.json", "assessment.json"})
      Files.copy(
          directory("0.3.0").resolve(file),
          temp.resolve(file),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    return (ObjectNode) JsonData.parse(Files.readString(temp.resolve("manifest.json")));
  }

  private Bundle read(ObjectNode manifest) throws Exception {
    Files.writeString(temp.resolve("manifest.json"), manifest.toString());
    return BundleReader.read(
        temp, JsonData.hash(Files.readAllBytes(temp.resolve("manifest.json"))), CAPABILITIES, true);
  }

  @Test
  void rejectsTamperingAndUnknownMetadata() throws Exception {
    for (Map.Entry<String, Object> bad :
        Map.<String, Object>of(
                "schema_version",
                "2.0.0",
                "manifest_version",
                2,
                "bundle_version",
                "bad",
                "source_commit",
                "bad")
            .entrySet()) {
      ObjectNode manifest = copy();
      manifest.set(bad.getKey(), JsonData.tree(bad.getValue()));
      assertThrows(ApiFailure.class, () -> read(manifest));
    }
    ObjectNode manifest = copy();
    Files.writeString(temp.resolve("learner.json"), "{}");
    assertThrows(ApiFailure.class, () -> read(manifest));
    ObjectNode audience = copy();
    ((ObjectNode) audience.path("artifacts").path("learner.json")).put("audience", "trusted");
    assertThrows(ApiFailure.class, () -> read(audience));
    ObjectNode extra = copy();
    ((ObjectNode) extra.path("artifacts")).put("other", "x");
    assertThrows(ApiFailure.class, () -> read(extra));
    ObjectNode requirements = copy();
    requirements.putArray("evaluator_requirements");
    assertThrows(ApiFailure.class, () -> read(requirements));
  }

  @Test
  void rejectsCoercedOrDuplicateCapabilities() throws Exception {
    ObjectNode fractional = copy();
    fractional.put("manifest_version", 1.5);
    assertThrows(ApiFailure.class, () -> read(fractional));
    ObjectNode flag = copy();
    flag.put("release_eligible", "true");
    assertThrows(ApiFailure.class, () -> read(flag));
    ObjectNode fractionalEvaluator = copy();
    ((ObjectNode) fractionalEvaluator.path("evaluator_requirements").get(0)).put("version", 1.5);
    assertThrows(ApiFailure.class, () -> read(fractionalEvaluator));
    ObjectNode duplicate = copy();
    ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.path("evaluator_requirements"))
        .add(duplicate.path("evaluator_requirements").get(0));
    assertThrows(ApiFailure.class, () -> read(duplicate));
    ObjectNode released = copy();
    released.put("release_eligible", true);
    Files.writeString(temp.resolve("manifest.json"), released.toString());
    // Synthetic release-reader proof only; no pilot artifact or approved hash is activated.
    assertEquals(
        "0.3.0",
        BundleReader.read(temp, JsonData.hash(Files.readAllBytes(temp.resolve("manifest.json"))))
            .version());
  }

  @Test
  void rejectsLearnerLeakEvenWithMatchingHash() throws Exception {
    ObjectNode manifest = copy();
    ObjectNode learner =
        (ObjectNode) JsonData.parse(Files.readString(temp.resolve("learner.json")));
    ((ObjectNode) learner.path("entities").get(0)).put("answer_key", "never public");
    byte[] bytes = learner.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(temp.resolve("learner.json"), bytes);
    ObjectNode metadata = (ObjectNode) manifest.path("artifacts").path("learner.json");
    metadata.put("sha256", JsonData.hash(bytes));
    metadata.put("bytes", bytes.length);
    assertThrows(ApiFailure.class, () -> read(manifest));
  }

  private ObjectNode catalog() throws Exception {
    return (ObjectNode)
        JsonData.parse(Files.readString(directory("0.3.0").resolve("assessment.json")));
  }

  private ObjectNode entity(ObjectNode catalog, String id) {
    for (JsonNode entity : catalog.path("entities"))
      if (entity.path("id").asText().equals(id)) return (ObjectNode) entity;
    throw new IllegalArgumentException(id);
  }

  @Test
  void graphRejectsDuplicateBrokenCyclesAndUncoveredObjectives() throws Exception {
    ObjectNode duplicate = catalog();
    ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.path("entities"))
        .add(duplicate.path("entities").get(0));
    assertThrows(ApiFailure.class, () -> BundleGraph.validate(duplicate));
    ObjectNode cycle = catalog();
    entity(cycle, "oc-orientation").putArray("prerequisites").add("oc-observe");
    assertThrows(ApiFailure.class, () -> BundleGraph.validate(cycle));
    ObjectNode uncovered = catalog();
    entity(uncovered, "oc-orientation").putArray("exercise_ids");
    assertThrows(ApiFailure.class, () -> BundleGraph.validate(uncovered));
    ObjectNode broken = catalog();
    entity(broken, "oc-orientation").putArray("exercise_ids").add("missing");
    assertThrows(ApiFailure.class, () -> BundleGraph.validate(broken));
    ObjectNode wrong = catalog();
    entity(wrong, "oc-orientation").putArray("exercise_ids").add("oc-observe.pods");
    assertThrows(ApiFailure.class, () -> BundleGraph.validate(wrong));
    ObjectNode skill = catalog();
    entity(skill, "oc-orientation.scope").putArray("skill_ids");
    assertThrows(ApiFailure.class, () -> BundleGraph.validate(skill));
    ObjectNode objective = catalog();
    entity(objective, "oc-orientation.scope").putArray("objective_ids").add("missing");
    assertThrows(ApiFailure.class, () -> BundleGraph.validate(objective));
  }

  @Test
  void canonicalRequestsAndStrictJson() {
    assertEquals(
        JsonData.requestHash(JsonData.parse("{\"b\":[1,2],\"a\":null}")),
        JsonData.requestHash(JsonData.parse("{\"a\":null,\"b\":[1,2]}")));
    assertThrows(ApiFailure.class, () -> JsonData.parse("{\"a\":1,\"a\":2}"));
    assertThrows(ApiFailure.class, () -> JsonData.parse(""));
    assertThrows(ApiFailure.class, () -> JsonData.parse("bad"));
    assertThrows(ApiFailure.class, () -> JsonData.parse("{} {}"));
  }
}
