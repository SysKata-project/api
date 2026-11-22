package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.syskata.api.foundation.ApiFailure;

@ApplicationScoped
public class LabClient {
  @ConfigProperty(name = "syskata.labs.url", defaultValue = "https://unconfigured.invalid")
  String base;

  @ConfigProperty(name = "syskata.labs.token", defaultValue = "unconfigured")
  String token;

  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public JsonNode call(String path, JsonNode body) throws IOException, InterruptedException {
    validateEndpoint();
    var request =
        HttpRequest.newBuilder(URI.create(base.replaceFirst("/$", "") + path))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json");
    if (body == null) request.GET();
    else request.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    var response =
        client.send(
            request.build(),
            HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 65536));
    if (response.statusCode() < 200 || response.statusCode() >= 300)
      throw new ApiFailure(503, "infrastructure_unavailable");
    return response.body().length == 0
        ? JsonData.object()
        : JsonData.parse(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
  }

  private void validateEndpoint() {
    URI uri = URI.create(base);
    boolean local = java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
    if ((!"https".equals(uri.getScheme()) && !(local && "http".equals(uri.getScheme())))
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || token.length() < 32) throw new ApiFailure(503, "infrastructure_unavailable");
  }
}
