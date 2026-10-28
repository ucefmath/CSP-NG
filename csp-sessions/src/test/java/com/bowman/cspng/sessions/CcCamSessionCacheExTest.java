package com.bowman.cspng.sessions;

import com.bowman.cspng.cache.CacheListener;
import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.cccam.CcCacheExCodec;
import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.codec.cccam.CcConstants;
import com.bowman.cspng.codec.cccam.CcCrypt;
import com.bowman.cspng.codec.cccam.CcFrameCodec;
import com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.users.CacheExPolicy;
import com.bowman.cspng.users.ProtocolAuthResolver;
import com.bowman.cspng.users.SessionEcmRouter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CacheEx CCcam ENTRANT (contrat v2 §6.2) — loopback réel client/serveur.
 *
 * <p>Sémantique (arbitrage D2) : user mode 3 = NOUS recevons (le client émet) ;
 * user mode 2 = NOUS émettons vers le client (il reçoit).
 */
@DisplayName("CcCamSession CacheEx entrant — loopback mode 2/3")
class CcCamSessionCacheExTest {

  private static final String USER = "cxuser";
  private static final String PASS = "cxpass";
  private static final int CAID = 0x0100;
  private static final int PRID = 0x00006A;
  private static final int SRVID = 0x1234;
  private static final int DATA_HASH = 0x0EADBEEF;
  private static final byte[] SERVER_NODE_ID = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
  private static final byte[] CLIENT_NODE_ID = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88};

  private Vertx vertx;
  private NetServer server;
  private NetClient client;
  private int serverPort;
  private SessionRegistry registry;
  private ProtocolAuthResolver.Fake authResolver;
  private CacheVerticle cacheVerticle;
  private CacheExStatsRegistry statsRegistry;
  /** T5 : dernier CwReply réellement injecté dans le cache (via CacheListener). */
  private final AtomicReference<CwReply> injectedReply = new AtomicReference<>();

  /** CW 16 octets valide (checksum DVB par groupes de 4, pas de zéros suspects). */
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

  private static byte[] sha1(byte[] in) {
    try {
      return MessageDigest.getInstance("SHA-1").digest(in);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    cacheVerticle = new CacheVerticle();
    cacheVerticle.addListener(new CacheListener() {
      @Override
      public void onReply(EcmRequest request, CwReply reply) {
        if (reply != null) injectedReply.set(reply);
      }
    });
    CountDownLatch deployLatch = new CountDownLatch(1);
    vertx.deployVerticle(cacheVerticle, ar -> deployLatch.countDown());
    assertTrue(deployLatch.await(5, TimeUnit.SECONDS), "CacheVerticle déployé");
    registry = new SessionRegistry(vertx);
    statsRegistry = new CacheExStatsRegistry();
    authResolver = new ProtocolAuthResolver.Fake();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (client != null) client.close();
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

  private final AtomicReference<CcCamSession> lastSession = new AtomicReference<>();

  private void startServer() throws Exception {
    CountDownLatch listenLatch = new CountDownLatch(1);
    server = vertx.createNetServer();
    server.connectHandler(sock -> {
      CcCamSession session = new CcCamSession(vertx, cacheVerticle, registry, authResolver,
          (ref, ctx) -> Future.succeededFuture(PASS),
          (req, auth) -> Future.succeededFuture(null),
          null, SERVER_NODE_ID, "manual", 0, 512, 32, 5, 60, statsRegistry);
      session.setProfileName("default");
      session.setListenerId(7);
      lastSession.set(session);
      session.accept(sock);
    });
    server.listen(0, "127.0.0.1", ar -> {
      if (ar.succeeded()) {
        serverPort = ar.result().actualPort();
        listenLatch.countDown();
      }
    });
    assertTrue(listenLatch.await(5, TimeUnit.SECONDS));
    client = vertx.createNetClient();
  }

  private void addUser(CacheExPolicy policy) {
    addUser(policy, -1);
  }

  private void addUser(CacheExPolicy policy, int allowedEcmRate) {
    authResolver.add("cccam", USER, new ProtocolAuthResolver.Fake.Entry(
        true, Instant.now().plusSeconds(3600), "file:/run/secrets/cxuser", 1,
        Set.of("default"), java.util.Map.of(), java.util.Map.of(), Set.of(), null,
        allowedEcmRate, policy));
  }

  @Test
  @DisplayName("R3 : flood RX — au-delà de allowedEcmRate/min, push droppés 'flood'")
  void rxFloodGateTrips() throws Exception {
    // allowedEcmRate=3 : les 3 premiers push passent, le 4e et suivants sont droppés
    // 'flood' via la fenêtre dédiée "<user>/cacheex" (budget ECM intact et séparé).
    addUser(new CacheExPolicy(3, 10, null), 3);
    startServer();

    CountDownLatch done = new CountDownLatch(1);
    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      CcCrypt cliEnc = new CcCrypt();
      CcCrypt cliDec = new CcCrypt();
      Buffer[] acc = new Buffer[]{Buffer.buffer()};
      doHandshake(sock, cliEnc, cliDec, acc, () -> {
        sock.handler(data -> {
          acc[0].appendBuffer(data);
          while (acc[0].length() >= 4) {
            byte[] hdr = acc[0].getBytes(0, 4);
            cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
            int[] decoded = CcFrameCodec.decodeHeader(hdr);
            int total = 4 + decoded[2];
            if (acc[0].length() < total) return;
            byte[] payload = acc[0].getBytes(4, total);
            cliDec.crypt(payload, decoded[2], CcConstants.DECRYPT);
            acc[0] = Buffer.buffer(acc[0].getBytes(total, acc[0].length()));
            if (decoded[1] == CcConstants.MSG_CACHE_FILTER) {
              // Rafale de 5 push valides distincts (hash différents => pas de dédup)
              for (int i = 0; i < 5; i++) {
                byte[] push = CcCacheExCodec.encodePush(CAID, PRID, SRVID, 0, 0x80,
                    new byte[16], DATA_HASH + i, validCw(21 + i), List.of(CLIENT_NODE_ID));
                byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CACHE_PUSH, push);
                cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
                sock.write(Buffer.buffer(frame));
              }
              done.countDown();
            }
          }
        });
      });
    });

    assertTrue(done.await(8, TimeUnit.SECONDS));
    // Laisser le traitement arriver (5 push)
    long deadline = System.currentTimeMillis() + 5000;
    var pair = statsRegistry.snapshot().stream()
        .filter(p -> p.peerId().equals("L7:" + USER)).findFirst().orElseThrow();
    while (System.currentTimeMillis() < deadline
        && pair.receivedCw() + pair.droppedCw() < 5) {
      Thread.sleep(100);
      pair = statsRegistry.snapshot().stream()
          .filter(p -> p.peerId().equals("L7:" + USER)).findFirst().orElseThrow();
    }
    assertEquals(3, pair.receivedCw(), "3 premiers dans le budget allowedEcmRate=3");
    assertEquals(2, pair.droppedCw(), "2 au-delà droppés");
    assertEquals(2L, pair.dropReasons().get("flood"), "raison flood dédiée");
  }

  /** Handshake client complet ; appelle onReady une fois SRV_DATA reçu. */
  private void doHandshake(NetSocket sock, CcCrypt cliEnc, CcCrypt cliDec,
                           Buffer[] accHolder, Runnable onReady) {
    sock.handler(data -> {
      accHolder[0].appendBuffer(data);
      Buffer acc = accHolder[0];
      byte[] seed = acc.getBytes(0, 16);
      accHolder[0] = Buffer.buffer(acc.getBytes(16, acc.length()));

      byte[] seedXor = seed.clone();
      CcCrypt.xor(seedXor);
      byte[] hash = sha1(seedXor);
      cliDec.initCrypt(hash, CcConstants.SHA1_SIZE);
      cliDec.crypt(seedXor, CcConstants.SEED_SIZE, CcConstants.DECRYPT);
      cliEnc.initCrypt(seedXor, CcConstants.SEED_SIZE);
      cliEnc.crypt(hash, CcConstants.SHA1_SIZE, CcConstants.DECRYPT);

      byte[] hashCopy = hash.clone();
      cliEnc.crypt(hashCopy, 20, CcConstants.ENCRYPT);
      sock.write(Buffer.buffer(hashCopy));

      byte[] userBuf = new byte[20];
      System.arraycopy(USER.getBytes(), 0, userBuf, 0, USER.length());
      cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
      sock.write(Buffer.buffer(userBuf));

      byte[] pwd = PASS.getBytes();
      cliEnc.crypt(pwd, pwd.length, CcConstants.ENCRYPT);
      byte[] magic = Arrays.copyOf(CcConstants.CCCAM_MAGIC, 6);
      cliEnc.crypt(magic, 6, CcConstants.ENCRYPT);
      sock.write(Buffer.buffer(magic));

      // Lire ACK 20 puis CLI_DATA dans un second handler simplifié
      sock.handler(data2 -> {
        accHolder[0].appendBuffer(data2);
        Buffer a2 = accHolder[0];
        if (a2.length() < 20) return;
        byte[] ack = a2.getBytes(0, 20);
        cliDec.crypt(ack, 20, CcConstants.DECRYPT);
        accHolder[0] = Buffer.buffer(a2.getBytes(20, a2.length()));
        byte[] cliData = CcCardCodec.buildCliData(USER, CLIENT_NODE_ID, "2.3.2", "4000");
        byte[] cliFrame = CcFrameCodec.encode(CcConstants.MSG_CLI_DATA, cliData);
        cliEnc.crypt(cliFrame, cliFrame.length, CcConstants.ENCRYPT);
        sock.write(Buffer.buffer(cliFrame));
        onReady.run();
      });
    });
  }

  @Test
  @DisplayName("Mode 3 : PUSH client accepté, CW injectée au cache + receivedCw compté")
  void mode3PushAcceptedAndFedToCache() throws Exception {
    addUser(new CacheExPolicy(3, 10, "0100:00006A"));
    startServer();

    CountDownLatch pushSent = new CountDownLatch(1);
    AtomicReference<byte[]> filterFrame = new AtomicReference<>();
    byte[] cw = validCw(11);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      CcCrypt cliEnc = new CcCrypt();
      CcCrypt cliDec = new CcCrypt();
      Buffer[] acc = new Buffer[]{Buffer.buffer()};
      doHandshake(sock, cliEnc, cliDec, acc, () -> {
        // Attendre SRV_DATA + notre FILTER 0x82 puis envoyer le PUSH 0x81
        sock.handler(data -> {
          acc[0].appendBuffer(data);
          while (acc[0].length() >= 4) {
            byte[] hdr = acc[0].getBytes(0, 4);
            cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
            int[] decoded = CcFrameCodec.decodeHeader(hdr);
            int total = 4 + decoded[2];
            if (acc[0].length() < total) return;
            byte[] payload = acc[0].getBytes(4, total);
            cliDec.crypt(payload, decoded[2], CcConstants.DECRYPT);
            acc[0] = Buffer.buffer(acc[0].getBytes(total, acc[0].length()));
            if (decoded[1] == CcConstants.MSG_CACHE_FILTER) {
              filterFrame.set(payload);
              // Envoyer le PUSH cacheex
              byte[] push = CcCacheExCodec.encodePush(CAID, PRID, SRVID, 0, 0x80,
                  new byte[16], DATA_HASH, cw, List.of(CLIENT_NODE_ID));
              byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CACHE_PUSH, push);
              cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
              sock.write(Buffer.buffer(frame));
              pushSent.countDown();
            }
          }
        });
      });
    });

    assertTrue(pushSent.await(8, TimeUnit.SECONDS), "PUSH envoyé");
    // Notre filtre annoncé doit porter l'entrée 0100 (cevnum=1)
    assertTrue(filterFrame.get() != null && filterFrame.get().length == 482);
    CcCacheExCodec.FilterRecord fr = CcCacheExCodec.parseFilter(filterFrame.get());
    assertEquals(1, fr.cevnum());
    assertEquals(CAID, fr.caids()[0]);

    // La CW doit être visible dans le cache local + compteur receivedCw
    EcmKey key = new EcmKey(0x80, SRVID, 0, CAID, DATA_HASH);
    long deadline = System.currentTimeMillis() + 5000;
    com.bowman.cspng.domain.CwReply got = null;
    while (System.currentTimeMillis() < deadline && got == null) {
      got = cacheVerticle.peekLocalCache(key);
      if (got == null) Thread.sleep(50);
    }
    assertNotNull(got, "CW du PUSH doit être injectée au cache");
    assertArrayEquals(cw, got.cw());

    var pair = statsRegistry.snapshot().stream()
        .filter(p -> p.peerId().equals("L7:" + USER)).findFirst().orElseThrow();
    assertEquals("inbound", pair.direction());
    assertEquals("cccam", pair.protocol());
    assertEquals(3, pair.mode());
    assertEquals(1, pair.receivedCw());
    assertEquals(0, pair.droppedCw());

    // T5 — identité du pair, stats par SID et provenance du CwReply injecté.
    CwReply injected = injectedReply.get();
    assertNotNull(injected, "CwReply du PUSH 0x81 injecté dans cacheVerticle");
    var sidList = pair.sids();
    String firstServiceKey = sidList.isEmpty() ? null : sidList.get(0).serviceKey();
    long firstSidReceived = sidList.isEmpty() ? -1 : sidList.get(0).receivedCw();
    assertAll("T5 identité du pair, stats par SID et provenance",
        () -> assertFalse(pair.remoteIp() == null || pair.remoteIp().isEmpty(),
            "IP distante du pair renseignée dans le snapshot"),
        () -> assertFalse(sidList.isEmpty(), "stats par SID présentes après le PUSH"),
        () -> assertTrue(firstSidReceived >= 1, "CW reçue comptée pour ce SID"),
        () -> assertEquals(String.format("%04X@%06X:%04X", CAID, PRID, SRVID),
            firstServiceKey, "clé de service CAID@PROVID:SID"),
        () -> assertEquals("L7:" + USER, injected.connectorName(),
            "provenance = pair L<listenerId>:<user>, pas l'utilisateur seul"));
  }

  @Test
  @DisplayName("Compat OSCam : PUSH oddEven=0x00 accepté comme INCONNU et servi")
  void mode3PushUnknownOddEvenAccepted() throws Exception {
    addUser(new CacheExPolicy(3, 10, "0100:00006A"));
    startServer();

    CountDownLatch pushSent = new CountDownLatch(1);
    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      CcCrypt cliEnc = new CcCrypt();
      CcCrypt cliDec = new CcCrypt();
      Buffer[] acc = new Buffer[]{Buffer.buffer()};
      doHandshake(sock, cliEnc, cliDec, acc, () -> {
        sock.handler(data -> {
          acc[0].appendBuffer(data);
          while (acc[0].length() >= 4) {
            byte[] hdr = acc[0].getBytes(0, 4);
            cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
            int[] decoded = CcFrameCodec.decodeHeader(hdr);
            int total = 4 + decoded[2];
            if (acc[0].length() < total) return;
            byte[] payload = acc[0].getBytes(4, total);
            cliDec.crypt(payload, decoded[2], CcConstants.DECRYPT);
            acc[0] = Buffer.buffer(acc[0].getBytes(total, acc[0].length()));
            if (decoded[1] == CcConstants.MSG_CACHE_FILTER) {
              // PUSH valide par ailleurs mais oddEven 0x00 inconnu
              byte[] push = CcCacheExCodec.encodePush(CAID, PRID, SRVID, 0, 0x00,
                  new byte[16], DATA_HASH, validCw(21), List.of(CLIENT_NODE_ID));
              byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CACHE_PUSH, push);
              cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
              sock.write(Buffer.buffer(frame));
              pushSent.countDown();
            }
          }
        });
      });
    });

    assertTrue(pushSent.await(8, TimeUnit.SECONDS));
    Thread.sleep(500); // laisser le traitement arriver
    // Accepté comme INCONNU : reçu compté, rien droppé
    var pair = statsRegistry.snapshot().stream()
        .filter(p -> p.peerId().equals("L7:" + USER)).findFirst().orElseThrow();
    assertEquals(1, pair.receivedCw(), "push 0 accepté");
    assertEquals(0, pair.droppedCw(), "aucun drop");
    // Réellement SERVI : lookup hash trouve la CW pour une requête 0x80
    var served = cacheVerticle.peekLocalCache(new EcmKey(0x80, SRVID, 0, CAID, DATA_HASH));
    assertNotNull(served, "CW inconnue servie pour requête 0x80 (fallback hash)");
    assertArrayEquals(validCw(21), served.cw(), "octets CW intacts");
  }

  @Test
  @DisplayName("Mode 3 : PUSH hors filtre rejeté (filtered) sans injection")
  void mode3PushOutOfFilterRejected() throws Exception {
    addUser(new CacheExPolicy(3, 10, "0100:00006A"));
    startServer();

    CountDownLatch pushSent = new CountDownLatch(1);
    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      CcCrypt cliEnc = new CcCrypt();
      CcCrypt cliDec = new CcCrypt();
      Buffer[] acc = new Buffer[]{Buffer.buffer()};
      doHandshake(sock, cliEnc, cliDec, acc, () -> {
        sock.handler(data -> {
          acc[0].appendBuffer(data);
          while (acc[0].length() >= 4) {
            byte[] hdr = acc[0].getBytes(0, 4);
            cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
            int[] decoded = CcFrameCodec.decodeHeader(hdr);
            int total = 4 + decoded[2];
            if (acc[0].length() < total) return;
            byte[] payload = acc[0].getBytes(4, total);
            cliDec.crypt(payload, decoded[2], CcConstants.DECRYPT);
            acc[0] = Buffer.buffer(acc[0].getBytes(total, acc[0].length()));
            if (decoded[1] == CcConstants.MSG_CACHE_FILTER) {
              // PUSH avec CAID 0x0500 hors filtre
              byte[] push = CcCacheExCodec.encodePush(0x0500, 0x030B00, SRVID, 0, 0x80,
                  new byte[16], DATA_HASH, validCw(21), List.of(CLIENT_NODE_ID));
              byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CACHE_PUSH, push);
              cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
              sock.write(Buffer.buffer(frame));
              pushSent.countDown();
            }
          }
        });
      });
    });

    assertTrue(pushSent.await(8, TimeUnit.SECONDS));
    Thread.sleep(500); // laisser le traitement arriver
    EcmKey key = new EcmKey(0x80, SRVID, 0, 0x0500, DATA_HASH);
    assertNull(cacheVerticle.peekLocalCache(key), "CW hors filtre ne doit PAS être injectée");
    var pair = statsRegistry.snapshot().stream()
        .filter(p -> p.peerId().equals("L7:" + USER)).findFirst().orElseThrow();
    assertEquals(0, pair.receivedCw());
    assertEquals(1, pair.droppedCw());
    assertEquals(1L, pair.dropReasons().get("filtered"));
  }

  @Test
  @DisplayName("Mode 3 : PUSH contenant notre node => drop loop (triangle impossible)")
  void mode3PushWithOwnNodeDroppedAsLoop() throws Exception {
    addUser(new CacheExPolicy(3, 10, "0100:00006A"));
    startServer();

    CountDownLatch pushSent = new CountDownLatch(1);
    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      CcCrypt cliEnc = new CcCrypt();
      CcCrypt cliDec = new CcCrypt();
      Buffer[] acc = new Buffer[]{Buffer.buffer()};
      doHandshake(sock, cliEnc, cliDec, acc, () -> {
        sock.handler(data -> {
          acc[0].appendBuffer(data);
          while (acc[0].length() >= 4) {
            byte[] hdr = acc[0].getBytes(0, 4);
            cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
            int[] decoded = CcFrameCodec.decodeHeader(hdr);
            int total = 4 + decoded[2];
            if (acc[0].length() < total) return;
            byte[] payload = acc[0].getBytes(4, total);
            cliDec.crypt(payload, decoded[2], CcConstants.DECRYPT);
            acc[0] = Buffer.buffer(acc[0].getBytes(total, acc[0].length()));
            if (decoded[1] == CcConstants.MSG_CACHE_FILTER) {
              // PUSH dont le chemin contient NOTRE serverNodeId (boucle A->B->A)
              byte[] push = CcCacheExCodec.encodePush(CAID, PRID, SRVID, 0, 0x80,
                  new byte[16], DATA_HASH, validCw(41),
                  List.of(CLIENT_NODE_ID, SERVER_NODE_ID));
              byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CACHE_PUSH, push);
              cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
              sock.write(Buffer.buffer(frame));
              pushSent.countDown();
            }
          }
        });
      });
    });

    assertTrue(pushSent.await(8, TimeUnit.SECONDS));
    Thread.sleep(500);
    EcmKey key = new EcmKey(0x80, SRVID, 0, CAID, DATA_HASH);
    assertNull(cacheVerticle.peekLocalCache(key), "CW en boucle ne doit PAS être injectée");
    var pair = statsRegistry.snapshot().stream()
        .filter(p -> p.peerId().equals("L7:" + USER)).findFirst().orElseThrow();
    assertEquals(0, pair.receivedCw());
    assertEquals(1, pair.droppedCw());
    assertEquals(1L, pair.dropReasons().get("loop"));
  }

  @Test
  @DisplayName("Mode 3 : PUSH à 11 hops (maxHop 10) => drop hop")
  void mode3PushOverMaxHopDropped() throws Exception {
    addUser(new CacheExPolicy(3, 10, "0100:00006A"));
    startServer();

    CountDownLatch pushSent = new CountDownLatch(1);
    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      CcCrypt cliEnc = new CcCrypt();
      CcCrypt cliDec = new CcCrypt();
      Buffer[] acc = new Buffer[]{Buffer.buffer()};
      doHandshake(sock, cliEnc, cliDec, acc, () -> {
        sock.handler(data -> {
          acc[0].appendBuffer(data);
          while (acc[0].length() >= 4) {
            byte[] hdr = acc[0].getBytes(0, 4);
            cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
            int[] decoded = CcFrameCodec.decodeHeader(hdr);
            int total = 4 + decoded[2];
            if (acc[0].length() < total) return;
            byte[] payload = acc[0].getBytes(4, total);
            cliDec.crypt(payload, decoded[2], CcConstants.DECRYPT);
            acc[0] = Buffer.buffer(acc[0].getBytes(total, acc[0].length()));
            if (decoded[1] == CcConstants.MSG_CACHE_FILTER) {
              java.util.List<byte[]> longPath = new java.util.ArrayList<>();
              for (int i = 0; i < 11; i++) {
                byte[] n = new byte[8];
                n[0] = (byte) (0x50 + i);
                longPath.add(n);
              }
              byte[] push = CcCacheExCodec.encodePush(CAID, PRID, SRVID, 0, 0x80,
                  new byte[16], DATA_HASH, validCw(43), longPath);
              byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CACHE_PUSH, push);
              cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
              sock.write(Buffer.buffer(frame));
              pushSent.countDown();
            }
          }
        });
      });
    });

    assertTrue(pushSent.await(8, TimeUnit.SECONDS));
    Thread.sleep(500);
    EcmKey key = new EcmKey(0x80, SRVID, 0, CAID, DATA_HASH);
    assertNull(cacheVerticle.peekLocalCache(key));
    var pair = statsRegistry.snapshot().stream()
        .filter(p -> p.peerId().equals("L7:" + USER)).findFirst().orElseThrow();
    assertEquals(0, pair.receivedCw());
    assertEquals(1, pair.droppedCw());
    assertEquals(1L, pair.dropReasons().get("hop"));
  }

  @Test
  @DisplayName("Mode 2 : re-push préserve le chemin amont appris (forwarding)")
  void mode2ForwardPreservesUpstreamPath() throws Exception {
    addUser(new CacheExPolicy(2, 10, null)); // nous émettons
    startServer();

    CountDownLatch pushReceived = new CountDownLatch(1);
    AtomicReference<byte[]> pushPayload = new AtomicReference<>();
    byte[] cw = validCw(45);

    // Chemin amont appris via la session serveur capturée (voir plus bas).
    // le contrat noteUpstreamPath + onReply au niveau du bridge worker (déjà couvert)
    // et ici on vérifie le chemin minimal [serverNode] pour une CW fraîche.
    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      CcCrypt cliEnc = new CcCrypt();
      CcCrypt cliDec = new CcCrypt();
      Buffer[] acc = new Buffer[]{Buffer.buffer()};
      doHandshake(sock, cliEnc, cliDec, acc, () -> {
        sock.handler(data -> {
          acc[0].appendBuffer(data);
          while (acc[0].length() >= 4) {
            byte[] hdr = acc[0].getBytes(0, 4);
            cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
            int[] decoded = CcFrameCodec.decodeHeader(hdr);
            int total = 4 + decoded[2];
            if (acc[0].length() < total) return;
            byte[] payload = acc[0].getBytes(4, total);
            cliDec.crypt(payload, decoded[2], CcConstants.DECRYPT);
            acc[0] = Buffer.buffer(acc[0].getBytes(total, acc[0].length()));
            if (decoded[1] == CcConstants.MSG_SRV_DATA) {
              byte[] filter = CcCacheExCodec.encodeFilterAcceptAll();
              byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CACHE_FILTER, filter);
              cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
              sock.write(Buffer.buffer(frame));
            } else if (decoded[1] == CcConstants.MSG_CACHE_PUSH) {
              pushPayload.set(payload);
              pushReceived.countDown();
            }
          }
        });
      });
    });

    Thread.sleep(800);
    // Chemin amont appris (comme après un PUSH [nodeA] reçu) => re-push [server, nodeA]
    byte[] nodeA = {0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68};
    assertNotNull(lastSession.get(), "session serveur capturée");
    lastSession.get().noteUpstreamPath(DATA_HASH, List.of(nodeA), 7, false);
    EcmKey key = new EcmKey(0x80, SRVID, 0, CAID, DATA_HASH);
    CwReply reply = new CwReply(key, 0x80, cw, "other-conn", "10.9.9.9:1",
        System.currentTimeMillis());
    EcmRequest synthetic = new EcmRequest(key, new byte[0], "default", "10.9.9.9:1",
        "other-conn", System.currentTimeMillis(), null, 5000);
    cacheVerticle.handleReply(synthetic, reply);

    assertTrue(pushReceived.await(8, TimeUnit.SECONDS));
    CcCacheExCodec.PushRecord rec = CcCacheExCodec.parsePush(pushPayload.get());
    // Forwarding C2 : [serverNode, nodeA] + cycle préservé
    assertEquals(2, rec.nodeIds().size());
    assertArrayEquals(SERVER_NODE_ID, rec.nodeIds().get(0));
    assertArrayEquals(nodeA, rec.nodeIds().get(1));
    assertEquals(7, rec.cycleTimeSec());
    assertFalse(rec.nextCwCycle());
    assertArrayEquals(cw, rec.cw());
  }

  @Test
  @DisplayName("Mode 2 : CW du cache poussée au client (0x81) + pushedCw compté")
  void mode2CacheReplyPushedToClient() throws Exception {
    addUser(new CacheExPolicy(2, 10, null)); // nous émettons
    startServer();

    CountDownLatch pushReceived = new CountDownLatch(1);
    AtomicReference<byte[]> pushPayload = new AtomicReference<>();
    byte[] cw = validCw(31);

    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      CcCrypt cliEnc = new CcCrypt();
      CcCrypt cliDec = new CcCrypt();
      Buffer[] acc = new Buffer[]{Buffer.buffer()};
      doHandshake(sock, cliEnc, cliDec, acc, () -> {
        sock.handler(data -> {
          acc[0].appendBuffer(data);
          while (acc[0].length() >= 4) {
            byte[] hdr = acc[0].getBytes(0, 4);
            cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
            int[] decoded = CcFrameCodec.decodeHeader(hdr);
            int total = 4 + decoded[2];
            if (acc[0].length() < total) return;
            byte[] payload = acc[0].getBytes(4, total);
            cliDec.crypt(payload, decoded[2], CcConstants.DECRYPT);
            acc[0] = Buffer.buffer(acc[0].getBytes(total, acc[0].length()));
            if (decoded[1] == CcConstants.MSG_SRV_DATA) {
              // Annoncer notre filtre accept-all (0x82) pour recevoir les push
              byte[] filter = CcCacheExCodec.encodeFilterAcceptAll();
              byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CACHE_FILTER, filter);
              cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
              sock.write(Buffer.buffer(frame));
            } else if (decoded[1] == CcConstants.MSG_CACHE_PUSH) {
              pushPayload.set(payload);
              pushReceived.countDown();
            }
          }
        });
      });
    });

    // Injecter une CW dans le cache SERVEUR -> le transport session doit la pousser
    Thread.sleep(800); // laisser handshake + filtre arriver
    EcmKey key = new EcmKey(0x80, SRVID, 0, CAID, DATA_HASH);
    CwReply reply = new CwReply(key, 0x80, cw, "other-conn", "10.9.9.9:1",
        System.currentTimeMillis());
    EcmRequest synthetic = new EcmRequest(key, new byte[0], "default", "10.9.9.9:1",
        "other-conn", System.currentTimeMillis(), null, 5000);
    cacheVerticle.handleReply(synthetic, reply);

    assertTrue(pushReceived.await(8, TimeUnit.SECONDS), "PUSH 0x81 attendu côté client");
    CcCacheExCodec.PushRecord rec = CcCacheExCodec.parsePush(pushPayload.get());
    assertEquals(CAID, rec.caid());
    assertEquals(SRVID, rec.srvid());
    assertArrayEquals(cw, rec.cw());
    // Chemin : notre serverNodeId en premier
    assertEquals(1, rec.nodeIds().size());
    assertArrayEquals(SERVER_NODE_ID, rec.nodeIds().get(0));

    var pair = statsRegistry.snapshot().stream()
        .filter(p -> p.peerId().equals("L7:" + USER)).findFirst().orElseThrow();
    assertEquals(2, pair.mode());
    assertEquals(1, pair.pushedCw());
  }
}
