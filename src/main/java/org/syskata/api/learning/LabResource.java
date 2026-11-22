package org.syskata.api.learning;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import java.util.Set;
import java.util.UUID;

@Path("/v1")
@Authenticated
@Consumes("application/json")
@Produces("application/json")
public class LabResource {
  @Inject LabService service;
  @Inject LearningStore store;
  @Inject LabAccess access;
  @Context ContainerRequestContext context;

  private UUID learner() {
    return (UUID) context.getProperty("learner");
  }

  @POST
  @Path("/lab-sessions")
  public Response create(@HeaderParam("Idempotency-Key") String key, String text) throws Exception {
    var body =
        LabRequests.body(
            text, Set.of("session_id", "session_revision", "exercise_instance_id", "attempt_id"));
    return mutation(key, "", body, c -> service.create(c, learner(), body), 202);
  }

  @GET
  @Path("/lab-sessions/{id}")
  public Response read(@PathParam("id") String id) throws Exception {
    return response(service.read(learner(), LearningRequests.sessionId(id), false), 200);
  }

  @GET
  @Path("/assessments/{id}")
  public Response assessment(@PathParam("id") String id) throws Exception {
    return response(service.read(learner(), LearningRequests.sessionId(id), true), 200);
  }

  @POST
  @Path("/lab-sessions/{id}/reset")
  public Response reset(
      @PathParam("id") String id, @HeaderParam("Idempotency-Key") String key, String text)
      throws Exception {
    return change(id, key, text, true);
  }

  @POST
  @Path("/lab-sessions/{id}/close")
  public Response close(
      @PathParam("id") String id, @HeaderParam("Idempotency-Key") String key, String text)
      throws Exception {
    return change(id, key, text, false);
  }

  private Response change(String id, String key, String text, boolean reset) throws Exception {
    var body = LabRequests.body(text, Set.of("session_revision", "generation"));
    return mutation(
        key,
        "/" + id + (reset ? "/reset" : "/close"),
        body,
        c -> service.change(c, learner(), LearningRequests.sessionId(id), body, reset),
        202);
  }

  @POST
  @Path("/lab-sessions/{id}/assessments")
  public Response assess(
      @PathParam("id") String id, @HeaderParam("Idempotency-Key") String key, String text)
      throws Exception {
    var body = LabRequests.body(text, Set.of("session_revision", "generation", "attempt_id"));
    return mutation(
        key,
        "/" + id + "/assessments",
        body,
        c -> service.assess(c, learner(), LearningRequests.sessionId(id), body),
        202);
  }

  @POST
  @Path("/lab-sessions/{id}/terminal-connections")
  public Response ticket(
      @PathParam("id") String id, @HeaderParam("Idempotency-Key") String key, String text)
      throws Exception {
    var body = LabRequests.body(text, Set.of("session_revision", "generation"));
    return mutation(
        key,
        "/" + id + "/terminal-connections",
        body,
        c -> access.ticket(c, learner(), LearningRequests.sessionId(id), body),
        200);
  }

  @POST
  @Path("/lab-sessions/{id}/fault-stage")
  public Response stage(
      @PathParam("id") String id, @HeaderParam("Idempotency-Key") String key, String text)
      throws Exception {
    var body = LabRequests.body(text, Set.of("session_revision", "generation"));
    return mutation(
        key,
        "/" + id + "/fault-stage",
        body,
        c -> access.stage(c, learner(), LearningRequests.sessionId(id), body),
        202);
  }

  @GET
  @Path("/lab-sessions/{id}/fault-stage")
  public Response stageStatus(@PathParam("id") String id) throws Exception {
    return response(access.stageStatus(learner(), LearningRequests.sessionId(id)), 200);
  }

  private Response mutation(
      String key, String suffix, JsonNode body, LearningStore.Work work, int status)
      throws Exception {
    return response(
        store.mutate(
            learner(), "POST /v1/lab-sessions" + suffix, LearningRequests.key(key), body, work),
        status);
  }

  private Response response(JsonNode body, int status) {
    var response = Response.status(status).entity(body).header("Cache-Control", "no-store");
    if (status == 202) {
      response.header("Retry-After", "2");
      response.header(
          "Location",
          body.has("status_url")
              ? body.path("status_url").asText()
              : "/v1/lab-sessions/" + body.path("lab_session_id").asText());
    }
    return response.build();
  }
}
