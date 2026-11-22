package org.syskata.api.foundation;

import io.quarkus.security.AuthenticationFailedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class AuthenticationFailure implements ExceptionMapper<AuthenticationFailedException> {
  @Override
  public Response toResponse(AuthenticationFailedException failure) {
    return Errors.response(401, "unauthenticated");
  }
}
