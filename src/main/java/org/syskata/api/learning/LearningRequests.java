package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import org.syskata.api.foundation.ApiFailure;

final class LearningRequests {
  private LearningRequests() {}

  static JsonNode body(String text, Set<String> fields) {
    JsonNode body = JsonData.parse(text == null ? "null" : text);
    require(body.isObject() && body.size() == fields.size());
    require(fields.stream().allMatch(body::has));
    id(body.path("journey_id"));
    return body;
  }

  static void id(JsonNode value) {
    require(value.isTextual() && value.asText().matches("[a-z][a-z0-9]*([.-][a-z0-9]+)*"));
  }

  static String key(String key) {
    require(key != null && key.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"));
    return UUID.fromString(key).toString();
  }

  static void enrollment(JsonNode body) {
    require(
        body.path("timezone").isTextual()
            && ZoneId.getAvailableZoneIds().contains(body.path("timezone").asText()));
  }

  static void session(JsonNode body) {
    String mode = body.path("mode").asText();
    require(Set.of("auto", "lesson", "review").contains(mode));
    if (mode.equals("lesson")) id(body.path("lesson_id"));
    else require(body.path("lesson_id").isNull());
  }

  static UUID sessionId(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException failure) {
      throw new ApiFailure(404, "not_found");
    }
  }

  private static void require(boolean valid) {
    if (!valid) throw new ApiFailure(400, "invalid_request");
  }
}
