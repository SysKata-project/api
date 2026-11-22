package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.syskata.api.foundation.ApiFailure;

final class TrustedResult {
  private TrustedResult() {}

  static void validate(JsonNode result, JsonNode dispatch, JsonNode assessment) {
    Set<String> fields =
        new HashSet<>(
            Set.of(
                "assessment_id",
                "lab_session_id",
                "session_id",
                "exercise_instance_id",
                "attempt_id",
                "generation",
                "versions",
                "exercise_revision",
                "evaluator",
                "evaluator_version",
                "observed_at",
                "outcome"));
    boolean evaluated = result.path("outcome").asText().equals("evaluated");
    fields.add(evaluated ? "criteria" : "failure_code");
    TextGrading.require(
        result.isObject()
            && result.size() == fields.size()
            && fields.stream().allMatch(result::has));
    Map<String, String> binding =
        Map.of(
            "assessment_id",
            "id",
            "lab_session_id",
            "labId",
            "session_id",
            "sessionId",
            "exercise_instance_id",
            "instanceId",
            "attempt_id",
            "attemptId",
            "generation",
            "generation",
            "versions",
            "versions",
            "exercise_revision",
            "exerciseRevision",
            "evaluator",
            "evaluator",
            "evaluator_version",
            "evaluatorVersion");
    for (var entry : binding.entrySet())
      if (!result.path(entry.getKey()).equals(dispatch.path(entry.getValue())))
        throw new ApiFailure(409, "result_mismatch");
    observed(result);
    if (evaluated) criteria(result.path("criteria"), assessment);
    else
      TextGrading.require(
          result.path("outcome").asText().equals("infrastructure_error")
              && Set.of("cluster_unavailable", "evaluator_timeout", "dispatch_failed")
                  .contains(result.path("failure_code").asText()));
  }

  static Instant observed(JsonNode result) {
    try {
      return Instant.parse(result.path("observed_at").asText());
    } catch (java.time.format.DateTimeParseException failure) {
      throw new ApiFailure(400, "invalid_request");
    }
  }

  private static void criteria(JsonNode criteria, JsonNode assessment) {
    TextGrading.require(criteria.isArray() && !criteria.isEmpty() && criteria.size() <= 1000);
    Set<String> found = new HashSet<>();
    for (var criterion : criteria) {
      TextGrading.require(
          criterion.isObject()
              && criterion.size() == 2
              && criterion.path("criterion_id").isTextual()
              && criterion.path("passed").isBoolean());
      if (!found.add(criterion.path("criterion_id").asText()))
        throw new ApiFailure(409, "result_mismatch");
    }
    if (!found.equals(LabProfile.ids(assessment.path("checks"))))
      throw new ApiFailure(409, "result_mismatch");
  }
}
