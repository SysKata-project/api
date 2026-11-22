package org.syskata.api.learning;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.syskata.api.foundation.ApiFailure;

record LabRecord(
    UUID id,
    UUID learner,
    UUID session,
    UUID instance,
    long generation,
    String status,
    Instant expiry,
    long remoteRevision,
    String scenario) {
  static LabRecord owned(Connection c, UUID learner, UUID id) throws SQLException {
    try (var q =
            LabSql.query(
                c,
                "SELECT session_id,instance_id,generation,status,expires_at,remote_revision,scenario FROM lab_sessions WHERE id=? AND learner_id=?",
                id,
                learner);
        var r = q.executeQuery()) {
      if (!r.next()) throw new ApiFailure(404, "not_found");
      return new LabRecord(
          id,
          learner,
          r.getObject(1, UUID.class),
          r.getObject(2, UUID.class),
          r.getLong(3),
          r.getString(4),
          r.getTimestamp(5) == null ? null : r.getTimestamp(5).toInstant(),
          r.getLong(6),
          r.getString(7));
    }
  }

  void generation(long requested) {
    if (generation != requested) throw new ApiFailure(409, "generation_mismatch");
  }

  boolean expired() {
    return expiry != null && !Instant.now().isBefore(expiry);
  }

  void ready() {
    if (expired() || status.equals("expired")) throw new ApiFailure(410, "expired");
    if (!status.equals("ready")) throw new ApiFailure(409, "ineligible");
  }

  ObjectNode response(long revision) {
    var result = JsonData.object();
    result.put("lab_session_id", id.toString());
    result.put("session_id", session.toString());
    result.put("exercise_instance_id", instance.toString());
    result.put("generation", generation);
    result.put("status", expired() && !status.equals("closed") ? "expired" : status);
    if (expiry == null) result.putNull("expires_at");
    else result.put("expires_at", expiry.toString());
    result.put("session_revision", revision);
    result.set(
        "error",
        status.equals("infrastructure_error")
            ? LabState.error("infrastructure_unavailable")
            : JsonData.tree(null));
    return result;
  }
}
