package org.syskata.api.foundation;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import java.sql.SQLException;
import org.eclipse.microprofile.jwt.JsonWebToken;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class LearnerBoundary implements ContainerRequestFilter {
  @Inject SecurityIdentity identity;
  @Inject JsonWebToken token;
  @Inject Accounts accounts;

  @Override
  public void filter(ContainerRequestContext request) {
    if (!request.getUriInfo().getPath().replaceFirst("^/", "").startsWith("v1/")) {
      return;
    }
    if (identity.isAnonymous()) {
      request.abortWith(Errors.response(401, "unauthenticated"));
      return;
    }
    try {
      request.setProperty("learner", accounts.active(token.getIssuer(), token.getSubject()));
    } catch (ApiFailure failure) {
      request.abortWith(Errors.response(failure.status(), failure.code()));
    } catch (SQLException failure) {
      request.abortWith(Errors.response(503, "infrastructure_unavailable"));
    }
  }
}
