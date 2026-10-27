package com.bowman.cspng.worker;

import com.bowman.cspng.codec.cccam.CcCacheExCodec;
import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.codec.cccam.CcConstants;
import com.bowman.cspng.codec.cccam.CcCrypt;
import com.bowman.cspng.codec.cccam.CcFrameCodec;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B1 : CW-cycle sur worker de bout en bout — frozen/swap/old refusés + failover
 * (pas de CW servie), log autorise, off rétrocompatible, reconfig sans reset TTL.
 */
@DisplayName("Worker CW-cycle end-to-end (B1)")
class WorkerCwCycleEndToEndTest {

  private static final String USER = "wcxuser";
  private static final String PASS = "wcxpass";
  private static final int CAID = 0x0100;
  private static final int PRID = 0x00006A;
  private static final int SRVID = 0x1234;
  private static final int CARD_ID = 0x2222;
  private static final byte[] CLIENT_NODE_ID = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88};
  private static final int LISTENER_PORT = 14341;

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

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    pwdFile = Files.createTempFile("wcx-cw", ".pwd");
    Files.writeString(pwdFile, PASS);
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

  private RelayWorkerContract.DesiredWorkerConfig desired(
      long version, long expiresAt, RelayWorkerContract.CwCycleView cw) {
    long now = System.currentTimeMillis();
    var listeners = List.of(new RelayWorkerContract.ListenerView(
        31L, "default", "cccam", LISTENER_PORT, "127.0.0.1",
        "manual", 0, 512, 32, 5, 60, null, null));
    var caches = List.of(new RelayWorkerContract.CacheView("c1", "local", true, 8000L, 10000, 512));
    var links = List.of(new RelayWorkerContract.LinkView(31L, "c1", true, true, false, true));
    var users = List.of(new RelayWorkerContract.UserAclView(USER, true, List.of("default"),
        List.of(), Map.of(), Map.of()));
    var secrets = List.of(new RelayWorkerContract.UserSecretView(USER, "cccam",
        "file:" + pwdFile.toAbsolutePath(), 1L, true, 3, 10, "0100:00006A"));
    if (cw == null) {
      return new RelayWorkerContract.DesiredWorkerConfig("peer-w", version, expiresAt, 30000L,
          listeners, caches, links, List.of(), users, secrets,
          List.of(), List.of(), List.of(), null);
    }
    return new RelayWorkerContract.DesiredWorkerConfig("peer-w", version, expiresAt, 30000L,
        listeners, caches, links, List.of(), users, secrets,
        List.of(), List.of(), List.of(), null, cw);
  }

  /** Session cliente persistante : handshake, puis envoi de push à la demande. */
  private static final class Client {
    final NetSocket sock;
    final CcCrypt cliEnc = new CcCrypt();
    final CcCrypt cliDec = new CcCrypt();
    Buffer acc = Buffer.buffer();
    final AtomicInteger pushesSent = new AtomicInteger();

    Client(NetSocket sock) {
      this.sock = sock;
    }

    void handshake(String user, String pass, Runnable onReady) {
      sock.handler(data -> {
        acc.appendBuffer(data);
        if (acc.length() < 16) return;
        byte[] seed = acc.getBytes(0, 16);
        acc = Buffer.buffer(acc.getBytes(16, acc.length()));
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
        System.arraycopy(user.getBytes(), 0, userBuf, 0, user.length());
        cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
        sock.write(Buffer.buffer(userBuf));
        byte[] pwd = pass.getBytes();
        cliEnc.crypt(pwd, pwd.length, CcConstants.ENCRYPT);
        byte[] magic = Arrays.copyOf(CcConstants.CCCAM_MAGIC, 6);
        cliEnc.crypt(magic, 6, CcConstants.ENCRYPT);
        sock.write(Buffer.buffer(magic));
        sock.handler(data2 -> {
          acc.appendBuffer(data2);
          if (acc.length() < 20) return;
          byte[] ack = acc.getBytes(0, 20);
          cliDec.crypt(ack, 20, CcConstants.DECRYPT);
          acc = Buffer.buffer(acc.getBytes(20, acc.length()));
          byte[] cliData = CcCardCodec.buildCliData(user, CLIENT_NODE_ID, "2.3.2", "4000");
          byte[] cliFrame = CcFrameCodec.encode(CcConstants.MSG_CLI_DATA, cliData);
          cliEnc.crypt(cliFrame, cliFrame.length, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(cliFrame));
          onReady.run();
        });
      });
    }

    void sendPush(int caid, int prid, int srvid, int oddEven, int dataHash, byte[] cw,
                  int cycleTimeSec) {
      byte[] push = CcCacheExCodec.encodePush(caid, prid, srvid, 0, oddEven,
          new byte[16], dataHash, cw, List.of(CLIENT_NODE_ID), cycleTimeSec, false);
      byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CACHE_PUSH, push);
      cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
      sock.write(Buffer.buffer(frame));
      pushesSent.incrementAndGet();
    }
  }

  @Test
  @DisplayName("B1 : frozen/swap refusés, log autorise, off rétrocompatible, reconfig sans reset TTL")
  void cwCycleWorkerMatrix() throws Exception {
    var cwView = new RelayWorkerContract.CwCycleView(
        true, List.of(), 500, 15, "drop", true, true);
    WorkerState state = new WorkerState("peer-w");
    WorkerRuntime runtime = new WorkerRuntime(vertx, "peer-w", state);
    long now = System.currentTimeMillis();
    assertTrue(runtime.applyDesired(desired(1L, now + 60000L, cwView),
        System.currentTimeMillis()).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));

    client = vertx.createNetClient();
    CountDownLatch ready = new CountDownLatch(1);
    CountDownLatch filterSeen = new CountDownLatch(1);
    AtomicReference<Client> cli = new AtomicReference<>();
    client.connect(LISTENER_PORT, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      Client c = new Client(ar.result());
      cli.set(c);
      c.handshake(USER, PASS, () -> {
        // Après CLI_DATA, le serveur envoie SRV_DATA + FILTER 0x82 : attendre le filtre
        // (preuve que la session est en état normal) avant de pusher.
        c.sock.handler(data -> {
          c.acc.appendBuffer(data);
          while (c.acc.length() >= 4) {
            byte[] hdr = c.acc.getBytes(0, 4);
            c.cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
            int[] decoded = CcFrameCodec.decodeHeader(hdr);
            int total = 4 + decoded[2];
            if (c.acc.length() < total) return;
            // Déchiffrer AUSSI le payload skippé (sinon désync du stream RC4).
            byte[] skipped = c.acc.getBytes(4, total);
            c.cliDec.crypt(skipped, skipped.length, CcConstants.DECRYPT);
            c.acc = Buffer.buffer(c.acc.getBytes(total, c.acc.length()));
            if (decoded[1] == CcConstants.MSG_CACHE_FILTER) filterSeen.countDown();
          }
        });
        ready.countDown();
      });
    });
    assertTrue(ready.await(10, TimeUnit.SECONDS), "handshake");
    assertTrue(filterSeen.await(10, TimeUnit.SECONDS), "filtre 0x82 (session prête)");
    Client c = cli.get();

    byte[] cwA = validCw(41);
    int hash = 0xA11CE001;
    // 1) push initial accepté (apprentissage, cycletime pair 2 s)
    c.sendPush(CAID, PRID, SRVID, 0x80, hash, cwA, 2);
    Thread.sleep(700);
    assertEquals(1, pairStats(runtime, "L31:" + USER).receivedCw(), "push initial accepté");

    // 2) renouvellement TTL même version => PAS de reset d'historique
    // (applyDesired retourne false = renouvelé sans ré-appliquer, futur succédé).
    runtime.applyDesired(desired(1L, System.currentTimeMillis() + 60000L, cwView),
        System.currentTimeMillis()).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

    // 3) même CW même parité après le cycle appris (2 s) => FROZEN rejeté
    Thread.sleep(2600);
    c.sendPush(CAID, PRID, SRVID, 0x80, hash, cwA, 2);
    Thread.sleep(700);
    var pair = pairStats(runtime, "L31:" + USER);
    assertEquals(1, pair.receivedCw(), "push frozen rejeté (received inchangé)");
    assertEquals(1, pair.droppedCw(), "drop compté");
    assertEquals(1L, pair.dropReasons().get("invalid"), "rejet cycle => invalid");

    var cwStats = runtime.collectStats().cwCycle();
    assertNotNull(cwStats, "stats cw-cycle remontées (honnêtes, pas de zéros fictifs)");
    assertTrue(cwStats.enabled());
    assertTrue(cwStats.badCycles() >= 1, "badCycles réel");
    assertTrue(cwStats.dropped() >= 1, "dropped réel");

    // 4) SWAP immédiat : même CW parité opposée => rejeté sans attente
    c.sendPush(CAID, PRID, SRVID, 0x81, hash, cwA, 2);
    Thread.sleep(700);
    assertEquals(2, pairStats(runtime, "L31:" + USER).droppedCw(), "swap rejeté");

    // 5) reconfig log : la reconfig recrée le checker (historique réinitialisé —
    // sémantique voulue : seuils changés) ; on établit un historique frais sous log :
    // odd cwB accepté, puis even cwB = SWAP détecté mais AUTORISÉ (compteur logged).
    var logView = new RelayWorkerContract.CwCycleView(
        true, List.of(), 500, 15, "log", true, true);
    assertTrue(runtime.applyDesired(desired(2L, System.currentTimeMillis() + 60000L, logView),
        System.currentTimeMillis()).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
    byte[] cwB = validCw(43);
    c.sendPush(CAID, PRID, SRVID, 0x80, hash + 1, cwB, 2);
    Thread.sleep(700);
    assertEquals(2, pairStats(runtime, "L31:" + USER).receivedCw(), "log mode : 1er push accepté");
    c.sendPush(CAID, PRID, SRVID, 0x81, hash + 1, cwB, 2);
    Thread.sleep(700);
    assertEquals(3, pairStats(runtime, "L31:" + USER).receivedCw(), "log mode : swap autorisé");
    assertTrue(runtime.collectStats().cwCycle().logged() >= 1, "logged réel");

    // 6) off rétrocompatible (desired SANS champ cwCycle, ctor 14 args) : tout passe,
    // stats disabled honnêtes
    assertTrue(runtime.applyDesired(desired(3L, System.currentTimeMillis() + 60000L, null),
        System.currentTimeMillis()).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
    c.sendPush(CAID, PRID, SRVID, 0x80, hash, cwA, 2);
    Thread.sleep(700);
    assertEquals(4, pairStats(runtime, "L31:" + USER).receivedCw(), "off : accepté");
    var offStats = runtime.collectStats().cwCycle();
    assertNotNull(offStats);
    assertFalse(offStats.enabled(), "stats reflètent off (pas de zéros fictifs)");
  }

  private static RelayWorkerContract.CacheExPairStatsView pairStats(
      WorkerRuntime runtime, String peerId) {
    return runtime.collectStats().cacheex().stream()
        .filter(p -> p.peerId().equals(peerId)).findFirst()
        .orElseThrow(() -> new AssertionError("paire attendue : " + peerId));
  }
}
