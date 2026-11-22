package org.syskata.api.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.syskata.api.foundation.ApiFailure;

class TextGradingTest {
  @Test
  void exactChoicesAndPermutationsForAllPilotExercises() throws Exception {
    Bundle bundle = BundleTest.fixture("0.3.0");
    for (JsonNode question : bundle.entities("exercise")) {
      JsonNode assessment = bundle.assessment(question.path("id").asText());
      String type = question.path("type").asText();
      ObjectNode answer = JsonData.object().put("type", type);
      switch (type) {
        case "single_choice" -> {
          for (JsonNode option : question.path("options")) {
            answer.set("option_id", option.path("id"));
            assertEquals(
                option.path("id").equals(assessment.path("correct_option_id")),
                TextGrading.evaluate(question, assessment, answer));
          }
          answer.put("option_id", "not-issued");
          assertThrows(ApiFailure.class, () -> TextGrading.evaluate(question, assessment, answer));
        }
        case "command_order" -> {
          answer.set("step_ids", assessment.path("correct_order"));
          assertTrue(TextGrading.evaluate(question, assessment, answer));
          var reversed = JsonData.strings(assessment.path("correct_order"));
          java.util.Collections.reverse(reversed);
          answer.set("step_ids", JsonData.tree(reversed));
          assertFalse(TextGrading.evaluate(question, assessment, answer));
          for (JsonNode bad :
              List.of(
                  JsonData.tree("text"),
                  JsonData.tree(List.of()),
                  JsonData.tree(
                      java.util.Collections.nCopies(reversed.size(), reversed.getFirst())),
                  JsonData.tree(java.util.Collections.nCopies(reversed.size(), 5)))) {
            answer.set("step_ids", bad);
            assertThrows(
                ApiFailure.class, () -> TextGrading.evaluate(question, assessment, answer));
          }
          reversed.set(0, "unknown");
          answer.set("step_ids", JsonData.tree(reversed));
          assertThrows(ApiFailure.class, () -> TextGrading.evaluate(question, assessment, answer));
        }
        default -> {
          for (String command : JsonData.strings(assessment.path("accepted_commands"))) {
            answer.put("command", "  " + command + "  ");
            assertTrue(TextGrading.evaluate(question, assessment, answer));
            answer.put("command", command + "; whoami");
            assertFalse(TextGrading.evaluate(question, assessment, answer));
          }
        }
      }
      assertThrows(
          ApiFailure.class, () -> TextGrading.evaluate(question, assessment, JsonData.tree(null)));
      assertThrows(
          ApiFailure.class,
          () ->
              TextGrading.evaluate(
                  question, assessment, JsonData.tree(Map.of("type", "wrong", "value", "x"))));
      ObjectNode unsupported = assessment.deepCopy();
      unsupported.put("evaluator_version", 2);
      assertThrows(ApiFailure.class, () -> TextGrading.evaluate(question, unsupported, answer));
      unsupported.put("evaluator_version", 1);
      unsupported.put("evaluator", "fixture.choice");
      assertThrows(ApiFailure.class, () -> TextGrading.evaluate(question, unsupported, answer));
    }
  }

  @Test
  void literalCommandsRejectControlsAndPreserveSpacingCaseAndOperators() throws Exception {
    Bundle bundle = BundleTest.fixture("0.3.0");
    JsonNode question = bundle.entity("oc-orientation.project", "exercise");
    JsonNode assessment = bundle.assessment("oc-orientation.project");
    for (String input :
        List.of(
            "",
            "   ",
            "oc\tproject -q",
            "oc project -q\n",
            "oc project -q\u007f",
            "x".repeat(4097)))
      assertThrows(
          ApiFailure.class,
          () ->
              TextGrading.evaluate(
                  question,
                  assessment,
                  JsonData.tree(Map.of("type", "command_entry", "command", input))));
    for (String input :
        List.of(
            "OC project -q",
            "oc  project -q",
            "oc 'project' -q",
            "oc project -q | cat",
            "oc project -q > file",
            "$(oc project -q)",
            "\u00a0oc project -q"))
      assertFalse(
          TextGrading.evaluate(
              question,
              assessment,
              JsonData.tree(Map.of("type", "command_entry", "command", input))));
    assertThrows(
        ApiFailure.class,
        () ->
            TextGrading.evaluate(
                question,
                assessment,
                JsonData.tree(Map.of("type", "command_entry", "command", 5))));
    ObjectNode unknown = assessment.deepCopy();
    unknown.put("normalization", "unknown");
    assertThrows(
        ApiFailure.class,
        () ->
            TextGrading.evaluate(
                question,
                unknown,
                JsonData.tree(Map.of("type", "command_entry", "command", "oc project -q"))));
    ObjectNode lab = question.deepCopy();
    lab.put("type", "lab_task");
    assertThrows(ApiFailure.class, () -> TextGrading.evaluate(lab, assessment, JsonData.object()));
  }
}
