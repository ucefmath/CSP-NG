package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.camd35.Camd35CacheExCodec;
import com.bowman.cspng.codec.camd35.Camd35Constants;
import com.bowman.cspng.codec.camd35.Camd35Crypto;
import com.bowman.cspng.codec.camd35.Camd35FrameCodec;
import com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Connecteur CS378x sortant (contrat v2 §6.3) — loopback contre un faux serveur
 * camd35 : connexion/ucrc, échange d'id 0x3D/0x3E, filtre 0x3C, PUSH 0x3F in/out.
 *
 * <p>Sémantique : connecteur mode 2 = NOUS recevons ; mode 3 = NOUS émettons.
 */
@DisplayName("Camd35TcpConnector — loopback cs378x sortant")
class Camd35TcpConnectorLoopbackTest {

  private static final String USER = "upconn";
  private static final String PASS = "uppass";
  private static final byte[] AES_KEY = Camd35Crypto.aesKey(PASS);
  private static final byte[] UCRC = Camd35Crypto.ucrc(USER);
  private static final int CAID = 0x0100;
  private static final int PRID = 0x00006A;
  private static final int SRVID = 0x1234;
  private static final int DATA_HASH = 0x0EADBEEF;
  private static final byte[] SERVER_NODE = {0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58};

  private Vertx vertx;
  private NetServer server;
  private int serverPort;
  private CacheVerticle cacheVerticle;
  private CacheExStatsRegistry statsRegistry;
  private CwsConnectorManager manager;

  static byte[] validCw(int seed) {
    byte[] cw = new byte[16];
    for (int g = 0; g < 4; g++) {
      cw[g * 4] = (byte) (seed + g * 3 + 1);
      cw[g * 4 + 1] = (byte) (seed + g * 3 + 2);
      cw[g * 4 + 2] = (byte) (seed + g * 3 + 3);
      cw[g * 4 + 3] = (byte) ((cw[g * 4] + cw[g * 4 + 1] + cw[g * 4 + 2]) & 0xFF);
    }
    return cw;
  }

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    cacheVerticle = new CacheVerticle();
    CountDownLatch deployLatch = new CountDownLatch(1);
    vertx.deployVerticle(cacheVerticle, ar -> deployLatch.countDown());
    assertTrue(deployLatch.await(5, TimeUnit.SECONDS));
    statsRegistry = new CacheExStatsRegistry();
    manager = new CwsConnectorManager(vertx, cacheVerticle);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (server != null) {
      CountDownLatch closeLatch = new CountDownLatch(1);
      server.close(v -> closeLatch.countDown());
      closeLatch.await(5, TimeUnit.SECONDS);
    }
    if (vertx != null) {
      CountDownLatch vClose = new CountDownLatch(1);
      vertx.close(v -> vClose.countDown());
      vClose.await(5, TimeUnit.SECONDS);
    }
  }

  private final AtomicReference<NetSocket> serverSock = new AtomicReference<>();

  /** Faux serveur camd35 : répond 0x3E à tout 0x3D, capture les frames reçues. */
  private void startFakeServer(java.util.function.Consumer<Camd35FrameCodec.Camd35Frame> onFrame,
                               java.util.List<Camd35FrameCodec.Camd35Frame> received) throws Exception {
    CountDownLatch listenLatch = new CountDownLatch(1);
    server = vertx.createNetServer();
    Camd35FrameCodec.Decoder serverDec = new Camd35FrameCodec.Decoder(AES_KEY);
    server.connectHandler(sock -> {
      serverSock.set(sock);
      sock.handler(buf -> {
        Camd35FrameCodec.DecodeResult res = serverDec.feed(buf.getBytes());
        while (res.status() == Camd35FrameCodec.Status.FRAME) {
          var f = res.frame();
          received.add(f);
          if (f.cmd() == Camd35Constants.CMD_CACHEEX_ID_REQ) {
            byte[] body = new byte[12];
            System.arraycopy(SERVER_NODE, 0, body, 0, 8);
            sock.write(Buffer.buffer(Camd35FrameCodec.encode(
                new Camd35FrameCodec.Camd35Frame(UCRC, Camd35Constants.CMD_CACHEEX_ID_ANSWER,
                    0, 0, 0, 0, 0, body, new byte[0]), AES_KEY)));
          }
          if (onFrame != null) onFrame.accept(f);
          res = serverDec.feed(new byte[0]);
        }
      });
    });
    server.listen(0, "127.0.0.1", ar -> {
      serverPort = ar.result().actualPort();
      listenLatch.countDown();
    });
    assertTrue(listenLatch.await(5, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("Mode 2 : id-exchange + FILTER annoncé, PUSH serveur injecté au cache")
  void mode2ReceivesServerPush() throws Exception {
    java.util.List<Camd35FrameCodec.Camd35Frame> received =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    startFakeServer(null, received);

    Camd35TcpConnector conn = manager.addCamd35TcpConnector("cx-out", "127.0.0.1", serverPort,
        USER, PASS, "default", 2, 10, 5000, null, "0100:00006A", 2000, statsRegistry);
    statsRegistry.registerOutbound("cx-out", "cs378x", 2, "default", 10, 2000);
    conn.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    // Le connecteur a envoyé 0x3D (id) + 0x3C (filtre)
    long deadline = System.currentTimeMillis() + 5000;
    boolean sawFilter = false;
    boolean sawIdReq = false;
    while (System.currentTimeMillis() < deadline && !(sawFilter && sawIdReq)) {
      for (var f : received) {
        if (f.cmd() == Camd35Constants.CMD_CACHEEX_ID_REQ) sawIdReq = true;
        if (f.cmd() == Camd35Constants.CMD_CACHEEX_FILTER) sawFilter = true;
      }
      if (!(sawFilter && sawIdReq)) Thread.sleep(50);
    }
    assertTrue(sawIdReq, "0x3D envoyé par le connecteur");
    assertTrue(sawFilter, "0x3C envoyé par le connecteur (mode 2)");
    // Vérifier le contenu du filtre annoncé (entrée 0100)
    Camd35FrameCodec.Camd35Frame filterFrame = received.stream()
        .filter(f -> f.cmd() == Camd35Constants.CMD_CACHEEX_FILTER).findFirst().orElseThrow();
    var entries = Camd35CacheExCodec.parseFilterBody(filterFrame.data());
    assertEquals(1, entries.size());
    assertEquals(CAID, entries.get(0).caid());

    // Le serveur pousse une CW 0x3F -> le connecteur doit l'injecter + compter receivedCw
    byte[] cw = validCw(73);
    byte[] body = Camd35CacheExCodec.encodePushBody(new byte[16], DATA_HASH, cw,
        List.of(SERVER_NODE));
    Camd35FrameCodec.Camd35Frame push = new Camd35FrameCodec.Camd35Frame(
        UCRC, Camd35Constants.CMD_CACHEEX_PUSH, SRVID, CAID, PRID, 0x00CC,
        (10 << 8) | 0x80, body, new byte[0], 0);
    serverSock.get().write(Buffer.buffer(Camd35FrameCodec.encode(push, AES_KEY)));

    EcmKey key = new EcmKey(0x80, SRVID, 0, CAID, DATA_HASH);
    long injectDeadline = System.currentTimeMillis() + 5000;
    CwReply got = null;
    while (System.currentTimeMillis() < injectDeadline && got == null) {
      got = cacheVerticle.peekLocalCache(key);
      if (got == null) Thread.sleep(50);
    }
    assertNotNull(got, "CW du PUSH serveur doit être injectée");
    assertArrayEquals(cw, got.cw());

    var pair = statsRegistry.snapshot().stream()
        .filter(p -> p.peerId().equals("cx-out")).findFirst().orElseThrow();
    assertEquals(2, pair.mode());
    assertEquals(1, pair.receivedCw());
    assertEquals(0, pair.droppedCw());

    conn.stop();
  }

  @Test
  @DisplayName("Mode 3 : CW du cache poussée au serveur (0x3F) + pushedCw compté")
  void mode3EmitsCachePushToServer() throws Exception {
    java.util.List<Camd35FrameCodec.Camd35Frame> received =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    CountDownLatch pushSeen = new CountDownLatch(1);
    AtomicReference<Camd35FrameCodec.Camd35Frame> pushFrame = new AtomicReference<>();
    startFakeServer(f -> {
      if (f.cmd() == Camd35Constants.CMD_CACHEEX_PUSH) {
        pushFrame.set(f);
        pushSeen.countDown();
      }
    }, received);

    Camd35TcpConnector conn = manager.addCamd35TcpConnector("cx-out3", "127.0.0.1", serverPort,
        USER, PASS, "default", 3, 10, 5000, null, null, 2000, statsRegistry);
    statsRegistry.registerOutbound("cx-out3", "cs378x", 3, "default", 10, 2000);
    conn.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    Thread.sleep(500); // laisser l'id-exchange se faire

    // Injecter une CW dans le cache du connecteur -> onReply doit pousser 0x3F
    byte[] cw = validCw(71);
    EcmKey key = new EcmKey(0x80, SRVID, 0, CAID, DATA_HASH);
    CwReply reply = new CwReply(key, 0x80, cw, "other", "10.9.9.9:1",
        System.currentTimeMillis());
    EcmRequest synthetic = new EcmRequest(key, new byte[0], "default", "10.9.9.9:1",
        "other", System.currentTimeMillis(), null, 5000);
    cacheVerticle.handleReply(synthetic, reply);

    assertTrue(pushSeen.await(8, TimeUnit.SECONDS), "PUSH 0x3F attendu côté serveur");
    Camd35CacheExCodec.Camd35Push push = Camd35CacheExCodec.parsePush(pushFrame.get());
    assertEquals(CAID, push.caid());
    assertEquals(SRVID, push.srvid());
    assertArrayEquals(cw, push.cw());
    assertEquals(0, push.rc());
    assertEquals(1, push.nodeIds().size(), "chemin = node id du connecteur");

    var pair = statsRegistry.snapshot().stream()
        .filter(p -> p.peerId().equals("cx-out3")).findFirst().orElseThrow();
    assertEquals("outbound", pair.direction());
    assertEquals("cs378x", pair.protocol());
    assertEquals(3, pair.mode());
    assertEquals(1, pair.pushedCw());

    conn.stop();
  }
}
