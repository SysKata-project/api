package org.syskata.api.foundation;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Provider
public class Errors implements ExceptionMapper<Exception> {
  @Override
  public Response toResponse(Exception failure) {
    if (failure instanceof io.quarkus.security.AuthenticationFailedException
        || failure instanceof io.quarkus.security.UnauthorizedException) {
      return response(401, "unauthenticated");
    }
    if (failure instanceof io.quarkus.security.ForbiddenException) {
      return response(403, "forbidden");
    }
    if (failure instanceof ApiFailure expected) {
      return response(expected.status(), expected.code());
    }
    if (failure instanceof WebApplicationException web) {
      int status = web.getResponse().getStatus();
      return response(status, status == 404 ? "not_found" : "invalid_request");
    }
    // Never serialize exception messages: JDBC/OIDC errors may contain secrets.
    return response(503, "infrastructure_unavailable");
  }

  public static Response response(int status, String code) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("code", code);
    error.put("message", code.replace('_', ' '));
    error.put("retryable", status == 503);
    error.put("correlation_id", UUID.randomUUID().toString());
    error.put("recovery", recovery(status));
    error.put("current_revision", null);
    Response.ResponseBuilder response =
        Response.status(status)
            .entity(Map.of("error", error))
            .type("application/json")
            .header("Cache-Control", "no-store");
    if (status == 503) {
      response.header("Retry-After", "5");
    }
    return response.build();
  }

  private static String recovery(int status) {
    return switch (status) {
      case 401 -> "sign_in";
      case 403 -> "contact_support";
      case 404 -> "refresh_state";
      case 503 -> "retry_same_request";
      default -> "correct_request";
    };
  }
}
