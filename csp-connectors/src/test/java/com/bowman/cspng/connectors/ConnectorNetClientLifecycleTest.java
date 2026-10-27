package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests du cycle de vie bas niveau des connecteurs (revue croisée — bloquants) :
 * <ul>
 *   <li><b>Options exactes</b> : direct = DÉFAUTS Vert.x historiques (timeout 60 s —
 *       le connectTimeoutMs du connecteur n'écrase JAMAIS le chemin direct) ;
 *       proxy = T du connecteur aux DEUX phases (TCP + handshake SOCKS5).</li>
 *   <li><b>Fuite NetClient</b> : l'ancien client est FERMÉ avant remplacement à
 *       chaque reconnect — tracking wrapper sur cycles répétés.</li>
 *   <li><b>Callbacks obsolètes</b> : résultat de connexion TARDIF après stop ignoré
 *       (génération) — kill switch étanche : pas de connected revival, pas de
 *       reconnexion fantôme ; closeHandler d'une génération obsolète ignoré ;
 *       restart de la même instance : l'ancien cycle ne perturbe pas le nouveau.</li>
 *   <li><b>sendEcm refusé après stop</b>.</li>
 * </ul>
 */
@DisplayName("Cycle de vie NetClient des connecteurs")
class ConnectorNetClientLifecycleTest {

  Vertx vertx;

  @BeforeEach
  void setUp() { vertx = Vertx.vertx(); }

  @AfterEach
  void tearDown() throws Exception {
    if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  // -- Tracking wrapper : compte les clients créés et les fermetures réelles --

  static class TrackingNetClient implements NetClient {
    final NetClient delegate;
    final AtomicBoolean closed = new AtomicBoolean(false);
    static final List<TrackingNetClient> ALL = new CopyOnWriteArrayList<>();

    TrackingNetClient(NetClient delegate) {
      this.delegate = delegate;
      ALL.add(this);
    }

    static void reset() { ALL.clear(); }
    static int closedCount() { return (int) ALL.stream().filter(c -> c.closed.get()).count(); }

    @Override public NetClient connect(int port, String host, Handler<AsyncResult<NetSocket>> h) {
      delegate.connect(port, host, h);
      return this;
    }
    @Override public Future<NetSocket> connect(int port, String host) { return delegate.connect(port, host); }
    @Override public NetClient connect(int port, String host, String serverName, Handler<AsyncResult<NetSocket>> h) {
      delegate.connect(port, host, serverName, h);
      return this;
    }
    @Override public Future<NetSocket> connect(int port, String host, String serverName) {
      return delegate.connect(port, host, serverName);
    }
    @Override public Future<NetSocket> connect(SocketAddress remoteAddress) {
      return delegate.connect(remoteAddress);
    }
    @Override public Future<NetSocket> connect(SocketAddress remoteAddress, String serverName) {
      return delegate.connect(remoteAddress, serverName);
    }
    @Override public NetClient connect(SocketAddress remoteAddress, Handler<AsyncResult<NetSocket>> h) {
      delegate.connect(remoteAddress, h);
      return this;
    }
    @Override public NetClient connect(SocketAddress remoteAddress, String serverName, Handler<AsyncResult<NetSocket>> h) {
      delegate.connect(remoteAddress, serverName, h);
      return this;
    }
    @Override public void close(Handler<AsyncResult<Void>> handler) { closed.set(true); delegate.close(handler); }
    @Override public Future<Void> close() { closed.set(true); return delegate.close(); }
    @Override public Future<Boolean> updateSSLOptions(io.vertx.core.net.SSLOptions options, boolean force) {
      return delegate.updateSSLOptions(options, force);
    }
    @Override public boolean isMetricsEnabled() { return delegate.isMetricsEnabled(); }
  }

  /** Crée un RadegastConnector qui track ses NetClient via le hook createNetClient. */
  private RadegastConnector trackedRadegast(String name, int targetPort, int connectTimeoutMs,
                                            AbstractConnector.NetworkProxyOptions proxyOpts) {
    return new RadegastConnector(vertx, new CacheVerticle(), name, "127.0.0.1", targetPort, "prof",
        connectTimeoutMs, proxyOpts) {
      @Override protected NetClient createNetClient(NetClientOptions options) {
        return new TrackingNetClient(vertx.createNetClient(options));
      }
    };
  }

  private static int freePort() {
    try (var ss = new java.net.ServerSocket(0)) { ss.setReuseAddress(true); return ss.getLocalPort(); }
    catch (Exception e) { return 34100 + (int) (Math.random() * 500); }
  }

  // ============================================================
  // Bloquant 1 — options exactes : direct = défauts Vert.x (60 s), proxy = T 2 phases
  // ============================================================

  @Test
  @Timeout(10)
  @DisplayName("Sans proxy : options par défaut Vert.x historiques — timeout 60 s, PAS d'override cfg")
  void directOptionsKeepVertxDefaults() {
    RadegastConnector c = new RadegastConnector(vertx, new CacheVerticle(),
        "t-opts-direct", "h", 1234, "prof", 5000, null);
    NetClientOptions opts = c.buildNetClientOptions(5000);
    assertEquals(io.vertx.core.net.NetClientOptions.DEFAULT_CONNECT_TIMEOUT,
        opts.getConnectTimeout(),
        "direct: connect timeout doit rester le DÉFAUT Vert.x (" + NetClientOptions.DEFAULT_CONNECT_TIMEOUT
            + "s historique), pas le cfg connecteur (5s)");
    assertNull(opts.getProxyOptions(), "direct: aucune proxy options");
    // Ré-flexion par défaut même avec un cfg grand : l'override direct n'existe plus
    assertEquals(io.vertx.core.net.NetClientOptions.DEFAULT_CONNECT_TIMEOUT,
        c.buildNetClientOptions(60_000).getConnectTimeout());
  }

  @Test
  @Timeout(10)
  @DisplayName("Avec proxy : T du connecteur appliqué aux DEUX phases (TCP + handshake SOCKS5)")
  void proxyOptionsApplyConnectorTToBothPhases() {
    RadegastConnector c = new RadegastConnector(vertx, new CacheVerticle(),
        "t-opts-proxy", "h", 1234, "prof", 7000,
        new AbstractConnector.NetworkProxyOptions("127.0.0.1", 1080, "u", "p"));
    NetClientOptions opts = c.buildNetClientOptions(7000);
    assertEquals(7000, opts.getConnectTimeout(), "TCP connect timeout = T (connecteur)");
    assertNotNull(opts.getProxyOptions());
    assertEquals(7000, opts.getProxyOptions().getConnectTimeout().toMillis(),
        "SOCKS5 handshake timeout = T (connecteur)");
    assertEquals(io.vertx.core.net.ProxyType.SOCKS5, opts.getProxyOptions().getType());
    assertEquals("127.0.0.1", opts.getProxyOptions().getHost());
    assertEquals(1080, opts.getProxyOptions().getPort());
  }

  // ============================================================
  // Bloquant 2 — ancien client FERMÉ avant remplacement, cycles répétés
  // ============================================================

  @Test
  @Timeout(30)
  @DisplayName("Reconnects répétés (Radegast) : ancien client fermé avant remplacement à CHAQUE cycle")
  void oldClientClosedOnRepeatedReconnectCycles() throws Exception {
    TrackingNetClient.reset();
    int deadPort = freePort(); // rien n'écoute → connect échoue → reconnect chaque 5 s
    RadegastConnector c = trackedRadegast("t-leak", deadPort, 2000, null);
    c.start();
    try {
      // Attendre 2 cycles de reconnect : client#0 (start) → fail → +5s client#1 → fail → +5s client#2
      long deadline = System.currentTimeMillis() + 13_000;
      while (System.currentTimeMillis() < deadline && TrackingNetClient.ALL.size() < 3) {
        Thread.sleep(50);
      }
      assertEquals(3, TrackingNetClient.ALL.size(),
          "3 clients attendus (start + 2 reconnects), got " + TrackingNetClient.ALL.size());
      // Les 2 ANCIENS clients doivent être FERMÉS ; seul le dernier (courant) reste ouvert
      assertTrue(TrackingNetClient.ALL.get(0).closed.get(),
          "client#0 (remplacé au 1er reconnect) doit être fermé");
      assertTrue(TrackingNetClient.ALL.get(1).closed.get(),
          "client#1 (remplacé au 2e reconnect) doit être fermé");
      assertFalse(TrackingNetClient.ALL.get(2).closed.get(), "client#2 (courant) reste ouvert");
      assertEquals(2, TrackingNetClient.closedCount(), "exactement 2 clients fermés sur 3 créés");
    } finally {
      c.stop();
    }
    // stop() ferme le dernier client
    Thread.sleep(200);
    assertEquals(3, TrackingNetClient.closedCount(), "stop() ferme le client courant — 3/3 fermés");
  }

  @Test
  @Timeout(20)
  @DisplayName("Reconnect Newcamd : ancien client fermé avant remplacement (1 cycle)")
  void newcamdReconnectClosesOldClient() throws Exception {
    TrackingNetClient.reset();
    int deadPort = freePort();
    NewcamdConnector c = new NewcamdConnector(vertx, new CacheVerticle(),
        "t-nc-leak", "127.0.0.1", deadPort,
        new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14},
        "u", "p", "prof", 2000, null) {
      @Override protected NetClient createNetClient(NetClientOptions options) {
        return new TrackingNetClient(vertx.createNetClient(options));
      }
    };
    c.start();
    try {
      long deadline = System.currentTimeMillis() + 8_000;
      while (System.currentTimeMillis() < deadline && TrackingNetClient.ALL.size() < 2) {
        Thread.sleep(50);
      }
      assertEquals(2, TrackingNetClient.ALL.size(), "2 clients (start + 1 reconnect)");
      assertTrue(TrackingNetClient.ALL.get(0).closed.get(), "ancien client fermé au reconnect");
    } finally {
      c.stop();
    }
  }

  @Test
  @Timeout(20)
  @DisplayName("Reconnect CCcam : ancien client fermé avant remplacement (1 cycle)")
  void cccamReconnectClosesOldClient() throws Exception {
    TrackingNetClient.reset();
    int deadPort = freePort();
    CcCamConnector c = new CcCamConnector(vertx, new CacheVerticle(),
        "t-cc-leak", "127.0.0.1", deadPort, "u", "p", "prof",
        "2.3.2", "4000", 0, 10, 2000, null) {
      @Override protected NetClient createNetClient(NetClientOptions options) {
        return new TrackingNetClient(vertx.createNetClient(options));
      }
    };
    c.start();
    try {
      long deadline = System.currentTimeMillis() + 8_000;
      while (System.currentTimeMillis() < deadline && TrackingNetClient.ALL.size() < 2) {
        Thread.sleep(50);
      }
      assertEquals(2, TrackingNetClient.ALL.size(), "2 clients (start + 1 reconnect)");
      assertTrue(TrackingNetClient.ALL.get(0).closed.get(), "ancien client fermé au reconnect");
    } finally {
      c.stop();
    }
  }

  // ============================================================
  // Bloquant 3 — callbacks obsolètes / génération
  // ============================================================

  @Test
  @Timeout(10)
  @DisplayName("Guard génération : isStale détecte stop et génération dépassée (white-box)")
  void generationGuardWhiteBox() {
    RadegastConnector c = new RadegastConnector(vertx, new CacheVerticle(),
        "t-gen", "h", 1234, "prof", 2000, null);
    c.stop(); // stopped=true, génération bumpée
    int staleGen = 0;
    assertTrue(c.isStale(staleGen), "génération antérieure = obsolète après stop");
    // après un start, les callbacks de l'ancienne génération restent obsolètes
    c.start();
    assertTrue(c.isStale(staleGen), "génération antérieure = obsolète après restart");
    c.stop();
  }

  @Test
  @Timeout(20)
  @DisplayName("Résultat de connexion TARDIF après stop : ignoré — pas de connected, pas de reconnect fantôme")
  void lateConnectResultAfterStopIsIgnored() throws Exception {
    // Greeting SOCKS5 RETARDÉ de 900 ms : TCP établi immédiatement, handshake en attente.
    // stop() à ~200 ms → client.close() annule le connect → callback (ÉCHEC) s'exécute
    // APRÈS le stop → GUARD génération → socket fermée si obtenue, AUCUNE relance.
    Socks5MockServer socks = new Socks5MockServer(vertx, freePort(), null, null);
    socks.greetingDelayMs = 900;
    socks.start();
    RadegastConnector c = new RadegastConnector(vertx, new CacheVerticle(),
        "t-late", "upstream.invalid", 6000, "prof", 10_000,
        new AbstractConnector.NetworkProxyOptions("127.0.0.1", socks.port(), null, null));
    c.start();
    try {
      // TCP atteint le proxy (connect en vol, handshake SOCKS5 en attente de réponse)
      long deadline = System.currentTimeMillis() + 5_000;
      while (System.currentTimeMillis() < deadline && socks.proxyConnections.get() < 1) {
        Thread.sleep(20);
      }
      assertEquals(1, socks.proxyConnections.get(), "tentative TCP reçue par le proxy");
      // STOP pendant le handshake en vol
      c.stop();
      assertFalse(c.isConnected(), "stopped → pas connected");
      // Le greeting part à ~900ms : le handshake peut COMPLÉTER juste avant/pendant le
      // close — le callback de connexion (succès ou échec d'annulation) s'exécute après
      // le stop → le guard doit l'ignorer. Attendre largement le RECONNECT_DELAY (5 s) :
      // AUCUNE nouvelle tentative TCP ne doit apparaître (pas de reconnect fantôme).
      Thread.sleep(RadegastConnector.RECONNECT_DELAY + 1500);
      assertEquals(1, socks.proxyConnections.get(),
          "AUCUN reconnect après stop malgré le résultat tardif du handshake (got "
              + socks.proxyConnections.get() + ")");
      assertFalse(c.isConnected(), "connected ne doit JAMAIS revivre après stop");
      assertTrue(c.loginFuture().isComplete(), "loginFuture complété (FAILED) — pas de pending");
      assertNotEquals(AbstractConnector.LoginState.OK, c.loginFuture().result());
    } finally {
      c.stop();
      socks.stop();
    }
  }

  @Test
  @Timeout(20)
  @DisplayName("Restart même instance : l'ancien cycle (closeHandler/génération) ne coupe pas le nouveau")
  void restartSameInstanceIgnoresOldGenerationResults() throws Exception {
    ConnectorSocks5ProxyTest.CountingBackend backend = new ConnectorSocks5ProxyTest.CountingBackend(vertx);
    backend.start();
    Socks5MockServer socks = new Socks5MockServer(vertx, backend.port, null, null);
    socks.start();
    RadegastConnector c = new RadegastConnector(vertx, new CacheVerticle(),
        "t-restart", "upstream.invalid", 6000, "prof", 5000,
        new AbstractConnector.NetworkProxyOptions("127.0.0.1", socks.port(), null, null));
    try {
      c.start();
      long deadline = System.currentTimeMillis() + 6_000;
      while (System.currentTimeMillis() < deadline && !c.isConnected()) Thread.sleep(20);
      assertTrue(c.isConnected(), "cycle #1 connecté via proxy");

      c.stop(); // cycle #1 terminé — génération bumpée
      assertFalse(c.isConnected());

      c.start(); // cycle #2 : MÊME instance, NOUVELLE génération
      deadline = System.currentTimeMillis() + 6_000;
      while (System.currentTimeMillis() < deadline && !c.isConnected()) Thread.sleep(20);
      assertTrue(c.isConnected(), "cycle #2 connecté");

      // L'ancien closeHandler (socket du cycle #1, morte) est guardé par génération :
      // il ne peut pas mettre connected=false du cycle #2 ni déclencher de reconnect.
      Thread.sleep(1200);
      assertTrue(c.isConnected(), "cycle #2 reste connecté (pas de coupure fantôme)");
      assertEquals(2, socks.proxyConnections.get(),
          "exactement 2 tentatives TCP (une par cycle), pas de reconnect fantôme");
    } finally {
      c.stop();
      socks.stop();
      backend.stop();
    }
  }

  @Test
  @Timeout(20)
  @DisplayName("sendEcm refusé après stop (message explicite, pas de pending)")
  void sendEcmRefusedAfterStop() throws Exception {
    ConnectorSocks5ProxyTest.CountingBackend backend = new ConnectorSocks5ProxyTest.CountingBackend(vertx);
    backend.start();
    Socks5MockServer socks = new Socks5MockServer(vertx, backend.port, null, null);
    socks.start();
    RadegastConnector c = new RadegastConnector(vertx, new CacheVerticle(),
        "t-ecm-stopped", "upstream.invalid", 6000, "prof", 5000,
        new AbstractConnector.NetworkProxyOptions("127.0.0.1", socks.port(), null, null));
    try {
      c.start();
      long deadline = System.currentTimeMillis() + 6_000;
      while (System.currentTimeMillis() < deadline && !c.isConnected()) Thread.sleep(20);
      assertTrue(c.isConnected());
      c.stop();
      Future<com.bowman.cspng.domain.CwReply> f = c.sendEcm(new com.bowman.cspng.domain.EcmRequest(
          new com.bowman.cspng.domain.EcmKey(0x80, 0x1234, 0x0D00, 0x42, 0),
          new byte[16], "prof", "127.0.0.1", "test", System.currentTimeMillis(), null, 5000));
      assertTrue(f.isComplete(), "sendEcm après stop doit compléter immédiatement");
      assertTrue(f.failed(), "sendEcm après stop doit échouer");
      assertEquals("Connector stopped", f.cause().getMessage(),
          "message explicite 'Connector stopped', pas 'Not connected'");
    } finally {
      c.stop();
      socks.stop();
      backend.stop();
    }
  }
}
