package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.syskata.api.foundation.Accounts;
import org.syskata.api.foundation.ApiFailure;

@ApplicationScoped
public class LabDispatcher {
  @Inject AgroalDataSource database;
  @Inject LabClient client;
  @Inject Accounts accounts;

  record Job(UUID id, UUID learner, String operation, JsonNode payload, int attempts) {}

  public void tick() throws Exception {
    // Session advisory lock spans bounded external requests, not learner transactions.
    try (var coordinator = database.getConnection()) {
      try (var q = LabSql.query(coordinator, "SELECT pg_try_advisory_lock(5105001)");
          var r = q.executeQuery()) {
        r.next();
        if (!r.getBoolean(1)) return;
      }
      try {
        for (Job job : jobs(coordinator)) dispatch(job);
        for (var item : live(coordinator)) reconcile(item[0], item[1]);
      } finally {
        try (var unlock = LabSql.query(coordinator, "SELECT pg_advisory_unlock(5105001)")) {
          unlock.execute();
        }
      }
    }
  }

  private List<Job> jobs(Connection c) throws Exception {
    List<Job> jobs = new ArrayList<>();
    try (var q =
            LabSql.query(
                c,
                "SELECT id,learner_id,operation,payload,attempts FROM outbox_jobs j WHERE completed_at IS NULL AND available_at<=now() AND NOT EXISTS (SELECT 1 FROM outbox_jobs older WHERE older.learner_id=j.learner_id AND older.completed_at IS NULL AND older.sequence<j.sequence) ORDER BY sequence LIMIT 20");
        var r = q.executeQuery()) {
      while (r.next())
        jobs.add(
            new Job(
                r.getObject(1, UUID.class),
                r.getObject(2, UUID.class),
                r.getString(3),
                JsonData.parse(r.getString(4)),
                r.getInt(5)));
    }
    return jobs;
  }

  private List<UUID[]> live(Connection c) throws Exception {
    List<UUID[]> result = new ArrayList<>();
    try (var q =
            LabSql.query(
                c,
                "SELECT id,learner_id FROM lab_sessions WHERE scenario IS NOT NULL AND NOT cleanup_complete ORDER BY created_at LIMIT 100");
        var r = q.executeQuery()) {
      while (r.next())
        result.add(new UUID[] {r.getObject(1, UUID.class), r.getObject(2, UUID.class)});
    }
    return result;
  }

  private boolean active(UUID learner) throws Exception {
    try (var c = database.getConnection();
        var q =
            LabSql.query(c, "SELECT issuer,subject,disabled_at FROM learners WHERE id=?", learner);
        var r = q.executeQuery()) {
      if (!r.next()) return false;
      try {
        if (r.getObject(3) != null) return false;
        accounts.ensureNotDeleted(r.getString(1), r.getString(2));
        return true;
      } catch (ApiFailure failure) {
        if (failure.code().equals("account_disabled")) return false;
        throw failure;
      }
    }
  }

  private void dispatch(Job job) throws Exception {
    try {
      if (!active(job.learner())) {
        client.call(
            "/internal/accounts/disable",
            JsonData.tree(Map.of("subject", job.learner().toString())));
        finish(job.id());
        return;
      }
      if (!current(job)) {
        finish(job.id());
        return;
      }
      JsonNode response = client.call(path(job), wire(job));
      UUID lab =
          UUID.fromString(
              job.payload().path(job.operation().equals("assess") ? "labId" : "id").asText());
      if (java.util.Set.of("provision", "reset", "close").contains(job.operation()))
        synchronize(job.learner(), lab, response);
      finish(job.id());
    } catch (InterruptedException failure) {
      throw failure;
    } catch (Exception failure) {
      retry(job);
    }
  }

  private boolean current(Job job) throws Exception {
    try (var c = database.getConnection()) {
      if (job.operation().equals("assess")) {
        try (var q = LabSql.query(c, "SELECT status FROM assessments WHERE id=?", job.id());
            var r = q.executeQuery()) {
          return r.next() && r.getString(1).equals("pending");
        }
      }
      if (job.operation().equals("stage")) {
        var lab =
            LabRecord.owned(c, job.learner(), UUID.fromString(job.payload().path("id").asText()));
        return lab.generation() == job.payload().path("generation").asLong()
            && lab.status().equals("ready")
            && !lab.expired();
      }
      return true;
    }
  }

  private String path(Job job) {
    return switch (job.operation()) {
      case "provision" -> "/internal/labs";
      case "assess" -> "/internal/assessments";
      case "stage" -> "/internal/labs/" + job.payload().path("id").asText() + "/fault-stage";
      case "reset", "close" ->
          "/internal/labs/" + job.payload().path("id").asText() + "/" + job.operation();
      default -> throw new ApiFailure(503, "infrastructure_unavailable");
    };
  }

  private JsonNode wire(Job job) throws Exception {
    ObjectNode result = job.payload().deepCopy();
    if (job.operation().equals("close") && result.path("revision").asLong() == 0) {
      JsonNode remote =
          client.call(
              "/internal/labs/" + result.path("id").asText() + "?subject=" + job.learner(), null);
      validateRemote(job.learner(), UUID.fromString(result.path("id").asText()), remote);
      result.set("revision", remote.path("revision"));
      try (var c = database.getConnection()) {
        LabSql.update(
            c, "UPDATE outbox_jobs SET payload=?::jsonb WHERE id=?", result.toString(), job.id());
      }
    }

    if (java.util.Set.of("reset", "close", "stage").contains(job.operation())) result.remove("id");
    return result;
  }

  private void finish(UUID id) throws Exception {
    try (var c = database.getConnection()) {
      LabSql.update(c, "UPDATE outbox_jobs SET completed_at=now() WHERE id=?", id);
    }
  }

  private void retry(Job job) throws Exception {
    try (var c = database.getConnection()) {
      int delay = Math.min(60, 1 << Math.min(job.attempts(), 6));
      LabSql.update(
          c,
          "UPDATE outbox_jobs SET attempts=attempts+1,available_at=now()+(? * interval '1 second') WHERE id=?",
          delay,
          job.id());
      if (job.attempts() >= 7 && job.operation().equals("provision")) {
        LabSql.update(
            c,
            "UPDATE lab_sessions SET status='infrastructure_error' WHERE id=? AND status='provisioning'",
            UUID.fromString(job.payload().path("id").asText()));
      }
    }
  }

  private void reconcile(UUID id, UUID learner) throws Exception {
    try {
      if (!active(learner)) {
        client.call(
            "/internal/accounts/disable", JsonData.tree(Map.of("subject", learner.toString())));
        return;
      }
      JsonNode remote = client.call("/internal/labs/" + id + "?subject=" + learner, null);
      synchronize(learner, id, remote);
    } catch (InterruptedException failure) {
      throw failure;
    } catch (Exception failure) {
      // Fail closed at the stored lease deadline even while the lab service is unreachable.
      try (var c = database.getConnection()) {
        c.setAutoCommit(false);
        var lab = LabRecord.owned(c, learner, id);
        LearningStore.lockLearner(c, learner);
        LabService.expire(c, lab, new AttemptState(c, learner, lab.session()));
        c.commit();
      }
    }
  }

  private void synchronize(UUID learner, UUID id, JsonNode remote) throws Exception {
    validateRemote(learner, id, remote);
    String status = remote.path("state").asText();
    Instant expiry = Instant.parse(remote.path("expiresAt").asText());
    try (var c = database.getConnection()) {
      c.setAutoCommit(false);
      LearningStore.lockLearner(c, learner);
      var lab = LabRecord.owned(c, learner, id);
      var state = new AttemptState(c, learner, lab.session());
      boolean same = lab.generation() == remote.path("generation").asLong();
      // Old in-flight reads must never reverse reset, close, or expiry intents.
      if (same
          && remote.path("revision").asLong() >= lab.remoteRevision()
          && !reverses(lab.status(), status)) {
        LabSql.update(
            c,
            "UPDATE lab_sessions SET status=?,expires_at=?::timestamptz,allocated_at=COALESCE(allocated_at,now()),remote_revision=?,cleanup_complete=? WHERE id=?",
            status,
            expiry.toString(),
            remote.path("revision").asLong(),
            remote.path("cleanupComplete").asBoolean(),
            id);
        terminal(c, lab, state, status);
      }
      LabService.expire(c, LabRecord.owned(c, learner, id), state);
      c.commit();
    }
  }

  private void terminal(Connection c, LabRecord lab, AttemptState state, String status)
      throws Exception {
    if (!java.util.Set.of("expired", "closed", "infrastructure_error").contains(status)) return;
    int changed;
    try (var q =
        LabSql.query(
            c,
            "UPDATE attempts SET status='cancelled' WHERE instance_id=? AND status IN ('open','awaiting_assessment')",
            lab.instance())) {
      changed = q.executeUpdate();
    }
    LabState.cancel(
        c,
        lab,
        status.equals("expired")
            ? "expired"
            : status.equals("closed") ? "superseded" : "infrastructure_error");
    if (changed > 0) state.save(c);
  }

  private void validateRemote(UUID learner, UUID id, JsonNode remote) {
    if (!remote.path("id").asText().equals(id.toString())
        || !remote.path("subject").asText().equals(learner.toString()))
      throw new ApiFailure(503, "infrastructure_unavailable");
    String status = remote.path("state").asText();
    if (!java.util.Set.of(
            "provisioning",
            "ready",
            "resetting",
            "closing",
            "closed",
            "expired",
            "infrastructure_error")
        .contains(status)) throw new ApiFailure(503, "infrastructure_unavailable");
  }

  private boolean reverses(String local, String remote) {
    if (local.equals("closing")) return !java.util.Set.of("closing", "closed").contains(remote);
    if (local.equals("expired")) return !java.util.Set.of("expired", "closed").contains(remote);
    return local.equals("closed") && !remote.equals("closed");
  }
}
