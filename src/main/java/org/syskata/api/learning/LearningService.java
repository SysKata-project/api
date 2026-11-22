package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.syskata.api.foundation.ApiFailure;

@ApplicationScoped
public class LearningService {
  private final AgroalDataSource database;
  private final ContentCatalog catalog;
  private final LearningStore store;

  public LearningService(AgroalDataSource database, ContentCatalog catalog, LearningStore store) {
    this.database = database;
    this.catalog = catalog;
    this.store = store;
  }

  public JsonNode catalog() throws Exception {
    Bundle bundle = catalog.current();
    return JsonData.tree(
        Map.of(
            "versions",
            bundle.versions(),
            "journeys",
            bundle.entities("journey"),
            "references",
            bundle.entities("reference")));
  }

  public JsonNode journey(UUID learner, String journey) throws Exception {
    Bundle bundle = catalog.current();
    try (var c = database.getConnection()) {
      c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      c.setAutoCommit(false);
      JsonNode result = new JourneyState(c, learner).path(bundle, journey);
      c.commit();
      return result;
    }
  }

  public JsonNode enroll(Connection c, UUID learner, JsonNode request) throws Exception {
    String journey = request.path("journey_id").asText();
    catalog.current().entity(journey, "journey");
    String timezone = request.path("timezone").asText();
    PracticeDays.load(
        c, learner, java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    try (var q =
        c.prepareStatement(
            "SELECT timezone,(EXISTS(SELECT 1 FROM enrollments WHERE learner_id=?) OR EXISTS(SELECT 1 FROM timezone_changes WHERE learner_id=learners.id)) FROM learners WHERE id=?")) {
      q.setObject(1, learner);
      q.setObject(2, learner);
      try (var rows = q.executeQuery()) {
        rows.next();
        if (rows.getBoolean(2) && !timezone.equals(rows.getString(1)))
          throw new ApiFailure(409, "ineligible");
      }
    }
    try (var q = c.prepareStatement("UPDATE learners SET timezone=? WHERE id=?")) {
      q.setString(1, timezone);
      q.setObject(2, learner);
      q.executeUpdate();
    }
    try (var q =
        c.prepareStatement(
            "INSERT INTO enrollments(learner_id,journey_id) VALUES (?,?) ON CONFLICT DO NOTHING")) {
      q.setObject(1, learner);
      q.setString(2, journey);
      q.executeUpdate();
    }
    try (var q =
        c.prepareStatement(
            "SELECT enrolled_at FROM enrollments WHERE learner_id=? AND journey_id=?")) {
      q.setObject(1, learner);
      q.setString(2, journey);
      try (var rows = q.executeQuery()) {
        rows.next();
        return JsonData.tree(
            Map.of(
                "enrollment_id",
                UUID.nameUUIDFromBytes(
                        (learner + ":" + journey).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .toString(),
                "journey_id",
                journey,
                "created_at",
                rows.getTimestamp(1).toInstant().toString(),
                "timezone",
                timezone));
      }
    }
  }

  public JsonNode start(Connection c, UUID learner, JsonNode request) throws Exception {
    String journey = request.path("journey_id").asText();
    try (var q =
        c.prepareStatement(
            "SELECT id FROM learning_sessions WHERE learner_id=? AND journey_id=? AND status='active'")) {
      q.setObject(1, learner);
      q.setString(2, journey);
      try (var rows = q.executeQuery()) {
        if (rows.next()) {
          JsonNode existing = store.session(c, learner, rows.getObject(1, UUID.class));
          return existing;
        }
      }
    }
    requireEnrollment(c, learner, journey);
    Bundle bundle = catalog.current();
    ObjectNode plan = SessionSelection.choose(c, learner, bundle, request);
    UUID session = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    ObjectNode response = presentation(bundle, journey, session, instance, plan);
    plan.set("response", response);
    try (var q =
        c.prepareStatement(
            "INSERT INTO learning_sessions(id,learner_id,journey_id,bundle_version,policy_version,mode,status,revision,selection_state) VALUES (?,?,?,?,'1.0.0',?,'active',1,?::jsonb)")) {
      q.setObject(1, session);
      q.setObject(2, learner);
      q.setString(3, journey);
      q.setString(4, bundle.version());
      q.setString(5, plan.path("mode").asText());
      q.setString(6, plan.toString());
      q.executeUpdate();
    }
    saveInstance(c, learner, session, instance, response.path("current_instance"));
    return response;
  }

  private void requireEnrollment(Connection c, UUID learner, String journey) throws SQLException {
    try (var q =
        c.prepareStatement("SELECT 1 FROM enrollments WHERE learner_id=? AND journey_id=?")) {
      q.setObject(1, learner);
      q.setString(2, journey);
      try (var rows = q.executeQuery()) {
        if (!rows.next()) throw new ApiFailure(409, "ineligible");
      }
    }
  }

  private ObjectNode presentation(
      Bundle bundle, String journey, UUID session, UUID instance, ObjectNode plan) {
    String exercise = plan.path("exercise_queue").get(0).asText();
    JsonNode question = bundle.entity(exercise, "exercise");
    JsonNode assessment = bundle.assessment(exercise);
    ObjectNode result = JsonData.object();
    result.put("session_id", session.toString());
    result.put("journey_id", journey);
    result.set("lesson_id", plan.path("lesson_id"));
    result.set("mode", plan.path("mode"));
    result.put("status", "active");
    result.put("revision", 1);
    result.set("versions", bundle.versions());
    result.set("pending_objective_ids", plan.path("pending_objective_ids"));
    result.set("references", JsonData.tree(bundle.entities("reference")));
    result.putNull("current_attempt");
    var current = result.putObject("current_instance");
    current.put("exercise_instance_id", instance.toString());
    current.set("question", question);
    current.set("evaluator", assessment.path("evaluator"));
    current.set("evaluator_version", assessment.path("evaluator_version"));
    return result;
  }

  static void saveInstance(
      Connection c, UUID learner, UUID session, UUID instance, JsonNode current)
      throws SQLException {
    try (var q =
        c.prepareStatement(
            "INSERT INTO exercise_instances(id,session_id,learner_id,exercise_id,exercise_revision,evaluator_id,evaluator_version,presentation) VALUES (?,?,?,?,?,?,?,?::jsonb)")) {
      q.setObject(1, instance);
      q.setObject(2, session);
      q.setObject(3, learner);
      q.setString(4, current.path("question").path("id").asText());
      q.setInt(5, current.path("question").path("revision").asInt());
      q.setString(6, current.path("evaluator").asText());
      q.setString(7, current.path("evaluator_version").asText());
      q.setString(8, current.path("question").toString());
      q.executeUpdate();
    }
  }
}
