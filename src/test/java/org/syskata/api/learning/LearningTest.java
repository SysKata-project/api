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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.syskata.api.foundation.Accounts;
import org.syskata.api.foundation.ApiFailure;
import org.syskata.api.foundation.TestKeys;

@QuarkusTest
@QuarkusTestResource(TestKeys.class)
class LearningTest {
  @Inject ContentCatalog catalog;
  @Inject AgroalDataSource database;
  @Inject Accounts accounts;
  @Inject LearningStore store;
  String bearer;
  UUID learner;

  @BeforeEach
  void setup() throws Exception {
    catalog.index = java.util.Optional.empty();
    catalog.activate(BundleTest.fixture("0.3.0"));
    String subject = "learning-" + UUID.randomUUID();
    learner = accounts.active("https://identity.test/realms/syskata", subject);
    bearer = TestKeys.token(subject, "syskata-api", Instant.now().getEpochSecond() + 300, "Bearer");
  }

  private JsonNode post(String path, String key, String body, int status) {
    return JsonData.parse(
        given()
            .auth()
            .oauth2(bearer)
            .contentType("application/json")
            .header("Idempotency-Key", key)
            .body(body)
            .post(path)
            .then()
            .statusCode(status)
            .header("Cache-Control", "no-store")
            .extract()
            .asString());
  }

  private JsonNode get(String path, int status) {
    return JsonData.parse(
        given().auth().oauth2(bearer).get(path).then().statusCode(status).extract().asString());
  }

  private JsonNode enroll() {
    return post(
        "/v1/me/enrollments",
        UUID.randomUUID().toString(),
        "{\"journey_id\":\"journey.openshift\",\"timezone\":\"America/Denver\"}",
        200);
  }

  private String request(String mode, String lesson) {
    return "{\"journey_id\":\"journey.openshift\",\"mode\":\""
        + mode
        + "\",\"lesson_id\":"
        + (lesson == null ? "null" : "\"" + lesson + "\"")
        + "}";
  }

  private JsonNode start(String mode, String lesson, int status) {
    return post(
        "/v1/learning-sessions", UUID.randomUUID().toString(), request(mode, lesson), status);
  }

  private void contract(String operation, JsonNode response) throws Exception {
    Path root = Path.of("target/learning-contracts");
    Files.createDirectories(root);
    Files.writeString(
        root.resolve(operation + ".json"),
        JsonData.tree(Map.of("operation_id", operation, "body", response)).toString());
  }

  @Test
  void catalogEnrollmentSelectionAndResume() throws Exception {
    contract("listJourneys", get("/v1/journeys", 200));
    assertEquals("ineligible", start("auto", null, 409).path("error").path("code").asText());
    JsonNode enrollment = enroll();
    contract("enroll", enrollment);
    assertEquals(enrollment, enroll());
    JsonNode path = get("/v1/me/journeys/journey.openshift", 200);
    contract("getJourney", path);
    assertEquals("available", path.path("lessons").get(0).path("status").asText());
    assertEquals("locked", path.path("lessons").get(1).path("status").asText());
    start("lesson", "oc-observe", 409);
    JsonNode session = start("auto", null, 200);
    contract("startSession", session);
    assertEquals("oc-orientation", session.path("lesson_id").asText());
    assertEquals(
        "oc-orientation.boundary",
        session.path("current_instance").path("question").path("id").asText());
    assertEquals(session, start("review", null, 200));
    assertEquals(session, get("/v1/learning-sessions/" + session.path("session_id").asText(), 200));
    assertFalse(session.toString().contains("correct_option_id"));
    assertFalse(session.toString().contains("accepted_commands"));
    // Recreate application service/cache state: persisted presentation survives interruption.
    assertEquals(
        session,
        JsonData.parse(
            new LearningStore(database)
                .read(learner, UUID.fromString(session.path("session_id").asText()))
                .toString()));
  }

  @Test
  void idempotencyConcurrencyAndOwnership() throws Exception {
    String key = UUID.randomUUID().toString();
    String body = "{\"journey_id\":\"journey.openshift\",\"timezone\":\"America/Denver\"}";
    JsonNode first = post("/v1/me/enrollments", key, body, 200);
    assertEquals(
        first,
        post(
            "/v1/me/enrollments",
            key,
            "{\"timezone\":\"America/Denver\",\"journey_id\":\"journey.openshift\"}",
            200));
    assertEquals(
        "idempotency_conflict",
        post("/v1/me/enrollments", key, body.replace("America/Denver", "UTC"), 409)
            .path("error")
            .path("code")
            .asText());
    String sessionKey = UUID.randomUUID().toString();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures =
          executor.invokeAll(
              List.of(
                  () -> post("/v1/learning-sessions", sessionKey, request("auto", null), 200),
                  () -> post("/v1/learning-sessions", sessionKey, request("auto", null), 200),
                  () -> start("auto", null, 200)));
      assertEquals(futures.get(0).get(), futures.get(1).get());
      assertEquals(futures.get(0).get(), futures.get(2).get());
    }
    JsonNode session = start("auto", null, 200);
    String stranger =
        TestKeys.token(
            "stranger-" + UUID.randomUUID(),
            "syskata-api",
            Instant.now().getEpochSecond() + 300,
            "Bearer");
    given()
        .auth()
        .oauth2(stranger)
        .get("/v1/learning-sessions/" + session.path("session_id").asText())
        .then()
        .statusCode(404);
    get("/v1/learning-sessions/not-a-uuid", 404);
    get("/v1/learning-sessions/" + UUID.randomUUID(), 404);
    assertThrows(
        ApiFailure.class,
        () -> store.read(UUID.randomUUID(), UUID.fromString(session.path("session_id").asText())));
  }

  private void complete(String... lessons) throws Exception {
    try (var c = database.getConnection();
        var q =
            c.prepareStatement(
                "INSERT INTO reward_events(id,learner_id,kind,activity_id,timezone,xp) VALUES (?,?,'lesson',?,'UTC',10)")) {
      for (String lesson : lessons) {
        q.setObject(1, UUID.randomUUID());
        q.setObject(2, learner);
        q.setString(3, lesson);
        q.executeUpdate();
      }
    }
    try (var c = database.getConnection();
        var q =
            c.prepareStatement(
                "INSERT INTO skill_progress(learner_id,skill_id,assisted_successes) VALUES (?,?,1) ON CONFLICT DO NOTHING")) {
      Bundle bundle = BundleTest.fixture("0.3.0");
      for (String lesson : lessons)
        for (JsonNode objective : bundle.entity(lesson, "lesson").path("objectives"))
          for (String skill : JsonData.strings(objective.path("skill_ids"))) {
            q.setObject(1, learner);
            q.setString(2, skill);
            q.executeUpdate();
          }
    }
  }

  private void due(String skill, String time) throws Exception {
    try (var c = database.getConnection()) {
      try (var q =
          c.prepareStatement(
              "INSERT INTO skill_progress(learner_id,skill_id,assisted_successes) VALUES (?,?,1) ON CONFLICT DO NOTHING")) {
        q.setObject(1, learner);
        q.setString(2, skill);
        q.executeUpdate();
      }
      try (var q =
          c.prepareStatement(
              "INSERT INTO review_schedule(learner_id,skill_id,due_at,interval_days) VALUES (?,?,?::timestamptz,1)")) {
        q.setObject(1, learner);
        q.setString(2, skill);
        q.setString(3, time);
        q.executeUpdate();
      }
    }
  }

  @Test
  void dueReviewOrderAndLessonPrerequisites() throws Exception {
    enroll();
    start("review", null, 409);
    complete("oc-orientation");
    assertEquals(
        "completed",
        get("/v1/me/journeys/journey.openshift", 200)
            .path("lessons")
            .get(0)
            .path("status")
            .asText());
    due("skill.unavailable", "2020-01-01T00:00:00Z");
    due("skill.oc.context", "2020-01-02T00:00:00Z");
    due("skill.oc.observe", "2020-01-03T00:00:00Z");
    assertEquals("review", start("review", null, 200).path("mode").asText());
    assertEquals("review", start("auto", null, 200).path("mode").asText());
    try (var c = database.getConnection()) {
      var plan =
          SessionSelection.choose(
              c, learner, BundleTest.fixture("0.3.0"), JsonData.parse(request("review", null)));
      assertEquals("review", plan.path("mode").asText());
      assertEquals("2020-01-02T00:00:00Z", plan.path("review_due_at").asText());
    }
  }

  @Test
  void nextLessonReplayAndNoEligibleWork() throws Exception {
    enroll();
    complete("oc-orientation");
    assertEquals("oc-observe", start("auto", null, 200).path("lesson_id").asText());
    try (var c = database.getConnection();
        var q =
            c.prepareStatement(
                "UPDATE learning_sessions SET status='completed' WHERE learner_id=?")) {
      q.setObject(1, learner);
      q.executeUpdate();
    }
    assertEquals(
        "oc-orientation", start("lesson", "oc-orientation", 200).path("lesson_id").asText());
  }

  @Test
  void versionActivationRollbackAndAttemptRecovery() throws Exception {
    catalog.activate(BundleTest.fixture("0.2.0"));
    enroll();
    JsonNode original = start("auto", null, 200);
    UUID session = UUID.fromString(original.path("session_id").asText());
    catalog.activate(BundleTest.fixture("0.3.0"));
    assertEquals(
        "0.3.0", get("/v1/journeys", 200).path("versions").path("bundle_version").asText());
    assertEquals(original, start("auto", null, 200));
    catalog.activate(BundleTest.fixture("0.2.0"));
    assertEquals(original, get("/v1/learning-sessions/" + session, 200));
    try (var c = database.getConnection();
        var q =
            c.prepareStatement(
                "INSERT INTO attempts(id,instance_id,session_id,learner_id,status,hints,revealed,assisted) VALUES (?,?,?,?,'open','[\"hint.test\"]',true,true)")) {
      q.setObject(1, UUID.randomUUID());
      q.setObject(
          2,
          UUID.fromString(original.path("current_instance").path("exercise_instance_id").asText()));
      q.setObject(3, session);
      q.setObject(4, learner);
      q.executeUpdate();
    }
    JsonNode resumed = get("/v1/learning-sessions/" + session, 200);
    contract("getSession", resumed);
    assertTrue(resumed.path("current_attempt").path("assistance").path("revealed").asBoolean());
    assertEquals(
        List.of("hint.test"),
        JsonData.strings(resumed.path("current_attempt").path("assistance").path("hint_ids")));
    try (var c = database.getConnection();
        var q =
            c.prepareStatement(
                "UPDATE learning_sessions SET status='completed',revision=2 WHERE id=?")) {
      q.setObject(1, session);
      q.executeUpdate();
    }
    JsonNode done = get("/v1/learning-sessions/" + session, 200);
    assertTrue(done.path("current_instance").isNull());
    assertTrue(done.path("current_attempt").isNull());
    assertTrue(done.path("pending_objective_ids").isEmpty());
    assertEquals(2, done.path("revision").asInt());
  }

  @Test
  void validationAndTransactionRollback() throws Exception {
    String key = UUID.randomUUID().toString();
    for (String invalid :
        List.of(
            "null",
            "{}",
            "{\"journey_id\":\"bad/id\",\"timezone\":\"UTC\"}",
            "{\"journey_id\":\"journey.openshift\",\"timezone\":\"unknown\"}",
            "{\"journey_id\":\"journey.openshift\",\"timezone\":\"UTC\",\"extra\":1}",
            "{\"journey_id\":\"journey.openshift\",\"timezone\":\"UTC\",\"timezone\":\"UTC\"}"))
      post("/v1/me/enrollments", key, invalid, 400);
    post(
        "/v1/me/enrollments",
        "bad",
        "{\"journey_id\":\"journey.openshift\",\"timezone\":\"UTC\"}",
        400);
    enroll();
    post(
        "/v1/me/enrollments",
        key,
        "{\"journey_id\":\"journey.openshift\",\"timezone\":\"UTC\"}",
        409);
    for (String invalid :
        List.of(request("bad", null), request("auto", "oc-orientation"), request("lesson", null)))
      post("/v1/learning-sessions", key, invalid, 400);
    get("/v1/me/journeys/missing", 404);
    assertThrows(
        IllegalStateException.class,
        () ->
            store.mutate(
                learner,
                "test",
                key,
                JsonData.object(),
                c -> {
                  try (var q =
                      c.prepareStatement("UPDATE learners SET timezone='UTC' WHERE id=?")) {
                    q.setObject(1, learner);
                    q.executeUpdate();
                  }
                  throw new IllegalStateException("synthetic rollback");
                }));
    try (var c = database.getConnection();
        var q = c.prepareStatement("SELECT timezone FROM learners WHERE id=?")) {
      q.setObject(1, learner);
      try (var rows = q.executeQuery()) {
        rows.next();
        assertEquals("America/Denver", rows.getString(1));
      }
    }
  }

  @Test
  void hashCacheReloadRetentionAndImmutableRegistration() throws Exception {
    Path root = Files.createTempDirectory("syskata-content-test-");
    try {
      for (String version : List.of("0.2.0", "0.3.0")) {
        Files.createDirectory(root.resolve(version));
        for (String file : List.of("manifest.json", "learner.json", "assessment.json"))
          Files.copy(
              BundleTest.directory(version).resolve(file), root.resolve(version).resolve(file));
      }
      // Foundation's constraint-only fixture has no readable session representation.
      // Remove only those synthetic sessions from this disposable test database.
      try (var c = database.getConnection();
          var q = c.createStatement()) {
        q.executeUpdate("DELETE FROM learning_sessions WHERE bundle_version='test'");
      }
      catalog.activate(BundleTest.fixture("0.2.0"));
      enroll();
      JsonNode session = start("auto", null, 200);
      class FixtureCatalog extends ContentCatalog {
        int loads;

        @Override
        Bundle load(Path directory, String hash) throws java.io.IOException {
          loads++;
          return BundleReader.read(directory, hash, BundleTest.CAPABILITIES, true);
        }
      }
      FixtureCatalog reloaded = new FixtureCatalog();
      reloaded.database = database;
      reloaded.index = java.util.Optional.of(root.resolve("index.json").toString());
      var entries = new java.util.ArrayList<Map<String, String>>();
      for (String version : List.of("0.2.0", "0.3.0"))
        entries.add(
            Map.of(
                "version",
                version,
                "manifest_sha256",
                JsonData.hash(Files.readAllBytes(root.resolve(version).resolve("manifest.json")))));
      Files.writeString(
          root.resolve("index.json"),
          JsonData.tree(Map.of("active", "0.3.0", "releases", entries)).toString());
      assertEquals("0.3.0", reloaded.current().version());
      assertEquals("0.3.0", reloaded.current().version());
      assertEquals(2, reloaded.loads);
      Files.writeString(
          root.resolve("index.json"),
          JsonData.tree(Map.of("active", "0.2.0", "releases", entries)).toString());
      assertEquals("0.2.0", reloaded.current().version());
      assertEquals(2, reloaded.loads);
      Files.writeString(
          root.resolve("index.json"),
          JsonData.tree(Map.of("active", "0.3.0", "releases", List.of(entries.get(1)))).toString());
      assertThrows(ApiFailure.class, reloaded::reload);
      assertEquals(
          session, get("/v1/learning-sessions/" + session.path("session_id").asText(), 200));
      ContentCatalog production = new ContentCatalog();
      production.database = database;
      production.index = java.util.Optional.empty();
      assertThrows(ApiFailure.class, production::current);
      production.index = reloaded.index;
      assertThrows(ApiFailure.class, production::reload);
      Bundle bundle = BundleTest.fixture("0.3.0");
      var changed = (com.fasterxml.jackson.databind.node.ObjectNode) bundle.manifest();
      changed.put("source_commit", "d".repeat(40));
      JsonNode trusted =
          JsonData.parse(
              Files.readString(BundleTest.directory("0.3.0").resolve("assessment.json")));
      assertThrows(
          ApiFailure.class,
          () -> catalog.activate(new Bundle(changed, BundleReader.projection(trusted), trusted)));
    } finally {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
          Files.delete(path);
      }
    }
  }
}
