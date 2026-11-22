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

@Path("/v1/learning-sessions/{session}/attempts")
@Authenticated
@Consumes("application/json")
@Produces("application/json")
public class AttemptResource {
  @Inject AttemptService service;
  @Inject LearningStore store;
  @Context ContainerRequestContext context;

  @PathParam("session")
  String session;

  private UUID learner() {
    return (UUID) context.getProperty("learner");
  }

  private Response response(JsonNode body) {
    return Response.ok(body).header("Cache-Control", "no-store").build();
  }

  @POST
  public Response start(@HeaderParam("Idempotency-Key") String key, String text) throws Exception {
    JsonNode body = body(text, Set.of("session_revision", "exercise_instance_id"));
    return mutation(
        key, "", body, c -> service.start(c, learner(), LearningRequests.sessionId(session), body));
  }

  @GET
  @Path("/{attempt}")
  public Response read(@PathParam("attempt") String attempt) throws Exception {
    return response(
        service.read(
            learner(), LearningRequests.sessionId(session), LearningRequests.sessionId(attempt)));
  }

  @POST
  @Path("/{attempt}/submission")
  public Response submit(
      @PathParam("attempt") String attempt, @HeaderParam("Idempotency-Key") String key, String text)
      throws Exception {
    JsonNode body = body(text, Set.of("session_revision", "exercise_instance_id", "answer"));
    return mutation(
        key,
        "/" + attempt + "/submission",
        body,
        c ->
            service.submit(
                c,
                learner(),
                LearningRequests.sessionId(session),
                LearningRequests.sessionId(attempt),
                body));
  }

  @POST
  @Path("/{attempt}/hints")
  public Response hint(
      @PathParam("attempt") String attempt, @HeaderParam("Idempotency-Key") String key, String text)
      throws Exception {
    JsonNode body = body(text, Set.of("session_revision", "hint_id"));
    LearningRequests.id(body.path("hint_id"));
    return assist(attempt, key, body, false);
  }

  @POST
  @Path("/{attempt}/reveal")
  public Response reveal(
      @PathParam("attempt") String attempt, @HeaderParam("Idempotency-Key") String key, String text)
      throws Exception {
    return assist(attempt, key, body(text, Set.of("session_revision")), true);
  }

  private Response assist(String attempt, String key, JsonNode body, boolean reveal)
      throws Exception {
    return mutation(
        key,
        "/" + attempt + (reveal ? "/reveal" : "/hints"),
        body,
        c ->
            service.assistance(
                c,
                learner(),
                LearningRequests.sessionId(session),
                LearningRequests.sessionId(attempt),
                body,
                reveal));
  }

  private Response mutation(String key, String suffix, JsonNode body, LearningStore.Work work)
      throws Exception {
    return response(
        store.mutate(
            learner(),
            "POST /v1/learning-sessions/" + session + "/attempts" + suffix,
            LearningRequests.key(key),
            body,
            work));
  }

  private JsonNode body(String text, Set<String> fields) {
    JsonNode body = JsonData.parse(text == null ? "null" : text);
    TextGrading.require(
        body.isObject() && body.size() == fields.size() && fields.stream().allMatch(body::has));
    JsonNode revision = body.path("session_revision");
    TextGrading.require(
        revision.isIntegralNumber()
            && revision.canConvertToLong()
            && revision.asLong() >= 1
            && revision.asLong() <= 9007199254740991L);
    if (body.has("exercise_instance_id")) {
      JsonNode instance = body.path("exercise_instance_id");
      TextGrading.require(
          instance.isTextual()
              && instance.asText().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"));
    }
    return body;
  }
}
