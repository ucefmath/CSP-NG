package com.bowman.cspng.sessions;

import com.bowman.cspng.codec.cccam.*;
import com.bowman.cspng.connectors.routing.StrictCacheKey;
import com.bowman.cspng.connectors.routing.StrictPartitionedCache;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.users.ProtocolAuthResolver;
import com.bowman.cspng.users.SessionEcmRouter;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Inbound Cams Hardening & Robustness Tests (Bloc B & Bloc D)")
class InboundCamsHardeningTest {

  private static final String USER = "ccuser";
  private static final String PASS = "ccpass";
  private static final int CARD_ID = 0x0064C0DE;
  private static final int CAID = 0x0100;
  private static final int SRVID = 0x0001;
  private static final byte[] SERVER_NODE_ID = { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 };
  private static final byte[] CLIENT_NODE_ID = { 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88 };

  private static final byte[] CW_CONST = {
      (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, 0x67, 0x12, 0x34, 0x56, (byte) 0x9C,
      0x78, (byte) 0x9A, (byte) 0xBC, (byte) 0xCE, (byte) 0xDE, (byte) 0xF0, 0x11, (byte) 0xDF
  };

  private Vertx vertx;
  private NetServer server;
  private NetClient client;
  private int serverPort;
  private SessionRegistry registry;
  private ProtocolAuthResolver.Fake authResolver;
  private SecretResolver secretResolver;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    registry = new SessionRegistry(vertx);
    authResolver = new ProtocolAuthResolver.Fake();
    authResolver.add("cccam", USER, new ProtocolAuthResolver.Fake.Entry(
        true, Instant.now().plusSeconds(3600), "file:/run/secrets/ccuser", 1,
        Set.of("default"), Map.of(), Map.of(), Set.of(), null));

    secretResolver = (ref, ctx) -> Future.succeededFuture(PASS);
    client = vertx.createNetClient();
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

  private void startServer(SessionEcmRouter router, int maxInflight, int maxPerIp, int idleTimeoutSec) throws Exception {
    CountDownLatch listenLatch = new CountDownLatch(1);
    server = vertx.createNetServer();
    server.connectHandler(sock -> {
      String ip = sock.remoteAddress().host();
      registry.tryAcquirePreauthIp(ip, maxPerIp);
      AbstractSession.PreauthLease lease = new AbstractSession.PreauthLease(registry, ip);
      CcCamSession session = new CcCamSession(
          vertx, null, registry, authResolver, secretResolver, router, null,
          SERVER_NODE_ID, "manual", 0, 512, maxInflight, maxPerIp, idleTimeoutSec);
      session.setPreauthLease(lease);
      session.accept(sock);
    });

    server.listen(0, "127.0.0.1", ar -> {
      if (ar.succeeded()) {
        serverPort = ar.result().actualPort();
        listenLatch.countDown();
      }
    });
    assertTrue(listenLatch.await(5, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("Bloc B: Révocation mid-route -> Requête en vol résolue après révocation ne délivre AUCUNE CW")
  void midRouteRevocationDoesNotDeliverCw() throws Exception {
    Promise<CwReply> deferredReply = Promise.promise();
    SessionEcmRouter pendingRouter = (req, auth) -> deferredReply.future();

    startServer(pendingRouter, 32, 5, 60);

    CountDownLatch readyForEcm = new CountDownLatch(1);
    CountDownLatch nokOrClosedLatch = new CountDownLatch(1);
    AtomicBoolean cwReceived = new AtomicBoolean(false);
    AtomicReference<NetSocket> socketRef = new AtomicReference<>();
    CcCrypt cliEnc = new CcCrypt();

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      socketRef.set(sock);
      sock.closeHandler(v -> nokOrClosedLatch.countDown());

      Buffer[] accHolder = new Buffer[]{ Buffer.buffer() };
      CcCrypt cliDec = new CcCrypt();
      int[] step = new int[]{0};

      sock.handler(data -> {
        accHolder[0].appendBuffer(data);
        Buffer acc = accHolder[0];

        if (step[0] == 0 && acc.length() >= 16) {
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
          step[0] = 1;
        }

        if (step[0] == 1 && acc.length() >= 20) {
          byte[] ack = acc.getBytes(0, 20);
          cliDec.crypt(ack, 20, CcConstants.DECRYPT);
          accHolder[0] = Buffer.buffer(acc.getBytes(20, acc.length()));

          byte[] cliData = CcCardCodec.buildCliData(USER, CLIENT_NODE_ID, "2.3.2", "4000");
          byte[] cliFrame = CcFrameCodec.encode(CcConstants.MSG_CLI_DATA, cliData);
          cliEnc.crypt(cliFrame, cliFrame.length, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(cliFrame));
          step[0] = 2;
        }

        if (step[0] == 2) {
          while (accHolder[0].length() >= 4) {
            acc = accHolder[0];
            byte[] hdr = acc.getBytes(0, 4);
            cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
            int[] decoded = CcFrameCodec.decodeHeader(hdr);
            int total = 4 + decoded[2];
            if (acc.length() < total) return;

            byte[] payload = acc.getBytes(4, total);
            cliDec.crypt(payload, decoded[2], CcConstants.DECRYPT);
            accHolder[0] = Buffer.buffer(acc.getBytes(total, acc.length()));

            if (decoded[1] == CcConstants.MSG_SRV_DATA) {
              readyForEcm.countDown();
            } else if (decoded[1] == CcConstants.MSG_CW_ECM) {
              cwReceived.set(true);
            } else if (decoded[1] == CcConstants.MSG_CW_NOK1 || decoded[1] == CcConstants.MSG_CW_NOK2) {
              nokOrClosedLatch.countDown();
            }
          }
        }
      });
    });

    assertTrue(readyForEcm.await(5, TimeUnit.SECONDS), "Client prêt");

    // 1. Envoyer ECM
    byte[] ecmPayload = new byte[32];
    byte[] section = CcCardCodec.reconstructSection(0x80, ecmPayload);
    byte[] req = CcCardCodec.buildEcmRequest(CAID, 0, CARD_ID, SRVID, section);
    byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CW_ECM, req);
    cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
    socketRef.get().write(Buffer.buffer(frame));

    // 2. Attendre que l'ECM soit en vol, puis révoquer / expirer l'utilisateur
    Thread.sleep(100);
    authResolver.expire("cccam", USER);

    // 3. Résoudre maintenant la réponse avec une CW
    deferredReply.tryComplete(new CwReply(
        new EcmKey(0x80, SRVID, 0, CAID, 1234), 0x80, CW_CONST, "cws1", "127.0.0.1", System.currentTimeMillis()));

    // 4. Vérifier que la socket reçoit un NOK ou se ferme et ne reçoit JAMAIS la CW
    assertTrue(nokOrClosedLatch.await(5, TimeUnit.SECONDS), "NOK ou fermeture reçue");
    assertFalse(cwReceived.get(), "La CW ne doit JAMAIS être envoyée après révocation mid-route");
  }

  @Test
  @DisplayName("Bloc D: Ordonnancement FIFO strict CCcam (résolution 3-1-2 -> émission 1-2-3)")
  void cccamFifoOrderingGuaranteesSequence() throws Exception {
    Map<Integer, Promise<CwReply>> promises = new HashMap<>();
    promises.put(1, Promise.promise());
    promises.put(2, Promise.promise());
    promises.put(3, Promise.promise());

    SessionEcmRouter router = (req, auth) -> {
      int srv = req.key().serviceId();
      Promise<CwReply> p = promises.get(srv);
      return p != null ? p.future() : Future.succeededFuture(CwReply.empty(req.key()));
    };

    startServer(router, 32, 5, 60);

    CountDownLatch readyLatch = new CountDownLatch(1);
    CountDownLatch replyLatch = new CountDownLatch(3);
    List<Integer> receivedReplies = new ArrayList<>();
    AtomicReference<NetSocket> socketRef = new AtomicReference<>();
    CcCrypt cliEnc = new CcCrypt();

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      socketRef.set(sock);

      Buffer[] accHolder = new Buffer[]{ Buffer.buffer() };
      CcCrypt cliDec = new CcCrypt();
      int[] step = new int[]{0};

      sock.handler(data -> {
        accHolder[0].appendBuffer(data);
        Buffer acc = accHolder[0];

        if (step[0] == 0 && acc.length() >= 16) {
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
          step[0] = 1;
        }

        if (step[0] == 1 && acc.length() >= 20) {
          byte[] ack = acc.getBytes(0, 20);
          cliDec.crypt(ack, 20, CcConstants.DECRYPT);
          accHolder[0] = Buffer.buffer(acc.getBytes(20, acc.length()));

          byte[] cliData = CcCardCodec.buildCliData(USER, CLIENT_NODE_ID, "2.3.2", "4000");
          byte[] cliFrame = CcFrameCodec.encode(CcConstants.MSG_CLI_DATA, cliData);
          cliEnc.crypt(cliFrame, cliFrame.length, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(cliFrame));
          step[0] = 2;
        }

        if (step[0] == 2) {
          while (accHolder[0].length() >= 4) {
            acc = accHolder[0];
            byte[] hdr = acc.getBytes(0, 4);
            cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
            int[] decoded = CcFrameCodec.decodeHeader(hdr);
            int total = 4 + decoded[2];
            if (acc.length() < total) return;

            byte[] payload = acc.getBytes(4, total);
            cliDec.crypt(payload, decoded[2], CcConstants.DECRYPT);
            accHolder[0] = Buffer.buffer(acc.getBytes(total, acc.length()));

            if (decoded[1] == CcConstants.MSG_SRV_DATA) {
              readyLatch.countDown();
            } else if (decoded[1] == CcConstants.MSG_CW_ECM && payload.length >= 16) {
              byte[] clear = payload.clone();
              CcCwCrypt.cwCrypt(clear, CLIENT_NODE_ID, CARD_ID);
              // CW clair commence par l'identifiant seq
              int id = clear[0] & 0xFF;
              receivedReplies.add(id);

              byte[] sync = clear.clone();
              cliDec.crypt(sync, 16, CcConstants.ENCRYPT);
              replyLatch.countDown();
            }
          }
        }
      });
    });

    assertTrue(readyLatch.await(5, TimeUnit.SECONDS));

    // Envoyer ECM 1, ECM 2, ECM 3
    for (int seq = 1; seq <= 3; seq++) {
      byte[] ecmPayload = new byte[32];
      ecmPayload[0] = (byte) seq;
      byte[] section = CcCardCodec.reconstructSection(0x80, ecmPayload);
      byte[] req = CcCardCodec.buildEcmRequest(CAID, 0, CARD_ID, seq, section);
      byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CW_ECM, req);
      cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
      socketRef.get().write(Buffer.buffer(frame));
    }

    Thread.sleep(100);

    // Résoudre dans l'ordre 3, puis 1, puis 2
    byte[] cw3 = CW_CONST.clone(); cw3[0] = 3;
    promises.get(3).tryComplete(new CwReply(new EcmKey(0x80, 3, 0, CAID, 3), 0x80, cw3, "c3", "127.0.0.1", System.currentTimeMillis()));

    byte[] cw1 = CW_CONST.clone(); cw1[0] = 1;
    promises.get(1).tryComplete(new CwReply(new EcmKey(0x80, 1, 0, CAID, 1), 0x80, cw1, "c1", "127.0.0.1", System.currentTimeMillis()));

    byte[] cw2 = CW_CONST.clone(); cw2[0] = 2;
    promises.get(2).tryComplete(new CwReply(new EcmKey(0x80, 2, 0, CAID, 2), 0x80, cw2, "c2", "127.0.0.1", System.currentTimeMillis()));

    assertTrue(replyLatch.await(5, TimeUnit.SECONDS), "Les 3 CWs doivent être reçues");
    assertEquals(List.of(1, 2, 3), receivedReplies, "L'émission doit respecter STRICTEMENT la séquence FIFO 1, 2, 3");
  }

  @Test
  @DisplayName("Bloc D: StrictPartitionedCache capacité stricte & éviction")
  void strictCacheCapacityEviction() {
    StrictPartitionedCache cache = new StrictPartitionedCache(3);
    Set<String> el = Set.of("cws1");

    StrictCacheKey k1 = new StrictCacheKey("default", 0x100, 0, 1, 0x80, new byte[]{1}, 1L, el);
    StrictCacheKey k2 = new StrictCacheKey("default", 0x100, 0, 2, 0x80, new byte[]{2}, 1L, el);
    StrictCacheKey k3 = new StrictCacheKey("default", 0x100, 0, 3, 0x80, new byte[]{3}, 1L, el);
    StrictCacheKey k4 = new StrictCacheKey("default", 0x100, 0, 4, 0x80, new byte[]{4}, 1L, el);

    CwReply r = new CwReply(new EcmKey(0x80, 1, 0, 0x100, 1), 0x80, CW_CONST, "cws1", "127.0.0.1", System.currentTimeMillis());

    cache.put(k1, r, 10_000);
    cache.put(k2, r, 10_000);
    cache.put(k3, r, 10_000);
    assertEquals(3, cache.size());

    // Ingestion de la 4ème clé -> éviction stricte déclenchée
    cache.put(k4, r, 10_000);
    assertTrue(cache.size() <= 3, "La capacité maximale ne doit jamais être dépassée");
  }

  @Test
  @DisplayName("Bloc D: Dépassement maxInflight -> Rejet immédiat avec NOK")
  void maxInflightRejection() throws Exception {
    Promise<CwReply> blocker = Promise.promise();
    SessionEcmRouter router = (req, auth) -> blocker.future();

    // maxInflight = 1
    startServer(router, 1, 5, 60);

    CountDownLatch readyLatch = new CountDownLatch(1);
    CountDownLatch nokLatch = new CountDownLatch(1);
    AtomicReference<NetSocket> socketRef = new AtomicReference<>();
    CcCrypt cliEnc = new CcCrypt();

    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      socketRef.set(sock);
      Buffer[] accHolder = new Buffer[]{ Buffer.buffer() };
      CcCrypt cliDec = new CcCrypt();
      int[] step = new int[]{0};

      sock.handler(data -> {
        accHolder[0].appendBuffer(data);
        Buffer acc = accHolder[0];

        if (step[0] == 0 && acc.length() >= 16) {
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
          step[0] = 1;
        }

        if (step[0] == 1 && acc.length() >= 20) {
          byte[] ack = acc.getBytes(0, 20);
          cliDec.crypt(ack, 20, CcConstants.DECRYPT);
          accHolder[0] = Buffer.buffer(acc.getBytes(20, acc.length()));

          byte[] cliData = CcCardCodec.buildCliData(USER, CLIENT_NODE_ID, "2.3.2", "4000");
          byte[] cliFrame = CcFrameCodec.encode(CcConstants.MSG_CLI_DATA, cliData);
          cliEnc.crypt(cliFrame, cliFrame.length, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(cliFrame));
          step[0] = 2;
        }

        if (step[0] == 2) {
          while (accHolder[0].length() >= 4) {
            acc = accHolder[0];
            byte[] hdr = acc.getBytes(0, 4);
            cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
            int[] decoded = CcFrameCodec.decodeHeader(hdr);
            int total = 4 + decoded[2];
            if (acc.length() < total) return;

            byte[] payload = acc.getBytes(4, total);
            cliDec.crypt(payload, decoded[2], CcConstants.DECRYPT);
            accHolder[0] = Buffer.buffer(acc.getBytes(total, acc.length()));

            if (decoded[1] == CcConstants.MSG_SRV_DATA) {
              readyLatch.countDown();
            } else if (decoded[1] == CcConstants.MSG_CW_NOK1 || decoded[1] == CcConstants.MSG_CW_NOK2) {
              nokLatch.countDown();
            }
          }
        }
      });
    });

    assertTrue(readyLatch.await(5, TimeUnit.SECONDS));

    // 1er ECM envoyé (occupe le slot inFlight = 1)
    byte[] p1 = new byte[32]; p1[0] = 1;
    byte[] s1 = CcCardCodec.reconstructSection(0x80, p1);
    byte[] f1 = CcFrameCodec.encode(CcConstants.MSG_CW_ECM, CcCardCodec.buildEcmRequest(CAID, 0, CARD_ID, 1, s1));
    cliEnc.crypt(f1, f1.length, CcConstants.ENCRYPT);
    socketRef.get().write(Buffer.buffer(f1));

    // 2ème ECM envoyé immédiatement -> quota in-flight (1) dépassé -> rejet NOK
    byte[] p2 = new byte[32]; p2[0] = 2;
    byte[] s2 = CcCardCodec.reconstructSection(0x80, p2);
    byte[] f2 = CcFrameCodec.encode(CcConstants.MSG_CW_ECM, CcCardCodec.buildEcmRequest(CAID, 0, CARD_ID, 2, s2));
    cliEnc.crypt(f2, f2.length, CcConstants.ENCRYPT);
    socketRef.get().write(Buffer.buffer(f2));

    assertTrue(nokLatch.await(5, TimeUnit.SECONDS), "Le 2ème ECM doit être rejeté avec NOK car in-flight saturé");
  }

  @Test
  @DisplayName("Bloc B: Epoch bump périodique avec utilisateur révoqué -> Fermeture de la session en <= 2s")
  void periodicEpochBumpRevocationClosesSession() throws Exception {
    SessionEcmRouter router = (req, auth) -> Future.succeededFuture(CwReply.empty(req.key()));
    startServer(router, 32, 5, 60);

    CountDownLatch readyLatch = new CountDownLatch(1);
    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

      Buffer[] accHolder = new Buffer[]{ Buffer.buffer() };
      CcCrypt cliDec = new CcCrypt();
      CcCrypt cliEnc = new CcCrypt();
      int[] step = new int[]{0};

      sock.handler(data -> {
        accHolder[0].appendBuffer(data);
        Buffer acc = accHolder[0];

        if (step[0] == 0 && acc.length() >= 16) {
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
          step[0] = 1;
        }

        if (step[0] == 1 && acc.length() >= 20) {
          byte[] ack = acc.getBytes(0, 20);
          cliDec.crypt(ack, 20, CcConstants.DECRYPT);
          accHolder[0] = Buffer.buffer(acc.getBytes(20, acc.length()));

          byte[] cliData = CcCardCodec.buildCliData(USER, CLIENT_NODE_ID, "2.3.2", "4000");
          byte[] cliFrame = CcFrameCodec.encode(CcConstants.MSG_CLI_DATA, cliData);
          cliEnc.crypt(cliFrame, cliFrame.length, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(cliFrame));
          step[0] = 2;
        }

        if (step[0] == 2) {
          while (accHolder[0].length() >= 4) {
            acc = accHolder[0];
            byte[] hdr = acc.getBytes(0, 4);
            cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
            int[] decoded = CcFrameCodec.decodeHeader(hdr);
            int total = 4 + decoded[2];
            if (acc.length() < total) return;
            accHolder[0] = Buffer.buffer(acc.getBytes(total, acc.length()));

            if (decoded[1] == CcConstants.MSG_SRV_DATA) {
              readyLatch.countDown();
            }
          }
        }
      });
    });

    assertTrue(readyLatch.await(5, TimeUnit.SECONDS));

    // Révoquer l'utilisateur dans l'authResolver
    authResolver.revoke("cccam", USER);

    // Le timer périodique (1s) doit détecter la révocation et fermer la session en <= 2.5s
    assertTrue(closedLatch.await(3, TimeUnit.SECONDS), "La session doit être fermée par le check d'époque périodique");
  }

  @Test
  @DisplayName("Bloc B: Camd35 / CS378x Révocation mid-route -> aucune CW délivrée")
  void camd35MidRouteRevocationDoesNotDeliverCw() throws Exception {
    Promise<CwReply> deferredReply = Promise.promise();
    SessionEcmRouter pendingRouter = (req, auth) -> deferredReply.future();

    byte[] clientUcrc = new byte[]{ 0x12, 0x34, 0x56, 0x78 };
    long ucrc = 0x12345678L;
    authResolver.add("cs378x", "csuser", new ProtocolAuthResolver.Fake.Entry(
        true, Instant.now().plusSeconds(3600), "file:/run/secrets/csuser", 1,
        Set.of("default"), Map.of(), Map.of(), Set.of(), ucrc));

    CountDownLatch listenLatch = new CountDownLatch(1);
    NetServer csServer = vertx.createNetServer();
    csServer.connectHandler(sock -> {
      Camd35TcpSession session = new Camd35TcpSession(
          vertx, null, registry, authResolver, secretResolver, pendingRouter, 32, 5, 60);
      session.accept(sock);
    });
    AtomicInteger csPort = new AtomicInteger(0);
    csServer.listen(0, "127.0.0.1", ar -> {
      csPort.set(ar.result().actualPort());
      listenLatch.countDown();
    });
    assertTrue(listenLatch.await(5, TimeUnit.SECONDS));

    CountDownLatch readyLatch = new CountDownLatch(1);
    CountDownLatch nokOrCloseLatch = new CountDownLatch(1);
    AtomicBoolean cwReceived = new AtomicBoolean(false);
    AtomicReference<NetSocket> csSocketRef = new AtomicReference<>();
    byte[] aesKey = com.bowman.cspng.codec.camd35.Camd35Crypto.aesKey(PASS);
    com.bowman.cspng.codec.camd35.Camd35FrameCodec.Decoder decoder = new com.bowman.cspng.codec.camd35.Camd35FrameCodec.Decoder(aesKey);

    client.connect(csPort.get(), "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      csSocketRef.set(sock);
      sock.closeHandler(v -> nokOrCloseLatch.countDown());

      // 1. Envoyer ucrc 4 octets en clair
      sock.write(Buffer.buffer(clientUcrc));

      sock.handler(data -> {
        var res = decoder.feed(data.getBytes());
        while (res.status() == com.bowman.cspng.codec.camd35.Camd35FrameCodec.Status.FRAME) {
          var f = res.frame();
          if (f.cmd() == com.bowman.cspng.codec.camd35.Camd35Constants.CMD_CW) {
            cwReceived.set(true);
          } else if (f.cmd() == com.bowman.cspng.codec.camd35.Camd35Constants.CMD_NOK_NOTFOUND) {
            nokOrCloseLatch.countDown();
          }
          res = decoder.feed(new byte[0]);
        }
      });
      readyLatch.countDown();
    });

    assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
    Thread.sleep(100);

    // 2. Envoyer ECM CS378x
    byte[] ecm = new byte[]{ (byte)0x80, 0x70, 0x07, 1, 2, 3, 4, 5, 6, 7 };
    var reqFrame = new com.bowman.cspng.codec.camd35.Camd35FrameCodec.Camd35Frame(
        clientUcrc, com.bowman.cspng.codec.camd35.Camd35Constants.CMD_ECM, SRVID, CAID, 0, 1, 0, ecm, new byte[0]);
    byte[] wire = com.bowman.cspng.codec.camd35.Camd35FrameCodec.encode(reqFrame, aesKey);
    csSocketRef.get().write(Buffer.buffer(wire));

    Thread.sleep(100);

    // 3. Révocation mid-route
    authResolver.expire("cs378x", "csuser");

    // 4. Résolution de la Promise
    deferredReply.tryComplete(new CwReply(
        new EcmKey(0x80, SRVID, 0, CAID, 1234), 0x80, CW_CONST, "cws1", "127.0.0.1", System.currentTimeMillis()));

    assertTrue(nokOrCloseLatch.await(5, TimeUnit.SECONDS), "NOK ou fermeture reçue sur Camd35");
    assertFalse(cwReceived.get(), "Aucune CW ne doit être transmise après révocation mid-route");

    csServer.close();
  }

  @Test
  @DisplayName("Bloc D: PreauthLease exactly-once release : succès, échec et close remettent les compteurs preauth à zéro")
  void preauthLeaseExactlyOnceNoLeakOnSuccessAndFailure() throws Exception {
    SessionEcmRouter router = (req, auth) -> Future.succeededFuture(CwReply.empty(req.key()));
    startServer(router, 32, 2, 60);

    // 1. Connexion avec fermeture brutale du client -> lease libérée
    CountDownLatch failCloseLatch = new CountDownLatch(1);
    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      sock.closeHandler(v -> failCloseLatch.countDown());
      sock.close();
    });
    assertTrue(failCloseLatch.await(5, TimeUnit.SECONDS));
    Thread.sleep(50);
    assertEquals(0, registry.getGlobalPreauthCount(), "Compteur global pre-auth doit être revenu à 0 après échec/close client");

    // 2. Connexion avec succès -> lease libérée à l'auth
    CountDownLatch authLatch = new CountDownLatch(1);
    CountDownLatch successCloseLatch = new CountDownLatch(1);
    AtomicReference<NetSocket> successSockRef = new AtomicReference<>();

    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      successSockRef.set(sock);
      sock.closeHandler(v -> successCloseLatch.countDown());

      Buffer[] accHolder = new Buffer[]{ Buffer.buffer() };
      CcCrypt cliDec = new CcCrypt();
      CcCrypt cliEnc = new CcCrypt();
      int[] step = new int[]{0};

      sock.handler(data -> {
        accHolder[0].appendBuffer(data);
        Buffer acc = accHolder[0];

        if (step[0] == 0 && acc.length() >= 16) {
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
          step[0] = 1;
        }

        if (step[0] == 1 && acc.length() >= 20) {
          byte[] ack = acc.getBytes(0, 20);
          cliDec.crypt(ack, 20, CcConstants.DECRYPT);
          accHolder[0] = Buffer.buffer(acc.getBytes(20, acc.length()));

          byte[] cliData = CcCardCodec.buildCliData(USER, CLIENT_NODE_ID, "2.3.2", "4000");
          byte[] cliFrame = CcFrameCodec.encode(CcConstants.MSG_CLI_DATA, cliData);
          cliEnc.crypt(cliFrame, cliFrame.length, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(cliFrame));
          step[0] = 2;
        }

        if (step[0] == 2) {
          while (accHolder[0].length() >= 4) {
            acc = accHolder[0];
            byte[] hdr = acc.getBytes(0, 4);
            cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
            int[] decoded = CcFrameCodec.decodeHeader(hdr);
            int total = 4 + decoded[2];
            if (acc.length() < total) return;
            accHolder[0] = Buffer.buffer(acc.getBytes(total, acc.length()));

            if (decoded[1] == CcConstants.MSG_SRV_DATA) {
              authLatch.countDown();
            }
          }
        }
      });
    });

    assertTrue(authLatch.await(5, TimeUnit.SECONDS));
    assertEquals(0, registry.getGlobalPreauthCount(), "Compteur global pre-auth doit être revenu à 0 après authentification réussie");

    // Fermeture de la socket
    successSockRef.get().close();
    assertTrue(successCloseLatch.await(5, TimeUnit.SECONDS));
    Thread.sleep(50);
    assertEquals(0, registry.getGlobalPreauthCount(), "Compteur pre-auth toujours à 0 après fermeture normale");
  }

  @Test
  @DisplayName("Bloc D: allowedEcmRate configuré sur l'utilisateur est partagé et agrégé entre sessions distinctes")
  void userRateLimitAggregatedAcrossSessions() {
    // allowedEcmRate = 2 pour userRateTest
    String u = "rateUser";
    authResolver.add("cccam", u, new ProtocolAuthResolver.Fake.Entry(
        true, Instant.now().plusSeconds(3600), "file:/run/secrets/rateUser", 1,
        Set.of("default"), Map.of(), Map.of(), Set.of(), null, 2));

    // Session 1 consomme 1 requête
    assertTrue(registry.checkUserRateLimit(u, 2), "1ère requête autorisée");
    // Session 2 consomme 1 requête
    assertTrue(registry.checkUserRateLimit(u, 2), "2ème requête autorisée (quota atteint 2/2)");
    // 3ème requête tentée par Session 1 ou 2 -> Bloquée !
    assertFalse(registry.checkUserRateLimit(u, 2), "3ème requête bloquée par rate limit global partagé");

    // Un autre utilisateur sans rate limit (ou avec allowedEcmRate=-1) n'est pas impacté
    String other = "unlimitedUser";
    assertTrue(registry.checkUserRateLimit(other, -1), "Utilisateur illimité (-1) jamais bloqué");
    assertTrue(registry.checkUserRateLimit(other, 0), "Utilisateur illimité (0) jamais bloqué");
  }

  @Test
  @DisplayName("Bloc D: Cycle de 505 connexions successives sans fuite de PreauthLease (dépasse capacité 500 sans blocage)")
  void lifecycle505ConnectionsNoPreauthLeak() {
    String ip = "192.168.1.100";
    for (int i = 0; i < 505; i++) {
      assertTrue(registry.tryAcquirePreauthIp(ip, 50), "Acquisition slot preauth " + i);
      assertEquals(1, registry.getGlobalPreauthCount());
      AbstractSession.PreauthLease lease = new AbstractSession.PreauthLease(registry, ip);

      // Simule soit libération à l'auth, soit libération à la clôture
      if (i % 2 == 0) {
        lease.release();
      } else {
        lease.close();
      }
      assertEquals(0, registry.getGlobalPreauthCount(), "Compteur doit être à 0 après libération du slot " + i);
    }
  }

  @Test
  @DisplayName("Bloc D: RadegastSession et NewcamdSession libèrent exactement leur PreauthLease")
  void radegastAndNewcamdPreauthLeaseRelease() {
    String ip = "10.0.0.50";

    // 1. RadegastSession
    assertTrue(registry.tryAcquirePreauthIp(ip, 5));
    AbstractSession.PreauthLease radegastLease = new AbstractSession.PreauthLease(registry, ip);
    RadegastSession radSession = new RadegastSession(vertx, null, registry);
    radSession.setPreauthLease(radegastLease);
    radSession.close();
    assertEquals(0, registry.getGlobalPreauthCount(), "PreauthLease libérée après fermeture Radegast");

    // 2. NewcamdSession
    assertTrue(registry.tryAcquirePreauthIp(ip, 5));
    AbstractSession.PreauthLease newcamdLease = new AbstractSession.PreauthLease(registry, ip);
    byte[] key14 = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};
    NewcamdSession newcamdSession = new NewcamdSession(vertx, null, registry, key14);
    newcamdSession.setPreauthLease(newcamdLease);
    newcamdSession.close();
    assertEquals(0, registry.getGlobalPreauthCount(), "PreauthLease libérée après fermeture Newcamd");
  }

  private static byte[] sha1(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-1").digest(data);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
