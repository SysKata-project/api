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
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.syskata.api.foundation.ApiFailure;

@Path("/v1")
@Authenticated
@Consumes("application/json")
@Produces("application/json")
public class LearningResource {
  @Inject LearningService service;
  @Inject ProgressService progress;
  @Inject LearningStore store;
  @Context ContainerRequestContext context;

  private UUID learner() {
    return (UUID) context.getProperty("learner");
  }

  private Response response(JsonNode value) {
    return Response.ok(value).header("Cache-Control", "no-store").build();
  }

  @GET
  @Path("/journeys")
  public Response journeys() throws Exception {
    return response(service.catalog());
  }

  @GET
  @Path("/me/journeys/{journey}")
  public Response journey(@PathParam("journey") String journey) throws Exception {
    return response(service.journey(learner(), journey));
  }

  @POST
  @Path("/me/enrollments")
  public Response enroll(@HeaderParam("Idempotency-Key") String key, String text) throws Exception {
    JsonNode body = LearningRequests.body(text, Set.of("journey_id", "timezone"));
    LearningRequests.enrollment(body);
    UUID learner = learner();
    return response(
        store.mutate(
            learner,
            "POST /v1/me/enrollments",
            LearningRequests.key(key),
            body,
            c -> service.enroll(c, learner, body)));
  }

  @POST
  @Path("/learning-sessions")
  public Response start(@HeaderParam("Idempotency-Key") String key, String text) throws Exception {
    JsonNode body = LearningRequests.body(text, Set.of("journey_id", "mode", "lesson_id"));
    LearningRequests.session(body);
    UUID learner = learner();
    return response(
        store.mutate(
            learner,
            "POST /v1/learning-sessions",
            LearningRequests.key(key),
            body,
            c -> service.start(c, learner, body)));
  }

  @GET
  @Path("/learning-sessions/{session}")
  public Response session(@PathParam("session") String session) throws Exception {
    return response(store.read(learner(), LearningRequests.sessionId(session)));
  }

  @GET
  @Path("/me/progress")
  public Response progress() throws Exception {
    return response(progress.read(learner()));
  }

  @POST
  @Path("/me/timezone")
  public Response timezone(@HeaderParam("Idempotency-Key") String key, String text)
      throws Exception {
    var body = JsonData.parse(text == null ? "null" : text);
    if (!body.isObject() || body.size() != 1 || !body.has("timezone"))
      throw new ApiFailure(400, "invalid_request");
    LearningRequests.enrollment(body);
    UUID learner = learner();
    return response(
        store.mutate(
            learner,
            "POST /v1/me/timezone",
            LearningRequests.key(key),
            body,
            c ->
                progress.timezone(
                    c,
                    learner,
                    body.path("timezone").asText(),
                    Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS))));
  }
}
