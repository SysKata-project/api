package org.syskata.api.scaffold;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ToolingProbeQuarkusTest {
  @Inject ToolingProbe probe;

  @Test
  void injectsProbeAndDescribesReady() {
    assertEquals("ready", probe.describe(true));
  }
}
