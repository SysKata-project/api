package org.syskata.api.learning;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.syskata.api.foundation.Accounts;
import org.syskata.api.foundation.TestKeys;

@QuarkusTest
@QuarkusTestResource(TestKeys.class)
class LabTest {
  @Inject ContentCatalog catalog;
  @Inject AgroalDataSource database;
  @Inject Accounts accounts;
  @Inject LabService service;
  @Inject LabDispatcher dispatcher;
  @Inject FakeLabs labs;
  @Inject ResultService results;
  @Inject LabAccess access;
  UUID learner;
  String token;
  JsonNode session;
  JsonNode attempt;
  JsonNode lab;
  String path;

  @BeforeEach
  void setup() throws Exception {
    labs.unavailable = false;
    labs.loseResponse = false;
    service.worker = "worker-test";
    catalog.index = java.util.Optional.empty();
    catalog.activate(fixture());
    String subject = "labs-" + UUID.randomUUID();
    learner = accounts.active("https://identity.test/realms/syskata", subject);
    token = TestKeys.token(subject, "syskata-api", Instant.now().getEpochSecond() + 300, "Bearer");
    post("/v1/me/enrollments", Map.of("journey_id", "journey.openshift", "timezone", "UTC"), 200);
    session =
        post(
            "/v1/learning-sessions",
            Map.of(
                "journey_id", "journey.openshift", "mode", "lesson", "lesson_id", "oc-orientation"),
            200);
    path = "/v1/learning-sessions/" + session.path("session_id").asText();
    start();
  }

  @AfterEach
  void cleanup() throws Exception {
    if (learner != null)
      try (var c = database.getConnection()) {
        LabSql.update(c, "DELETE FROM learners WHERE id=?", learner);
      }
  }

  static Bundle fixture() throws Exception {
    return fixture("foundations");
  }

  static Bundle fixture(String scenario) throws Exception {
    var original = BundleTest.fixture("0.3.0");
    var trusted = JsonData.object();
    trusted.put("schema_version", "1.0.0");
    trusted.put("bundle_version", scenario.equals("foundations") ? "0.5.0" : "0.5.1");
    var entities = trusted.putArray("entities");
    for (String kind : List.of("reference", "skill", "journey", "unit", "lesson", "exercise"))
      for (JsonNode entity : original.entities(kind))
        entities.add(original.trusted(entity.path("id").asText()));
    for (var entity : entities) {
      var e = (ObjectNode) entity;
      if (e.path("id").asText().equals("oc-orientation"))
        e.putArray("exercise_ids").add("checkpoint.oc-" + scenario);
    }
    ObjectNode question = JsonData.object();
    // Pick an existing orientation exercise without depending on its authored ID.
    for (var e : entities)
      if (e.path("kind").asText().equals("exercise")
          && e.path("id").asText().startsWith("oc-orientation.")) {
        question = e.deepCopy();
        break;
      }
    question.put("id", "checkpoint.oc-" + scenario);
    question.put("type", "lab_task");
    question.remove(List.of("options", "steps"));
    question.put("reset_behavior", "new-generation");
    question.set(
        "runtime",
        JsonData.tree(
            Map.of(
                "product",
                "openshift",
                "curriculum_version",
                "4.22",
                "tested_versions",
                List.of("4.22.22"),
                "requirements",
                List.of("Synthetic integration fixture"))));
    var a = question.putObject("assessment");
    a.put("evaluator", "openshift." + scenario);
    a.put("evaluator_version", 1);
    a.put("completion_evidence", "trusted-lab-result");
    a.put("reveal_markdown", "Synthetic help");
    var checks = a.putArray("checks");
    var feedback = a.putArray("feedback");
    for (String name :
        scenario.equals("foundations")
            ? List.of("context", "deployment", "service", "route", "response", "repair")
            : List.of("context", "manifest", "access", "quota")) {
      checks
          .addObject()
          .put("criterion_id", "criterion." + name)
          .put("evaluator", "openshift." + scenario)
          .put("evaluator_version", 1)
          .putObject("parameters");
      var f = feedback.addObject();
      f.put("criterion_id", "criterion." + name);
      f.put("explanation", "Synthetic criterion feedback");
      f.put("misconception_code", "misconception.fixture");
      f.set("reference_ids", question.path("source").path("reference_ids"));
    }
    entities.add(question);
    var manifest = (ObjectNode) original.manifest();
    manifest.put("bundle_version", scenario.equals("foundations") ? "0.5.0" : "0.5.1");
    return new Bundle(manifest, BundleReader.projection(trusted), trusted);
  }

  private JsonNode post(String url, Object body, int code) {
    return post(url, UUID.randomUUID().toString(), body, code);
  }

  private JsonNode post(String url, String key, Object body, int code) {
    return JsonData.parse(
        given()
            .auth()
            .oauth2(token)
            .contentType("application/json")
            .header("Idempotency-Key", key)
            .body(JsonData.tree(body).toString())
            .post(url)
            .then()
            .statusCode(code)
            .extract()
            .asString());
  }

  private JsonNode get(String url) {
    return JsonData.parse(
        given().auth().oauth2(token).get(url).then().statusCode(200).extract().asString());
  }

  private void refresh() {
    session = get(path);
  }

  private void start() {
    refresh();
    attempt =
        post(
                path + "/attempts",
                Map.of(
                    "session_revision",
                    session.path("revision").asLong(),
                    "exercise_instance_id",
                    session.path("current_instance").path("exercise_instance_id").asText()),
                200)
            .path("attempt");
    refresh();
  }

  private JsonNode create() {
    var body =
        Map.of(
            "session_id",
            session.path("session_id").asText(),
            "session_revision",
            session.path("revision").asLong(),
            "exercise_instance_id",
            attempt.path("exercise_instance_id").asText(),
            "attempt_id",
            attempt.path("attempt_id").asText());
    String key = UUID.randomUUID().toString();
    lab = post("/v1/lab-sessions", key, body, 202);
    assertEquals(lab, post("/v1/lab-sessions", key, body, 202));
    refresh();
    return lab;
  }

  private void ready() throws Exception {
    create();
    dispatcher.tick();
    lab = get("/v1/lab-sessions/" + lab.path("lab_session_id").asText());
    assertEquals("ready", lab.path("status").asText());
  }

  private JsonNode assess() {
    refresh();
    var accepted =
        post(
            "/v1/lab-sessions/" + lab.path("lab_session_id").asText() + "/assessments",
            Map.of(
                "session_revision",
                session.path("revision").asLong(),
                "generation",
                lab.path("generation").asLong(),
                "attempt_id",
                attempt.path("attempt_id").asText()),
            202);
    refresh();
    return accepted;
  }

  private ObjectNode result(JsonNode accepted, boolean correct) throws Exception {
    try (var c = database.getConnection();
        var q =
            LabSql.query(
                c,
                "SELECT dispatch FROM assessments WHERE id=?",
                UUID.fromString(accepted.path("assessment_id").asText()));
        var r = q.executeQuery()) {
      r.next();
      var d = JsonData.parse(r.getString(1));
      var b = JsonData.object();
      Map<String, String> bindings =
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
      bindings.forEach((k, v) -> b.set(k, d.path(v)));
      b.put("observed_at", Instant.now().toString());
      b.put("outcome", "evaluated");
      var criteria = b.putArray("criteria");
      for (String name :
          d.path("evaluator").asText().equals("openshift.foundations")
              ? List.of("context", "deployment", "service", "route", "response", "repair")
              : List.of("context", "manifest", "access", "quota"))
        criteria.addObject().put("criterion_id", "criterion." + name).put("passed", correct);
      return b;
    }
  }

  private JsonNode callback(JsonNode b, String jwt, int code) {
    return JsonData.parse(
        given()
            .auth()
            .oauth2(jwt)
            .contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(b.toString())
            .post("/internal/v1/assessments/" + b.path("assessment_id").asText() + "/result")
            .then()
            .statusCode(code)
            .extract()
            .asString());
  }

  private long count(String table) throws Exception {
    try (var c = database.getConnection();
        var q = LabSql.query(c, "SELECT count(*) FROM " + table + " WHERE learner_id=?", learner);
        var r = q.executeQuery()) {
      r.next();
      return r.getLong(1);
    }
  }

  @Test
  void durableDispatchAndDuplicateCompletion() throws Exception {
    create();
    labs.loseResponse = true;
    dispatcher.tick();
    try (var c = database.getConnection()) {
      LabSql.update(c, "UPDATE outbox_jobs SET available_at=now() WHERE learner_id=?", learner);
    }
    var restarted = new LabDispatcher();
    restarted.database = database;
    restarted.client = labs;
    restarted.accounts = accounts;
    restarted.tick();
    lab = get("/v1/lab-sessions/" + lab.path("lab_session_id").asText());
    assertEquals("ready", lab.path("status").asText());
    var accepted = assess();
    labs.loseResponse = true;
    dispatcher.tick();
    try (var c = database.getConnection()) {
      LabSql.update(c, "UPDATE outbox_jobs SET available_at=now() WHERE learner_id=?", learner);
    }
    restarted.tick();
    var body = result(accepted, true);
    assertEquals(
        "applied",
        callback(body, TestKeys.worker("worker-test"), 200).path("disposition").asText());
    assertEquals(
        "duplicate",
        callback(body, TestKeys.worker("worker-test"), 200).path("disposition").asText());
    assertEquals(2, count("reward_events"));
    assertEquals("completed", get(accepted.path("status_url").asText()).path("status").asText());
    assertEquals("completed", get(path).path("status").asText());
    ((ObjectNode) body.path("criteria").get(0)).put("passed", false);
    assertEquals(
        "result_conflict",
        callback(body, TestKeys.worker("worker-test"), 409).path("error").path("code").asText());
  }

  @Test
  void authenticatesAndValidatesBindingsBeforeProgress() throws Exception {
    ready();
    var accepted = assess();
    var body = result(accepted, true);
    callback(body, token, 401);
    callback(body, TestKeys.worker("foreign-worker"), 403);
    var bad = body.deepCopy();
    bad.put("generation", 2);
    callback(bad, TestKeys.worker("worker-test"), 409);
    bad = body.deepCopy();
    ((com.fasterxml.jackson.databind.node.ArrayNode) bad.path("criteria")).remove(0);
    callback(bad, TestKeys.worker("worker-test"), 409);
    bad = body.deepCopy();
    bad.put("observed_at", Instant.now().plusSeconds(5).toString());
    callback(bad, TestKeys.worker("worker-test"), 409);
    assertEquals(0, count("reward_events"));
    assertEquals(0, count("skill_progress"));
    assertEquals(
        "applied",
        callback(body, TestKeys.worker("worker-test"), 200).path("disposition").asText());
  }

  @Test
  void resetIgnoresLateResultAndDoesNotExtendLease() throws Exception {
    ready();
    var accepted = assess();
    var body = result(accepted, true);
    String expiry = lab.path("expires_at").asText();
    var reset =
        post(
            "/v1/lab-sessions/" + lab.path("lab_session_id").asText() + "/reset",
            Map.of("session_revision", session.path("revision").asLong(), "generation", 1),
            202);
    assertEquals(2, reset.path("generation").asInt());
    assertEquals(expiry, reset.path("expires_at").asText());
    dispatcher.tick();
    dispatcher.tick();
    lab = get("/v1/lab-sessions/" + lab.path("lab_session_id").asText());
    start();
    var newer = assess();
    assertEquals(
        "ignored_superseded",
        callback(body, TestKeys.worker("worker-test"), 200).path("disposition").asText());
    assertEquals(
        "duplicate",
        callback(body, TestKeys.worker("worker-test"), 200).path("disposition").asText());
    assertEquals(0, count("reward_events"));
    assertEquals("pending", get(newer.path("status_url").asText()).path("status").asText());
    assertEquals(
        "applied",
        callback(result(newer, true), TestKeys.worker("worker-test"), 200)
            .path("disposition")
            .asText());
  }

  @Test
  void infrastructureAndConcurrentDelivery() throws Exception {
    ready();
    var accepted = assess();
    var body = result(accepted, true);
    body.remove("criteria");
    body.put("outcome", "infrastructure_error");
    body.put("failure_code", "evaluator_timeout");
    assertEquals(
        "applied",
        callback(body, TestKeys.worker("worker-test"), 200).path("disposition").asText());
    assertEquals(
        "infrastructure_error", get(accepted.path("status_url").asText()).path("status").asText());
    assertEquals(0, count("skill_progress"));
    start();
    var retry = assess();
    var completed = result(retry, true);
    UUID id = UUID.fromString(retry.path("assessment_id").asText());
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<JsonNode> work = () -> results.receive(id, "worker-test", completed);
      var answers = pool.invokeAll(List.of(work, work));
      var dispositions =
          java.util.Set.of(
              answers.get(0).get().path("disposition").asText(),
              answers.get(1).get().path("disposition").asText());
      assertEquals(java.util.Set.of("applied", "duplicate"), dispositions);
    }
    assertEquals(2, count("reward_events"));
  }

  @Test
  void terminalStageAndClose() throws Exception {
    ready();
    String prefix = "/v1/lab-sessions/" + lab.path("lab_session_id").asText();
    access.terminal = "wss://terminal.test";
    var body = Map.of("session_revision", session.path("revision").asLong(), "generation", 1);
    String key = UUID.randomUUID().toString();
    var ticket = post(prefix + "/terminal-connections", key, body, 200);
    assertEquals(ticket, post(prefix + "/terminal-connections", key, body, 200));
    assertFalse(ticket.path("url").asText().contains(ticket.path("ticket").asText()));
    post(prefix + "/fault-stage", body, 202);
    dispatcher.tick();
    assertEquals("complete", get(prefix + "/fault-stage").path("state").asText());
    post(prefix + "/close", body, 202);
    dispatcher.tick();
    assertEquals("closed", get(prefix).path("status").asText());
  }

  @Test
  void expiryOwnershipAndUnavailableDispatch() throws Exception {
    create();
    labs.unavailable = true;
    dispatcher.tick();
    assertEquals(
        "provisioning",
        get("/v1/lab-sessions/" + lab.path("lab_session_id").asText()).path("status").asText());
    labs.unavailable = false;
    try (var c = database.getConnection()) {
      LabSql.update(c, "UPDATE outbox_jobs SET available_at=now() WHERE learner_id=?", learner);
    }
    dispatcher.tick();
    lab = get("/v1/lab-sessions/" + lab.path("lab_session_id").asText());
    var accepted = assess();
    var body = result(accepted, true);
    UUID id = UUID.fromString(lab.path("lab_session_id").asText());
    try (var c = database.getConnection()) {
      LabSql.update(
          c,
          "UPDATE assessments SET created_at=now()-interval '2 minutes' WHERE id=?",
          UUID.fromString(accepted.path("assessment_id").asText()));
      LabSql.update(
          c, "UPDATE lab_sessions SET expires_at=now()-interval '30 seconds' WHERE id=?", id);
    }
    body.put("observed_at", Instant.now().minusSeconds(60).toString());
    assertEquals(
        "ignored_expired",
        callback(body, TestKeys.worker("worker-test"), 200).path("disposition").asText());
    assertEquals("expired", get(accepted.path("status_url").asText()).path("status").asText());
    assertEquals(0, count("skill_progress"));
    given()
        .auth()
        .oauth2(
            TestKeys.token(
                "other-" + UUID.randomUUID(),
                "syskata-api",
                Instant.now().getEpochSecond() + 300,
                "Bearer"))
        .get("/v1/lab-sessions/" + id)
        .then()
        .statusCode(404);
    post(
        "/v1/lab-sessions/" + id + "/reset",
        Map.of("session_revision", get(path).path("revision").asLong(), "generation", 1),
        410);
  }

  @Test
  void wrongCriteriaPreserveFeedbackAndAssistance() throws Exception {
    ready();
    var hint =
        post(
            path + "/attempts/" + attempt.path("attempt_id").asText() + "/reveal",
            Map.of("session_revision", session.path("revision").asLong()),
            200);
    assertEquals(true, hint.path("assistance").path("revealed").asBoolean());
    var accepted = assess();
    var body = result(accepted, false);
    callback(body, TestKeys.worker("worker-test"), 200);
    var result = get(accepted.path("status_url").asText()).path("result");
    assertEquals("incorrect", result.path("outcome").asText());
    assertEquals("assisted", result.path("evidence").asText());
    assertEquals("remediate", result.path("next_action").path("type").asText());
    assertEquals(0, count("reward_events"));
    assertEquals(1, result.path("progress").path("skills").get(0).path("mistakes").asInt());
    post(
        "/v1/lab-sessions/" + lab.path("lab_session_id").asText() + "/close",
        Map.of("session_revision", get(path).path("revision").asLong(), "generation", 1),
        202);
  }

  @Test
  void transactionRollbackAndDeletedAccountDenyResults() throws Exception {
    ready();
    var accepted = assess();
    var body = result(accepted, true);
    try (var c = database.getConnection()) {
      LabSql.update(
          c, "ALTER TABLE reward_events ADD CONSTRAINT api5_test_failure CHECK (xp<>25) NOT VALID");
    }
    try {
      callback(body, TestKeys.worker("worker-test"), 503);
      assertEquals(0, count("reward_events"));
      assertEquals("pending", get(accepted.path("status_url").asText()).path("status").asText());
    } finally {
      try (var c = database.getConnection()) {
        LabSql.update(c, "ALTER TABLE reward_events DROP CONSTRAINT api5_test_failure");
      }
    }
    try (var c = database.getConnection()) {
      LabSql.update(c, "UPDATE learners SET disabled_at=now() WHERE id=?", learner);
    }
    callback(body, TestKeys.worker("worker-test"), 403);
    given().auth().oauth2(token).get(accepted.path("status_url").asText()).then().statusCode(403);
    assertEquals(0, count("reward_events"));
  }

  @Test
  void malformedCallbacksAndStaleMutationsDoNotChangeProgress() throws Exception {
    ready();
    var accepted = assess();
    var good = result(accepted, true);
    var bad = good.deepCopy();
    bad.put("observed_at", "invalid");
    callback(bad, TestKeys.worker("worker-test"), 400);
    bad = good.deepCopy();
    bad.put("arbitrary", 1);
    callback(bad, TestKeys.worker("worker-test"), 400);
    bad = good.deepCopy();
    ((ObjectNode) bad.path("criteria").get(0)).put("passed", "true");
    callback(bad, TestKeys.worker("worker-test"), 400);
    bad = good.deepCopy();
    ((com.fasterxml.jackson.databind.node.ArrayNode) bad.path("criteria"))
        .add(bad.path("criteria").get(0).deepCopy());
    callback(bad, TestKeys.worker("worker-test"), 409);
    bad = good.deepCopy();
    bad.remove("criteria");
    bad.put("outcome", "infrastructure_error");
    bad.put("failure_code", "arbitrary");
    callback(bad, TestKeys.worker("worker-test"), 400);
    assertEquals(0, count("reward_events"));
    String prefix = "/v1/lab-sessions/" + lab.path("lab_session_id").asText();
    post(prefix + "/reset", Map.of("session_revision", 1, "generation", 1), 409);
    post(
        prefix + "/reset",
        Map.of("session_revision", session.path("revision").asLong(), "generation", 2),
        409);
    callback(good, TestKeys.worker("worker-test"), 200);
    post(
        prefix + "/close",
        Map.of("session_revision", get(path).path("revision").asLong(), "generation", 1),
        202);
    dispatcher.tick();
    dispatcher.tick();
    var closed =
        post(
            prefix + "/close",
            Map.of("session_revision", get(path).path("revision").asLong(), "generation", 1),
            202);
    assertEquals("closed", closed.path("status").asText());
  }

  private void artifact(String operation, int status, JsonNode body) throws Exception {
    var root = java.nio.file.Path.of("target/lab-contracts");
    java.nio.file.Files.createDirectories(root);
    java.nio.file.Files.writeString(
        root.resolve(operation + "-" + UUID.randomUUID() + ".json"),
        JsonData.tree(Map.of("operation", operation, "status", status, "body", body)).toString());
  }

  @Test
  void actualResponsesFollowContract() throws Exception {
    ready();
    artifact("createLab", 202, lab);
    artifact("getLab", 200, get("/v1/lab-sessions/" + lab.path("lab_session_id").asText()));
    var accepted = assess();
    artifact("requestAssessment", 202, accepted);
    artifact("getAssessment", 200, get(accepted.path("status_url").asText()));
    artifact(
        "deliverResult",
        200,
        callback(result(accepted, true), TestKeys.worker("worker-test"), 200));
    artifact("getAssessment", 200, get(accepted.path("status_url").asText()));
    artifact("getAttempt", 200, get(path + "/attempts/" + attempt.path("attempt_id").asText()));
  }

  @Test
  void administrationCheckpointAndCloseBeforeProvisioning() throws Exception {
    try (var c = database.getConnection()) {
      LabSql.update(c, "DELETE FROM learning_sessions WHERE learner_id=?", learner);
    }
    catalog.activate(fixture("administration"));
    session =
        post(
            "/v1/learning-sessions",
            Map.of(
                "journey_id", "journey.openshift", "mode", "lesson", "lesson_id", "oc-orientation"),
            200);
    path = "/v1/learning-sessions/" + session.path("session_id").asText();
    start();
    ready();
    String prefix = "/v1/lab-sessions/" + lab.path("lab_session_id").asText();
    post(
        prefix + "/fault-stage",
        Map.of("session_revision", session.path("revision").asLong(), "generation", 1),
        409);
    var accepted = assess();
    var result = result(accepted, true);
    callback(result, TestKeys.worker("worker-test"), 200);
    var progress = get(accepted.path("status_url").asText()).path("result").path("progress");
    assertEquals(
        "checkpoint.oc-administration", progress.path("completed_checkpoint_ids").get(0).asText());
  }

  @Test
  void cancellationBeforeAllocationSurvivesDelayedDispatch() throws Exception {
    create();
    String prefix = "/v1/lab-sessions/" + lab.path("lab_session_id").asText();
    post(
        prefix + "/close",
        Map.of("session_revision", session.path("revision").asLong(), "generation", 1),
        202);
    dispatcher.tick();
    dispatcher.tick();
    assertEquals("closed", get(prefix).path("status").asText());
    assertEquals(0, count("reward_events"));
  }
}
