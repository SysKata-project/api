package org.syskata.api.learning;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.syskata.api.foundation.Accounts;
import org.syskata.api.foundation.ApiFailure;
import org.syskata.api.foundation.TestKeys;

@QuarkusTest
@QuarkusTestResource(TestKeys.class)
class AttemptTest {
  @Inject ContentCatalog catalog;
  @Inject AgroalDataSource database;
  @Inject Accounts accounts;
  @Inject LearningStore store;
  @Inject AttemptService service;
  UUID learner;
  String token;
  String path;
  JsonNode session;
  Bundle bundle;

  @BeforeEach
  void setup() throws Exception {
    catalog.index = java.util.Optional.empty();
    bundle = BundleTest.fixture("0.3.0");
    catalog.activate(bundle);
    String subject = "grading-" + UUID.randomUUID();
    learner = accounts.active("https://identity.test/realms/syskata", subject);
    token = TestKeys.token(subject, "syskata-api", Instant.now().getEpochSecond() + 300, "Bearer");
    post(
        "/v1/me/enrollments",
        Map.of("journey_id", "journey.openshift", "timezone", "America/Denver"),
        200);
    session =
        post(
            "/v1/learning-sessions",
            Map.of(
                "journey_id", "journey.openshift", "mode", "lesson", "lesson_id", "oc-orientation"),
            200);
    path = "/v1/learning-sessions/" + session.path("session_id").asText();
  }

  private JsonNode post(String url, Object body, int status) {
    return post(url, UUID.randomUUID().toString(), JsonData.tree(body).toString(), status);
  }

  private JsonNode post(String url, String key, String body, int status) {
    return JsonData.parse(
        given()
            .auth()
            .oauth2(token)
            .contentType("application/json")
            .header("Idempotency-Key", key)
            .body(body)
            .post(url)
            .then()
            .statusCode(status)
            .header("Cache-Control", "no-store")
            .extract()
            .asString());
  }

  private JsonNode get(String url, int status) {
    return JsonData.parse(
        given().auth().oauth2(token).get(url).then().statusCode(status).extract().asString());
  }

  private ObjectNode request(long revision) {
    ObjectNode body = JsonData.object();
    body.put("session_revision", revision);
    body.set("exercise_instance_id", session.path("current_instance").path("exercise_instance_id"));
    return body;
  }

  private JsonNode start() {
    JsonNode result = post(path + "/attempts", request(session.path("revision").asLong()), 200);
    session = get(path, 200);
    return result;
  }

  private String attemptPath(JsonNode started) {
    return path + "/attempts/" + started.path("attempt").path("attempt_id").asText();
  }

  private JsonNode answer(boolean correct) {
    JsonNode question = session.path("current_instance").path("question");
    JsonNode assessment = bundle.assessment(question.path("id").asText());
    ObjectNode value = JsonData.object();
    String type = question.path("type").asText();
    value.put("type", type);
    switch (type) {
      case "single_choice" -> {
        String id = assessment.path("correct_option_id").asText();
        if (!correct)
          for (JsonNode option : question.path("options"))
            if (!option.path("id").asText().equals(id)) {
              id = option.path("id").asText();
              break;
            }
        value.put("option_id", id);
      }
      case "command_order" -> {
        var steps = JsonData.strings(assessment.path("correct_order"));
        if (!correct) java.util.Collections.reverse(steps);
        value.set("step_ids", JsonData.tree(steps));
      }
      default ->
          value.put(
              "command",
              correct ? assessment.path("accepted_commands").get(0).asText() : "wrong command");
    }
    return value;
  }

  private ObjectNode submission(boolean correct) {
    ObjectNode body = request(session.path("revision").asLong());
    body.set("answer", answer(correct));
    return body;
  }

  private long count(String table) throws Exception {
    try (var c = database.getConnection();
        var q = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE learner_id=?")) {
      q.setObject(1, learner);
      try (var rows = q.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  private void contract(String operation, JsonNode body) throws Exception {
    Path root = Path.of("target/learning-contracts");
    Files.createDirectories(root);
    Files.writeString(
        root.resolve(operation + ".json"), JsonData.tree(Map.of("body", body)).toString());
  }

  @Test
  void assistedCompletionReplayAndRecovery() throws Exception {
    JsonNode started = start();
    contract("startAttempt", started);
    String attempt = attemptPath(started);
    String hint =
        session.path("current_instance").path("question").path("hint_ids").get(0).asText();
    JsonNode hinted =
        post(
            attempt + "/hints",
            Map.of("session_revision", session.path("revision").asLong(), "hint_id", hint),
            200);
    contract("requestHint", hinted);
    JsonNode again =
        post(
            attempt + "/hints",
            Map.of("session_revision", hinted.path("session_revision").asLong(), "hint_id", hint),
            200);
    assertEquals(hinted, again);
    JsonNode revealed =
        post(
            attempt + "/reveal",
            Map.of("session_revision", hinted.path("session_revision").asLong()),
            200);
    contract("revealAnswer", revealed);
    assertEquals(
        revealed,
        post(
            attempt + "/reveal",
            Map.of("session_revision", revealed.path("session_revision").asLong()),
            200));
    session = get(path, 200);
    assertTrue(session.path("current_attempt").path("assistance").path("revealed").asBoolean());
    String key = UUID.randomUUID().toString();
    String body = submission(true).toString();
    JsonNode result = post(attempt + "/submission", key, body, 200);
    contract("submitAttempt", result);
    assertEquals("assisted", result.path("evidence").asText());
    assertEquals("session_complete", result.path("next_action").path("type").asText());
    assertEquals(10, result.path("progress").path("total_xp").asInt());
    assertEquals("partial", result.path("progress").path("status").asText());
    assertFalse(result.path("progress").has("daily_goal_completed"));
    assertEquals(
        1, result.path("progress").path("skills").get(0).path("assisted_successes").asInt());
    assertEquals(result, post(attempt + "/submission", key, body, 200));
    assertEquals(
        "attempt_closed",
        post(attempt + "/submission", UUID.randomUUID().toString(), body, 409)
            .path("error")
            .path("code")
            .asText());
    ObjectNode changed = (ObjectNode) JsonData.parse(body);
    changed.put("session_revision", 20);
    assertEquals(
        "idempotency_conflict",
        post(attempt + "/submission", key, changed.toString(), 409)
            .path("error")
            .path("code")
            .asText());
    JsonNode detail = get(attempt, 200);
    contract("getAttempt", detail);
    assertEquals(result, detail.path("result"));
    assertEquals("submitted", detail.path("attempt").path("status").asText());
    assertEquals("completed", get(path, 200).path("status").asText());
    assertEquals(1, count("reward_events"));
    assertEquals(1, count("lesson_completion_events"));
    assertEquals(0, count("review_schedule"));
  }

  @Test
  void missedObjectivesRemainUntilIndependentRemediation() throws Exception {
    for (int i = 0; i < 4; i++) {
      JsonNode started = start();
      String attempt = attemptPath(started);
      if (i == 0)
        post(
            attempt + "/reveal",
            Map.of("session_revision", session.path("revision").asLong()),
            200);
      session = get(path, 200);
      JsonNode result = post(attempt + "/submission", submission(false), 200);
      assertEquals("incorrect", result.path("outcome").asText());
      assertTrue(result.path("rewards").isEmpty());
      assertEquals(
          i == 3 ? "remediate" : "continue", result.path("next_action").path("type").asText());
      session = get(path, 200);
      assertFalse(session.path("pending_objective_ids").isEmpty());
    }
    JsonNode started = start();
    assertFalse(started.path("attempt").path("assistance").path("revealed").asBoolean());
    JsonNode result = post(attemptPath(started) + "/submission", submission(true), 200);
    assertEquals("independent", result.path("evidence").asText());
    assertEquals(4, result.path("progress").path("skills").get(0).path("mistakes").asInt());
    assertEquals(
        1, result.path("progress").path("skills").get(0).path("independent_successes").asInt());
    assertEquals(1, count("reward_events"));
  }

  @Test
  void parallelSameAndDifferentKeysGradeOnce() throws Exception {
    JsonNode started = start();
    String url = attemptPath(started) + "/submission";
    String body = submission(true).toString();
    String key = UUID.randomUUID().toString();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures =
          executor.invokeAll(
              List.<Callable<JsonNode>>of(
                  () -> post(url, key, body, 200), () -> post(url, key, body, 200)));
      assertEquals(futures.get(0).get(), futures.get(1).get());
    }
    assertEquals(1, count("reward_events"));
    // A fresh lesson replay has a different business event but earns no second award.
    session =
        post(
            "/v1/learning-sessions",
            Map.of(
                "journey_id", "journey.openshift", "mode", "lesson", "lesson_id", "oc-orientation"),
            200);
    path = "/v1/learning-sessions/" + session.path("session_id").asText();
    String nextUrl = attemptPath(start()) + "/submission";
    String nextBody = submission(true).toString();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var results =
          executor.invokeAll(
              List.<Callable<Integer>>of(
                  () -> rawStatus(nextUrl, nextBody), () -> rawStatus(nextUrl, nextBody)));
      assertEquals(
          List.of(200, 409),
          java.util.stream.Stream.of(results.get(0).get(), results.get(1).get()).sorted().toList());
    }
    assertEquals(1, count("reward_events"));
    assertEquals(2, count("lesson_completion_events"));
  }

  private int rawStatus(String url, String body) {
    return given()
        .auth()
        .oauth2(token)
        .contentType("application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .body(body)
        .post(url)
        .statusCode();
  }

  @Test
  void rollbackAfterGradingRestoresEveryWrite() throws Exception {
    JsonNode started = start();
    ObjectNode body = submission(true);
    UUID id = UUID.fromString(started.path("attempt").path("attempt_id").asText());
    UUID sid = UUID.fromString(session.path("session_id").asText());
    String key = UUID.randomUUID().toString();
    long replays = count("idempotency_records");
    assertThrows(
        IllegalStateException.class,
        () ->
            store.mutate(
                learner,
                "rollback-probe",
                key,
                body,
                c -> {
                  service.submit(c, learner, sid, id, body);
                  throw new IllegalStateException("synthetic rollback");
                }));
    assertEquals(0, count("reward_events"));
    assertEquals(0, count("skill_progress"));
    assertEquals(0, count("lesson_completion_events"));
    assertEquals(replays, count("idempotency_records"));
    assertEquals(session, get(path, 200));
    assertTrue(get(attemptPath(started), 200).path("result").isNull());
    post(attemptPath(started) + "/submission", key, body.toString(), 200);
  }

  @Test
  void ownershipStaleStateAndInvalidBodies() throws Exception {
    JsonNode started = start();
    String attempt = attemptPath(started);
    ObjectNode valid = submission(true);
    assertEquals(
        started, post(path + "/attempts", request(session.path("revision").asLong()), 200));
    // Equivalent start is a no-op: started snapshot carries the same current revision.
    String originalToken = token;
    token =
        TestKeys.token(
            "other-" + UUID.randomUUID(),
            "syskata-api",
            Instant.now().getEpochSecond() + 300,
            "Bearer");
    get(attempt, 404);
    post(attempt + "/submission", valid, 404);
    post(path + "/attempts", request(2), 404);
    token = originalToken;
    get(path + "/attempts/" + UUID.randomUUID(), 404);
    get(path + "/attempts/not-a-uuid", 404);
    ObjectNode stale = valid.deepCopy();
    stale.put("session_revision", 1);
    JsonNode error = post(attempt + "/submission", stale, 409).path("error");
    assertEquals("stale_revision", error.path("code").asText());
    assertEquals(2, error.path("current_revision").asLong());
    ObjectNode wrong = valid.deepCopy();
    wrong.put("exercise_instance_id", UUID.randomUUID().toString());
    post(attempt + "/submission", wrong, 409);
    for (String text :
        List.of(
            "null",
            "{}",
            valid.toString().replace("\"session_revision\":2", "\"session_revision\":0"),
            valid.toString().replace("\"session_revision\":2", "\"session_revision\":2.5"),
            valid
                .toString()
                .replace(
                    "\"session_revision\":2", "\"session_revision\":2,\"session_revision\":2")))
      post(attempt + "/submission", UUID.randomUUID().toString(), text, 400);
    wrong = valid.deepCopy();
    wrong.put("extra", true);
    post(attempt + "/submission", wrong, 400);
    wrong = valid.deepCopy();
    wrong.putObject("answer").put("type", "single_choice").put("option_id", "not-issued");
    post(attempt + "/submission", wrong, 400);
    post(attempt + "/hints", Map.of("session_revision", 2, "hint_id", "hint.missing"), 400);
    post(attempt + "/submission", "bad", valid.toString(), 400);
    assertEquals(0, count("skill_progress"));
    post(attempt + "/submission", valid, 200);
    post(attempt + "/hints", Map.of("session_revision", 3, "hint_id", "hint.reason"), 409);
    post(path + "/attempts", request(3), 409);
    try (var c = database.getConnection();
        var q = c.prepareStatement("UPDATE learners SET disabled_at=now() WHERE id=?")) {
      q.setObject(1, learner);
      q.executeUpdate();
    }
    get(attempt, 403);
  }

  @Test
  void pinnedVersionSurvivesActivationAndReplay() throws Exception {
    JsonNode started = start();
    String attempt = attemptPath(started);
    String body = submission(true).toString();
    String key = UUID.randomUUID().toString();
    catalog.activate(BundleTest.fixture("0.2.0"));
    JsonNode result = post(attempt + "/submission", key, body, 200);
    assertEquals("0.3.0", result.path("versions").path("bundle_version").asText());
    assertEquals(result, post(attempt + "/submission", key, body, 200));
    assertThrows(ApiFailure.class, () -> catalog.pinned("99.0.0"));
  }

  @Test
  void hintSubmissionRaceAndAwaitingAttemptFreeze() throws Exception {
    JsonNode started = start();
    String attempt = attemptPath(started);
    String submission = submission(true).toString();
    String hintId =
        session.path("current_instance").path("question").path("hint_ids").get(0).asText();
    String hint =
        JsonData.tree(
                Map.of("session_revision", session.path("revision").asLong(), "hint_id", hintId))
            .toString();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var results =
          executor.invokeAll(
              List.<Callable<Integer>>of(
                  () -> rawStatus(attempt + "/hints", hint),
                  () -> rawStatus(attempt + "/submission", submission)));
      assertEquals(
          List.of(200, 409),
          java.util.stream.Stream.of(results.get(0).get(), results.get(1).get()).sorted().toList());
      if (results.get(0).get() == 200) {
        session = get(path, 200);
        assertEquals(
            "assisted",
            post(attempt + "/submission", submission(true), 200).path("evidence").asText());
      } else
        assertEquals("independent", get(attempt, 200).path("result").path("evidence").asText());
    }
    assertEquals(1, count("reward_events"));
  }

  @Test
  void awaitingAssessmentAndStoredReviewCannotBeMutated() throws Exception {
    JsonNode started = start();
    String attempt = attemptPath(started);
    try (var c = database.getConnection();
        var q = c.prepareStatement("UPDATE attempts SET status='awaiting_assessment' WHERE id=?")) {
      q.setObject(1, UUID.fromString(started.path("attempt").path("attempt_id").asText()));
      q.executeUpdate();
    }
    post(attempt + "/reveal", Map.of("session_revision", session.path("revision").asLong()), 409);
    post(attempt + "/submission", submission(true), 409);
    assertEquals(
        "awaiting_assessment",
        post(path + "/attempts", request(session.path("revision").asLong()), 200)
            .path("attempt")
            .path("status")
            .asText());
    try (var c = database.getConnection();
        var q =
            c.prepareStatement(
                "UPDATE learning_sessions SET mode='review',selection_state=jsonb_set(jsonb_set(selection_state,'{mode}','\"review\"'),'{response,mode}','\"review\"') WHERE id=?")) {
      q.setObject(1, UUID.fromString(session.path("session_id").asText()));
      q.executeUpdate();
    }
    post(path + "/attempts", request(session.path("revision").asLong()), 409);
    post(
        "/v1/learning-sessions",
        Map.of("journey_id", "journey.openshift", "mode", "auto", "lesson_id", "oc-orientation"),
        400);
    post(
        "/v1/learning-sessions",
        JsonData.parse(
            "{\"journey_id\":\"journey.openshift\",\"mode\":\"auto\",\"lesson_id\":null}"),
        409);
  }
}
