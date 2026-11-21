package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import org.syskata.api.foundation.ApiFailure;

/** Pure, versioned comparison. Learner input is never executed or shell-parsed. */
final class TextGrading {
  static final Set<String> CAPABILITIES =
      Set.of("choice.exact:1", "order.exact:1", "command.literal:1");

  private TextGrading() {}

  static boolean evaluate(JsonNode question, JsonNode assessment, JsonNode answer) {
    String kind = question.path("type").asText();
    String capability =
        assessment.path("evaluator").asText() + ":" + assessment.path("evaluator_version").asText();
    String expected =
        switch (kind) {
          case "single_choice" -> "choice.exact:1";
          case "command_order" -> "order.exact:1";
          case "command_entry" -> "command.literal:1";
          default -> "unsupported";
        };
    if (!CAPABILITIES.contains(capability) || !capability.equals(expected))
      throw new ApiFailure(503, "unsupported_content");
    require(answer.isObject() && answer.size() == 2 && answer.path("type").asText().equals(kind));
    return switch (kind) {
      case "single_choice" -> choice(question, assessment, answer.path("option_id"));
      case "command_order" -> order(question, assessment, answer.path("step_ids"));
      default -> command(assessment, answer.path("command"));
    };
  }

  private static boolean choice(JsonNode question, JsonNode assessment, JsonNode value) {
    Set<String> ids = new HashSet<>();
    question.path("options").forEach(option -> ids.add(option.path("id").asText()));
    require(value.isTextual() && ids.contains(value.asText()));
    return value.equals(assessment.path("correct_option_id"));
  }

  private static boolean order(JsonNode question, JsonNode assessment, JsonNode value) {
    Set<String> expected = new HashSet<>();
    question.path("steps").forEach(step -> expected.add(step.path("id").asText()));
    require(value.isArray() && value.size() == expected.size());
    Set<String> seen = new HashSet<>();
    for (JsonNode step : value) require(step.isTextual() && seen.add(step.asText()));
    require(seen.equals(expected));
    return value.equals(assessment.path("correct_order"));
  }

  private static boolean command(JsonNode assessment, JsonNode value) {
    if (!assessment.path("normalization").asText().equals("literal-command-v1"))
      throw new ApiFailure(503, "unsupported_content");
    require(value.isTextual() && value.asText().length() <= 4096);
    String input = value.asText();
    require(input.codePoints().noneMatch(c -> c < 32 || c == 127));
    String normalized = input.replaceAll("^ +| +$", "");
    require(!normalized.isEmpty());
    return JsonData.strings(assessment.path("accepted_commands")).stream()
        .anyMatch(command -> command.replaceAll("^ +| +$", "").equals(normalized));
  }

  static void require(boolean valid) {
    if (!valid) throw new ApiFailure(400, "invalid_request");
  }
}
