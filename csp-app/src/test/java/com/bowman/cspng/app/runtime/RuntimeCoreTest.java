package com.bowman.cspng.app.runtime;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.sessions.SessionRegistry;
import com.bowman.cspng.users.InMemoryUserDirectory;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RuntimeCoreTest {

  @Inject Vertx vertx;
  @Inject RuntimeCore runtimeCore;
  @Inject CacheVerticle cacheVerticle;
  @Inject SessionRegistry sessionRegistry;
  @Inject CwsConnectorManager connectorManager;

  @Test
  void producersNeverNullAndSameIdentity() {
    // %test profile has runtime disabled, but producers must still return stable non-null singletons
    assertNotNull(cacheVerticle);
    assertNotNull(sessionRegistry);
    assertNotNull(connectorManager);
    // second injection should be same identity (singleton)
    CacheVerticle cv2 = runtimeCore.cacheVerticle();
    assertSame(cacheVerticle, cv2);
    assertSame(sessionRegistry, runtimeCore.sessionRegistry());
    assertSame(connectorManager, runtimeCore.cwsConnectorManager());
  }

  @Test
  void directRuntimeCoreSameIdentity() {
    // Direct access via RuntimeCore getters matches injected
    assertSame(cacheVerticle, runtimeCore.getCacheVerticle());
    assertNotNull(runtimeCore.getCacheVerticle());
  }

  @Test
  void runtimeCoreDisabledNotDeployed() {
    // In test profile, runtimeEnabled=false, so not started/deployed
    assertFalse(runtimeCore.isStarted());
    // but singleton still exists
    assertNotNull(runtimeCore.getCacheVerticle());
  }

  @Test
  void idempotentDoubleStartStableDeploymentId() throws Exception {
    Vertx v = Vertx.vertx();
    try {
      var rc = new RuntimeCore(v, new InMemoryUserDirectory(v));
      rc.runtimeEnabled = true;
      rc.quarkusProfile = "test";
      rc.onStart(null);
      String id1 = rc.getDeploymentId();
      assertNotNull(id1);
      assertTrue(rc.isStarted());
      rc.onStart(null);
      String id2 = rc.getDeploymentId();
      assertEquals(id1, id2, "second start must not redeploy");
      assertTrue(rc.isStarted());
      rc.onStop(null);
      assertFalse(rc.isStarted());
      assertNull(rc.getDeploymentId());
    } finally {
      v.close();
    }
  }
}
