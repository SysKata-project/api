package org.syskata.api.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.syskata.api.foundation.ApiFailure;

class LabClientTest {
  @Test
  void transportBoundsRedirectsAndConfiguration() throws Exception {
    var client = new LabClient();
    client.token = "synthetic-local-service-authentication";
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ok",
        exchange -> {
          byte[] body = "{\"state\":\"ready\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/empty",
        exchange -> {
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "/ok");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/large",
        exchange -> {
          byte[] body = new byte[65537];
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      client.base = base;
      assertEquals("ready", client.call("/ok", null).path("state").asText());
      assertEquals("ready", client.call("/ok", JsonData.object()).path("state").asText());
      assertEquals(JsonData.object(), client.call("/empty", null));
      assertThrows(ApiFailure.class, () -> client.call("/redirect", null));
      assertThrows(java.io.IOException.class, () -> client.call("/large", null));
      for (String bad :
          List.of(
              "http://remote.invalid",
              "https://u:p@remote.invalid",
              "https://remote.invalid?q=1",
              "https://remote.invalid#fragment")) {
        client.base = bad;
        assertThrows(ApiFailure.class, () -> client.call("/ok", null));
      }
      client.base = base;
      client.token = "short";
      assertThrows(ApiFailure.class, () -> client.call("/ok", null));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsMalformedRequestBodiesAndProfileBindings() throws Exception {
    var fields = java.util.Set.of("session_revision", "generation");
    for (String bad :
        List.of(
            "null",
            "[]",
            "{}",
            "{\"session_revision\":1,\"generation\":0}",
            "{\"session_revision\":1.5,\"generation\":1}",
            "{\"session_revision\":1,\"generation\":1,\"extra\":1}"))
      assertThrows(ApiFailure.class, () -> LabRequests.body(bad, fields));
    assertThrows(
        ApiFailure.class,
        () -> LabRequests.body("{\"attempt_id\":12}", java.util.Set.of("attempt_id")));
    assertThrows(
        ApiFailure.class,
        () -> LabRequests.body("{\"attempt_id\":\"no\"}", java.util.Set.of("attempt_id")));
    assertEquals(
        1,
        LabRequests.body("{\"session_revision\":1,\"generation\":1}", fields)
            .path("generation")
            .asInt());
    var bundle = LabTest.fixture();
    var q = bundle.entity("checkpoint.oc-foundations", "exercise");
    var a = bundle.assessment("checkpoint.oc-foundations");
    assertEquals("foundations", LabProfile.scenario(q, a));
    var bad = (com.fasterxml.jackson.databind.node.ObjectNode) a.deepCopy();
    bad.put("evaluator_version", 2);
    assertThrows(ApiFailure.class, () -> LabProfile.scenario(q, bad));
  }

  @Test
  void dispatchProcessStopsOnInterruptionAndRetriesFailures() throws Exception {
    var main = new DispatchMain();
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    main.dispatcher =
        new LabDispatcher() {
          @Override
          public void tick() throws Exception {
            if (calls.incrementAndGet() == 1) throw new java.io.IOException("synthetic");
            throw new InterruptedException("stop");
          }
        };
    try {
      assertEquals(0, main.run());
      assertEquals(2, calls.get());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void singleDispatchPassSupportsOperatorRecovery() throws Exception {
    var main = new DispatchMain();
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    main.dispatcher =
        new LabDispatcher() {
          @Override
          public void tick() {
            calls.incrementAndGet();
          }
        };
    assertEquals(0, main.run("--once"));
    assertEquals(1, calls.get());
  }
}
