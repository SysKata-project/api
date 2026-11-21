package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.syskata.api.foundation.ApiFailure;

@ApplicationScoped
public class LearningStore {
  private final AgroalDataSource database;

  public LearningStore(AgroalDataSource database) {
    this.database = database;
  }

  @FunctionalInterface
  public interface Work {
    JsonNode run(Connection connection) throws Exception;
  }

  public JsonNode mutate(UUID learner, String operation, String key, JsonNode body, Work work)
      throws Exception {
    try (var c = database.getConnection()) {
      c.setAutoCommit(false);
      try {
        lockLearner(c, learner);
        String hash = JsonData.requestHash(body);
        JsonNode replay = replay(c, learner, operation, key, hash);
        JsonNode result = replay == null ? work.run(c) : replay;
        if (replay == null) saveReplay(c, learner, operation, key, hash, result);
        c.commit();
        return result;
      } catch (Exception failure) {
        c.rollback();
        throw failure;
      }
    }
  }

  private void lockLearner(Connection c, UUID learner) throws SQLException {
    try (var q = c.prepareStatement("SELECT disabled_at FROM learners WHERE id=? FOR UPDATE")) {
      q.setObject(1, learner);
      try (var rows = q.executeQuery()) {
        if (!rows.next() || rows.getObject(1) != null)
          throw new ApiFailure(403, "account_disabled");
      }
    }
  }

  private JsonNode replay(Connection c, UUID learner, String operation, String key, String hash)
      throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT request_sha256,response FROM idempotency_records WHERE learner_id=? AND operation=? AND idempotency_key=?")) {
      q.setObject(1, learner);
      q.setString(2, operation);
      q.setString(3, key);
      try (var rows = q.executeQuery()) {
        if (!rows.next()) return null;
        if (!rows.getString(1).equals(hash)) throw new ApiFailure(409, "idempotency_conflict");
        return JsonData.parse(rows.getString(2));
      }
    }
  }

  private void saveReplay(
      Connection c, UUID learner, String operation, String key, String hash, JsonNode response)
      throws SQLException {
    try (var q =
        c.prepareStatement(
            "INSERT INTO idempotency_records(learner_id,operation,idempotency_key,request_sha256,response) VALUES (?,?,?,?,?::jsonb)")) {
      q.setObject(1, learner);
      q.setString(2, operation);
      q.setString(3, key);
      q.setString(4, hash);
      q.setString(5, response.toString());
      q.executeUpdate();
    }
  }

  public JsonNode read(UUID learner, UUID session) throws SQLException {
    try (var c = database.getConnection()) {
      c.setAutoCommit(false);
      JsonNode result = session(c, learner, session);
      c.commit();
      return result;
    }
  }

  public JsonNode session(Connection c, UUID learner, UUID session) throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT selection_state,status,revision FROM learning_sessions WHERE id=? AND learner_id=? FOR SHARE")) {
      q.setObject(1, session);
      q.setObject(2, learner);
      try (var rows = q.executeQuery()) {
        if (!rows.next()) throw new ApiFailure(404, "not_found");
        ObjectNode result =
            (ObjectNode) JsonData.parse(rows.getString(1)).path("response").deepCopy();
        result.put("status", rows.getString(2));
        result.put("revision", rows.getLong(3));
        if (rows.getString(2).equals("completed")) {
          result.putNull("current_instance");
          result.putNull("current_attempt");
          result.putArray("pending_objective_ids");
        } else result.set("current_attempt", attempt(c, learner, session));
        return result;
      }
    }
  }

  private JsonNode attempt(Connection c, UUID learner, UUID session) throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT id,instance_id,status,hints,revealed FROM attempts WHERE learner_id=? AND session_id=? AND status IN ('open','awaiting_assessment')")) {
      q.setObject(1, learner);
      q.setObject(2, session);
      try (var rows = q.executeQuery()) {
        if (!rows.next()) return JsonData.tree(null);
        ObjectNode result = JsonData.object();
        result.put("attempt_id", rows.getString(1));
        result.put("exercise_instance_id", rows.getString(2));
        result.put("status", rows.getString(3));
        var assistance = result.putObject("assistance");
        assistance.set("hint_ids", JsonData.parse(rows.getString(4)));
        assistance.put("revealed", rows.getBoolean(5));
        return result;
      }
    }
  }
}
