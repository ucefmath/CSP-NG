package com.bowman.cspng.worker;

import com.bowman.cspng.codec.cccam.CcCacheExCodec;
import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.codec.cccam.CcConstants;
import com.bowman.cspng.codec.cccam.CcCrypt;
import com.bowman.cspng.codec.cccam.CcFrameCodec;
import com.bowman.cspng.codec.cccam.CcServerCodec;
import com.bowman.cspng.config.RelayWorkerContract;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CacheEx worker de bout en bout (C1) — trafic observable réel :
 * <ol>
 *   <li>session entrante mode 3 : PUSH client → store pont → ECM servi (lookup) ;</li>
 *   <li>connecteur sortant mode 3 (desired) : login réel vers faux serveur →
 *       onReply → PUSH 0x81 observable sur le wire + compteurs collectStats.</li>
 * </ol>
 */
@DisplayName("Worker CacheEx — trafic end-to-end observable")
class WorkerCacheExEndToEndTest {

  private static final String USER = "wcxuser";
  private static final String PASS = "wcxpass";
  private static final int CAID = 0x0100;
  private static final int PRID = 0x00006A;
  private static final int SRVID = 0x1234;
  private static final int CARD_ID = 0x2222;
  private static final byte[] CLIENT_NODE_ID = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88};
  private static final int LISTENER_PORT = 14331;

  private Vertx vertx;
  private NetClient client;
  private Path pwdFile;

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

  /** Hash ECM amputé (même algo que les sessions : 31*h+byte sur body[3..]). */
  static int ecmHash(byte[] section) {
    int h = 0;
    for (int i = 3; i < section.length; i++) h = 31 * h + (section[i] & 0xFF);
    return h;
  }

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    pwdFile = Files.createTempFile("wcx-test", ".pwd");
    Files.writeString(pwdFile, PASS);
    try {
      java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
          java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
              java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
      Files.setPosixFilePermissions(pwdFile, perms);
    } catch (UnsupportedOperationException ignored) {
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    if (client != null) client.close();
    if (pwdFile != null) Files.deleteIfExists(pwdFile);
    if (vertx != null) {
      CountDownLatch l = new CountDownLatch(1);
      vertx.close(v -> l.countDown());
      l.await(10, TimeUnit.SECONDS);
    }
  }

  private RelayWorkerContract.DesiredWorkerConfig desiredWithSecrets(
      List<RelayWorkerContract.ConnectorView> connectors) {
    long now = System.currentTimeMillis();
    var listeners = List.of(new RelayWorkerContract.ListenerView(
        21L, "default", "cccam", LISTENER_PORT, "127.0.0.1",
        "manual", 0, 512, 32, 5, 60, null, null));
    var caches = List.of(new RelayWorkerContract.CacheView("c1", "local", true, 8000L, 10000, 512));
    var links = List.of(new RelayWorkerContract.LinkView(21L, "c1", true, true, false, true));
    var users = List.of(new RelayWorkerContract.UserAclView(USER, true, List.of("default"),
        List.of(), Map.of(), Map.of()));
    var secrets = List.of(new RelayWorkerContract.UserSecretView(USER, "cccam",
        "file:" + pwdFile.toAbsolutePath(), 1L, true, 3, 10, "0100:00006A"));
    return new RelayWorkerContract.DesiredWorkerConfig("peer-w", 1L, now + 60000L, 30000L,
        listeners, caches, links, connectors, users, secrets,
        List.of(), List.of(), List.of(), null);
  }

  /** Handshake client CCcam complet ; onReady après SRV_DATA. */
  private void doHandshake(NetSocket sock, CcCrypt cliEnc, CcCrypt cliDec,
                           Buffer[] accHolder, Runnable onReady) {
    sock.handler(data -> {
      accHolder[0].appendBuffer(data);
      Buffer acc = accHolder[0];
      if (acc.length() < 16) return;
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

  private volatile WorkerRuntime testRuntime;

  @Test
  @DisplayName("Mode 3 entrant : PUSH client → ECM servi (lookup pont) + compteurs")
  void inboundPushThenEcmServed() throws Exception {
    WorkerState state = new WorkerState("peer-w");
    WorkerRuntime runtime = new WorkerRuntime(vertx, "peer-w", state);
    testRuntime = runtime;
    assertTrue(runtime.applyDesired(desiredWithSecrets(List.of()),
        System.currentTimeMillis()).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));

    // Section ECM choisie d'abord => dataHash connu pour le PUSH
    byte[] payload = new byte[37];
    for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i + 5);
    byte[] section = new byte[3 + payload.length];
    section[0] = (byte) 0x80;
    section[1] = (byte) (0x70 | ((payload.length >> 8) & 0x0F));
    section[2] = (byte) (payload.length & 0xFF);
    System.arraycopy(payload, 0, section, 3, payload.length);
    int dataHash = ecmHash(section);
    byte[] cw = validCw(77);

    CountDownLatch ecmDone = new CountDownLatch(1);
    AtomicReference<byte[]> cwGot = new AtomicReference<>();
    client = vertx.createNetClient();

    client.connect(LISTENER_PORT, "127.0.0.1", ar -> {
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
            byte[] pl = acc[0].getBytes(4, total);
            cliDec.crypt(pl, decoded[2], CcConstants.DECRYPT);
            acc[0] = Buffer.buffer(acc[0].getBytes(total, acc[0].length()));
            if (decoded[1] == CcConstants.MSG_CACHE_FILTER) {
              // Notre filtre reçu : envoyer le PUSH puis l'ECM (hors event-loop)
              byte[] push = CcCacheExCodec.encodePush(CAID, PRID, SRVID, 0, 0x80,
                  new byte[16], dataHash, cw, List.of(CLIENT_NODE_ID));
              byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CACHE_PUSH, push);
              cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
              sock.write(Buffer.buffer(frame));
              new Thread(() -> {
                try {
                  Thread.sleep(600); // laisser l'injection se faire
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                byte[] req = CcCardCodec.buildEcmRequest(CAID, PRID, CARD_ID, SRVID, section);
                byte[] ecmFrame = CcFrameCodec.encode(CcConstants.MSG_CW_ECM, req);
                cliEnc.crypt(ecmFrame, ecmFrame.length, CcConstants.ENCRYPT);
                sock.write(Buffer.buffer(ecmFrame));
              }).start();
            } else if (decoded[1] == CcConstants.MSG_CW_ECM && pl.length >= 16) {
              byte[] clear = Arrays.copyOf(pl, 16);
              com.bowman.cspng.codec.cccam.CcCwCrypt.cwCrypt(clear, CLIENT_NODE_ID, CARD_ID);
              byte[] sync = clear.clone();
              cliDec.crypt(sync, 16, CcConstants.ENCRYPT);
              cwGot.set(clear);
              ecmDone.countDown();
            }
          }
        });
      });
    });

    assertTrue(ecmDone.await(10, TimeUnit.SECONDS), "ECM servi depuis le PUSH");
    assertArrayEquals(cw, cwGot.get(), "la CW servie est celle du PUSH (lookup pont)");

    // Compteurs remontés : paire inbound avec receivedCw
    var stats = runtime.collectStats();
    assertNotNull(stats.cacheex());
    var pair = stats.cacheex().stream()
        .filter(p -> p.peerId().equals("L21:" + USER)).findFirst().orElseThrow(
            () -> new AssertionError("paire inbound attendue dans collectStats"));
    assertEquals("inbound", pair.direction());
    assertEquals("cccam", pair.protocol());
    assertEquals(3, pair.mode());
    assertEquals(1, pair.receivedCw());
  }

  // ============================================================
  // Test B : connecteur sortant mode 3 (desired) -> faux serveur
  // ============================================================

  @Test
  @DisplayName("Mode 3 sortant : login réel + onReply -> PUSH 0x81 observable + compteurs")
  void outboundConnectorEmitsToUpstream() throws Exception {
    // Faux serveur CCcam amont
    CountDownLatch serverReady = new CountDownLatch(1);
    CountDownLatch pushSeen = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean srvDataSent = new java.util.concurrent.atomic.AtomicBoolean();
    AtomicReference<byte[]> pushPayload = new AtomicReference<>();
    AtomicReference<byte[]> filterPayload = new AtomicReference<>();
    int[] upstreamPort = new int[1];
    io.vertx.core.net.NetServer fakeUpstream = vertx.createNetServer();
    byte[] serverNode = {0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48};
    fakeUpstream.connectHandler(sock -> {
      CcCrypt srvEnc = new CcCrypt();
      CcCrypt srvDec = new CcCrypt();
      Buffer[] acc = new Buffer[]{Buffer.buffer()};
      int[] step = {0};
      byte[] seed = CcServerCodec.generateSeed(new java.security.SecureRandom());
      sock.write(Buffer.buffer(seed));
      CcServerCodec.initServerHandshake(seed.clone(), srvEnc, srvDec);
      sock.handler(data -> {
        acc[0].appendBuffer(data);
        // Login CCcam : hash(20) + user(20) + token magic(6) = 46 octets.
        // Le mot de passe n'est JAMAIS transmis (zero-knowledge) : le token est
        // vérifié via checkPasswordToken qui avance la crypto avec le mot de passe
        // connu, exactement comme CcCamSession.
        if (step[0] == 0 && acc[0].length() >= 46) {
          byte[] blob = acc[0].getBytes(0, 46);
          // hash(20) + user(20) déchiffrés ; le mot de passe n'est JAMAIS transmis :
          // on avance la crypto avec le mot de passe connu (miroir zero-knowledge),
          // puis on déchiffre le token => magic. Exactement comme CcCamSession.
          srvDec.crypt(blob, 40, CcConstants.DECRYPT);
          acc[0] = Buffer.buffer(acc[0].getBytes(46, acc[0].length()));
          byte[] pwdBytes = PASS.getBytes();
          srvDec.crypt(pwdBytes, pwdBytes.length, CcConstants.ENCRYPT);
          byte[] token = java.util.Arrays.copyOfRange(blob, 40, 46);
          srvDec.crypt(token, token.length, CcConstants.DECRYPT);
          for (int i = 0; i < 6; i++) {
            assertEquals(CcConstants.CCCAM_MAGIC[i], token[i], "magic CCcam");
          }
          byte[] ack = CcServerCodec.buildLoginAck();
          srvEnc.crypt(ack, ack.length, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(ack));
          step[0] = 1;
        } else if (step[0] == 1 && acc[0].length() >= 4) {
          byte[] hdr = acc[0].getBytes(0, 4);
          srvDec.crypt(hdr, 4, CcConstants.DECRYPT);
          int[] decoded = CcFrameCodec.decodeHeader(hdr);
          int total = 4 + decoded[2];
          if (acc[0].length() < total || total > 2048) return;
          byte[] pl = acc[0].getBytes(4, total);
          srvDec.crypt(pl, decoded[2], CcConstants.DECRYPT);
          acc[0] = Buffer.buffer(acc[0].getBytes(total, acc[0].length()));
          if (decoded[1] == CcConstants.MSG_CACHE_PUSH) {
            // Push précoce (émis avant CLI_DATA — tolérable côté serveur réel) :
            // capturé sans faire avancer le login.
            pushPayload.set(pl);
            pushSeen.countDown();
            return;
          }
          if (decoded[1] == CcConstants.MSG_CLI_DATA) {
            byte[] emptyAck = CcFrameCodec.encode(CcConstants.MSG_CLI_DATA, new byte[0]);
            srvEnc.crypt(emptyAck, emptyAck.length, CcConstants.ENCRYPT);
            sock.write(Buffer.buffer(emptyAck));
            byte[] srvData = CcServerCodec.buildSrvData(serverNode, "2.3.2", "4000");
            byte[] srvFrame = CcFrameCodec.encode(CcConstants.MSG_SRV_DATA, srvData);
            srvEnc.crypt(srvFrame, srvFrame.length, CcConstants.ENCRYPT);
            sock.write(Buffer.buffer(srvFrame));
            srvDataSent.set(true);
            step[0] = 2;
          }
        } else if (step[0] == 2) {
          while (acc[0].length() >= 4) {
            byte[] hdr = acc[0].getBytes(0, 4);
            srvDec.crypt(hdr, 4, CcConstants.DECRYPT);
            int[] decoded = CcFrameCodec.decodeHeader(hdr);
            int total = 4 + decoded[2];
            if (acc[0].length() < total) return;
            byte[] pl = acc[0].getBytes(4, total);
            srvDec.crypt(pl, decoded[2], CcConstants.DECRYPT);
            acc[0] = Buffer.buffer(acc[0].getBytes(total, acc[0].length()));
            System.err.println("DIAG-FAKE step2 cmd=0x" + Integer.toHexString(decoded[1]));
            if (decoded[1] == CcConstants.MSG_CACHE_FILTER) {
              filterPayload.set(pl);
            } else if (decoded[1] == CcConstants.MSG_CACHE_PUSH) {
              pushPayload.set(pl);
              pushSeen.countDown();
            }
          }
        }
      });
    });
    fakeUpstream.listen(0, "127.0.0.1", ar -> {
      upstreamPort[0] = ar.result().actualPort();
      serverReady.countDown();
    });
    assertTrue(serverReady.await(5, TimeUnit.SECONDS));

    // Desired worker : connecteur cccam mode 3 vers le faux serveur
    long now = System.currentTimeMillis();
    var connectors = List.of(new RelayWorkerContract.ConnectorView(
        "cx-up", "cccam", "default", true, "127.0.0.1", upstreamPort[0],
        USER, "file:" + pwdFile.toAbsolutePath(), null, null, 5000,
        "2.3.2", 0, false, null, "legacy", 3, "plaintext",
        null, 2000, 10));
    var listeners = List.of(new RelayWorkerContract.ListenerView(
        22L, "default", "cccam", 14332, "127.0.0.1",
        "manual", 0, 512, 32, 5, 60, null, null));
    var caches = List.of(new RelayWorkerContract.CacheView("c1", "local", true, 8000L, 10000, 512));
    var links = List.of(new RelayWorkerContract.LinkView(22L, "c1", true, true, false, true));
    var users = List.of(new RelayWorkerContract.UserAclView(USER, true, List.of("default"),
        List.of(), Map.of(), Map.of()));
    var secrets = List.of(new RelayWorkerContract.UserSecretView(USER, "cccam",
        "file:" + pwdFile.toAbsolutePath(), 1L, true, null, null, null));
    var desired = new RelayWorkerContract.DesiredWorkerConfig("peer-w", 1L, now + 60000L, 30000L,
        listeners, caches, links, connectors, users, secrets,
        List.of(), List.of(), List.of(), null);

    WorkerState state = new WorkerState("peer-w");
    WorkerRuntime runtime = new WorkerRuntime(vertx, "peer-w", state);
    assertTrue(runtime.applyDesired(desired, System.currentTimeMillis())
        .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));

    // Attendre la FIN du login (SRV_DATA émis par le faux serveur) avant onReply,
    // sinon le push peut précéder CLI_DATA sur le wire (race tolérable mais non déterministe)
    long loginDeadline = System.currentTimeMillis() + 8000;
    boolean loggedIn = false;
    com.bowman.cspng.connectors.CcCamConnector cc = null;
    while (System.currentTimeMillis() < loginDeadline && !loggedIn) {
      var c = runtime.connectors().getConnector("cx-up");
      if (c instanceof com.bowman.cspng.connectors.CcCamConnector ccc && ccc.isConnected()
          && srvDataSent.get()) {
        loggedIn = true;
        cc = ccc;
      } else {
        Thread.sleep(100);
      }
    }
    assertTrue(loggedIn, "connecteur worker loggé (login réel complet)");
    assertNotNull(cc);

    // Déclencher l'émission via onReply (CW fraîche du dispatch)
    byte[] cw = validCw(91);
    com.bowman.cspng.domain.EcmKey key =
        new com.bowman.cspng.domain.EcmKey(0x80, SRVID, 0, CAID, 0x0BADF00D);
    com.bowman.cspng.domain.CwReply reply = new com.bowman.cspng.domain.CwReply(
        key, 0x80, cw, "local-src", "10.1.1.1:5", System.currentTimeMillis());
    cc.onReply(reply);

    assertTrue(pushSeen.await(8, TimeUnit.SECONDS), "PUSH 0x81 observable sur le wire");
    CcCacheExCodec.PushRecord rec = CcCacheExCodec.parsePush(pushPayload.get());
    assertEquals(CAID, rec.caid());
    assertEquals(SRVID, rec.srvid());
    assertArrayEquals(cw, rec.cw());
    assertEquals(1, rec.nodeIds().size(), "chemin = node du connecteur worker");

    var pair = runtime.collectStats().cacheex().stream()
        .filter(p -> p.peerId().equals("cx-up")).findFirst().orElseThrow(
            () -> new AssertionError("paire sortante attendue dans collectStats"));
    assertEquals("outbound", pair.direction());
    assertEquals("cccam", pair.protocol());
    assertEquals(3, pair.mode());
    assertEquals(1, pair.pushedCw());

    fakeUpstream.close();
  }
}
