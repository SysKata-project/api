package org.syskata.api.learning;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

final class ReviewSummary {
  private ReviewSummary() {}

  static void append(Connection c, UUID learner, ObjectNode result) throws SQLException {
    for (var skill : result.path("skills")) {
      var value = (ObjectNode) skill;
      value.putNull("review_due_at");
      value.put("review_interval_days", 1);
      try (var q =
              LabSql.query(
                  c,
                  "SELECT due_at,interval_days FROM review_schedule WHERE learner_id=? AND skill_id=?",
                  learner,
                  skill.path("skill_id").asText());
          var r = q.executeQuery()) {
        if (r.next()) {
          value.put("review_due_at", r.getTimestamp(1).toInstant().toString());
          value.put("review_interval_days", r.getInt(2));
        }
      }
    }
  }
}
