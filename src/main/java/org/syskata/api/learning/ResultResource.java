package org.syskata.api.learning;

import io.quarkus.oidc.Tenant;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

@Path("/internal/v1/assessments/{id}/result")
@Tenant("results")
@RolesAllowed("assessment-result-writer")
@Consumes("application/json")
@Produces("application/json")
public class ResultResource {
  @Inject ResultService service;
  @Inject JsonWebToken token;

  @POST
  public Response receive(
      @PathParam("id") String id, @HeaderParam("Idempotency-Key") String key, String text)
      throws Exception {
    LearningRequests.key(key);
    return Response.ok(
            service.receive(
                LearningRequests.sessionId(id),
                token.getSubject(),
                JsonData.parse(text == null ? "null" : text)))
        .header("Cache-Control", "no-store")
        .build();
  }
}
