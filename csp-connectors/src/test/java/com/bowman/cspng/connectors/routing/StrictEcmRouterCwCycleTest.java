package com.bowman.cspng.connectors.routing;

import com.bowman.cspng.config.CwCycleConfig;
import com.bowman.cspng.connectors.AbstractConnector;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.filters.CwCycleChecker;
import com.bowman.cspng.users.ProtocolAuthResolver;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CW-cycle branché sur le dispatch (P4) : une CW figée servie par un connecteur
 * est REJETÉE (failover, jamais servie au client) et les compteurs réels avancent.
 */
@DisplayName("StrictEcmRouter + CwCycleChecker — rejet réel des mauvais cycles")
class StrictEcmRouterCwCycleTest {

  private Vertx vertx;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (vertx != null) {
      CountDownLatch latch = new CountDownLatch(1);
      vertx.close(v -> latch.countDown());
      latch.await(5, TimeUnit.SECONDS);
    }
  }

  /** CW 16 octets valide (checksum DVB) et stable. */
  private static byte[] validCw(int seed) {
    byte[] cw = new byte[16];
    for (int g = 0; g < 4; g++) {
      cw[g * 4] = (byte) (seed + g * 3 + 1);
      cw[g * 4 + 1] = (byte) (seed + g * 3 + 2);
      cw[g * 4 + 2] = (byte) (seed + g * 3 + 3);
      cw[g * 4 + 3] = (byte) ((cw[g * 4] + cw[g * 4 + 1] + cw[g * 4 + 2]) & 0xFF);
    }
    return cw;
  }

  private static ProtocolAuthResolver.AuthResult auth(String user) {
    return new ProtocolAuthResolver.AuthResult(user, "file:/run/secrets/u", 1L, 1L,
        Instant.now().plusSeconds(3600), 1L, Set.of("default"), Map.of(), Map.of(),
        Set.of("conn1"), null);
  }

  private static class DummyConnector extends AbstractConnector {
    private final java.util.function.BiFunction<EcmRequest, Long, Future<CwReply>> handler;

    DummyConnector(String name, String profile,
                   java.util.function.BiFunction<EcmRequest, Long, Future<CwReply>> handler) {
      super(null, null, name, "127.0.0.1", 1000, profile);
      this.handler = handler;
    }

    @Override public boolean isConnected() { return true; }
    @Override public Future<CwReply> sendEcm(EcmRequest request) {
      return handler.apply(request, System.currentTimeMillis());
    }
    @Override public Future<Void> start() { return Future.succeededFuture(); }
    @Override public void stop() {}
    @Override public Future<LoginState> loginFuture() { return Future.succeededFuture(LoginState.OK); }
  }

  @Test
  @DisplayName("CW figée (même parité, cycle écoulé) => rejetée, failover vide, compteurs++")
  void frozenCwIsRejectedAndNeverServed() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);
    byte[] frozen = validCw(41);
    long t0 = 1_000_000L;

    DummyConnector conn = new DummyConnector("conn1", "default",
        (req, now) -> Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(),
            frozen.clone(), "conn1", "127.0.0.1", req.key().dataHash() == 100 ? t0 : t0 + 60_000L)));
    cm.addTestConnector(conn);

    StrictEcmRouter router = new StrictEcmRouter(cm);
    CwCycleChecker checker = new CwCycleChecker(true, List.of(), 500, 15, "drop", true, false);
    router.setCwCycle(checker, new CwCycleConfig(true, List.of(), 500, 15, "drop", true, false, 1L));

    // 1ère requête (hash 100) : CW apprise, servie
    EcmRequest req1 = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 100),
        new byte[]{1, 2}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000);
    AtomicReference<CwReply> r1 = new AtomicReference<>();
    CountDownLatch l1 = new CountDownLatch(1);
    router.routeEcm(req1, auth("u1")).onComplete(ar -> {
      assertTrue(ar.succeeded());
      r1.set(ar.result());
      l1.countDown();
    });
    assertTrue(l1.await(2, TimeUnit.SECONDS));
    assertNotNull(r1.get());
    assertFalse(r1.get().isEmpty(), "la 1ère CW doit être servie");
    assertArrayEquals(frozen, r1.get().cw());

    // 2ème requête (hash 200, même scope service) : MÊME CW, cycle écoulé => FROZEN => rejet
    EcmRequest req2 = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 200),
        new byte[]{3, 4}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000);
    AtomicReference<CwReply> r2 = new AtomicReference<>();
    CountDownLatch l2 = new CountDownLatch(1);
    router.routeEcm(req2, auth("u1")).onComplete(ar -> {
      assertTrue(ar.succeeded());
      r2.set(ar.result());
      l2.countDown();
    });
    assertTrue(l2.await(2, TimeUnit.SECONDS));
    assertNotNull(r2.get());
    assertTrue(r2.get().isEmpty(), "la CW figée doit être REJETÉE (failover vide), jamais servie");

    assertEquals(1, checker.badCycles(), "compteur badCycles réel");
    assertEquals(1, checker.droppedCount(), "compteur dropped réel");
  }

  @Test
  @DisplayName("Checker désactivé => aucun rejet (comportement historique conservé)")
  void disabledCheckerChangesNothing() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);
    byte[] frozen = validCw(42);
    DummyConnector conn = new DummyConnector("conn1", "default",
        (req, now) -> Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(),
            frozen.clone(), "conn1", "127.0.0.1", System.currentTimeMillis())));
    cm.addTestConnector(conn);

    StrictEcmRouter router = new StrictEcmRouter(cm);
    CwCycleChecker checker = new CwCycleChecker(500, 15, "drop", true, false); // enabled=false
    router.setCwCycle(checker, CwCycleConfig.DEFAULT);

    EcmRequest req = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 300),
        new byte[]{1, 2}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000);
    AtomicReference<CwReply> r = new AtomicReference<>();
    CountDownLatch l = new CountDownLatch(1);
    router.routeEcm(req, auth("u1")).onComplete(ar -> {
      r.set(ar.result());
      l.countDown();
    });
    assertTrue(l.await(2, TimeUnit.SECONDS));
    assertFalse(r.get().isEmpty(), "checker off => servi normalement");
    assertEquals(0, checker.checks(), "aucun check compté quand désactivé");
  }
}
