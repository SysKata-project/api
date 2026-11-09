package org.syskata.api.foundation;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(TestKeys.class)
class FoundationTest {
  @Inject AgroalDataSource database;

  @Inject
  @DataSource("evidence")
  AgroalDataSource evidence;

  @Inject Accounts accounts;

  private String token(String subject) throws Exception {
    return TestKeys.token(subject, "syskata-api", Instant.now().getEpochSecond() + 300, "Bearer");
  }

  @Test
  void readinessAndAuthentication() throws Exception {
    given().get("/q/health/ready").then().statusCode(200);
    given().get("/q/health/live").then().statusCode(200);
    given().get("/v1/foundation-probe").then().statusCode(401);
    given().auth().oauth2("not-a-token").get("/v1/foundation-probe").then().statusCode(401);
    for (String invalid :
        new String[] {
          TestKeys.token("bad", "wrong-audience", Instant.now().getEpochSecond() + 300, "Bearer"),
          TestKeys.token("bad", "syskata-api", Instant.now().getEpochSecond() - 300, "Bearer"),
          TestKeys.token("bad", "syskata-api", Instant.now().getEpochSecond() + 300, "ID")
        }) {
      given().auth().oauth2(invalid).get("/v1/foundation-probe").then().statusCode(401);
    }
    given()
        .auth()
        .oauth2(token("active-" + UUID.randomUUID()))
        .get("/v1/foundation-probe")
        .then()
        .statusCode(200)
        .body("status", equalTo("active"));
  }

  @Test
  void deletionBlocksStillValidTokensAndRecreation() throws Exception {
    String subject = "delete-" + UUID.randomUUID();
    String bearer = token(subject);
    given()
        .auth()
        .oauth2(bearer)
        .contentType("application/json")
        .body("{\"confirmation\":\"delete-account\"}")
        .post("/v1/me/account-deletion")
        .then()
        .statusCode(400);
    given()
        .auth()
        .oauth2(bearer)
        .header("Idempotency-Key", "11111111-1111-4111-8111-111111111111")
        .contentType("application/json")
        .body("{\"confirmation\":\"no\"}")
        .post("/v1/me/account-deletion")
        .then()
        .statusCode(400);
    var response =
        given()
            .auth()
            .oauth2(bearer)
            .header("Idempotency-Key", "11111111-1111-4111-8111-111111111111")
            .contentType("application/json")
            .body("{\"confirmation\":\"delete-account\"}")
            .post("/v1/me/account-deletion")
            .then()
            .statusCode(202)
            .body("access_disabled", equalTo(true))
            .extract()
            .jsonPath();
    assertEquals(
        30L * 86400,
        Instant.parse(response.getString("active_data_delete_by")).getEpochSecond()
            - Instant.parse(response.getString("accepted_at")).getEpochSecond());
    given()
        .auth()
        .oauth2(bearer)
        .get("/v1/foundation-probe")
        .then()
        .statusCode(403)
        .body("error.code", equalTo("account_disabled"));
    given()
        .auth()
        .oauth2(bearer)
        .header("Idempotency-Key", "11111111-1111-4111-8111-111111111111")
        .contentType("application/json")
        .body("{\"confirmation\":\"delete-account\"}")
        .post("/v1/me/account-deletion")
        .then()
        .statusCode(403);
    // Removing/restoring the application row cannot remove the independent deny ledger.
    try (var c = database.getConnection();
        var q = c.prepareStatement("DELETE FROM learners WHERE subject=?")) {
      q.setString(1, subject);
      q.executeUpdate();
    }
    given().auth().oauth2(bearer).get("/v1/foundation-probe").then().statusCode(403);
  }

  @Test
  void ownershipAndConstraints() throws Exception {
    String issuer = "https://identity.test/realms/syskata";
    String owner = "owner-" + UUID.randomUUID();
    UUID learner = accounts.active(issuer, owner);
    UUID other = accounts.active(issuer, "other-" + UUID.randomUUID());
    UUID session = UUID.randomUUID();
    try (Connection c = database.getConnection()) {
      try (var s = c.createStatement()) {
        s.execute(
            "INSERT INTO content_releases VALUES ('test','1.0.0',repeat('a',40),repeat('b',64),repeat('c',64),now()) ON CONFLICT DO NOTHING");
      }
      try (var q =
          c.prepareStatement(
              "INSERT INTO enrollments(learner_id,journey_id) VALUES (?,'journey.test')")) {
        q.setObject(1, learner);
        q.executeUpdate();
      }
      try (var q =
          c.prepareStatement(
              "INSERT INTO learning_sessions(id,learner_id,journey_id,bundle_version,policy_version,mode,status) VALUES (?,?,'journey.test','test','1.0.0','lesson','active')")) {
        q.setObject(1, session);
        q.setObject(2, learner);
        q.executeUpdate();
        q.setObject(1, UUID.randomUUID());
        assertThrows(SQLException.class, q::executeUpdate);
      }
      try (var q =
          c.prepareStatement(
              "INSERT INTO exercise_instances VALUES (?,?,?,'exercise.test',1,'literal-command-v1','1.0.0','{}',now())")) {
        q.setObject(1, UUID.randomUUID());
        q.setObject(2, session);
        q.setObject(3, other);
        assertThrows(SQLException.class, q::executeUpdate);
      }
    }
    accounts.requireSessionOwner(session, learner);
    assertEquals(
        404,
        assertThrows(ApiFailure.class, () -> accounts.requireSessionOwner(session, other))
            .status());
    given()
        .auth()
        .oauth2(token(owner))
        .get("/v1/foundation-probe/" + session)
        .then()
        .statusCode(200);
    given()
        .auth()
        .oauth2(token("unrelated-" + UUID.randomUUID()))
        .get("/v1/foundation-probe/" + session)
        .then()
        .statusCode(404);
    assertEquals(
        404,
        assertThrows(
                ApiFailure.class, () -> accounts.requireSessionOwner(UUID.randomUUID(), learner))
            .status());
  }

  @Test
  void invalidDeletionAndDisabledAccount() throws Exception {
    String subject = "invalid-" + UUID.randomUUID();
    String bearer = token(subject);
    for (String body :
        new String[] {"null", "{}", "{\"confirmation\":\"delete-account\",\"extra\":true}"}) {
      given()
          .auth()
          .oauth2(bearer)
          .header("Idempotency-Key", "22222222-2222-4222-8222-222222222222")
          .contentType("application/json")
          .body(body)
          .post("/v1/me/account-deletion")
          .then()
          .statusCode(400)
          .body("error.code", equalTo("invalid_request"));
    }
    given()
        .auth()
        .oauth2(bearer)
        .header("Idempotency-Key", "!")
        .contentType("application/json")
        .body("{\"confirmation\":\"delete-account\"}")
        .post("/v1/me/account-deletion")
        .then()
        .statusCode(400);
    given()
        .auth()
        .oauth2(bearer)
        .header("Idempotency-Key", "22222222-2222-4222-8222-222222222222")
        .contentType("application/json")
        .body("x".repeat(65537))
        .post("/v1/me/account-deletion")
        .then()
        .statusCode(413)
        .body("error.code", equalTo("invalid_request"));
    try (var c = database.getConnection();
        var q = c.prepareStatement("UPDATE learners SET disabled_at=now() WHERE subject=?")) {
      q.setString(1, subject);
      q.executeUpdate();
    }
    given()
        .auth()
        .oauth2(bearer)
        .get("/v1/foundation-probe")
        .then()
        .statusCode(403)
        .body("error.code", equalTo("account_disabled"));
  }

  @Test
  void missingIdentityIsUnauthenticated() throws Exception {
    for (String[] pair :
        new String[][] {{null, "subject"}, {"", "subject"}, {"issuer", null}, {"issuer", ""}}) {
      assertEquals(
          401, assertThrows(ApiFailure.class, () -> accounts.active(pair[0], pair[1])).status());
    }
    given().auth().oauth2(token("")).get("/v1/foundation-probe").then().statusCode(401);
  }

  @Test
  void errorsNeverEchoExceptionDetails() {
    Errors mapper = new Errors();
    assertEquals(
        401,
        mapper.toResponse(new io.quarkus.security.AuthenticationFailedException()).getStatus());
    assertEquals(
        401, mapper.toResponse(new io.quarkus.security.UnauthorizedException()).getStatus());
    assertEquals(403, mapper.toResponse(new io.quarkus.security.ForbiddenException()).getStatus());
    assertEquals(404, mapper.toResponse(new jakarta.ws.rs.NotFoundException()).getStatus());
    assertEquals(400, mapper.toResponse(new jakarta.ws.rs.BadRequestException()).getStatus());
    var result = new Errors().toResponse(new SQLException("credential-and-answer-canary"));
    assertEquals(503, result.getStatus());
    assertTrue(!result.getEntity().toString().contains("canary"));
    for (int status : new int[] {400, 401, 403, 404, 413, 415, 503}) {
      assertEquals(status, Errors.response(status, "invalid_request").getStatus());
    }
  }
}
