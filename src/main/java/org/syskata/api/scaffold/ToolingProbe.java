package org.syskata.api.scaffold;

import jakarta.enterprise.context.ApplicationScoped;

/** Minimal branch-bearing fixture for verifying test instrumentation; not a business service. */
@ApplicationScoped
public class ToolingProbe {
  public String describe(boolean ready) {
    if (ready) {
      return "ready";
    }
    return "waiting";
  }
}
