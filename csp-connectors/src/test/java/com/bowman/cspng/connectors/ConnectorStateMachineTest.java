package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConnectorStateMachineTest {

  private Vertx vertx;
  private CacheVerticle cacheVerticle;

  static class TestConnector extends AbstractConnector {
    private final Promise<LoginState> loginPromise = Promise.promise();

    public TestConnector(Vertx vertx, CacheVerticle cv, String name, String host, int port, String profile) {
      super(vertx, cv, name, host, port, profile);
    }

    public void setConnected(boolean c) { this.connected = c; }
    public void setStopped(boolean s) { this.stopped = s; }
    public void resolveLogin(LoginState s) { loginPromise.tryComplete(s); }

    @Override public Future<Void> start() { return Future.succeededFuture(); }
    @Override public void stop() { this.stopped = true; }
    @Override public Future<LoginState> loginFuture() { return loginPromise.future(); }
    @Override public Future<CwReply> sendEcm(EcmRequest request) { return Future.succeededFuture(); }
  }

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    cacheVerticle = new CacheVerticle();
  }

  @AfterEach
  void tearDown() {
    vertx.close();
  }

  @Test
  @DisplayName("État DEAD si déconnecté ou arrêté ou login FAILED")
  void testDeadStates() {
    TestConnector c = new TestConnector(vertx, cacheVerticle, "c1", "localhost", 10000, "p1");
    // Initial: non connecté -> DEAD
    assertEquals(AbstractConnector.ConnectorState.DEAD, c.getState());

    // Connecté mais login failed -> DEAD
    c.setConnected(true);
    c.resolveLogin(AbstractConnector.LoginState.FAILED);
    assertEquals(AbstractConnector.ConnectorState.DEAD, c.getState());

    // Arrêté -> DEAD
    c.setStopped(true);
    assertEquals(AbstractConnector.ConnectorState.DEAD, c.getState());
  }

  @Test
  @DisplayName("État CONNECTED si connecté et login OK sans erreurs récentes")
  void testConnectedState() {
    TestConnector c = new TestConnector(vertx, cacheVerticle, "c1", "localhost", 10000, "p1");
    c.setConnected(true);
    c.resolveLogin(AbstractConnector.LoginState.OK);

    assertEquals(AbstractConnector.ConnectorState.CONNECTED, c.getState());
  }

  @Test
  @DisplayName("État UNSTABLE si ≥ 3 erreurs/reconnexions dans les 5 dernières minutes")
  void testUnstableStateSlidingWindow() {
    TestConnector c = new TestConnector(vertx, cacheVerticle, "c1", "localhost", 10000, "p1");
    c.setConnected(true);
    c.resolveLogin(AbstractConnector.LoginState.OK);

    assertEquals(AbstractConnector.ConnectorState.CONNECTED, c.getState());

    c.recordError();
    c.recordReconnect();
    assertEquals(AbstractConnector.ConnectorState.CONNECTED, c.getState());
    assertFalse(c.isUnstable());

    // 3ème erreur -> UNSTABLE
    c.recordError();
    assertTrue(c.isUnstable());
    assertEquals(AbstractConnector.ConnectorState.UNSTABLE, c.getState());
  }

  @Test
  @DisplayName("Tracking métriques, CAIDs, providers et hits bornés")
  void testMetricsAndObservedTracking() {
    TestConnector c = new TestConnector(vertx, cacheVerticle, "c1", "localhost", 10000, "p1");
    c.setConnected(true);
    c.resolveLogin(AbstractConnector.LoginState.OK);

    c.recordEcmSent(0x0100, 0x000068);
    c.recordEcmSent(0x0500, 0x032830);
    c.recordHit();
    c.recordError();

    assertEquals(2, c.getEcmSent());
    assertEquals(1, c.getHits());
    assertEquals(1, c.getEcmFailed());
    assertTrue(c.getLastSeenMs() > 0);
    assertTrue(c.getObservedCaids().contains(0x0100));
    assertTrue(c.getObservedCaids().contains(0x0500));
    assertTrue(c.getObservedProviders().contains(0x000068));
    assertTrue(c.getObservedProviders().contains(0x032830));

    ConnectorSnapshot snap = new ConnectorSnapshot(
        c.getName(),
        ConnectorSnapshot.deriveProtocol(c),
        c.getProfile(),
        c.isConnected(),
        ConnectorSnapshot.deriveLoginState(c),
        ConnectorSnapshot.deriveState(c),
        c.getHits(),
        c.getEcmSent(),
        c.getEcmFailed(),
        c.getLastSeenMs(),
        c.getObservedCaids().stream().sorted().map(caid -> String.format("0x%04X", caid)).toList(),
        c.getObservedProviders().stream().sorted().map(prov -> String.format("0x%06X", prov)).toList()
    );

    assertEquals("c1", snap.name());
    assertEquals("CONNECTED", snap.state());
    assertEquals(1, snap.hits());
    assertEquals(2, snap.ecmSent());
    assertEquals(1, snap.ecmFailed());
    assertEquals(List.of("0x0100", "0x0500"), snap.caids());
    assertEquals(List.of("0x000068", "0x032830"), snap.providers());
  }
}
