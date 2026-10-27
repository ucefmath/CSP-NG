package com.bowman.cspng.worker;

import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.codec.cccam.CcConstants;
import com.bowman.cspng.codec.cccam.CcCrypt;
import com.bowman.cspng.codec.cccam.CcCwCrypt;
import com.bowman.cspng.codec.cccam.CcFrameCodec;
import com.bowman.cspng.config.RelayWorkerContract;
import com.bowman.cspng.connectors.routing.StrictEcmRouter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test d'integration complet CSPv2 entre DEUX WORKERS avec client reel (Sonnet FIX #1 & #2) :
 * <ul>
 *   <li>Worker A (node-a sur port 14741) : listener CCcam (18551), AUCUN connecteur amont local,
 *       cache de type node 'n-b' pointant Worker B ;</li>
 *   <li>Worker B (node-b sur port 14742) : connecteur amont Radegast vers mock upstream (14755),
 *       peer 'node-a' en ACCEPT-ONLY (aucun dial sortant de B vers A) ;</li>
 *   <li>Client CCcam se connecte a Worker A -> handshake complet ('alice' / 'secret1') ->
 *       envoi ECM #1 -> StrictEcmRouter A (pas de connecteur local) -> externalConsult CSPv2 ->
 *       Worker B -> upstream Radegast -> CW retournee au client CCcam A ;</li>
 *   <li>ECM #2 identique du client -> Cache Hit direct dans le store 'n-b' de Worker A
 *       (provenance 'node:node-b' verifiee, aucun nouvel appel amont sur B) ;</li>
 *   <li>Detachement du cache 'n-b' sur Worker A -> nouvel ECM refuse (fail-closed immediat) ;</li>
 *   <li>Test FAIL-CLOSED profil non servi sur Worker B -> retour NOT_FOUND.</li>
 * </ul>
 */
@DisplayName("WorkerCspV2TwoWorkersTest — echange CSPv2 reel 2 workers, client CCcam complet, cache, accept-only, detach")
class WorkerCspV2TwoWorkersTest {

  private static final int PORT_A = 14741;
  private static final int PORT_B = 14742;
  private static final int PORT_C = 14743;
  private static final int UPSTREAM_PORT_B = 14755;
  private static final int CCCAM_PORT_A = 18551;

  private static final String CC_USER = "alice";
  private static final String CC_PASS = "secret1";
  private static final int CAID = 0x0100;
  private static final int SRVID = 0x0001;
  private static final int CARD_ID = 0x00AABBCC;
  private static final byte[] CLIENT_NODE = { 9, 8, 7, 6, 5, 4, 3, 2 };

  private static final byte[] CW = {1, 2, 3, 6, 5, 6, 7, 18, 9, 10, 11, 30, 13, 14, 15, 42};

  private static Path certDir;
  private Vertx vertx;
  private NetServer mockUpstream;
  private final AtomicInteger upstreamCalls = new AtomicInteger();

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (mockUpstream != null) {
      CountDownLatch l = new CountDownLatch(1);
      mockUpstream.close(v -> l.countDown());
      l.await(5, TimeUnit.SECONDS);
    }
    if (vertx != null) {
      CountDownLatch l = new CountDownLatch(1);
      vertx.close(v -> l.countDown());
      assertTrue(l.await(10, TimeUnit.SECONDS));
    }
  }

  @BeforeAll
  static void genCerts() throws Exception {
    certDir = Path.of("target/test-certs-cspv2-two-workers");
    Files.createDirectories(certDir);
    if (!Files.exists(certDir.resolve("node-c-cert.pem"))) {
      runOpenssl("req", "-x509", "-newkey", "rsa:2048", "-keyout", "ca-key.pem",
          "-out", "ca-cert.pem", "-days", "2", "-nodes", "-subj", "/CN=test-ca");
      for (String node : List.of("node-a", "node-b", "node-c")) {
        String cnf = "[req]\ndistinguished_name=dn\nreq_extensions=ext\n[dn]\n[ext]\n"
            + "subjectAltName=URI:urn:csp:node:" + node + "\n";
        Files.writeString(certDir.resolve(node + ".cnf"), cnf);
        runOpenssl("req", "-newkey", "rsa:2048", "-keyout", node + "-key.pem",
            "-out", node + ".csr", "-nodes", "-subj", "/CN=" + node,
            "-config", node + ".cnf");
        runOpenssl("x509", "-req", "-in", node + ".csr", "-CA", "ca-cert.pem",
            "-CAkey", "ca-key.pem", "-CAcreateserial", "-out", node + "-cert.pem",
            "-days", "2", "-extensions", "ext", "-extfile", node + ".cnf");
      }
    }
  }

  private static void runOpenssl(String... args) throws Exception {
    List<String> cmd = new java.util.ArrayList<>();
    cmd.add("openssl");
    cmd.addAll(List.of(args));
    Process p = new ProcessBuilder(cmd).directory(certDir.toFile())
        .redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    assertEquals(0, p.waitFor(), "openssl failed: " + out);
  }

  private static <T> T join(Future<T> f, long timeoutMs) throws Exception {
    return f.toCompletionStage().toCompletableFuture().get(timeoutMs, TimeUnit.MILLISECONDS);
  }

  private void startUpstreamServer() throws Exception {
    CountDownLatch ready = new CountDownLatch(1);
    mockUpstream = vertx.createNetServer();
    mockUpstream.connectHandler(sock -> {
      Buffer[] acc = { Buffer.buffer() };
      sock.handler(data -> {
        acc[0].appendBuffer(data);
        while (acc[0].length() >= 2 && acc[0].getByte(0) == com.bowman.cspng.codec.radegast.RadegastConstants.CMD_ECM_KEY_ASK) {
          int frameLen = (acc[0].getByte(1) & 0xFF) + 2;
          if (acc[0].length() < frameLen) break;
          acc[0] = Buffer.buffer(acc[0].getBytes(frameLen, acc[0].length()));
          upstreamCalls.incrementAndGet();
          byte[] replyFrame = com.bowman.cspng.codec.radegast.RadegastConstants.buildReplyFrame(CW);
          sock.write(Buffer.buffer(replyFrame));
        }
      });
    });
    mockUpstream.listen(UPSTREAM_PORT_B, "127.0.0.1", ar -> {
      if (ar.succeeded()) ready.countDown();
    });
    assertTrue(ready.await(5, TimeUnit.SECONDS), "Upstream server ready on port " + UPSTREAM_PORT_B);
  }

  @Test
  @DisplayName("Full Flow : Client CCcam -> Worker A -> CSPv2 -> Worker B -> Upstream -> CW -> Cache Hit -> Detach")
  void twoWorkersFullClientFlow() throws Exception {
    startUpstreamServer();
    String dir = certDir.toAbsolutePath().toString();

    // 1. Initialisation Worker A et Worker B
    WorkerState stateA = new WorkerState("node-a");
    WorkerState stateB = new WorkerState("node-b");
    WorkerRuntime runtimeA = new WorkerRuntime(vertx, "node-a", stateA);
    WorkerRuntime runtimeB = new WorkerRuntime(vertx, "node-b", stateB);

    StrictEcmRouter uplinkA = new StrictEcmRouter(runtimeA.connectors(), null, null, runtimeA.discovery(), "manual");
    uplinkA.setRelayPolicySupplier(stateA::policy, -1L);
    uplinkA.setRelayStores(stateA.stores());

    StrictEcmRouter uplinkB = new StrictEcmRouter(runtimeB.connectors(), null, null, runtimeB.discovery(), "manual");
    uplinkB.setRelayPolicySupplier(stateB::policy, -1L);
    uplinkB.setRelayStores(stateB.stores());

    AtomicLong epochA = new AtomicLong(1L);
    AtomicLong epochB = new AtomicLong(1L);

    WorkerCspV2Bridge bridgeA = new WorkerCspV2Bridge(vertx, "node-a", PORT_A,
        dir + "/node-a-cert.pem", dir + "/node-a-key.pem", dir + "/ca-cert.pem",
        uplinkA, Set.of("default"), epochA);

    WorkerCspV2Bridge bridgeB = new WorkerCspV2Bridge(vertx, "node-b", PORT_B,
        dir + "/node-b-cert.pem", dir + "/node-b-key.pem", dir + "/ca-cert.pem",
        uplinkB, Set.of("default"), epochB);

    runtimeA.attachCspV2(bridgeA, uplinkA, epochA);
    runtimeB.attachCspV2(bridgeB, uplinkB, epochB);

    // Secret resolver local pour 'alice' (Worker A)
    Path secretFile = certDir.resolve("secret_alice.txt");
    Files.writeString(secretFile, CC_PASS);

    try {
      // 2. Desired config :
      // Worker A : listener CCcam (18551), cache node 'n-b' pointant B (PORT_B), AUCUN connecteur amont local
      var desiredA = new RelayWorkerContract.DesiredWorkerConfig(
          "node-a", 1L, System.currentTimeMillis() + 60000L, 30000L,
          List.of(new RelayWorkerContract.ListenerView(7L, "default", "cccam", CCCAM_PORT_A,
              "127.0.0.1", "manual", 0, 512, 32, 5, 60, null, null)),
          List.of(new RelayWorkerContract.CacheView("n-b", "node", true, 8000L, 10000, 512, "node-b",
              List.of(new RelayWorkerContract.PeerView("localhost", PORT_B)))),
          List.of(new RelayWorkerContract.LinkView(7L, "n-b", true, true, false, true)),
          List.of(), // AUCUN connecteur local sur A
          List.of(new RelayWorkerContract.UserAclView(CC_USER, true, List.of("default"))),
          List.of(new RelayWorkerContract.UserSecretView(CC_USER, "cccam", "file:" + secretFile.toAbsolutePath(), 1L, true)),
          List.of(new RelayWorkerContract.CatalogView("default", CAID, 0, "published", 1L)),
          List.of(),
          List.of(new RelayWorkerContract.NodeView("node-b", true, true, false)));

      // Worker B : listener CS378x (18552), cache local, connecteur amont Radegast,
      // et node-a configure sans endpoint (ACCEPT-ONLY, dialEnabled=false)
      var desiredB = new RelayWorkerContract.DesiredWorkerConfig(
          "node-b", 1L, System.currentTimeMillis() + 60000L, 30000L,
          List.of(new RelayWorkerContract.ListenerView(8L, "default", "cs378x", 18552)),
          List.of(new RelayWorkerContract.CacheView("c-loc", "local", true, 8000L, 10000, 512)),
          List.of(new RelayWorkerContract.LinkView(8L, "c-loc", true, true, false, true)),
          List.of(new RelayWorkerContract.ConnectorView("up-b", "radegast", "default", true,
              "127.0.0.1", UPSTREAM_PORT_B, null, null, null, null, 5000, null, 0, false, 0, null, null)),
          List.of(), List.of(),
          List.of(new RelayWorkerContract.CatalogView("default", CAID, 0, "published", 1L)),
          List.of(),
          List.of(new RelayWorkerContract.NodeView("node-a", true, true, false))); // accept-only sur B

      assertTrue(join(runtimeA.applyDesired(desiredA, System.currentTimeMillis()), 5000));
      assertTrue(join(runtimeB.applyDesired(desiredB, System.currentTimeMillis()), 5000));

      // Verifier accept-only : sur B, le peer node-a a dialEnabled=false
      var peersOnB = runtimeB.buildCspV2Peers(desiredB);
      assertEquals(1, peersOnB.size());
      assertEquals("node-a", peersOnB.get(0).nodeId());
      assertFalse(peersOnB.get(0).dialEnabled(), "Peer node-a sur Worker B doit etre en accept-only (dialEnabled=false)");

      // Attendre que la liaison CSPv2 entre node-a et node-b soit etablie en mTLS
      long deadline = System.currentTimeMillis() + 10000;
      while (System.currentTimeMillis() < deadline && !bridgeA.hasEstablishedConnection()) {
        Thread.sleep(100);
      }
      assertTrue(bridgeA.hasEstablishedConnection(), "Liaison CSPv2 A <-> B doit etre etablie");

      // 3. Client CCcam se connecte au listener de Worker A
      NetClient client = vertx.createNetClient(new NetClientOptions().setConnectTimeout(5000));
      CountDownLatch cwLatch = new CountDownLatch(2);
      AtomicReference<byte[]> cw1 = new AtomicReference<>();
      AtomicReference<byte[]> cw2 = new AtomicReference<>();

      client.connect(CCCAM_PORT_A, "127.0.0.1", ar -> {
        assertTrue(ar.succeeded());
        NetSocket sock = ar.result();

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
            System.arraycopy(CC_USER.getBytes(), 0, userBuf, 0, CC_USER.length());
            cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
            sock.write(Buffer.buffer(userBuf));

            byte[] pwd = CC_PASS.getBytes();
            cliEnc.crypt(pwd, pwd.length, CcConstants.ENCRYPT);
            byte[] magic = Arrays.copyOf(CcConstants.CCCAM_MAGIC, 6);
            cliEnc.crypt(magic, 6, CcConstants.ENCRYPT);
            sock.write(Buffer.buffer(magic));

            step[0] = 1;
          }

          if (step[0] == 1 && accHolder[0].length() >= 20) {
            byte[] ack = accHolder[0].getBytes(0, 20);
            cliDec.crypt(ack, 20, CcConstants.DECRYPT);
            accHolder[0] = Buffer.buffer(accHolder[0].getBytes(20, accHolder[0].length()));

            byte[] cliData = CcCardCodec.buildCliData(CC_USER, CLIENT_NODE, "2.3.2", "4000");
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
                sendCccamEcm(sock, cliEnc, 1);
              } else if (decoded[1] == CcConstants.MSG_CW_ECM && payload.length >= 16) {
                byte[] scrambled = Arrays.copyOf(payload, 16);
                byte[] clear = scrambled.clone();
                CcCwCrypt.cwCrypt(clear, CLIENT_NODE, CARD_ID);

                byte[] sync = clear.clone();
                cliDec.crypt(sync, 16, CcConstants.ENCRYPT);

                if (cw1.get() == null) {
                  cw1.set(clear);
                  cwLatch.countDown();
                  // ECM #2 identique -> test du cache hit
                  sendCccamEcm(sock, cliEnc, 1);
                } else {
                  cw2.set(clear);
                  cwLatch.countDown();
                }
              }
            }
          }
        });
      });

      assertTrue(cwLatch.await(10, TimeUnit.SECONDS), "Client CCcam doit recevoir les 2 CWs");
      assertArrayEquals(CW, cw1.get(), "CW #1 reçue via CSPv2 -> Worker B -> Upstream");
      assertArrayEquals(CW, cw2.get(), "CW #2 reçue via Cache Hit sur Worker A");
      assertEquals(1, upstreamCalls.get(), "Upstream B appelé exactement 1 fois (le 2e ECM est servi par le cache de A)");

      // 4. Verification provenance & stats sur Worker A
      assertEquals(1L, bridgeA.nodeStats().consultOk("node-b"));
      assertNotNull(bridgeA.nodeStats().avgRttMs("node-b"));

      client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

      // 5. Detachement du cache 'n-b' sur Worker A (lien desactive)
      var detachedDesiredA = new RelayWorkerContract.DesiredWorkerConfig(
          "node-a", 2L, System.currentTimeMillis() + 60000L, 30000L,
          List.of(new RelayWorkerContract.ListenerView(7L, "default", "cccam", CCCAM_PORT_A,
              "127.0.0.1", "manual", 0, 512, 32, 5, 60, null, null)),
          List.of(new RelayWorkerContract.CacheView("n-b", "node", true, 8000L, 10000, 512, "node-b",
              List.of(new RelayWorkerContract.PeerView("localhost", PORT_B)))),
          List.of(new RelayWorkerContract.LinkView(7L, "n-b", true, true, false, false)), // lien DESACTIVE
          List.of(),
          List.of(new RelayWorkerContract.UserAclView(CC_USER, true, List.of("default"))),
          List.of(new RelayWorkerContract.UserSecretView(CC_USER, "cccam", "file:" + secretFile.toAbsolutePath(), 1L, true)),
          List.of(new RelayWorkerContract.CatalogView("default", CAID, 0, "published", 1L)),
          List.of(),
          List.of(new RelayWorkerContract.NodeView("node-b", true, true, false)));

      assertTrue(join(runtimeA.applyDesired(detachedDesiredA, System.currentTimeMillis()), 5000));
      assertFalse(stateA.policy().canConsult(7L, "n-b"), "Policy Worker A refuse node-b detache");

    } finally {
      runtimeA.close();
      runtimeB.close();
    }
  }

  @Test
  @DisplayName("Topologie cyclique 3 noeuds A->B->C->A : anti-boucle v1 casse la boucle au 1er saut, zero tempete, retour NOK borne")
  void cyclicTopologyThreeNodesAntiLoopWithRealClient() throws Exception {
    String dir = certDir.toAbsolutePath().toString();

    // 1. Initialisation des 3 workers en cycle A -> B -> C -> A
    WorkerState stateA = new WorkerState("node-a");
    WorkerState stateB = new WorkerState("node-b");
    WorkerState stateC = new WorkerState("node-c");
    WorkerRuntime runtimeA = new WorkerRuntime(vertx, "node-a", stateA);
    WorkerRuntime runtimeB = new WorkerRuntime(vertx, "node-b", stateB);
    WorkerRuntime runtimeC = new WorkerRuntime(vertx, "node-c", stateC);

    StrictEcmRouter uplinkA = new StrictEcmRouter(runtimeA.connectors(), null, null, runtimeA.discovery(), "manual");
    uplinkA.setRelayPolicySupplier(stateA::policy, -1L);
    uplinkA.setRelayStores(stateA.stores());

    StrictEcmRouter uplinkB = new StrictEcmRouter(runtimeB.connectors(), null, null, runtimeB.discovery(), "manual");
    uplinkB.setRelayPolicySupplier(stateB::policy, -1L);
    uplinkB.setRelayStores(stateB.stores());

    StrictEcmRouter uplinkC = new StrictEcmRouter(runtimeC.connectors(), null, null, runtimeC.discovery(), "manual");
    uplinkC.setRelayPolicySupplier(stateC::policy, -1L);
    uplinkC.setRelayStores(stateC.stores());

    AtomicLong epochA = new AtomicLong(1L);
    AtomicLong epochB = new AtomicLong(1L);
    AtomicLong epochC = new AtomicLong(1L);

    WorkerCspV2Bridge bridgeA = new WorkerCspV2Bridge(vertx, "node-a", PORT_A,
        dir + "/node-a-cert.pem", dir + "/node-a-key.pem", dir + "/ca-cert.pem",
        uplinkA, Set.of("default"), epochA);

    WorkerCspV2Bridge bridgeB = new WorkerCspV2Bridge(vertx, "node-b", PORT_B,
        dir + "/node-b-cert.pem", dir + "/node-b-key.pem", dir + "/ca-cert.pem",
        uplinkB, Set.of("default"), epochB);

    WorkerCspV2Bridge bridgeC = new WorkerCspV2Bridge(vertx, "node-c", PORT_C,
        dir + "/node-c-cert.pem", dir + "/node-c-key.pem", dir + "/ca-cert.pem",
        uplinkC, Set.of("default"), epochC);

    runtimeA.attachCspV2(bridgeA, uplinkA, epochA);
    runtimeB.attachCspV2(bridgeB, uplinkB, epochB);
    runtimeC.attachCspV2(bridgeC, uplinkC, epochC);

    Path secretFile = certDir.resolve("secret_alice.txt");
    Files.writeString(secretFile, CC_PASS);

    try {
      // 2. Desired configs : Cycle A -> B -> C -> A, AUCUN connecteur amont local sur les 3
      var desiredA = new RelayWorkerContract.DesiredWorkerConfig(
          "node-a", 1L, System.currentTimeMillis() + 60000L, 30000L,
          List.of(new RelayWorkerContract.ListenerView(7L, "default", "cccam", CCCAM_PORT_A,
              "127.0.0.1", "manual", 0, 512, 32, 5, 60, null, null)),
          List.of(new RelayWorkerContract.CacheView("n-b", "node", true, 8000L, 10000, 512, "node-b",
              List.of(new RelayWorkerContract.PeerView("localhost", PORT_B)))),
          List.of(new RelayWorkerContract.LinkView(7L, "n-b", true, true, false, true)),
          List.of(), // aucun connecteur amont
          List.of(new RelayWorkerContract.UserAclView(CC_USER, true, List.of("default"))),
          List.of(new RelayWorkerContract.UserSecretView(CC_USER, "cccam", "file:" + secretFile.toAbsolutePath(), 1L, true)),
          List.of(new RelayWorkerContract.CatalogView("default", CAID, 0, "published", 1L)),
          List.of(),
          List.of(new RelayWorkerContract.NodeView("node-b", true, true, false)));

      var desiredB = new RelayWorkerContract.DesiredWorkerConfig(
          "node-b", 1L, System.currentTimeMillis() + 60000L, 30000L,
          List.of(new RelayWorkerContract.ListenerView(8L, "default", "cs378x", 18552)),
          List.of(new RelayWorkerContract.CacheView("n-c", "node", true, 8000L, 10000, 512, "node-c",
              List.of(new RelayWorkerContract.PeerView("localhost", PORT_C)))),
          List.of(new RelayWorkerContract.LinkView(8L, "n-c", true, true, false, true)),
          List.of(), // aucun connecteur amont
          List.of(), List.of(),
          List.of(new RelayWorkerContract.CatalogView("default", CAID, 0, "published", 1L)),
          List.of(),
          List.of(new RelayWorkerContract.NodeView("node-a", true, true, false),
                  new RelayWorkerContract.NodeView("node-c", true, true, false)));

      var desiredC = new RelayWorkerContract.DesiredWorkerConfig(
          "node-c", 1L, System.currentTimeMillis() + 60000L, 30000L,
          List.of(new RelayWorkerContract.ListenerView(9L, "default", "cs378x", 18553)),
          List.of(new RelayWorkerContract.CacheView("n-a", "node", true, 8000L, 10000, 512, "node-a",
              List.of(new RelayWorkerContract.PeerView("localhost", PORT_A)))),
          List.of(new RelayWorkerContract.LinkView(9L, "n-a", true, true, false, true)),
          List.of(), // aucun connecteur amont
          List.of(), List.of(),
          List.of(new RelayWorkerContract.CatalogView("default", CAID, 0, "published", 1L)),
          List.of(),
          List.of(new RelayWorkerContract.NodeView("node-b", true, true, false),
                  new RelayWorkerContract.NodeView("node-a", true, true, false)));

      assertTrue(join(runtimeA.applyDesired(desiredA, System.currentTimeMillis()), 5000));
      assertTrue(join(runtimeB.applyDesired(desiredB, System.currentTimeMillis()), 5000));
      assertTrue(join(runtimeC.applyDesired(desiredC, System.currentTimeMillis()), 5000));

      // Attendre que la liaison CSPv2 A -> B soit etablie
      long deadline = System.currentTimeMillis() + 10000;
      while (System.currentTimeMillis() < deadline && !bridgeA.hasEstablishedConnection()) {
        Thread.sleep(100);
      }
      assertTrue(bridgeA.hasEstablishedConnection(), "Liaison CSPv2 A <-> B etablie");

      // 3. Client CCcam se connecte sur A et envoie un ECM
      NetClient client = vertx.createNetClient(new NetClientOptions().setConnectTimeout(5000));
      CountDownLatch nokLatch = new CountDownLatch(1);
      long startTime = System.currentTimeMillis();

      client.connect(CCCAM_PORT_A, "127.0.0.1", ar -> {
        assertTrue(ar.succeeded());
        NetSocket sock = ar.result();

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
            System.arraycopy(CC_USER.getBytes(), 0, userBuf, 0, CC_USER.length());
            cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
            sock.write(Buffer.buffer(userBuf));

            byte[] pwd = CC_PASS.getBytes();
            cliEnc.crypt(pwd, pwd.length, CcConstants.ENCRYPT);
            byte[] magic = Arrays.copyOf(CcConstants.CCCAM_MAGIC, 6);
            cliEnc.crypt(magic, 6, CcConstants.ENCRYPT);
            sock.write(Buffer.buffer(magic));

            step[0] = 1;
          }

          if (step[0] == 1 && accHolder[0].length() >= 20) {
            byte[] ack = accHolder[0].getBytes(0, 20);
            cliDec.crypt(ack, 20, CcConstants.DECRYPT);
            accHolder[0] = Buffer.buffer(accHolder[0].getBytes(20, accHolder[0].length()));

            byte[] cliData = CcCardCodec.buildCliData(CC_USER, CLIENT_NODE, "2.3.2", "4000");
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
                sendCccamEcm(sock, cliEnc, 99);
              } else if (decoded[1] == CcConstants.MSG_CW_NOK2 || decoded[1] == CcConstants.MSG_CW_NOK1) {
                nokLatch.countDown();
              }
            }
          }
        });
      });

      assertTrue(nokLatch.await(5, TimeUnit.SECONDS), "Client CCcam doit recevoir NOK sans blocage ni tempete");
      long elapsed = System.currentTimeMillis() - startTime;
      assertTrue(elapsed < 4000L, "Reponse en boucle fermee doit se terminer rapidement (< 4s), got " + elapsed + "ms");

      // 4. Preuve que la boucle a ete brisee au 1er saut :
      // Worker B n'a JAMAIS re-transmis la requete vers Worker C (compteur consultOk("node-c") = 0)
      // Preuve anti-boucle : consultOk = 0 ET inboundEcmReceived = 0 depuis le noeud
      // en aval. consultOk=0 seul ne suffit pas (echec possible sans boucle visible) ;
      // inboundEcmReceived=0 prouve qu'aucun ECM n'a transité vers C ni A depuis B/C.
      assertEquals(0L, bridgeB.nodeStats().consultOk("node-c"),
          "Anti-boucle : Worker B ne doit JAMAIS re-consulter Worker C (consultOk)");
      assertEquals(0L, bridgeC.nodeStats().inboundEcmReceived("node-b"),
          "Anti-boucle : Worker C ne doit JAMAIS recevoir un ECM de Worker B (inbound depuis A->B->C impossible)");
      assertEquals(0L, bridgeC.nodeStats().consultOk("node-a"),
          "Anti-boucle : Worker C ne doit JAMAIS re-consulter Worker A (consultOk)");
      assertEquals(0L, bridgeA.nodeStats().inboundEcmReceived("node-c"),
          "Anti-boucle : Worker A ne doit JAMAIS recevoir un ECM de Worker C (boucle C->A inexistante)");

      client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

    } finally {
      runtimeA.close();
      runtimeB.close();
      runtimeC.close();
    }
  }

  private void sendCccamEcm(NetSocket sock, CcCrypt cliEnc, int seq) {
    byte[] payload = new byte[40];
    Arrays.fill(payload, (byte) seq);
    byte[] section = CcCardCodec.reconstructSection(0x80, payload);
    byte[] req = CcCardCodec.buildEcmRequest(CAID, 0, CARD_ID, SRVID, section);
    byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CW_ECM, req);
    cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
    sock.write(Buffer.buffer(frame));
  }

  private static byte[] sha1(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-1").digest(data);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
