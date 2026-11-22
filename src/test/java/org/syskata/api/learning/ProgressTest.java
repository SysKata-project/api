package org.syskata.api.learning;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.syskata.api.foundation.Accounts;
import org.syskata.api.foundation.ApiFailure;
import org.syskata.api.foundation.TestKeys;

@QuarkusTest
@QuarkusTestResource(TestKeys.class)
class ProgressTest {
  @Inject Accounts accounts;
  @Inject AgroalDataSource database;
  @Inject ProgressService service;
  @Inject LearningStore store;
  UUID learner;
  String token;

  @BeforeEach
  void setup() throws Exception {
    String subject = "progress-" + UUID.randomUUID();
    learner = accounts.active("https://identity.test/realms/syskata", subject);
    token = TestKeys.token(subject, "syskata-api", Instant.now().getEpochSecond() + 300, "Bearer");
  }

  private JsonNode change(String key, String body, int status) {
    return JsonData.parse(
        given()
            .auth()
            .oauth2(token)
            .contentType("application/json")
            .header("Idempotency-Key", key)
            .body(body)
            .post("/v1/me/timezone")
            .then()
            .statusCode(status)
            .header("Cache-Control", "no-store")
            .extract()
            .asString());
  }

  private void contract(String operation, JsonNode body) throws Exception {
    Path root = Path.of("target/learning-contracts");
    Files.createDirectories(root);
    Files.writeString(
        root.resolve(operation + ".json"),
        JsonData.tree(Map.of("operation_id", operation, "body", body)).toString());
  }

  @Test
  void authenticatedProgressTimezoneRetriesAndValidation() throws Exception {
    given().get("/v1/me/progress").then().statusCode(401);
    JsonNode empty =
        JsonData.parse(
            given()
                .auth()
                .oauth2(token)
                .get("/v1/me/progress")
                .then()
                .statusCode(200)
                .header("Cache-Control", "no-store")
                .extract()
                .asString());
    assertEquals(0, empty.path("total_xp").asInt());
    assertFalse(empty.path("daily_goal_completed").asBoolean());
    contract("getProgress", empty);
    for (String invalid :
        new String[] {
          "null",
          "{}",
          "{\"timezone\":\"Mars/Base\"}",
          "{\"timezone\":\"+02:00\"}",
          "{\"timezone\":1}",
          "{\"timezone\":\"UTC\",\"extra\":1}"
        }) change(UUID.randomUUID().toString(), invalid, 400);
    change("bad", "{\"timezone\":\"UTC\"}", 400);
    JsonNode noop = change(UUID.randomUUID().toString(), "{\"timezone\":\"UTC\"}", 200);
    assertEquals("UTC", noop.path("timezone").asText());
    String key = UUID.randomUUID().toString();
    String body = "{\"timezone\":\"America/Denver\"}";
    JsonNode result = change(key, body, 200);
    contract("updateTimezone", result);
    assertEquals(result, change(key, body, 200));
    assertEquals(result, change(UUID.randomUUID().toString(), body, 200));
    change(key, "{\"timezone\":\"Asia/Tokyo\"}", 409);
    change(UUID.randomUUID().toString(), "{\"timezone\":\"Asia/Tokyo\"}", 409);
    assertEquals(result, service.read(learner).path("pending_timezone_change"));
    assertTrue(service.read(learner).path("revision").asLong() > empty.path("revision").asLong());
  }

  @Test
  void transitionAppliesOnceAndBlocksOverlappingChangesUntilFullDay() throws Exception {
    Instant now = Instant.parse("2026-11-01T12:00:00Z");
    try (var c = database.getConnection()) {
      c.setAutoCommit(false);
      LearningStore.lockLearner(c, learner);
      JsonNode change = service.timezone(c, learner, "America/Denver", now);
      assertEquals("2026-11-02T00:00:00Z", change.path("effective_at").asText());
      Instant partial = Instant.parse("2026-11-02T03:00:00Z");
      PracticeDays days = PracticeDays.load(c, learner, partial);
      assertEquals("America/Denver", days.zone());
      assertEquals(Instant.parse("2026-11-01T00:00:00Z"), days.guardStart());
      assertEquals(change, service.timezone(c, learner, "America/Denver", partial));
      assertThrows(ApiFailure.class, () -> service.timezone(c, learner, "Asia/Tokyo", partial));
      var next = service.timezone(c, learner, "Asia/Tokyo", Instant.parse("2026-11-02T07:00:00Z"));
      assertEquals("2026-11-03T07:00:00Z", next.path("effective_at").asText());
      assertEquals("America/Denver", PracticeDays.load(c, learner, partial).zone());
      c.rollback();
    }
  }

  @Test
  void historicalPartialReplayIsNeverRecomputed() throws Exception {
    String key = UUID.randomUUID().toString();
    JsonNode body = JsonData.object();
    JsonNode partial = JsonData.parse("{\"progress\":{\"status\":\"partial\",\"revision\":1}}");
    assertEquals(partial, store.mutate(learner, "historical", key, body, c -> partial));
    JsonNode replay =
        store.mutate(
            learner,
            "historical",
            key,
            body,
            c -> {
              throw new AssertionError("Historical response must bypass new policy");
            });
    assertEquals(partial, replay);
    assertFalse(replay.path("progress").has("current_streak"));
  }

  @Test
  void reviewRewardGuardSpansTransitionWithoutCreditingPractice() throws Exception {
    try (var c = database.getConnection()) {
      c.setAutoCommit(false);
      LearningStore.lockLearner(c, learner);
      Instant outgoing = Instant.parse("2026-11-01T12:00:00Z");
      service.timezone(c, learner, "America/Denver", outgoing);
      assertEquals(
          5, ReviewProgress.reward(c, learner, "skill.first", outgoing).path("xp").asInt());
      Instant partial = Instant.parse("2026-11-02T03:00:00Z");
      assertEquals(null, ReviewProgress.reward(c, learner, "skill.first", partial));
      assertEquals(
          5, ReviewProgress.reward(c, learner, "skill.second", partial).path("xp").asInt());
      assertEquals(null, ReviewProgress.reward(c, learner, "skill.second", partial));
      var snapshot = GradeProgress.snapshot(c, learner, partial);
      assertEquals(10, snapshot.path("total_xp").asInt());
      assertEquals(0, snapshot.path("current_streak").asInt());
      assertFalse(snapshot.path("daily_goal_completed").asBoolean());
      assertTrue(snapshot.path("transition_day").asBoolean());
      assertEquals(
          5,
          ReviewProgress.reward(c, learner, "skill.first", Instant.parse("2026-11-02T07:00:00Z"))
              .path("xp")
              .asInt());
      c.rollback();
    }
  }
}
