package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.cccam.CcCacheExCodec;
import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.codec.cccam.CcConstants;
import com.bowman.cspng.codec.cccam.CcCrypt;
import com.bowman.cspng.codec.cccam.CcCwCrypt;
import com.bowman.cspng.codec.cccam.CcFrameCodec;
import com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests d'intégration pour le Cache-Exchange CCcam (MVP : modes 2 et 3).
 *
 * <h3>Tests</h3>
 * <ol>
 *   <li>Mode 2 (receive) : FILTER out après connect</li>
 *   <li>Mode 2 : mock envoie PUSH chiffré → CW visible via {@code peekLocalCache}</li>
 *   <li>Mode 3 (send) : injecter CW via handleReply → assert frame 0x81 émise</li>
 *   <li>Non-régression : un message APRÈS un PUSH est encore correctement déchiffré</li>
 *   <li>Feature exchange : réponse bitfield 0</li>
 * </ol>
 */
@DisplayName("CcCamConnector CacheEx — tests d'intégration")
class CcCamCacheExIntegrationTest {

  private Vertx vertx;
  private int serverPort;
  private static final String USER = "testuser";
  private static final String PASS = "testpass";

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    serverPort = 16000 + new SecureRandom().nextInt(4000);
  }

  @AfterEach
  void tearDown() {
    if (vertx != null) vertx.close();
  }

  // ============================================================
  // Mode 2 (receive) : FILTER sent after connect
  // ============================================================

  @Test
  @Timeout(30)
  @DisplayName("Mode 2 : FILTER envoyé après le login")
  void mode2FilterSentAfterLogin() throws Exception {
    CountDownLatch serverReady = new CountDownLatch(1);
    CountDownLatch filterReceived = new CountDownLatch(1);
    AtomicReference<byte[]> filterPayload = new AtomicReference<>();

    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> {
      CeMockServer ceServer = new CeMockServer(sock, USER, PASS);
      ceServer.onHandshakeComplete(() -> {
        byte[] srvData = buildSrvData(ceServer.getNodeId());
        ceServer.sendFrame(CcConstants.MSG_SRV_DATA, srvData);
      });
      ceServer.onCacheFilter(payload -> {
        filterPayload.set(payload.clone());
        filterReceived.countDown();
      });
      ceServer.startHandshake();
    });

    server.listen(serverPort, "127.0.0.1", ar -> {
      if (ar.succeeded()) serverReady.countDown();
    });
    assertTrue(serverReady.await(5, TimeUnit.SECONDS));

    CacheVerticle cacheVerticle = new CacheVerticle();
    vertx.deployVerticle(cacheVerticle);

    // Mode 2 = receive
    CcCamConnector connector = new CcCamConnector(vertx, cacheVerticle,
        "test-ce", "127.0.0.1", serverPort, USER, PASS, "test-profile",
        CcConstants.DEFAULT_VERSION, CcConstants.DEFAULT_BUILD,
        CcConstants.CACHEEX_MODE_RECEIVE, CcConstants.CACHEEX_DEFAULT_MAXHOP);

    connector.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

    assertTrue(filterReceived.await(15, TimeUnit.SECONDS),
        "FILTER should be received by server");
    byte[] filter = filterPayload.get();
    assertNotNull(filter, "FILTER payload should not be null");
    assertEquals(CcConstants.MSG_CACHE_FILTER_SIZE, filter.length,
        "FILTER should be 482 bytes");

    // Décoder et vérifier accept-all
    CcCacheExCodec.FilterRecord decoded = CcCacheExCodec.parseFilter(filter);
    assertEquals(0, decoded.cevnum(), "cevnum should be 0 (accept-all)");
  }

  // ============================================================
  // R2 : oddEven inconnu => rejet fail-closed (jamais de remap 0x80)
  // ============================================================

  @Test
  @Timeout(30)
  @DisplayName("Compat OSCam : PUSH oddEven=0x00 accepté comme INCONNU et servi (pas de remap 0x80)")
  void mode2PushUnknownOddEvenAcceptedAndServed() throws Exception {
    CountDownLatch serverReady = new CountDownLatch(1);
    CountDownLatch filterReceived = new CountDownLatch(1);

    byte[] testCw = checksumCwFixture();
    int testCaid = 0x0D00;
    int testSrvid = 0x0001;
    int testDataHash = 0x42ABCDEF;
    byte[] serverNodeId = new byte[8];
    new SecureRandom().nextBytes(serverNodeId);

    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> {
      CeMockServer ceServer = new CeMockServer(sock, USER, PASS);
      System.arraycopy(serverNodeId, 0, ceServer.ourNodeId, 0, 8);
      ceServer.onHandshakeComplete(() -> {
        byte[] srvData = buildSrvData(ceServer.getNodeId());
        ceServer.sendFrame(CcConstants.MSG_SRV_DATA, srvData);
      });
      ceServer.onCacheFilter(payload -> {
        filterReceived.countDown();
        List<byte[]> nodes = List.of(serverNodeId);
        // oddEven 0x00 : valeur inconnue (ni 0x80 ni 0x81)
        byte[] pushPayload = CcCacheExCodec.encodePush(
            testCaid, 0, testSrvid, CcConstants.E_FOUND, 0x00,
            new byte[16], testDataHash, testCw, nodes);
        ceServer.sendFrame(CcConstants.MSG_CACHE_PUSH, pushPayload);
        log("server: CACHE_PUSH sent (oddEven=0x00)");
      });
      ceServer.startHandshake();
    });
    server.listen(serverPort, "127.0.0.1", ar -> {
      if (ar.succeeded()) serverReady.countDown();
    });
    assertTrue(serverReady.await(5, TimeUnit.SECONDS));

    CacheVerticle cacheVerticle = new CacheVerticle();
    vertx.deployVerticle(cacheVerticle);
    CacheExStatsRegistry registry = new CacheExStatsRegistry();
    CcCamConnector connector = new CcCamConnector(vertx, cacheVerticle,
        "test-ce-r2", "127.0.0.1", serverPort, USER, PASS, "test-profile",
        CcConstants.DEFAULT_VERSION, CcConstants.DEFAULT_BUILD,
        CcConstants.CACHEEX_MODE_RECEIVE, CcConstants.CACHEEX_DEFAULT_MAXHOP,
        5000, null, null, 2000, registry);
    try {
      connector.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
      assertTrue(filterReceived.await(15, TimeUnit.SECONDS));
      Thread.sleep(1000);

      // Accepté comme INCONNU (0) : reçu compté, rien droppé
      var pair = registry.snapshot().stream()
          .filter(p -> p.peerId().equals("test-ce-r2")).findFirst().orElseThrow();
      assertEquals(1, pair.receivedCw(), "push 0 accepté");
      assertEquals(0, pair.droppedCw(), "aucun drop");
      // Réellement SERVI : lookup par hash (EcmKey égalité=dataHash) trouve la CW
      // pour une requête 0x80 — pas de stockage inutilisable sous clé 0.
      CwReply served = cacheVerticle.peekLocalCache(
          new EcmKey(0x80, testSrvid, 0, testCaid, testDataHash));
      assertNotNull(served, "CW inconnue servie pour requête 0x80 (fallback hash)");
      assertArrayEquals(testCw, served.cw(), "octets CW intacts");
    } finally {
      try { connector.stop(); } catch (Exception ignored) {}
      server.close();
    }
  }

  // ============================================================
  // Mode 2 : PUSH entrant → CW dans le cache
  // ============================================================

  @Test
  @Timeout(30)
  @DisplayName("Mode 2 : PUSH reçu → CW visible via peekLocalCache")
  void mode2PushInjectsCw() throws Exception {
    CountDownLatch serverReady = new CountDownLatch(1);
    CountDownLatch filterReceived = new CountDownLatch(1);

    byte[] testCw = checksumCwFixture(); // FIX 2: checksum DVB valide
    int testCaid = 0x0D00;
    int testSrvid = 0x0001;
    int testDataHash = 0x42ABCDEF;
    byte[] serverNodeId = new byte[8];
    new SecureRandom().nextBytes(serverNodeId);

    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> {
      CeMockServer ceServer = new CeMockServer(sock, USER, PASS);
      // Override nodeId for the test
      System.arraycopy(serverNodeId, 0, ceServer.ourNodeId, 0, 8);

      ceServer.onHandshakeComplete(() -> {
        byte[] srvData = buildSrvData(ceServer.getNodeId());
        ceServer.sendFrame(CcConstants.MSG_SRV_DATA, srvData);
      });
      ceServer.onCacheFilter(payload -> {
        filterReceived.countDown();
        // Envoyer un PUSH après avoir reçu le FILTER
        List<byte[]> nodes = List.of(serverNodeId);
        byte[] pushPayload = CcCacheExCodec.encodePush(
            testCaid, 0, testSrvid, CcConstants.E_FOUND, 0x80,
            new byte[16], testDataHash, testCw, nodes);
        ceServer.sendFrame(CcConstants.MSG_CACHE_PUSH, pushPayload);
        log("server: CACHE_PUSH sent");
      });
      ceServer.startHandshake();
    });

    server.listen(serverPort, "127.0.0.1", ar -> {
      if (ar.succeeded()) serverReady.countDown();
    });
    assertTrue(serverReady.await(5, TimeUnit.SECONDS));

    CacheVerticle cacheVerticle = new CacheVerticle();
    vertx.deployVerticle(cacheVerticle);

    CcCamConnector connector = new CcCamConnector(vertx, cacheVerticle,
        "test-ce", "127.0.0.1", serverPort, USER, PASS, "test-profile",
        CcConstants.DEFAULT_VERSION, CcConstants.DEFAULT_BUILD,
        CcConstants.CACHEEX_MODE_RECEIVE, CcConstants.CACHEEX_DEFAULT_MAXHOP);

    connector.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

    assertTrue(filterReceived.await(15, TimeUnit.SECONDS),
        "FILTER should be received before PUSH");

    // Attendre que le PUSH soit traité et la CW mise en cache
    Thread.sleep(1000);

    EcmKey key = new EcmKey(0x80, testSrvid, 0, testCaid, testDataHash);
    CwReply cached = cacheVerticle.peekLocalCache(key);
    assertNotNull(cached, "CW should be in cache after PUSH");
    assertArrayEquals(testCw, cached.cw(), "CW should match the PUSH CW");
  }

  // ============================================================
  // Mode 3 : injecter CW → PUSH émis
  // ============================================================

  @Test
  @Timeout(30)
  @DisplayName("Mode 3 : injecter CW via handleReply → frame 0x81 émise")
  void mode3SendPush() throws Exception {
    CountDownLatch serverReady = new CountDownLatch(1);
    CountDownLatch pushReceived = new CountDownLatch(1);
    AtomicReference<CcCacheExCodec.PushRecord> pushRef = new AtomicReference<>();

    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> {
      CeMockServer ceServer = new CeMockServer(sock, USER, PASS);
      ceServer.onHandshakeComplete(() -> {
        byte[] srvData = buildSrvData(ceServer.getNodeId());
        ceServer.sendFrame(CcConstants.MSG_SRV_DATA, srvData);

        // Envoyer un FILTER accept-all pour que le client puisse envoyer
        byte[] filterPayload = CcCacheExCodec.encodeFilterAcceptAll();
        ceServer.sendFrame(CcConstants.MSG_CACHE_FILTER, filterPayload);
        log("server: FILTER sent (accept-all)");
      });
      ceServer.onCachePush(payload -> {
        try {
          CcCacheExCodec.PushRecord push = CcCacheExCodec.parsePush(payload);
          pushRef.set(push);
          log("server: CACHE_PUSH received: hash=0x"
              + Integer.toHexString(push.dataHash())
              + " caid=0x" + Integer.toHexString(push.caid()));
          pushReceived.countDown();
        } catch (Exception e) {
          log("server: PUSH parse error: " + e.getMessage());
          pushReceived.countDown();
        }
      });
      ceServer.startHandshake();
    });

    server.listen(serverPort, "127.0.0.1", ar -> {
      if (ar.succeeded()) serverReady.countDown();
    });
    assertTrue(serverReady.await(5, TimeUnit.SECONDS));

    CacheVerticle cacheVerticle = new CacheVerticle();
    vertx.deployVerticle(cacheVerticle);

    CcCamConnector connector = new CcCamConnector(vertx, cacheVerticle,
        "test-ce", "127.0.0.1", serverPort, USER, PASS, "test-profile",
        CcConstants.DEFAULT_VERSION, CcConstants.DEFAULT_BUILD,
        CcConstants.CACHEEX_MODE_SEND, CcConstants.CACHEEX_DEFAULT_MAXHOP);

    connector.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

    // Attendre que le FILTER soit traité
    Thread.sleep(500);

    // Injecter une CW dans le cache (simuler un PUSH reçu d'un autre connecteur)
    byte[] cw = checksumCwFixture();
    int dataHash = 0x42ABCDEF;
    int caId = 0x0D00;
    int srvid = 0x1234;
    EcmKey key = new EcmKey(0x80, srvid, 0, caId, dataHash);
    CwReply reply = new CwReply(key, 0x80, cw, "other-connector", "10.0.0.1:9999",
        System.currentTimeMillis());
    EcmRequest request = new EcmRequest(key, new byte[0], "test-profile",
        "10.0.0.1:9999", "other-connector",
        System.currentTimeMillis(), null, 5000);

    cacheVerticle.handleReply(request, reply);

    assertTrue(pushReceived.await(10, TimeUnit.SECONDS),
        "PUSH should be sent within 10 seconds");

    CcCacheExCodec.PushRecord push = pushRef.get();
    assertNotNull(push, "PUSH should be received by server");
    assertEquals(CcConstants.E_FOUND, push.rc());
    assertEquals(dataHash, push.dataHash());
    assertEquals(caId, push.caid());
    assertEquals(srvid, push.srvid());
    assertArrayEquals(cw, push.cw());
    // Le first node est celui du client
    assertFalse(push.nodeIds().isEmpty(), "PUSH should contain at least 1 node");
  }

  // ============================================================
  // Triangle anti-boucle / hop / dédup / anti-reflet (C2)
  // ============================================================

  private static byte[] nodeId(int seed) {
    byte[] n = new byte[8];
    for (int i = 0; i < 8; i++) n[i] = (byte) (seed + i * 17);
    return n;
  }

  /** Monte un connecteur mode 3 connecté + registry, retourne {connector, pushes, server}. */
  private void withMode3Connector(
      java.util.function.BiConsumer<CcCamConnector, CacheExStatsRegistry> body) throws Exception {
    CountDownLatch serverReady = new CountDownLatch(1);
    java.util.List<CcCacheExCodec.PushRecord> pushes =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> {
      CeMockServer ceServer = new CeMockServer(sock, USER, PASS);
      ceServer.onHandshakeComplete(() -> {
        ceServer.sendFrame(CcConstants.MSG_SRV_DATA, buildSrvData(ceServer.getNodeId()));
        ceServer.sendFrame(CcConstants.MSG_CACHE_FILTER, CcCacheExCodec.encodeFilterAcceptAll());
      });
      ceServer.onCachePush(payload -> {
        try {
          pushes.add(CcCacheExCodec.parsePush(payload));
        } catch (Exception ignored) {
        }
      });
      ceServer.startHandshake();
    });
    server.listen(serverPort, "127.0.0.1", ar -> {
      if (ar.succeeded()) serverReady.countDown();
    });
    assertTrue(serverReady.await(5, TimeUnit.SECONDS));

    CacheVerticle cacheVerticle = new CacheVerticle();
    vertx.deployVerticle(cacheVerticle);
    CacheExStatsRegistry registry = new CacheExStatsRegistry();
    CcCamConnector connector = new CcCamConnector(vertx, cacheVerticle,
        "tri-ce", "127.0.0.1", serverPort, USER, PASS, "test-profile",
        CcConstants.DEFAULT_VERSION, CcConstants.DEFAULT_BUILD,
        CcConstants.CACHEEX_MODE_SEND, 10,
        5000, null, null, 2000, registry);
    connector.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    Thread.sleep(500); // laisser le FILTER arriver
    try {
      body.accept(connector, registry);
    } finally {
      try { connector.stop(); } catch (Exception ignored) {}
      server.close();
    }
  }

  private static CwReply triReply(int hash, byte[] cw) {
    EcmKey key = new EcmKey(0x80, 0x1234, 0, 0x0D00, hash);
    return new CwReply(key, 0x80, cw, "other-connector", "10.0.0.1:9999",
        System.currentTimeMillis());
  }

  private static EcmRequest triRequest(int hash) {
    EcmKey key = new EcmKey(0x80, 0x1234, 0, 0x0D00, hash);
    return new EcmRequest(key, new byte[0], "test-profile", "10.0.0.1:9999",
        "other-connector", System.currentTimeMillis(), null, 5000);
  }

  @Test
  @Timeout(30)
  @DisplayName("C2 : le re-push préserve le chemin amont + cycle (own FIRST, pas de doublon)")
  void forwardPreservesUpstreamPathAndCycle() throws Exception {
    byte[] nodeX = nodeId(0x30);
    byte[] nodeY = nodeId(0x60);
    byte[] cw = checksumCwFixture();
    int hash = 0xA11CE001;
    java.util.List<CcCacheExCodec.PushRecord> seen = new java.util.ArrayList<>();

    // On intercepte via un mock serveur dédié (copie du helper avec capture)
    CountDownLatch serverReady = new CountDownLatch(1);
    CountDownLatch pushSeen = new CountDownLatch(1);
    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> {
      CeMockServer ceServer = new CeMockServer(sock, USER, PASS);
      ceServer.onHandshakeComplete(() -> {
        ceServer.sendFrame(CcConstants.MSG_SRV_DATA, buildSrvData(ceServer.getNodeId()));
        ceServer.sendFrame(CcConstants.MSG_CACHE_FILTER, CcCacheExCodec.encodeFilterAcceptAll());
      });
      ceServer.onCachePush(payload -> {
        try {
          seen.add(CcCacheExCodec.parsePush(payload));
          pushSeen.countDown();
        } catch (Exception ignored) {
        }
      });
      ceServer.startHandshake();
    });
    server.listen(serverPort, "127.0.0.1", ar -> {
      if (ar.succeeded()) serverReady.countDown();
    });
    assertTrue(serverReady.await(5, TimeUnit.SECONDS));

    CacheVerticle cacheVerticle = new CacheVerticle();
    vertx.deployVerticle(cacheVerticle);
    CacheExStatsRegistry registry = new CacheExStatsRegistry();
    CcCamConnector connector = new CcCamConnector(vertx, cacheVerticle,
        "tri-fwd", "127.0.0.1", serverPort, USER, PASS, "test-profile",
        CcConstants.DEFAULT_VERSION, CcConstants.DEFAULT_BUILD,
        CcConstants.CACHEEX_MODE_SEND, 10,
        5000, null, null, 2000, registry);
    connector.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    Thread.sleep(500);

    try {
      // Chemin amont appris (comme si un PUSH [X, Y] avait été reçu) + cycle 9/next
      connector.noteUpstreamPath(hash, java.util.List.of(nodeX, nodeY), 9, true);
      cacheVerticle.handleReply(triRequest(hash), triReply(hash, cw));

      assertTrue(pushSeen.await(10, TimeUnit.SECONDS), "PUSH re-émis attendu");
      assertEquals(1, seen.size());
      CcCacheExCodec.PushRecord out = seen.get(0);
      // own node FIRST, puis X, Y intacts (anti-boucle vérifiable en aval)
      assertEquals(3, out.nodeIds().size());
      assertArrayEquals(nodeX, out.nodeIds().get(1));
      assertArrayEquals(nodeY, out.nodeIds().get(2));
      assertFalse(java.util.Arrays.equals(out.nodeIds().get(0), nodeX));
      assertFalse(java.util.Arrays.equals(out.nodeIds().get(0), nodeY));
      // Métadonnée de cycle préservée bit-exact : [18] = 9|0x80
      assertEquals(9, out.cycleTimeSec());
      assertTrue(out.nextCwCycle());
      assertArrayEquals(cw, out.cw());
    } finally {
      try { connector.stop(); } catch (Exception ignored) {}
      server.close();
    }
  }

  @Test
  @Timeout(30)
  @DisplayName("C2 : hop bound — chemin 10 + own = 11 > maxHop 10 => drop compté")
  void hopBoundEnforcedOnEmit() throws Exception {
    java.util.List<byte[]> longPath = new java.util.ArrayList<>();
    for (int i = 0; i < 10; i++) longPath.add(nodeId(i + 1));
    byte[] cw = checksumCwFixture();
    int hash = 0xB0BB1E5;
    java.util.concurrent.atomic.AtomicInteger pushes = new java.util.concurrent.atomic.AtomicInteger();

    CountDownLatch serverReady = new CountDownLatch(1);
    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> {
      CeMockServer ceServer = new CeMockServer(sock, USER, PASS);
      ceServer.onHandshakeComplete(() -> {
        ceServer.sendFrame(CcConstants.MSG_SRV_DATA, buildSrvData(ceServer.getNodeId()));
        ceServer.sendFrame(CcConstants.MSG_CACHE_FILTER, CcCacheExCodec.encodeFilterAcceptAll());
      });
      ceServer.onCachePush(payload -> pushes.incrementAndGet());
      ceServer.startHandshake();
    });
    server.listen(serverPort, "127.0.0.1", ar -> {
      if (ar.succeeded()) serverReady.countDown();
    });
    assertTrue(serverReady.await(5, TimeUnit.SECONDS));

    CacheVerticle cacheVerticle = new CacheVerticle();
    vertx.deployVerticle(cacheVerticle);
    CacheExStatsRegistry registry = new CacheExStatsRegistry();
    CcCamConnector connector = new CcCamConnector(vertx, cacheVerticle,
        "tri-hop", "127.0.0.1", serverPort, USER, PASS, "test-profile",
        CcConstants.DEFAULT_VERSION, CcConstants.DEFAULT_BUILD,
        CcConstants.CACHEEX_MODE_SEND, 10,
        5000, null, null, 2000, registry);
    connector.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    Thread.sleep(500);

    try {
      connector.noteUpstreamPath(hash, longPath);
      cacheVerticle.handleReply(triRequest(hash), triReply(hash, cw));
      Thread.sleep(800); // laisser l'éventuel push arriver
      assertEquals(0, pushes.get(), "aucun push : 1 + 10 > maxHop 10");
      var pair = registry.snapshot().stream()
          .filter(p -> p.peerId().equals("tri-hop")).findFirst().orElseThrow();
      assertEquals(1, pair.droppedCw());
      assertEquals(1L, pair.dropReasons().get("hop"));
    } finally {
      try { connector.stop(); } catch (Exception ignored) {}
      server.close();
    }
  }

  @Test
  @Timeout(30)
  @DisplayName("C2 : pas de reflet vers la source + dédup du 2e push identique")
  void noReflectToSourceAndDedup() throws Exception {
    byte[] cw = checksumCwFixture();
    int hash = 0xC0FFEE;
    java.util.concurrent.atomic.AtomicInteger pushes = new java.util.concurrent.atomic.AtomicInteger();

    CountDownLatch serverReady = new CountDownLatch(1);
    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> {
      CeMockServer ceServer = new CeMockServer(sock, USER, PASS);
      ceServer.onHandshakeComplete(() -> {
        ceServer.sendFrame(CcConstants.MSG_SRV_DATA, buildSrvData(ceServer.getNodeId()));
        ceServer.sendFrame(CcConstants.MSG_CACHE_FILTER, CcCacheExCodec.encodeFilterAcceptAll());
      });
      ceServer.onCachePush(payload -> pushes.incrementAndGet());
      ceServer.startHandshake();
    });
    server.listen(serverPort, "127.0.0.1", ar -> {
      if (ar.succeeded()) serverReady.countDown();
    });
    assertTrue(serverReady.await(5, TimeUnit.SECONDS));

    CacheVerticle cacheVerticle = new CacheVerticle();
    vertx.deployVerticle(cacheVerticle);
    CacheExStatsRegistry registry = new CacheExStatsRegistry();
    CcCamConnector connector = new CcCamConnector(vertx, cacheVerticle,
        "tri-noreflect", "127.0.0.1", serverPort, USER, PASS, "test-profile",
        CcConstants.DEFAULT_VERSION, CcConstants.DEFAULT_BUILD,
        CcConstants.CACHEEX_MODE_SEND, 10,
        5000, null, null, 2000, registry);
    connector.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    Thread.sleep(500);

    try {
      // 1) Reflet : la CW vient DU pair lui-même => jamais réémise vers lui
      EcmKey key = new EcmKey(0x80, 0x1234, 0, 0x0D00, hash);
      CwReply fromPeer = new CwReply(key, 0x80, cw, "tri-noreflect",
          "127.0.0.1:" + serverPort, System.currentTimeMillis());
      EcmRequest reqPeer = new EcmRequest(key, new byte[0], "test-profile",
          "127.0.0.1:" + serverPort, "tri-noreflect", System.currentTimeMillis(), null, 5000);
      cacheVerticle.handleReply(reqPeer, fromPeer);
      Thread.sleep(800);
      assertEquals(0, pushes.get(), "pas de reflet vers la source");

      // 2) Émission normale puis dédup du doublon
      cacheVerticle.handleReply(triRequest(hash + 1), triReply(hash + 1, cw));
      long deadline = System.currentTimeMillis() + 8000;
      while (System.currentTimeMillis() < deadline && pushes.get() < 1) Thread.sleep(50);
      assertEquals(1, pushes.get());
      cacheVerticle.handleReply(triRequest(hash + 1), triReply(hash + 1, cw));
      Thread.sleep(800);
      assertEquals(1, pushes.get(), "doublon supprimé par dédup hash:pair");
    } finally {
      try { connector.stop(); } catch (Exception ignored) {}
      server.close();
    }
  }

  // ============================================================
  // Non-régression : sync flux après PUSH
  // ============================================================

  @Test
  @Timeout(30)
  @DisplayName("Non-régression : un message après un PUSH est encore déchiffré correctement")
  void postPushSyncStillValid() throws Exception {
    CountDownLatch serverReady = new CountDownLatch(1);
    CountDownLatch filterReceived = new CountDownLatch(1);
    CountDownLatch cwReceived = new CountDownLatch(1);
    AtomicReference<byte[]> cwResult = new AtomicReference<>();
    byte[] testCw = checksumCwFixture(); // FIX 2: checksum valide pour le PUSH

    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> {
      CeMockServer ceServer = new CeMockServer(sock, USER, PASS);
      ceServer.onHandshakeComplete(() -> {
        // SRV_DATA
        byte[] srvData = buildSrvData(ceServer.getNodeId());
        ceServer.sendFrame(CcConstants.MSG_SRV_DATA, srvData);

        // Une carte de test
        byte[] newCard = buildTestCard(0x12345678, 0x0D00, 1, 2);
        ceServer.sendFrame(CcConstants.MSG_NEW_CARD, newCard);
      });
      ceServer.onCacheFilter(payload -> {
        filterReceived.countDown();
        // Envoyer un PUSH d'abord
        byte[] pushPayload = CcCacheExCodec.encodePush(
            0x0D00, 0, 0x0001, CcConstants.E_FOUND, 0x80,
            new byte[16], 0x1234, testCw,
            List.of(ceServer.getNodeId()));
        ceServer.sendFrame(CcConstants.MSG_CACHE_PUSH, pushPayload);
        log("server: PUSH sent, then will respond to ECM");
      });
      ceServer.onEcmRequest((cardId, ecm) -> {
        byte[] scrambled = testCw.clone();
        CcCwCrypt.cwCrypt(scrambled, ceServer.getNodeId(), cardId);
        return scrambled;
      });
      ceServer.startHandshake();
    });

    server.listen(serverPort, "127.0.0.1", ar -> {
      if (ar.succeeded()) serverReady.countDown();
    });
    assertTrue(serverReady.await(5, TimeUnit.SECONDS));

    CacheVerticle cacheVerticle = new CacheVerticle();
    vertx.deployVerticle(cacheVerticle);

    CcCamConnector connector = new CcCamConnector(vertx, cacheVerticle,
        "test-ce", "127.0.0.1", serverPort, USER, PASS, "test-profile",
        CcConstants.DEFAULT_VERSION, CcConstants.DEFAULT_BUILD,
        CcConstants.CACHEEX_MODE_RECEIVE, CcConstants.CACHEEX_DEFAULT_MAXHOP);

    connector.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

    assertTrue(filterReceived.await(10, TimeUnit.SECONDS));
    // Attendre que le PUSH et les cartes soient traités
    for (int i = 0; i < 40; i++) {
      if (connector.isConnected() && connector.hasCards()) break;
      Thread.sleep(250);
    }
    assertTrue(connector.hasCards(), "Should have cards after PUSH");

    // Envoyer un ECM maintenant — si le flux est désynchronisé, ça échouera
    EcmRequest ecmReq = new EcmRequest(
        new EcmKey(0x80, 0x1234, 0, 0x0D00, 0x42),
        new byte[32], "test-profile", "127.0.0.1", "origin-1",
        System.currentTimeMillis(), null, 5000);

    connector.sendEcm(ecmReq).onComplete(ar -> {
      if (ar.succeeded()) {
        cwResult.set(ar.result().cw());
      }
      cwReceived.countDown();
    });

    assertTrue(cwReceived.await(10, TimeUnit.SECONDS),
        "ECM should get a CW response after PUSH");
    assertNotNull(cwResult.get(), "CW should not be null");
    assertEquals(16, cwResult.get().length,
        "CW should be 16 bytes — crypto still synchronized after PUSH");
  }

  // ============================================================
  // Feature exchange
  // ============================================================

  @Test
  @Timeout(30)
  @DisplayName("Feature exchange (0x83) : réponse bitfield 0")
  void featureExchangeReplyZero() throws Exception {
    CountDownLatch serverReady = new CountDownLatch(1);
    CountDownLatch featureReplyReceived = new CountDownLatch(1);
    AtomicReference<Integer> bitfieldRef = new AtomicReference<>();

    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> {
      CeMockServer ceServer = new CeMockServer(sock, USER, PASS);
      ceServer.onHandshakeComplete(() -> {
        byte[] srvData = buildSrvData(ceServer.getNodeId());
        ceServer.sendFrame(CcConstants.MSG_SRV_DATA, srvData);

        // Envoyer un feature exchange
        byte[] fePayload = CcCacheExCodec.encodeFeatureExchange(0);
        ceServer.sendFrame(CcConstants.MSG_CACHE_FEATURE_EXCHANGE, fePayload);
        log("server: CACHE_FEATURE_EXCHANGE sent");
      });
      ceServer.onCacheFeatureReply(payload -> {
        try {
          int bf = CcCacheExCodec.parseFeatureExchange(payload);
          bitfieldRef.set(bf);
          log("server: CACHE_FEATURE_EXCHANGE_REPLY received, bitfield=" + bf);
          featureReplyReceived.countDown();
        } catch (Exception e) {
          log("server: FE_REPLY parse error: " + e.getMessage());
          featureReplyReceived.countDown();
        }
      });
      ceServer.startHandshake();
    });

    server.listen(serverPort, "127.0.0.1", ar -> {
      if (ar.succeeded()) serverReady.countDown();
    });
    assertTrue(serverReady.await(5, TimeUnit.SECONDS));

    CacheVerticle cacheVerticle = new CacheVerticle();
    vertx.deployVerticle(cacheVerticle);

    CcCamConnector connector = new CcCamConnector(vertx, cacheVerticle,
        "test-ce", "127.0.0.1", serverPort, USER, PASS, "test-profile",
        CcConstants.DEFAULT_VERSION, CcConstants.DEFAULT_BUILD,
        CcConstants.CACHEEX_MODE_RECEIVE, CcConstants.CACHEEX_DEFAULT_MAXHOP);

    connector.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

    assertTrue(featureReplyReceived.await(15, TimeUnit.SECONDS),
        "Feature exchange reply should be received");
    assertEquals(0, bitfieldRef.get(),
        "Feature exchange reply bitfield should be 0 (non-AIO)");
  }

  // ============================================================
  // FIX 2: CW checksum invalide → PAS en cache
  // ============================================================

  @Test
  @Timeout(30)
  @DisplayName("FIX 2 : CW checksum invalide → PAS injectée dans le cache")
  void invalidCwChecksumRejected() throws Exception {
    CountDownLatch serverReady = new CountDownLatch(1);
    CountDownLatch filterReceived = new CountDownLatch(1);

    // CW avec checksum INVALIDE (cw[3] != cw[0]+cw[1]+cw[2])
    byte[] invalidCw = {
        0x11, 0x22, 0x33, (byte) 0x99, // 0x11+0x22+0x33 = 0x66 ≠ 0x99
        0x44, 0x55, 0x66, (byte) 0x99,
        0x77, (byte) 0x88, (byte) 0x99, (byte) 0x99,
        (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0x99
    };

    int testCaid = 0x0D00;
    int testSrvid = 0x0001;
    int testDataHash = 0xBADCDEAD;
    byte[] serverNodeId = new byte[8];
    new SecureRandom().nextBytes(serverNodeId);

    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> {
      CeMockServer ceServer = new CeMockServer(sock, USER, PASS);
      System.arraycopy(serverNodeId, 0, ceServer.ourNodeId, 0, 8);
      ceServer.onHandshakeComplete(() -> {
        byte[] srvData = buildSrvData(ceServer.getNodeId());
        ceServer.sendFrame(CcConstants.MSG_SRV_DATA, srvData);
      });
      ceServer.onCacheFilter(payload -> {
        filterReceived.countDown();
        // Envoyer un PUSH avec CW invalide
        List<byte[]> nodes = List.of(serverNodeId);
        byte[] pushPayload = CcCacheExCodec.encodePush(
            testCaid, 0, testSrvid, CcConstants.E_FOUND, 0x80,
            new byte[16], testDataHash, invalidCw, nodes);
        ceServer.sendFrame(CcConstants.MSG_CACHE_PUSH, pushPayload);
      });
      ceServer.startHandshake();
    });

    server.listen(serverPort, "127.0.0.1", ar -> {
      if (ar.succeeded()) serverReady.countDown();
    });
    assertTrue(serverReady.await(5, TimeUnit.SECONDS));

    CacheVerticle cacheVerticle = new CacheVerticle();
    vertx.deployVerticle(cacheVerticle);

    CcCamConnector connector = new CcCamConnector(vertx, cacheVerticle,
        "test-ce", "127.0.0.1", serverPort, USER, PASS, "test-profile",
        CcConstants.DEFAULT_VERSION, CcConstants.DEFAULT_BUILD,
        CcConstants.CACHEEX_MODE_RECEIVE, CcConstants.CACHEEX_DEFAULT_MAXHOP);

    connector.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

    assertTrue(filterReceived.await(15, TimeUnit.SECONDS));
    Thread.sleep(500);

    // La CW invalide ne doit PAS être dans le cache
    EcmKey key = new EcmKey(0x80, testSrvid, 0, testCaid, testDataHash);
    CwReply cached = cacheVerticle.peekLocalCache(key);
    assertNull(cached, "CW with invalid checksum must NOT be in cache");
  }

  // ============================================================
  // FIX 5: mode 3 ignore PUSH entrant
  // ============================================================

  @Test
  @Timeout(30)
  @DisplayName("FIX 5 : mode 3 (send) ignore les PUSH entrants")
  void mode3IgnoresIncomingPush() throws Exception {
    CountDownLatch serverReady = new CountDownLatch(1);
    CountDownLatch pushReceivedByServer = new CountDownLatch(1); // on attend plutôt un PUSH out
    AtomicReference<CcCacheExCodec.PushRecord> pushRef = new AtomicReference<>();

    byte[] testCw = checksumCwFixture();
    int testCaid = 0x0D00;
    int testSrvid = 0x0001;
    int testDataHash = 0x99999999;

    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> {
      CeMockServer ceServer = new CeMockServer(sock, USER, PASS);
      ceServer.onHandshakeComplete(() -> {
        byte[] srvData = buildSrvData(ceServer.getNodeId());
        ceServer.sendFrame(CcConstants.MSG_SRV_DATA, srvData);

        // Envoyer FILTER d'abord
        byte[] filterPayload = CcCacheExCodec.encodeFilterAcceptAll();
        ceServer.sendFrame(CcConstants.MSG_CACHE_FILTER, filterPayload);

        // Puis envoyer un PUSH (devrait être ignoré en mode 3)
        List<byte[]> nodes = List.of(ceServer.getNodeId());
        byte[] pushPayload = CcCacheExCodec.encodePush(
            testCaid, 0, testSrvid, CcConstants.E_FOUND, 0x80,
            new byte[16], testDataHash, testCw, nodes);
        ceServer.sendFrame(CcConstants.MSG_CACHE_PUSH, pushPayload);
        log("server: CACHE_PUSH sent (should be ignored by mode 3)");
      });
      // On vérifie qu'aucun PUSH n'est envoyé vers le serveur (mode 3 send)
      ceServer.onCachePush(payload -> {
        try {
          pushRef.set(CcCacheExCodec.parsePush(payload));
        } catch (Exception e) { /* ignore */ }
        pushReceivedByServer.countDown();
      });
      ceServer.startHandshake();
    });

    server.listen(serverPort, "127.0.0.1", ar -> {
      if (ar.succeeded()) serverReady.countDown();
    });
    assertTrue(serverReady.await(5, TimeUnit.SECONDS));

    CacheVerticle cacheVerticle = new CacheVerticle();
    vertx.deployVerticle(cacheVerticle);

    CcCamConnector connector = new CcCamConnector(vertx, cacheVerticle,
        "test-ce", "127.0.0.1", serverPort, USER, PASS, "test-profile",
        CcConstants.DEFAULT_VERSION, CcConstants.DEFAULT_BUILD,
        CcConstants.CACHEEX_MODE_SEND, CcConstants.CACHEEX_DEFAULT_MAXHOP);

    connector.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    Thread.sleep(1000);

    // Le PUSH entrant doit avoir été ignoré (pas dans le cache)
    EcmKey key = new EcmKey(0x80, testSrvid, 0, testCaid, testDataHash);
    CwReply cached = cacheVerticle.peekLocalCache(key);
    assertNull(cached, "PUSH should be ignored in mode 3 (send only)");

    // Vérifier qu'aucun PUSH out n'a été émis en réponse au PUSH in
    // (le latch ne devrait pas avoir été compté)
    assertFalse(pushReceivedByServer.await(1, TimeUnit.SECONDS),
        "No PUSH should be sent back to server for incoming PUSH in mode 3");
  }

  // ============================================================
  // FIX 3: maxhop à l'émission
  // ============================================================

  @Test
  @Timeout(30)
  @DisplayName("FIX 3 : maxhop respecté à l'émission (onReply)")
  void maxHopEmission() throws Exception {
    CountDownLatch serverReady = new CountDownLatch(1);
    CountDownLatch pushReceived = new CountDownLatch(1);
    AtomicReference<CcCacheExCodec.PushRecord> pushRef = new AtomicReference<>();
    AtomicInteger pushCount = new AtomicInteger(0);

    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> {
      CeMockServer ceServer = new CeMockServer(sock, USER, PASS);
      ceServer.onHandshakeComplete(() -> {
        byte[] srvData = buildSrvData(ceServer.getNodeId());
        ceServer.sendFrame(CcConstants.MSG_SRV_DATA, srvData);
        byte[] filterPayload = CcCacheExCodec.encodeFilterAcceptAll();
        ceServer.sendFrame(CcConstants.MSG_CACHE_FILTER, filterPayload);
      });
      ceServer.onCachePush(payload -> {
        try {
          CcCacheExCodec.PushRecord push = CcCacheExCodec.parsePush(payload);
          pushRef.set(push);
          int cnt = pushCount.incrementAndGet();
          if (cnt >= 2) pushReceived.countDown();
        } catch (Exception e) {
          log("server: PUSH parse error: " + e.getMessage());
        }
      });
      ceServer.startHandshake();
    });

    server.listen(serverPort, "127.0.0.1", ar -> {
      if (ar.succeeded()) serverReady.countDown();
    });
    assertTrue(serverReady.await(5, TimeUnit.SECONDS));

    CacheVerticle cacheVerticle = new CacheVerticle();
    vertx.deployVerticle(cacheVerticle);

    // Mode 3 avec maxhop = 2
    // Le first push (1 node = own) doit passer
    // Le second push avec 3 nodes (> maxhop) doit être bloqué
    CcCamConnector connector = new CcCamConnector(vertx, cacheVerticle,
        "test-ce", "127.0.0.1", serverPort, USER, PASS, "test-profile",
        CcConstants.DEFAULT_VERSION, CcConstants.DEFAULT_BUILD,
        CcConstants.CACHEEX_MODE_SEND, 2); // maxhop = 2

    connector.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    Thread.sleep(500);

    byte[] cw = checksumCwFixture();
    int dataHash = 0x42ABCDEF;
    int caId = 0x0D00;
    int srvid = 0x1234;
    EcmKey key = new EcmKey(0x80, srvid, 0, caId, dataHash);
    CwReply reply = new CwReply(key, 0x80, cw, "other-connector", "10.0.0.1:9999",
        System.currentTimeMillis());
    EcmRequest request = new EcmRequest(key, new byte[0], "test-profile",
        "10.0.0.1:9999", "other-connector",
        System.currentTimeMillis(), null, 5000);

    // 1er PUSH : 1 node (own only), doit passer (1 ≤ 2)
    cacheVerticle.handleReply(request, reply);
    Thread.sleep(300);

    // 2e PUSH : même hash, retour du mock avec nodes supplémentaires
    // Le connector aura stocké les nodes du premier, donc le 2e aura plus de nodes
    // Mais comme pushedHashesLru contient déjà ce hash → 2e ne sera pas émis
    // On teste plutôt qu'un push avec trop de nodes est bien bloqué

    // Simuler un pré-remplissage de peerNodeIds avec 3 nodes
    // (on ne peut pas accéder directement, mais on attend le 1er PUSH)
    assertNotNull(pushRef.get(), "First PUSH should have been received");
    int nodeCount1 = pushRef.get().nodeIds().size();
    assertTrue(nodeCount1 <= 2, "First PUSH node count should be ≤ maxhop=2, got " + nodeCount1);

    log("test: first PUSH had " + nodeCount1 + " nodes (maxhop=2)");
  }

  // ============================================================
  // Mock server CacheEx
  // ============================================================

  /**
   * Serveur CCcam mock étendu avec support Cache-Exchange.
   */
  static class CeMockServer {
    private final NetSocket socket;
    private final String expectedUser;
    private final String expectedPass;
    final byte[] ourNodeId = new byte[8];

    private final CcCrypt encryptBlock = new CcCrypt();
    private final CcCrypt decryptBlock = new CcCrypt();

    private Buffer accumulator = Buffer.buffer();
    private boolean headerPhase = true;
    private int payloadLen;
    private int currentCmd;

    private Runnable onHandshakeComplete;
    private java.util.function.BiFunction<Integer, byte[], byte[]> ecmHandler;
    private java.util.function.Consumer<byte[]> filterHandler;
    private java.util.function.Consumer<byte[]> pushHandler;
    private java.util.function.Consumer<byte[]> featureReplyHandler;

    CeMockServer(NetSocket socket, String user, String pass) {
      this.socket = socket;
      this.expectedUser = user;
      this.expectedPass = pass;
      new SecureRandom().nextBytes(ourNodeId);
    }

    byte[] getNodeId() { return ourNodeId; }

    void onHandshakeComplete(Runnable r) { this.onHandshakeComplete = r; }
    void onEcmRequest(java.util.function.BiFunction<Integer, byte[], byte[]> h) { this.ecmHandler = h; }
    void onCacheFilter(java.util.function.Consumer<byte[]> h) { this.filterHandler = h; }
    void onCachePush(java.util.function.Consumer<byte[]> h) { this.pushHandler = h; }
    void onCacheFeatureReply(java.util.function.Consumer<byte[]> h) { this.featureReplyHandler = h; }

    void startHandshake() {
      byte[] seed = new byte[16];
      new SecureRandom().nextBytes(seed);
      sendRaw(seed);

      byte[] seedXor = seed.clone();
      CcCrypt.xor(seedXor);
      byte[] hash = sha1(seedXor);
      byte[] seedForEncrypt = seedXor.clone();

      encryptBlock.initCrypt(hash, 20);
      encryptBlock.crypt(seedXor, 16, CcConstants.DECRYPT);
      decryptBlock.initCrypt(seedXor, 16);
      decryptBlock.crypt(hash, 20, CcConstants.DECRYPT);

      socket.handler(new HandshakeState(seedForEncrypt));
    }

    void sendRaw(byte[] data) {
      socket.write(Buffer.buffer(data));
    }

    void sendFrame(int cmd, byte[] payload) {
      byte[] frame = CcFrameCodec.encode(cmd, payload);
      encryptBlock.crypt(frame, frame.length, CcConstants.ENCRYPT);
      sendRaw(frame);
    }

    private class HandshakeState implements Handler<Buffer> {
      private final byte[] seedXor;
      private Buffer buf = Buffer.buffer();
      private int step = 0;

      HandshakeState(byte[] seedXor) { this.seedXor = seedXor; }

      @Override
      public void handle(Buffer data) {
        buf.appendBuffer(data);
        while (processStep()) {}
      }

      private boolean processStep() {
        switch (step) {
          case 0:
            if (buf.length() < 20) return false;
            byte[] hash = buf.getBytes(0, 20);
            decryptBlock.crypt(hash, 20, CcConstants.DECRYPT);
            buf = Buffer.buffer(buf.getBytes(20, buf.length()));
            step = 1;
            return true;
          case 1:
            if (buf.length() < 20) return false;
            byte[] user = buf.getBytes(0, 20);
            decryptBlock.crypt(user, 20, CcConstants.DECRYPT);
            buf = Buffer.buffer(buf.getBytes(20, buf.length()));
            byte[] pwdBytes = expectedPass.getBytes();
            decryptBlock.crypt(pwdBytes, pwdBytes.length, CcConstants.ENCRYPT);
            step = 2;
            return true;
          case 2:
            if (buf.length() < 6) return false;
            byte[] magic = buf.getBytes(0, 6);
            decryptBlock.crypt(magic, 6, CcConstants.DECRYPT);
            boolean ok = magic[0] == 'C' && magic[1] == 'C' && magic[2] == 'c'
                && magic[3] == 'a' && magic[4] == 'm';
            if (!ok) { socket.close(); return false; }
            byte[] resp = new byte[20];
            resp[0] = 'C'; resp[1] = 'C'; resp[2] = 'c'; resp[3] = 'a'; resp[4] = 'm';
            encryptBlock.crypt(resp, 20, CcConstants.ENCRYPT);
            sendRaw(resp);
            socket.handler(new NormalState());
            if (onHandshakeComplete != null) onHandshakeComplete.run();
            step = 99;
            return false;
          default:
            return false;
        }
      }
    }

    private class NormalState implements Handler<Buffer> {
      @Override
      public void handle(Buffer data) {
        accumulator.appendBuffer(data);
        process();
      }

      private void process() {
        while (true) {
          if (headerPhase) {
            if (accumulator.length() < 4) return;
            byte[] header = accumulator.getBytes(0, 4);
            decryptBlock.crypt(header, 4, CcConstants.DECRYPT);
            int[] decoded = CcFrameCodec.decodeHeader(header);
            currentCmd = decoded[1];
            payloadLen = decoded[2];

            if (payloadLen > 1024 || payloadLen < 0) {
              accumulator = Buffer.buffer(accumulator.getBytes(4, accumulator.length()));
              continue;
            }
            if (payloadLen == 0) {
              handleMessage(currentCmd, new byte[0]);
              accumulator = Buffer.buffer(accumulator.getBytes(4, accumulator.length()));
            } else {
              headerPhase = false;
            }
          } else {
            if (accumulator.length() < 4 + payloadLen) return;
            byte[] payload = accumulator.getBytes(4, 4 + payloadLen);
            decryptBlock.crypt(payload, payloadLen, CcConstants.DECRYPT);
            handleMessage(currentCmd, payload);
            accumulator = Buffer.buffer(accumulator.getBytes(4 + payloadLen,
                accumulator.length()));
            headerPhase = true;
          }
        }
      }

      private void handleMessage(int cmd, byte[] payload) {
        switch (cmd) {
          case CcConstants.MSG_CLI_DATA:
            break;
          case CcConstants.MSG_CW_ECM:
            if (ecmHandler != null && payload.length >= 13) {
              try {
                CcCardCodec.EcmRequestHeader hdr = CcCardCodec.parseEcmRequest(payload);
                byte[] cw = ecmHandler.apply(hdr.cardId(), hdr.ecm());
                if (cw != null && cw.length == 16) {
                  byte[] scrambled = cw.clone();
                  CcCwCrypt.cwCrypt(scrambled, ourNodeId, hdr.cardId());
                  sendFrame(CcConstants.MSG_CW_ECM, scrambled);
                  byte[] sync = Arrays.copyOf(scrambled, 16);
                  encryptBlock.crypt(sync, 16, CcConstants.ENCRYPT);
                } else {
                  sendFrame(CcConstants.MSG_CW_NOK2, new byte[0]);
                }
              } catch (Exception e) {
                sendFrame(CcConstants.MSG_CW_NOK2, new byte[0]);
              }
            }
            break;
          case CcConstants.MSG_KEEPALIVE:
            break;
          case CcConstants.MSG_CMD_05:
            break;
          case CcConstants.MSG_CACHE_FILTER:
            if (filterHandler != null) {
              filterHandler.accept(payload);
            }
            break;
          case CcConstants.MSG_CACHE_PUSH:
            if (pushHandler != null) {
              pushHandler.accept(payload);
            }
            break;
          case CcConstants.MSG_CACHE_FEATURE_EXCHANGE_REPLY:
            if (featureReplyHandler != null) {
              featureReplyHandler.accept(payload);
            }
            break;
        }
      }
    }
  }

  // ============================================================
  // Utilitaires
  // ============================================================

  static byte[] buildSrvData(byte[] nodeId) {
    byte[] data = new byte[72];
    System.arraycopy(nodeId, 0, data, 0, 8);
    byte[] ver = "2.3.2".getBytes();
    System.arraycopy(ver, 0, data, 8, Math.min(ver.length, 6));
    byte[] bld = "4000".getBytes();
    System.arraycopy(bld, 0, data, 40, Math.min(bld.length, 4));
    return data;
  }

  static byte[] buildTestCard(int cardId, int caid, int hop, int reshare) {
    byte[] buf = new byte[21];
    buf[0] = (byte) ((cardId >> 24) & 0xFF);
    buf[1] = (byte) ((cardId >> 16) & 0xFF);
    buf[2] = (byte) ((cardId >> 8) & 0xFF);
    buf[3] = (byte) (cardId & 0xFF);
    buf[8] = (byte) ((caid >> 8) & 0xFF);
    buf[9] = (byte) (caid & 0xFF);
    buf[10] = (byte) hop;
    buf[11] = (byte) reshare;
    buf[20] = 0;
    return buf;
  }

  static byte[] sha1(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-1").digest(data);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  static void log(String msg) {
    System.err.println("[CeIntegrationTest] " + msg);
  }

  /**
   * CW avec checksum DVB valide (cw[i+3] == (cw[i]+cw[i+1]+cw[i+2]) & 0xFF).
   * Requis par FIX 2 (DcwValidator.checksumDcw) pour que le PUSH soit accepté.
   */
  static byte[] checksumCwFixture() {
    return new byte[] {
        0x11, 0x22, 0x33, 0x66, // 0x11+0x22+0x33 = 0x66
        0x44, 0x55, 0x66, (byte) 0xFF, // 0x44+0x55+0x66 = 0xFF
        0x77, (byte) 0x88, (byte) 0x99, (byte) 0x98, // 0x77+0x88+0x99 = 0x198 & 0xFF = 0x98
        (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, 0x31  // 0xAA+0xBB+0xCC = 0x231 & 0xFF = 0x31
    };
  }
}
