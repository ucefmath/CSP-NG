package com.bowman.cspng.app.logs;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.logging.LogManager;
import java.util.logging.Logger;

@ApplicationScoped
public class AdminLogInitializer {

  private static final Logger LOG = Logger.getLogger(AdminLogInitializer.class.getName());

  @Inject
  LogBufferManager logBufferManager;

  @Inject
  @ConfigProperty(name = "csp.logs.capture-prefixes", defaultValue = "com.bowman.cspng")
  String capturePrefixes;

  private volatile AdminLogHandler handler;

  public void onStart(@Observes @Priority(5) StartupEvent event) {
    if (handler != null) return;
    try {
      handler = new AdminLogHandler(logBufferManager, capturePrefixes);
      Logger rootLogger = LogManager.getLogManager().getLogger("");
      if (rootLogger != null) {
        rootLogger.addHandler(handler);
      } else {
        Logger.getLogger("com.bowman.cspng").addHandler(handler);
      }
      LOG.info("AdminLogHandler installed on root logger (ring buffer capacity="
          + logBufferManager.getMainBuffer().capacity() + ", prefixes=" + capturePrefixes + ")");
    } catch (Exception e) {
      LOG.warning("Failed to install AdminLogHandler on root logger: " + e.getMessage());
    }
  }
}
