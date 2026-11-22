package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.syskata.api.foundation.ApiFailure;

@ApplicationScoped
public class LabAccess {
  @Inject LabClient client;
  @Inject AgroalDataSource database;

  @ConfigProperty(name = "syskata.labs.terminal-url", defaultValue = "wss://unconfigured.invalid")
  String terminal;

  private LabRecord authorize(Connection c, UUID learner, UUID id, JsonNode body) throws Exception {
    var lab = LabRecord.owned(c, learner, id);
    var state = new AttemptState(c, learner, lab.session());
    state.validate(body);
    lab.generation(body.path("generation").asLong());
    lab.ready();
    return lab;
  }

  JsonNode ticket(Connection c, UUID learner, UUID id, JsonNode body) throws Exception {
    var lab = authorize(c, learner, id, body);
    var uri = java.net.URI.create(terminal);
    if (!"wss".equals(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) throw new ApiFailure(503, "infrastructure_unavailable");
    byte[] random = new byte[32];
    new java.security.SecureRandom().nextBytes(random);
    String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    Instant expiry = Instant.now().plusSeconds(60);
    if (lab.expiry().isBefore(expiry)) expiry = lab.expiry();
    expiry = expiry.truncatedTo(ChronoUnit.MICROS);
    client.call(
        "/internal/labs/" + id + "/tickets",
        JsonData.tree(
            Map.of(
                "subject",
                learner.toString(),
                "generation",
                lab.generation(),
                "hash",
                JsonData.hash(ticket.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "expiresAt",
                expiry.toString())));
    return JsonData.tree(
        Map.of(
            "lab_session_id",
            id.toString(),
            "generation",
            lab.generation(),
            "url",
            terminal.replaceFirst("/$", "") + "/terminal/" + id,
            "ticket",
            ticket,
            "expires_at",
            expiry.toString()));
  }

  JsonNode stage(Connection c, UUID learner, UUID id, JsonNode body) throws Exception {
    var lab = authorize(c, learner, id, body);
    if (!lab.scenario().equals("foundations")) throw new ApiFailure(409, "ineligible");
    LabState.enqueue(
        c,
        learner,
        "stage",
        UUID.randomUUID(),
        JsonData.tree(
            Map.of(
                "id",
                id.toString(),
                "subject",
                learner.toString(),
                "generation",
                lab.generation())));
    return JsonData.tree(
        Map.of(
            "lab_session_id", id.toString(), "generation", lab.generation(), "state", "pending"));
  }

  JsonNode stageStatus(UUID learner, UUID id) throws Exception {
    try (var c = database.getConnection()) {
      var lab = LabRecord.owned(c, learner, id);
      lab.ready();
      var stage =
          client.call(
              "/internal/labs/"
                  + id
                  + "/fault-stage?subject="
                  + learner
                  + "&generation="
                  + lab.generation(),
              null);
      String state = stage.path("state").asText();
      if (!java.util.Set.of(
              "not_requested", "pending", "complete", "infrastructure_error", "superseded")
          .contains(state)) throw new ApiFailure(503, "infrastructure_unavailable");
      return JsonData.tree(
          Map.of("lab_session_id", id.toString(), "generation", lab.generation(), "state", state));
    }
  }
}
