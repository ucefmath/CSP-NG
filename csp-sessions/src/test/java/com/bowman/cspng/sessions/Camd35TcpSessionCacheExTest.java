package com.bowman.cspng.sessions;

import com.bowman.cspng.cache.CacheListener;
import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.camd35.Camd35CacheExCodec;
import com.bowman.cspng.codec.camd35.Camd35Constants;
import com.bowman.cspng.codec.camd35.Camd35Crypto;
import com.bowman.cspng.codec.camd35.Camd35FrameCodec;
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

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CacheEx CS378x ENTRANT (contrat v2 §6.2) — loopback réel : 0x3D/0x3E (échange
 * d'id), 0x3C (filtres), 0x3F (push).
 *
 * <p>Sémantique : user mode 3 = NOUS recevons (le client émet) ;
 * user mode 2 = NOUS émettons vers le client (il reçoit).
 */
@DisplayName("Camd35TcpSession CacheEx entrant — loopback 0x3C-0x3F")
class Camd35TcpSessionCacheExTest {

  private static final String USER = "cx35user";
  private static final String PASS = "cx35pass";
  private static final byte[] AES_KEY = Camd35Crypto.aesKey(PASS);
  private static final byte[] UCRC = Camd35Crypto.ucrc(USER);
  private static final int CAID = 0x0100;
  private static final int PRID = 0x00006A;
  private static final int SRVID = 0x1234;
  private static final int DATA_HASH = 0x0EADBEEF;

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
    cacheVerticle.addListener(new CacheListener() {
      @Override
      public void onReply(EcmRequest request, CwReply reply) {
        if (reply != null) injectedReply.set(reply);
      }
    });
    CountDownLatch deployLatch = new CountDownLatch(1);
    vertx.deployVerticle(cacheVerticle, ar -> deployLatch.countDown());
    assertTrue(deployLatch.await(5, TimeUnit.SECONDS));
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

  private void startServer() throws Exception {
    CountDownLatch listenLatch = new CountDownLatch(1);
    server = vertx.createNetServer();
    server.connectHandler(sock -> {
      Camd35TcpSession session = new Camd35TcpSession(vertx, cacheVerticle, registry,
          authResolver, (ref, ctx) -> Future.succeededFuture(PASS),
          (req, auth) -> Future.succeededFuture(null),
          32, 5, 60, statsRegistry);
      session.setProfileName("default");
      session.setListenerId(9);
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
    long ucrcVal = ((UCRC[0] & 0xFFL) << 24) | ((UCRC[1] & 0xFFL) << 16)
        | ((UCRC[2] & 0xFFL) << 8) | (UCRC[3] & 0xFFL);
    authResolver.add("cs378x", USER, new ProtocolAuthResolver.Fake.Entry(
        true, Instant.now().plusSeconds(3600), "file:/run/secrets/cx35user", 1,
        Set.of("default"), java.util.Map.of(), java.util.Map.of(), Set.of(), ucrcVal, -1, policy));
  }

  private Camd35FrameCodec.Camd35Frame keepalive() {
    return new Camd35FrameCodec.Camd35Frame(UCRC, Camd35Constants.CMD_KEEPALIVE,
        0, 0, 0, 0, 0, new byte[0], new byte[0]);
  }

  @Test
  @DisplayName("Mode 3 : 0x3D->0x3E, notre FILTER 0x3C, PUSH 0x3F accepté + injecté")
  void mode3IdExchangeFilterAndPushAccepted() throws Exception {
    addUser(new CacheExPolicy(3, 10, "0100:00006A"));
    startServer();

    CountDownLatch pushSent = new CountDownLatch(1);
    AtomicReference<byte[]> idAnswer = new AtomicReference<>();
    AtomicReference<byte[]> ourFilter = new AtomicReference<>();
    byte[] cw = validCw(51);
    byte[] clientNode = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28};

    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      Camd35FrameCodec.Decoder dec = new Camd35FrameCodec.Decoder(AES_KEY);
      sock.handler(buf -> {
        Camd35FrameCodec.DecodeResult res = dec.feed(buf.getBytes());
        while (res.status() == Camd35FrameCodec.Status.FRAME) {
          var f = res.frame();
          if (f.cmd() == Camd35Constants.CMD_CACHEEX_ID_ANSWER) {
            idAnswer.set(f.data());
          } else if (f.cmd() == 0x3C) {
            ourFilter.set(f.data());
            // Envoyer le PUSH 0x3F (caid/prid dans le filtre)
            byte[] body = Camd35CacheExCodec.encodePushBody(new byte[16], DATA_HASH, cw,
                List.of(clientNode));
            Camd35FrameCodec.Camd35Frame push = new Camd35FrameCodec.Camd35Frame(
                UCRC, Camd35Constants.CMD_CACHEEX_PUSH, SRVID, CAID, PRID, 0x00BB,
                (10 << 8) | 0x80, body, new byte[0], 0);
            sock.write(Buffer.buffer(Camd35FrameCodec.encode(push, AES_KEY)));
            pushSent.countDown();
          }
          res = dec.feed(new byte[0]);
        }
      });
      // Auth (ucrc + keepalive) puis échange d'id 0x3D
      sock.write(Buffer.buffer(Camd35FrameCodec.encode(keepalive(), AES_KEY)));
      byte[] idBody = new byte[12];
      System.arraycopy(clientNode, 0, idBody, 0, 8);
      sock.write(Buffer.buffer(Camd35FrameCodec.encode(
          new Camd35FrameCodec.Camd35Frame(UCRC, Camd35Constants.CMD_CACHEEX_ID_REQ,
              0, 0, 0, 0, 0, idBody, new byte[0]), AES_KEY)));
    });

    assertTrue(pushSent.await(8, TimeUnit.SECONDS), "PUSH envoyé");
    // 0x3E : le serveur a répondu avec SON node id (8 octets).
    // NOTE : pushSent se déclenche sur le 0x3C qui PRÉCÈDE le 0x3E sur le wire —
    // attendre activement (pas d'assertion immédiate : race avec la boucle de décodage).
    long answerDeadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < answerDeadline && idAnswer.get() == null) {
      Thread.sleep(50);
    }
    assertNotNull(idAnswer.get(), "0x3E attendu");
    assertEquals(12, idAnswer.get().length);
    // 0x3C : notre filtre annoncé porte l'entrée 0100
    assertNotNull(ourFilter.get(), "FILTER 0x3C attendu");
    var entries = Camd35CacheExCodec.parseFilterBody(ourFilter.get());
    assertEquals(1, entries.size());
    assertEquals(CAID, entries.get(0).caid());

    // CW injectée + compteur + métadonnée de cycle apprise
    EcmKey key = new EcmKey(0x80, SRVID, 0, CAID, DATA_HASH);
    long deadline = System.currentTimeMillis() + 5000;
    com.bowman.cspng.domain.CwReply got = null;
    while (System.currentTimeMillis() < deadline && got == null) {
      got = cacheVerticle.peekLocalCache(key);
      if (got == null) Thread.sleep(50);
    }
    assertNotNull(got, "CW du PUSH 0x3F doit être injectée");
    assertArrayEquals(cw, got.cw());

    var pair = statsRegistry.snapshot().stream()
        .filter(p -> p.peerId().equals("L9:" + USER)).findFirst().orElseThrow();
    assertEquals("inbound", pair.direction());
    assertEquals("cs378x", pair.protocol());
    assertEquals(3, pair.mode());
    assertEquals(1, pair.receivedCw());
    assertEquals(0, pair.droppedCw());

    // T5 — identité du pair, stats par SID et provenance du CwReply injecté.
    CwReply injected = injectedReply.get();
    assertNotNull(injected, "CwReply du PUSH 0x3F injecté dans cacheVerticle");
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
        () -> assertEquals("L9:" + USER, injected.connectorName(),
            "provenance = pair L<listenerId>:<user>, pas l'utilisateur seul"));
  }

  @Test
  @DisplayName("Mode 2 : FILTER 0x3C client puis CW du cache poussée en 0x3F")
  void mode2ClientFilterThenCachePushEmitted() throws Exception {
    addUser(new CacheExPolicy(2, 10, null)); // nous émettons
    startServer();

    CountDownLatch pushReceived = new CountDownLatch(1);
    AtomicReference<Camd35FrameCodec.Camd35Frame> pushFrame = new AtomicReference<>();
    byte[] cw = validCw(61);

    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      Camd35FrameCodec.Decoder dec = new Camd35FrameCodec.Decoder(AES_KEY);
      sock.handler(buf -> {
        Camd35FrameCodec.DecodeResult res = dec.feed(buf.getBytes());
        while (res.status() == Camd35FrameCodec.Status.FRAME) {
          var f = res.frame();
          if (f.cmd() == Camd35Constants.CMD_CACHEEX_PUSH) {
            pushFrame.set(f);
            pushReceived.countDown();
          }
          res = dec.feed(new byte[0]);
        }
      });
      sock.write(Buffer.buffer(Camd35FrameCodec.encode(keepalive(), AES_KEY)));
      // Annoncer notre filtre accept-all (0x3C) pour recevoir les push
      sock.write(Buffer.buffer(Camd35FrameCodec.encode(
          new Camd35FrameCodec.Camd35Frame(UCRC, 0x3C, 0, 0, 0, 0, 0,
              Camd35CacheExCodec.encodeFilterBody(List.of()), new byte[0]), AES_KEY)));
    });

    Thread.sleep(800); // laisser auth + filtre arriver
    EcmKey key = new EcmKey(0x80, SRVID, 0, CAID, DATA_HASH);
    CwReply reply = new CwReply(key, 0x80, cw, "other-conn", "10.9.9.9:1",
        System.currentTimeMillis());
    EcmRequest synthetic = new EcmRequest(key, new byte[0], "default", "10.9.9.9:1",
        "other-conn", System.currentTimeMillis(), null, 5000);
    cacheVerticle.handleReply(synthetic, reply);

    assertTrue(pushReceived.await(8, TimeUnit.SECONDS), "PUSH 0x3F attendu");
    Camd35CacheExCodec.Camd35Push push = Camd35CacheExCodec.parsePush(pushFrame.get());
    assertEquals(CAID, push.caid());
    assertEquals(SRVID, push.srvid());
    assertArrayEquals(cw, push.cw());
    assertEquals(0, push.rc());
    assertEquals(1, push.nodeIds().size(), "chemin = notre node id de session");

    var pair = statsRegistry.snapshot().stream()
        .filter(p -> p.peerId().equals("L9:" + USER)).findFirst().orElseThrow();
    assertEquals(2, pair.mode());
    assertEquals(1, pair.pushedCw());
  }
}
