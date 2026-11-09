package org.syskata.api.foundation;

import io.vertx.core.json.Json;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class TransportFailures {
  public void configure(@Observes Router router) {
    router
        .route("/v1/*")
        .order(Integer.MIN_VALUE)
        .handler(BodyHandler.create().setBodyLimit(65536).setHandleFileUploads(false));
    router
        .route()
        .order(Integer.MIN_VALUE)
        .failureHandler(
            context -> {
              if (context.statusCode() == 413) {
                context
                    .response()
                    .setStatusCode(413)
                    .putHeader("Content-Type", "application/json")
                    .putHeader("Cache-Control", "no-store")
                    .end(Json.encode(Errors.response(413, "invalid_request").getEntity()));
              } else {
                context.next();
              }
            });
  }
}
