package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests proxy SOCKS5 (Plan P2 §4/§7) — mock RFC 1928/1929 réel :
 * <ul>
 *   <li>Trois protocoles (Newcamd, CCcam, Radegast) connectent VIA le proxy.</li>
 *   <li>ATYP DOMAIN : le proxy reçoit le hostname non résolu — aucune résolution DNS locale
 *       (destination "upstream.invalid" impossible à résoudre).</li>
 *   <li>Auth proxy succès/échec.</li>
 *   <li>Panne proxy : zéro connexion directe (compteur backend == 0).</li>
 *   <li>Reconnexion via proxy après coupure + cleanup stop (reconnect bloqué).</li>
 *   <li>Timeout handshake TCP borné (T=1000ms → échec rapide, pas de 30s).</li>
 * </ul>
 */
@DisplayName("Connecteurs via proxy SOCKS5")
class ConnectorSocks5ProxyTest {

  // Constantes newcamd dupliquées de NewcamdConnectorReplyTest (private là-bas) — mêmes valeurs
  private static final String NC_USER = "testuser";
  private static final String NC_PASS = "testpass";
  private static final byte[] NC_CONFIG_KEY14 = {
      0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
      0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E
  };

  private Vertx vertx;

  @BeforeEach
  void setUp() { vertx = Vertx.vertx(); }

  @AfterEach
  void tearDown() {
    if (vertx != null) vertx.close();
  }

  // -- helpers --

  private AbstractConnector.NetworkProxyOptions proxyOpts(int proxyPort, String user, String pass) {
    // PAS de timeout porté par le proxy (Plan P2) : T = connectTimeoutMs du connecteur.
    return new AbstractConnector.NetworkProxyOptions("127.0.0.1", proxyPort, user, pass);
  }

  /** Backend TCP qui accepte les connexions et compte. */
  static class CountingBackend {
    final NetServer server;
    final AtomicInteger connections = new AtomicInteger();
    int port;
    volatile boolean autoClose = false;

    CountingBackend(Vertx vertx) {
      server = vertx.createNetServer();
      server.connectHandler(sock -> {
        connections.incrementAndGet();
        if (autoClose) sock.close();
      });
    }

    void start() throws Exception {
      CountDownLatch ready = new CountDownLatch(1);
      server.listen(0, "127.0.0.1", ar -> { if (ar.succeeded()) { port = server.actualPort(); ready.countDown(); } });
      assertTrue(ready.await(5, TimeUnit.SECONDS));
    }

    void stop() { server.close(); }
  }

  private static void await(AbstractConnector c, long ms) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    c.loginFuture().onComplete(v -> latch.countDown());
    assertTrue(latch.await(ms, TimeUnit.MILLISECONDS), "loginFuture should complete");
  }

  /** Poll un compteur jusqu'à la valeur attendue (dispatch event-loop asynchrone). */
  private static void awaitCount(java.util.concurrent.atomic.AtomicInteger counter,
                                 int expected, long timeoutMs, String what) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (counter.get() >= expected) return;
      Thread.sleep(10);
    }
    assertEquals(expected, counter.get(), what);
  }

  // ============================================================
  // Radegast — tunnel via proxy + ATYP DOMAIN + zéro DNS locale
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("Radegast via proxy : ATYP DOMAIN, hostname non résolu, tunnel bout en bout")
  void radegastTunnelAtypDomain() throws Exception {
    CountingBackend backend = new CountingBackend(vertx);
    backend.start();
    Socks5MockServer socks = new Socks5MockServer(vertx, backend.port, null, null);
    socks.start();
    try {
      // "upstream.invalid" ne peut PAS être résolu localement : si une résolution DNS
      // locale survenait, la connexion échouerait AVANT d'atteindre le proxy.
      RadegastConnector c = new RadegastConnector(vertx, new CacheVerticle(),
          "t-radegast", "upstream.invalid", 5431, "prof", 5000,
          proxyOpts(socks.port(), null, null));
      c.start();
      await(c, 8000);
      assertEquals(AbstractConnector.LoginState.OK, c.loginFuture().result());
      assertTrue(c.isConnected());
      assertEquals(1, socks.connectRequests.size());
      Socks5MockServer.ConnectRecord rec = socks.connectRequests.get(0);
      assertEquals(Socks5MockServer.ConnectRecord.ATYP_DOMAIN, rec.atyp(),
          "destination doit être envoyée en ATYP DOMAIN (0x03) au proxy");
      assertEquals("upstream.invalid", rec.host());
      assertEquals(5431, rec.port());
      // Le pipe backend est asynchrone (après la réponse SUCCESS au connecteur)
      awaitCount(backend.connections, 1, 5000, "backend doit recevoir exactement 1 connexion (tunnel)");
      awaitCount(socks.backendPipes, 1, 5000, "pipe backend établi");
      c.stop();
    } finally {
      socks.stop();
      backend.stop();
    }
  }

  // ============================================================
  // Newcamd — login complet via proxy (handshake DES + tunnel)
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("Newcamd login via proxy : handshake complet à travers le tunnel SOCKS5")
  void newcamdLoginViaProxy() throws Exception {
    // Mock Newcamd complet du test de reply (même package, static class package-private)
    NewcamdConnectorReplyTest.MockNewcamdServer newcamd = new NewcamdConnectorReplyTest.MockNewcamdServer(vertx, true);
    newcamd.start();
    Socks5MockServer socks = new Socks5MockServer(vertx, newcamd.port(), null, null);
    socks.start();
    try {
      NewcamdConnector c = new NewcamdConnector(vertx, new CacheVerticle(),
          "t-newcamd", "upstream.invalid", newcamd.port(),
          NC_CONFIG_KEY14.clone(),
          NC_USER, NC_PASS, "prof",
          5000, proxyOpts(socks.port(), null, null));
      c.start();
      await(c, 8000);
      assertEquals(AbstractConnector.LoginState.OK, c.loginFuture().result());
      assertTrue(c.isConnected());
      assertEquals(1, socks.connectRequests.size());
      Socks5MockServer.ConnectRecord rec = socks.connectRequests.get(0);
      assertEquals(Socks5MockServer.ConnectRecord.ATYP_DOMAIN, rec.atyp());
      assertEquals("upstream.invalid", rec.host());
      c.stop();
    } finally {
      socks.stop();
      newcamd.stop();
    }
  }

  // ============================================================
  // CCcam — le CONNECT arrive au proxy avec le hostname (chemin proxy prouvé)
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("CCcam via proxy : requête CONNECT reçue au proxy avec ATYP DOMAIN")
  void cccamConnectViaProxy() throws Exception {
    CountingBackend backend = new CountingBackend(vertx);
    backend.start();
    Socks5MockServer socks = new Socks5MockServer(vertx, backend.port, null, null);
    socks.start();
    CcCamConnector c = null;
    try {
      c = new CcCamConnector(vertx, new CacheVerticle(),
          "t-cccam", "upstream.invalid", 12345, "u", "p", "prof",
          "2.3.2", "4000", 0, 10, 5000, proxyOpts(socks.port(), null, null));
      c.start();
      // Le backend n'implémente pas le seed CCcam → le login échouera, mais le
      // CONNECT SOCKS5 doit parvenir au proxy avec le hostname.
      assertTrue(socks.awaitConnects(1, 8000), "proxy doit recevoir le CONNECT");
      Socks5MockServer.ConnectRecord rec = socks.connectRequests.get(0);
      assertEquals(Socks5MockServer.ConnectRecord.ATYP_DOMAIN, rec.atyp());
      assertEquals("upstream.invalid", rec.host());
      assertEquals(12345, rec.port());
      // Aucune connexion directe : le backend n'a été contacté QUE via le pipe du proxy
      assertEquals(1, backend.connections.get(), "backend contacté uniquement via le tunnel proxy");
    } finally {
      if (c != null) c.stop();
      socks.stop();
      backend.stop();
    }
  }

  // ============================================================
  // Probe via proxy
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("ConnectorProbe via proxy : succès avec tunnel SOCKS5")
  void probeViaProxy() throws Exception {
    CountingBackend backend = new CountingBackend(vertx);
    backend.start();
    Socks5MockServer socks = new Socks5MockServer(vertx, backend.port, null, null);
    socks.start();
    try {
      ConnectorProbe.ProbeResult r = ConnectorProbe.probe(vertx, null, "radegast",
          "upstream.invalid", backend.port, null, null, null, 8000,
          proxyOpts(socks.port(), null, null))
          .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
      assertTrue(r.success(), "probe should succeed via proxy, err=" + r.errorMessage());
      assertEquals(1, socks.connectRequests.size());
      assertEquals("upstream.invalid", socks.connectRequests.get(0).host());
    } finally {
      socks.stop();
      backend.stop();
    }
  }

  // ============================================================
  // Auth proxy — succès / échec
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("Auth proxy user/pass : succès avec credentials correctes")
  void proxyAuthSuccess() throws Exception {
    CountingBackend backend = new CountingBackend(vertx);
    backend.start();
    Socks5MockServer socks = new Socks5MockServer(vertx, backend.port, "pxuser", "pxpass");
    socks.start();
    try {
      RadegastConnector c = new RadegastConnector(vertx, new CacheVerticle(),
          "t-auth-ok", "upstream.invalid", 6000, "prof", 5000,
          proxyOpts(socks.port(), "pxuser", "pxpass"));
      c.start();
      await(c, 8000);
      assertEquals(AbstractConnector.LoginState.OK, c.loginFuture().result());
      assertEquals(1, socks.connectRequests.size());
      c.stop();
    } finally {
      socks.stop();
      backend.stop();
    }
  }

  @Test
  @Timeout(15)
  @DisplayName("Auth proxy : échec avec mauvaises credentials → fail, pas de tunnel")
  void proxyAuthFailure() throws Exception {
    CountingBackend backend = new CountingBackend(vertx);
    backend.start();
    Socks5MockServer socks = new Socks5MockServer(vertx, backend.port, "pxuser", "pxpass");
    socks.start();
    RadegastConnector c = null;
    try {
      c = new RadegastConnector(vertx, new CacheVerticle(),
          "t-auth-ko", "upstream.invalid", 6000, "prof", 5000,
          proxyOpts(socks.port(), "pxuser", "WRONG"));
      c.start();
      await(c, 8000);
      // Auth refusée → login FAILED, PAS de CONNECT (l'auth précède le CONNECT)
      assertNotEquals(AbstractConnector.LoginState.OK, c.loginFuture().result());
      assertFalse(c.isConnected());
      assertEquals(0, socks.connectRequests.size(), "no CONNECT must reach proxy on auth failure");
      assertEquals(0, backend.connections.get(), "backend must not be contacted on auth failure");
      assertEquals(0, socks.backendPipes.get());
    } finally {
      if (c != null) c.stop();
      socks.stop();
      backend.stop();
    }
  }

  // ============================================================
  // Panne proxy — JAMAIS de connexion directe
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("Proxy injoignable : échec, zéro connexion directe vers le backend")
  void proxyDownNoDirectConnection() throws Exception {
    CountingBackend backend = new CountingBackend(vertx);
    backend.start();
    // Pas de serveur SOCKS : on réserve un port libre puis on le ferme.
    Socks5MockServer ghost = new Socks5MockServer(vertx, backend.port, null, null);
    ghost.start();
    int deadPort = ghost.port();
    ghost.stop();
    RadegastConnector c = null;
    try {
      // host = 127.0.0.1 (résoluble) : si le connecteur faisait du DIRECT, le backend
      // recevrait une connexion. Fail-closed exige zéro.
      c = new RadegastConnector(vertx, new CacheVerticle(),
          "t-nodirect", "127.0.0.1", backend.port, "prof", 2000,
          proxyOpts(deadPort, null, null));
      c.start();
      await(c, 8000);
      assertNotEquals(AbstractConnector.LoginState.OK, c.loginFuture().result());
      assertFalse(c.isConnected());
      assertEquals(0, backend.connections.get(),
          "FAIL-CLOSED violated: backend was contacted directly while proxy was down");
    } finally {
      if (c != null) c.stop();
      backend.stop();
    }
  }

  // ============================================================
  // Timeout handshake borné (T=1000ms → échec rapide)
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("Proxy blackhole : handshake timeout T=1000ms borné, pas d'attente infinie")
  void handshakeTimeoutBounded() throws Exception {
    CountingBackend backend = new CountingBackend(vertx);
    backend.start();
    Socks5MockServer socks = new Socks5MockServer(vertx, backend.port, null, null);
    socks.blackhole = true; // accepte TCP, ignore le greeting
    socks.start();
    RadegastConnector c = null;
    try {
      long start = System.currentTimeMillis();
      c = new RadegastConnector(vertx, new CacheVerticle(),
          "t-timeout", "upstream.invalid", 6000, "prof", 1000,
          proxyOpts(socks.port(), null, null));
      c.start();
      await(c, 6000);
      long elapsed = System.currentTimeMillis() - start;
      assertNotEquals(AbstractConnector.LoginState.OK, c.loginFuture().result());
      // T=1000ms par phase : le handshake SOCKS5 doit timeout en ~1s, jamais > 5s
      assertTrue(elapsed < 5000, "handshake timeout must be bounded (T=1000ms), took " + elapsed + "ms");
    } finally {
      if (c != null) c.stop();
      socks.stop();
      backend.stop();
    }
  }

  // ============================================================
  // Reconnexion via proxy + cleanup stop (reconnect bloqué)
  // ============================================================

  @Test
  @Timeout(30)
  @DisplayName("Coupure tunnel : reconnexion via proxy (2e CONNECT), stop bloque le reconnect")
  void reconnectViaProxyThenStopBlocksReconnect() throws Exception {
    CountingBackend backend = new CountingBackend(vertx);
    backend.start();
    Socks5MockServer socks = new Socks5MockServer(vertx, backend.port, null, null);
    socks.start();
    RadegastConnector c = null;
    try {
      c = new RadegastConnector(vertx, new CacheVerticle(),
          "t-reconnect", "upstream.invalid", 6500, "prof", 5000,
          proxyOpts(socks.port(), null, null));
      c.start();
      await(c, 8000);
      assertTrue(c.isConnected());
      assertEquals(1, socks.connectRequests.size());

      // Coupure : le backend ferme la connexion → le tunnel se propage → closeHandler
      // → reconnect (RECONNECT_DELAY=5s) → NOUVEAU CONNECT via proxy.
      // On ferme les sockets backend en réinitialisant le serveur backend.
      backend.stop();
      CountingBackend backend2 = new CountingBackend(vertx);
      backend2.start();
      try {
        assertTrue(socks.awaitConnects(2, 15000),
            "reconnect must go through proxy again (2nd CONNECT), got " + socks.connectRequests.size());
        assertEquals(2, socks.connectRequests.size());
        // Les deux CONNECT doivent porter le hostname (ATYP DOMAIN)
        for (Socks5MockServer.ConnectRecord rec : socks.connectRequests) {
          assertEquals(Socks5MockServer.ConnectRecord.ATYP_DOMAIN, rec.atyp());
          assertEquals("upstream.invalid", rec.host());
        }

        // stop() → reconnect bloqué : plus aucun nouveau CONNECT après RECONNECT_DELAY+1s
        c.stop();
        assertFalse(c.isConnected());
        Thread.sleep(RadegastConnector.RECONNECT_DELAY + 1200);
        assertEquals(2, socks.connectRequests.size(),
            "stopped connector must NOT reconnect");
      } finally {
        backend2.stop();
      }
    } finally {
      if (c != null) c.stop();
      socks.stop();
      backend.stop();
    }
  }

  // ============================================================
  // Callbacks obsolètes : stop PENDANT le connect en vol → aucun
  // reconnect ultérieur (l'ancien client ne recrée jamais de NetClient)
  // ============================================================

  @Test
  @Timeout(20)
  @DisplayName("Stop pendant connect en vol : aucun reconnect obsolète après RECONNECT_DELAY")
  void stopDuringInFlightConnectNoStaleReconnect() throws Exception {
    CountingBackend backend = new CountingBackend(vertx);
    backend.start();
    Socks5MockServer socks = new Socks5MockServer(vertx, backend.port, null, null);
    socks.blackhole = true; // accepte TCP, ignore le greeting — connect en vol
    socks.start();
    RadegastConnector c = null;
    try {
      c = new RadegastConnector(vertx, new CacheVerticle(),
          "t-inflight", "upstream.invalid", 6000, "prof", 2000,
          proxyOpts(socks.port(), null, null));
      c.start();
      // Laisser le connect atteindre le proxy (TCP OK, handshake SOCKS en attente)
      awaitCount(socks.proxyConnections, 1, 5000, "première tentative TCP doit atteindre le proxy");
      // STOP pendant le connect en vol — le client fermé ne doit JAMAIS recréer de connexion
      c.stop();
      assertFalse(c.isConnected());
      // Attendre : timeout handshake (T=2000) + RECONNECT_DELAY (5000) + marge
      Thread.sleep(RadegastConnector.RECONNECT_DELAY + 2200);
      assertEquals(1, socks.proxyConnections.get(),
          "AUCUN reconnect obsolète : le proxy ne doit avoir vu qu'UNE tentative TCP après stop "
          + "(got " + socks.proxyConnections.get() + ")");
      assertEquals(0, backend.connections.get(), "backend jamais contacté directement");
    } finally {
      if (c != null) c.stop();
      socks.stop();
      backend.stop();
    }
  }

  // ============================================================
  // Régression : sans proxy, comportement direct inchangé
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("Sans proxy (legacy) : connexion directe inchangée")
  void directLegacyStillWorks() throws Exception {
    CountingBackend backend = new CountingBackend(vertx);
    backend.start();
    try {
      RadegastConnector c = new RadegastConnector(vertx, new CacheVerticle(),
          "t-direct", "127.0.0.1", backend.port, "prof");
      c.start();
      await(c, 8000);
      assertEquals(AbstractConnector.LoginState.OK, c.loginFuture().result());
      awaitCount(backend.connections, 1, 5000, "backend doit recevoir la connexion directe");
      c.stop();
    } finally {
      backend.stop();
    }
  }
}
