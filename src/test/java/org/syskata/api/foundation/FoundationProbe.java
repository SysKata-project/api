package org.syskata.api.foundation;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

@Path("/v1/foundation-probe")
@Authenticated
public class FoundationProbe {
  @Inject Accounts accounts;
  @Context ContainerRequestContext request;

  @GET
  public Map<String, String> read() {
    return Map.of("status", "active");
  }

  @GET
  @Path("/{session}")
  public Map<String, String> owned(@PathParam("session") UUID session) throws SQLException {
    accounts.requireSessionOwner(session, (UUID) request.getProperty("learner"));
    return Map.of("status", "owned");
  }
}
