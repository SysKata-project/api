package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

final class LabRequests {
  private LabRequests() {}

  static JsonNode body(String text, Set<String> fields) {
    var body = JsonData.parse(text == null ? "null" : text);
    TextGrading.require(
        body.isObject() && body.size() == fields.size() && fields.stream().allMatch(body::has));
    for (String name : fields) {
      var value = body.path(name);
      if (name.endsWith("_id")) {
        TextGrading.require(value.isTextual());
        LearningRequests.key(value.asText());
      } else
        TextGrading.require(
            value.isIntegralNumber()
                && value.canConvertToLong()
                && value.asLong() > 0
                && value.asLong() <= 9007199254740991L);
    }
    return body;
  }
}
