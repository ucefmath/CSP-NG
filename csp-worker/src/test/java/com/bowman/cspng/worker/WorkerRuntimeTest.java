package com.bowman.cspng.worker;

import com.bowman.cspng.config.RelayWorkerContract;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClientOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dataplane worker reel en loopback : 2 listeners ouverts, session preauth
 * suivie, revocation (retrait listener) ferme sessions + serveurs, failClosed
 * purge tout. Sans PG, sans centrale.
 */
@DisplayName("WorkerRuntime — loopback 2 listeners, revocation ferme sessions")
class WorkerRuntimeTest {

  private Vertx vertx;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (vertx != null) {
      CountDownLatch l = new CountDownLatch(1);
      vertx.close(v -> l.countDown());
      l.await(10, TimeUnit.SECONDS);
    }
  }

  private static RelayWorkerContract.DesiredWorkerConfig desired(long version, boolean withSecond) {
    var listeners = withSecond
        ? List.of(
            new RelayWorkerContract.ListenerView(7L, "default", "cccam", 14121, "127.0.0.1",
                "manual", 0, 512, 32, 5, 60, null, null),
            new RelayWorkerContract.ListenerView(8L, "default", "cs378x", 14122, "127.0.0.1",
                "manual", 0, 512, 32, 5, 60, null, null))
        : List.of(
            new RelayWorkerContract.ListenerView(7L, "default", "cccam", 14121, "127.0.0.1",
                "manual", 0, 512, 32, 5, 60, null, null));
    var caches = List.of(
        new RelayWorkerContract.CacheView("c1", "local", true, 8000L, 10000, 512),
        new RelayWorkerContract.CacheView("c2", "local", true, 8000L, 10000, 512));
    var links = withSecond
        ? List.of(
            new RelayWorkerContract.LinkView(7L, "c1", true, true, false, true),
            new RelayWorkerContract.LinkView(8L, "c2", true, true, false, true))
        : List.of(
            new RelayWorkerContract.LinkView(7L, "c1", true, true, false, true));
    long now = System.currentTimeMillis();
    return new RelayWorkerContract.DesiredWorkerConfig("peer-a", version, now + 30000L, 30000L,
        listeners, caches, links, List.of(), List.of(), List.of(), List.of(), List.of());
  }

  @Test
  @DisplayName("2 listeners servis, session preauth trackee, retrait listener ferme la session")
  void twoListenersRevocationClosesSession() throws Exception {
    WorkerState state = new WorkerState("peer-a");
    WorkerRuntime runtime = new WorkerRuntime(vertx, "peer-a", state);
    try {
      Boolean applied = runtime.applyDesired(desired(1L, true), System.currentTimeMillis())
          .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
      assertTrue(applied);
      assertEquals(2, runtime.listenerCount());

      // Connexion brute (preauth) sur le listener 8.
      var client = vertx.createNetClient(new NetClientOptions().setConnectTimeout(3000));
      try {
        var socket = client.connect(14122, "127.0.0.1")
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        CountDownLatch closed = new CountDownLatch(1);
        socket.closeHandler(v -> closed.countDown());
        // Session creee a l'accept (avant fin handshake).
        assertTrue(eventuallySession(runtime));
        // Revocation : desired v2 sans le listener 8.
        Boolean applied2 = runtime.applyDesired(desired(2L, false), System.currentTimeMillis())
            .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        assertTrue(applied2);
        assertEquals(1, runtime.listenerCount());
        assertEquals(0, runtime.sessionCount(), "sessions du listener revoque fermees");
        assertTrue(closed.await(10, TimeUnit.SECONDS), "socket client du listener revoque fermee");
        socket.close();
      } finally {
        client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      }

      // failClosed global : plus aucun listener.
      runtime.failClosed("test");
      assertEquals(0, runtime.listenerCount());
    } finally {
      runtime.close();
    }
  }

  private static boolean eventuallySession(WorkerRuntime runtime) throws Exception {
    long deadline = System.currentTimeMillis() + 10000;
    while (System.currentTimeMillis() < deadline) {
      if (runtime.sessionCount() > 0) return true;
      Thread.sleep(100);
    }
    fail("aucune session preauth trackee");
    return false;
  }

  @Test
  @DisplayName("403/410 type -> failClosed (heartbeat revoque jamais prolonge)")
  void typedRevocationFailsClosed() {
    WorkerState state = new WorkerState("peer-a");
    WorkerRuntime runtime = new WorkerRuntime(vertx, "peer-a", state);
    try {
      WorkerMain.onControlError(runtime, new WorkerHttpException(403, "WORKER_REVOKED", "revoked"));
      assertEquals(0, runtime.listenerCount());
      WorkerMain.onControlError(runtime, new WorkerHttpException(410, "WORKER_EXPIRED", "expired"));
      assertEquals(0, runtime.listenerCount());
    } finally {
      runtime.close();
    }
  }

  @Test
  @DisplayName("Port UDP occupe -> apply echoue -> failClosed propre -> port libere -> retry MEME version reussit")
  void udpPortOccupiedRollbackAndRetrySameVersion() throws Exception {
    int udpPort = 14952;
    int listenerPort = 14951;

    // 1. Occupe le port UDP avec une socket brute non-partagee
    java.net.DatagramSocket blocker = new java.net.DatagramSocket(null);
    blocker.setReuseAddress(false);
    blocker.bind(new java.net.InetSocketAddress("0.0.0.0", udpPort));

    WorkerState state = new WorkerState("peer-a");
    WorkerRuntime runtime = new WorkerRuntime(vertx, "peer-a", state);
    runtime.setUdpPort(udpPort);

    long now = System.currentTimeMillis();
    var desired = new RelayWorkerContract.DesiredWorkerConfig("peer-a", 1L, now + 30000L, 30000L,
        List.of(new RelayWorkerContract.ListenerView(7L, "default", "cccam", listenerPort, "127.0.0.1",
            "manual", 0, 512, 32, 5, 60, null, null)),
        List.of(new RelayWorkerContract.CacheView("u", "udp", true, 8000L, 10000, 512, null,
            List.of(new RelayWorkerContract.PeerView("127.0.0.1", 14953)))),
        List.of(new RelayWorkerContract.LinkView(7L, "u", true, true, false, true)),
        List.of(), List.of(), List.of(), List.of(), List.of());

    try {
      // 2. L'application doit echouer a cause du port UDP occupe
      ExecutionException ex = assertThrows(ExecutionException.class, () ->
          runtime.applyDesired(desired, System.currentTimeMillis())
              .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
      assertTrue(ex.getMessage().contains("apply failed"), "Message: " + ex.getMessage());

      // 3. Verifie le failClosed transactionnel : pas d'avancement de version, ressources nettoyees
      assertEquals(-1L, runtime.appliedVersion(), "appliedVersion doit rester -1");
      assertEquals(-1L, state.appliedDesired(), "state.appliedDesired doit rester -1 (pas d'etat corrompu)");
      assertEquals(0, runtime.listenerCount(), "Le listener partiellement ouvert doit etre ferme");

      // Verifie que le port listener 14951 n'est pas reste ouvert (pas de fuite)
      var client = vertx.createNetClient(new NetClientOptions().setConnectTimeout(1000));
      try {
        assertThrows(Exception.class, () ->
            client.connect(listenerPort, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS));
      } finally {
        client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      }

      // 4. Libere le port UDP
      blocker.close();

      // 5. Re-applique la MEME version (1L) : doit reussir et ne pas etre rejetee en stale
      Boolean applied = runtime.applyDesired(desired, System.currentTimeMillis())
          .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
      assertTrue(applied, "La re-application de la meme version 1L doit reussir");
      assertEquals(1L, runtime.appliedVersion());
      assertEquals(1L, state.appliedDesired());
      assertEquals(1, runtime.listenerCount());

      // 6. Heartbeat suivant sur la meme version : retourne false (pas de re-application)
      Boolean heartbeat = runtime.applyDesired(desired, System.currentTimeMillis())
          .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
      assertFalse(heartbeat, "Heartbeat avec meme version retourne false");
    } finally {
      blocker.close();
      runtime.close();
    }
  }

  @Test
  @DisplayName("Port listener occupe -> apply echoue -> libere -> retry meme version reussit")
  void listenerPortOccupiedRollbackAndRetrySameVersion() throws Exception {
    int listenerPort = 14961;

    // 1. Occupe le port listener avec un ServerSocket
    java.net.ServerSocket blocker = new java.net.ServerSocket();
    blocker.setReuseAddress(false);
    blocker.bind(new java.net.InetSocketAddress("127.0.0.1", listenerPort));

    WorkerState state = new WorkerState("peer-a");
    WorkerRuntime runtime = new WorkerRuntime(vertx, "peer-a", state);

    long now = System.currentTimeMillis();
    var desired = new RelayWorkerContract.DesiredWorkerConfig("peer-a", 1L, now + 30000L, 30000L,
        List.of(new RelayWorkerContract.ListenerView(7L, "default", "cccam", listenerPort, "127.0.0.1",
            "manual", 0, 512, 32, 5, 60, null, null)),
        List.of(new RelayWorkerContract.CacheView("c1", "local", true, 8000L, 10000, 512)),
        List.of(new RelayWorkerContract.LinkView(7L, "c1", true, true, false, true)),
        List.of(), List.of(), List.of(), List.of(), List.of());

    try {
      // 2. L'application doit echouer a cause du port listener occupe
      ExecutionException ex = assertThrows(ExecutionException.class, () ->
          runtime.applyDesired(desired, System.currentTimeMillis())
              .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
      assertTrue(ex.getMessage().contains("apply failed"), "Message: " + ex.getMessage());

      assertEquals(-1L, runtime.appliedVersion());
      assertEquals(-1L, state.appliedDesired());
      assertEquals(0, runtime.listenerCount());

      // 3. Libere le port listener
      blocker.close();

      // 4. Retry meme version : succes
      Boolean applied = runtime.applyDesired(desired, System.currentTimeMillis())
          .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
      assertTrue(applied);
      assertEquals(1L, runtime.appliedVersion());
      assertEquals(1L, state.appliedDesired());
      assertEquals(1, runtime.listenerCount());
    } finally {
      blocker.close();
      runtime.close();
    }
  }

  @Test
  @DisplayName("Lease expiree pendant le retry -> refus immediat, reste failClosed")
  void expiredLeaseOnRetryRefused() throws Exception {
    WorkerState state = new WorkerState("peer-a");
    WorkerRuntime runtime = new WorkerRuntime(vertx, "peer-a", state);
    try {
      long past = System.currentTimeMillis() - 1000L;
      var expiredDesired = new RelayWorkerContract.DesiredWorkerConfig("peer-a", 1L, past, 30000L,
          List.of(new RelayWorkerContract.ListenerView(7L, "default", "cccam", 14971, "127.0.0.1",
              "manual", 0, 512, 32, 5, 60, null, null)),
          List.of(new RelayWorkerContract.CacheView("c1", "local", true, 8000L, 10000, 512)),
          List.of(new RelayWorkerContract.LinkView(7L, "c1", true, true, false, true)),
          List.of(), List.of(), List.of(), List.of(), List.of());

      ExecutionException ex = assertThrows(ExecutionException.class, () ->
          runtime.applyDesired(expiredDesired, System.currentTimeMillis())
              .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
      assertTrue(ex.getMessage().contains("desired expired") || ex.getMessage().contains("expired"),
          "Message: " + ex.getMessage());
      assertEquals(-1L, runtime.appliedVersion());
      assertEquals(-1L, state.appliedDesired());
    } finally {
      runtime.close();
    }
  }

  @Test
  @DisplayName("Heartbeat même version : prolonge le bail state/policy sans restart ni coupure de session")
  void heartbeatSameVersionRenewsLeaseWithoutRestartingListenersOrSessions() throws Exception {
    int listenerPort = 14981;
    WorkerState state = new WorkerState("peer-a");
    WorkerRuntime runtime = new WorkerRuntime(vertx, "peer-a", state);

    long now = System.currentTimeMillis();
    long initialExpiry = now + 30_000L;
    var desiredV1 = new RelayWorkerContract.DesiredWorkerConfig("peer-a", 1L, initialExpiry, 30000L,
        List.of(new RelayWorkerContract.ListenerView(7L, "default", "cccam", listenerPort, "127.0.0.1",
            "manual", 0, 512, 32, 5, 60, null, null)),
        List.of(new RelayWorkerContract.CacheView("c1", "local", true, 8000L, 10000, 512)),
        List.of(new RelayWorkerContract.LinkView(7L, "c1", true, true, false, true)),
        List.of(), List.of(), List.of(), List.of(), List.of());

    try {
      // 1. Initial apply
      Boolean applied = runtime.applyDesired(desiredV1, System.currentTimeMillis())
          .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
      assertTrue(applied);
      assertEquals(1L, runtime.appliedVersion());
      assertEquals(1L, state.appliedDesired());
      assertEquals(initialExpiry, state.expiresAtMs());
      assertEquals(1, runtime.listenerCount());

      // 2. Client connecté
      var client = vertx.createNetClient(new NetClientOptions().setConnectTimeout(3000));
      try {
        var socket = client.connect(listenerPort, "127.0.0.1")
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(eventuallySession(runtime));
        assertEquals(1, runtime.sessionCount());

        // 3. Répétition de >3 heartbeats successifs avec la même version et baux progressifs
        long currentExpiry = initialExpiry;
        for (int i = 1; i <= 5; i++) {
          long nextExpiry = currentExpiry + 10_000L;
          var heartbeatDesired = new RelayWorkerContract.DesiredWorkerConfig("peer-a", 1L, nextExpiry, 30000L,
              desiredV1.listeners(), desiredV1.caches(), desiredV1.links(), desiredV1.connectors(),
              desiredV1.users(), desiredV1.userSecrets(), desiredV1.catalog(), desiredV1.proxies(), desiredV1.nodes());

          Boolean res = runtime.applyDesired(heartbeatDesired, System.currentTimeMillis())
              .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
          assertFalse(res, "Heartbeat même version doit retourner false (pas de ré-instanciation)");
          assertEquals(1L, runtime.appliedVersion());
          assertEquals(1L, state.appliedDesired());
          assertEquals(nextExpiry, state.expiresAtMs(), "expiresAtMs doit avoir été prolongé");

          // Vérifie que les listeners et sessions sont STRICTEMENT restés intacts
          assertEquals(1, runtime.listenerCount(), "Listener toujours actif");
          assertEquals(1, runtime.sessionCount(), "Session cliente non coupée");
          currentExpiry = nextExpiry;
        }

        socket.close();
      } finally {
        client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      }
    } finally {
      runtime.close();
    }
  }

  @Test
  @DisplayName("Refus node mismatch et rollback")
  void nodeMismatchAndRollbackRefused() throws Exception {
    WorkerState state = new WorkerState("peer-a");
    WorkerRuntime runtime = new WorkerRuntime(vertx, "peer-a", state);
    try {
      long now = System.currentTimeMillis();
      var wrongNodeDesired = new RelayWorkerContract.DesiredWorkerConfig("peer-b", 1L, now + 30000L, 30000L,
          List.of(), List.of(), List.of(), List.of());
      ExecutionException exNode = assertThrows(ExecutionException.class, () ->
          runtime.applyDesired(wrongNodeDesired, now)
              .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
      assertTrue(exNode.getMessage().contains("desired node mismatch"));

      // Initial apply v2
      var v2 = new RelayWorkerContract.DesiredWorkerConfig("peer-a", 2L, now + 30000L, 30000L,
          List.of(), List.of(), List.of(), List.of());
      assertTrue(runtime.applyDesired(v2, now).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));

      // Rollback vers v1 refusé
      var v1 = new RelayWorkerContract.DesiredWorkerConfig("peer-a", 1L, now + 30000L, 30000L,
          List.of(), List.of(), List.of(), List.of());
      ExecutionException exRollback = assertThrows(ExecutionException.class, () ->
          runtime.applyDesired(v1, now).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
      assertTrue(exRollback.getMessage().contains("desired rollback refused"));
      assertEquals(2L, runtime.appliedVersion());
      assertEquals(2L, state.appliedDesired());
    } finally {
      runtime.close();
    }
  }

  @Test
  @DisplayName("Concurrence : higher version et old heartbeat ne corrompent ni ne prolongent le wrong state")
  void concurrentHigherVersionAndOldHeartbeat() throws Exception {
    WorkerState state = new WorkerState("peer-a");
    WorkerRuntime runtime = new WorkerRuntime(vertx, "peer-a", state);
    try {
      long now = System.currentTimeMillis();
      var v1 = new RelayWorkerContract.DesiredWorkerConfig("peer-a", 1L, now + 30000L, 30000L,
          List.of(), List.of(), List.of(), List.of());
      assertTrue(runtime.applyDesired(v1, now).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));

      // Apply v2
      long v2Expiry = now + 40000L;
      var v2 = new RelayWorkerContract.DesiredWorkerConfig("peer-a", 2L, v2Expiry, 30000L,
          List.of(), List.of(), List.of(), List.of());
      assertTrue(runtime.applyDesired(v2, now).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
      assertEquals(2L, state.appliedDesired());
      assertEquals(v2Expiry, state.expiresAtMs());

      // Arrivée retardée d'un heartbeat v1
      var oldHeartbeat = new RelayWorkerContract.DesiredWorkerConfig("peer-a", 1L, now + 50000L, 30000L,
          List.of(), List.of(), List.of(), List.of());
      ExecutionException ex = assertThrows(ExecutionException.class, () ->
          runtime.applyDesired(oldHeartbeat, now).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
      assertTrue(ex.getMessage().contains("rollback refused"));

      // L'état v2 et son expiration ne sont PAS écrasés par v1
      assertEquals(2L, runtime.appliedVersion());
      assertEquals(2L, state.appliedDesired());
      assertEquals(v2Expiry, state.expiresAtMs());
    } finally {
      runtime.close();
    }
  }
}
