package com.bowman.cspng.app;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CspBootstrapTest {

  @Inject
  Vertx vertx;

  @Test
  void vertxIsAvailable() {
    assertNotNull(vertx);
  }
}
