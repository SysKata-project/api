package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Version one is an allowlist of trusted worker profiles, never caller-supplied commands. */
final class LabProfile {
  private LabProfile() {}

  static String scenario(JsonNode question, JsonNode assessment) {
    String evaluator = assessment.path("evaluator").asText();
    BundleReader.require(question.path("type").asText().equals("lab_task"));
    BundleReader.require(
        Set.of("openshift.foundations", "openshift.administration").contains(evaluator));
    BundleReader.require(
        question.path("revision").asInt() == 1
            && assessment.path("evaluator_version").asInt() == 1);
    BundleReader.require(
        assessment.path("completion_evidence").asText().equals("trusted-lab-result"));
    String scenario = evaluator.substring("openshift.".length());
    BundleReader.require(question.path("id").asText().equals("checkpoint.oc-" + scenario));
    for (JsonNode check : assessment.path("checks")) {
      BundleReader.require(check.path("evaluator").asText().equals(evaluator));
      BundleReader.require(check.path("evaluator_version").asInt() == 1);
      BundleReader.require(
          check.path("parameters").isObject() && check.path("parameters").isEmpty());
    }
    Set<String> expected = new HashSet<>();
    List<String> suffixes =
        scenario.equals("foundations")
            ? List.of("context", "deployment", "service", "route", "response", "repair")
            : List.of("context", "manifest", "access", "quota");
    suffixes.forEach(s -> expected.add("criterion." + s));
    BundleReader.require(ids(assessment.path("checks")).equals(expected));
    BundleReader.require(ids(assessment.path("feedback")).equals(expected));
    return scenario;
  }

  static Set<String> ids(JsonNode checks) {
    Set<String> result = new HashSet<>();
    BundleReader.require(checks.isArray());
    for (JsonNode check : checks)
      BundleReader.require(result.add(check.path("criterion_id").asText()));
    return result;
  }
}
