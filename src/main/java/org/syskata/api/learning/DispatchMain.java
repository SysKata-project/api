package org.syskata.api.learning;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/** Launch the same artifact with -Dquarkus.package.main-class=dispatch at build time. */
@QuarkusMain(name = "dispatch")
public class DispatchMain implements QuarkusApplication {
  private static final Logger LOG = Logger.getLogger(DispatchMain.class);
  @Inject LabDispatcher dispatcher;

  @Override
  public int run(String... args) throws InterruptedException {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        dispatcher.tick();
        if (java.util.Arrays.asList(args).contains("--once")) return 0;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return 0;
      } catch (Exception failure) {
        LOG.warn("Lab dispatch pass failed; durable work retained");
      }
      Thread.sleep(2000);
    }
    return 0;
  }
}
