package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.syskata.api.foundation.ApiFailure;

@ApplicationScoped
public class ProgressService {
  private final AgroalDataSource database;

  public ProgressService(AgroalDataSource database) {
    this.database = database;
  }

  public JsonNode read(UUID learner) throws SQLException {
    try (var c = database.getConnection()) {
      c.setAutoCommit(false);
      LearningStore.lockLearner(c, learner);
      JsonNode result =
          GradeProgress.snapshot(
              c, learner, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
      c.commit();
      return result;
    }
  }

  JsonNode timezone(Connection c, UUID learner, String zone, Instant now) throws SQLException {
    PracticeDays days = PracticeDays.load(c, learner, now);
    try (var q =
            LabSql.query(
                c,
                "SELECT new_timezone,effective_at FROM timezone_changes WHERE learner_id=? AND full_day_start>?::timestamptz ORDER BY effective_at DESC LIMIT 1",
                learner,
                now.toString());
        var r = q.executeQuery()) {
      if (r.next()) {
        if (!zone.equals(r.getString(1))) throw new ApiFailure(409, "ineligible");
        return change(zone, r.getTimestamp(2).toInstant());
      }
    }
    if (zone.equals(days.zone())) return change(zone, now);
    Instant effective = PracticeDays.nextMidnight(now, days.zone());
    // If the destination happens to be at midnight, its first day is already full.
    Instant full =
        PracticeDays.midnight(effective, zone).equals(effective)
            ? effective
            : PracticeDays.nextMidnight(effective, zone);
    LabSql.update(
        c,
        "INSERT INTO timezone_changes VALUES (?,?,?, ?,?::timestamptz,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        learner,
        days.zone(),
        zone,
        PracticeDays.midnight(now, days.zone()).toString(),
        effective.toString(),
        full.toString());
    return change(zone, effective);
  }

  private JsonNode change(String zone, Instant effective) {
    var result = JsonData.object();
    result.put("timezone", zone);
    result.put("effective_at", effective.toString());
    return result;
  }
}
