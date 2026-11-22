package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.syskata.api.foundation.ApiFailure;

@Alternative
@Priority(1)
@Singleton
public class FakeLabs extends LabClient {
  final Map<String, ObjectNode> labs = new HashMap<>();
  final Map<String, JsonNode> jobs = new HashMap<>();
  final Map<String, JsonNode> responses = new HashMap<>();
  boolean unavailable;
  boolean loseResponse;
  int registrations;

  @Override
  public synchronized JsonNode call(String path, JsonNode body) {
    if (unavailable) throw new ApiFailure(503, "infrastructure_unavailable");
    if (path.equals("/internal/accounts/disable")) return JsonData.object();
    if (path.equals("/internal/assessments")) {
      String id = body.path("id").asText();
      JsonNode old = jobs.putIfAbsent(id, body.deepCopy());
      if (old != null && !old.equals(body)) throw new ApiFailure(409, "result_conflict");
      lost();
      return JsonData.tree(Map.of("request", body, "state", "pending"));
    }
    if (path.equals("/internal/labs")) {
      String id = body.path("id").asText();
      var lab =
          labs.computeIfAbsent(
              id,
              ignored -> {
                var result = JsonData.object();
                result.put("id", id);
                result.set("subject", body.path("subject"));
                result.put("generation", 1);
                result.put("revision", 1);
                result.put("state", "ready");
                result.put("expiresAt", Instant.now().plusSeconds(1800).toString());
                result.put("cleanupComplete", false);
                return result;
              });
      lost();
      return lab.deepCopy();
    }
    String id = path.substring("/internal/labs/".length()).split("[/?]")[0];
    var lab = labs.get(id);
    if (lab == null) throw new ApiFailure(404, "not_found");
    if (path.contains("/tickets")) {
      registrations++;
      return JsonData.object();
    }
    if (path.contains("/fault-stage"))
      return JsonData.tree(Map.of("state", body == null ? "complete" : "pending"));
    if (body != null) {
      String key = body.path("key").asText();
      if (responses.containsKey(key)) return responses.get(key).deepCopy();
      if (body.path("revision").asLong() != lab.path("revision").asLong())
        throw new ApiFailure(409, "stale_revision");
      lab.put("revision", lab.path("revision").asLong() + 1);
      if (path.endsWith("/reset")) lab.put("generation", lab.path("generation").asLong() + 1);
      else {
        lab.put("state", "closed");
        lab.put("cleanupComplete", true);
      }
      responses.put(key, lab.deepCopy());
      lost();
    }
    return lab.deepCopy();
  }

  private void lost() {
    if (loseResponse) {
      loseResponse = false;
      throw new ApiFailure(503, "infrastructure_unavailable");
    }
  }
}
