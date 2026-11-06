package org.syskata.api.scaffold;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ToolingProbeTest {
  @Test
  void describesWaiting() {
    assertEquals("waiting", new ToolingProbe().describe(false));
  }
}
