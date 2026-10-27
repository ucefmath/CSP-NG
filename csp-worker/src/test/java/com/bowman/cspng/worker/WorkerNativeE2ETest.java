package com.bowman.cspng.worker;

import com.bowman.cspng.codec.camd35.Camd35Constants;
import com.bowman.cspng.codec.camd35.Camd35Crypto;
import com.bowman.cspng.codec.camd35.Camd35FrameCodec;
import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.codec.cccam.CcConstants;
import com.bowman.cspng.codec.cccam.CcCrypt;
import com.bowman.cspng.codec.cccam.CcCwCrypt;
import com.bowman.cspng.codec.cccam.CcFrameCodec;
import com.bowman.cspng.codec.radegast.RadegastConstants;
import com.bowman.cspng.codec.cluster.ClusterUdpCodec;
import com.bowman.cspng.config.RelayWorkerContract;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Natif end-to-end complet :
 * <ul>
 *   <li>Centrale mTLS de test (certs openssl sous {@code target/}, jamais prod) ;</li>
 *   <li>Binaire natif {@code csp-worker} lance via ProcessBuilder (env de test) ;</li>
 *   <li>Desired avec listeners CCcam + CS378x, cache c1, users alice, connecteur amont Radegast ;</li>
 *   <li>Ack mTLS avec stats réelles ;</li>
 *   <li>Upstream fixture MockRadegastServer répondant aux ECMs ;</li>
 *   <li>Handshake client CCcam complet (seed -> hash -> user -> pass token -> ack -> cli data -> srv data) ;</li>
 *   <li>Envoi ECM CCcam #1 -> upstream -> CW retournée ;</li>
 *   <li>Envoi ECM CCcam #2 identique -> Cache Hit (aucun nouvel appel upstream) -> CW retournée ;</li>
 *   <li>Handshake client CS378x complet (ucrc + AES) -> envoi ECM -> CW retournée ;</li>
 *   <li>Révocation centrale 403 -> failClosed -> fermeture des listeners.</li>
 * </ul>
 */
@DisplayName("WorkerNativeE2ETest — binaire natif : mTLS control + full CCcam/CS378x + ECM upstream/cache + revoke")
class WorkerNativeE2ETest {

  private static final int CENTRAL_PORT = 18443;
  private static final int CCCAM_PORT = 18543;
  private static final int CS378X_PORT = 18544;
  private static final int RADEGAST_UPSTREAM_PORT = 18545;
  private static final String NODE = "test-worker";

  private static final String CC_USER = "alice";
  private static final String CC_PASS = "secret1";
  private static final String CS_USER = "bob";
  private static final String CS_PASS = "secret2";

  private static final int CAID = 0x0100;
  private static final int SRVID = 0x0001;
  private static final int CARD_ID = 0x00AABBCC;
  private static final byte[] SERVER_NODE = { 1, 2, 3, 4, 5, 6, 7, 8 };
  private static final byte[] CLIENT_NODE = { 9, 8, 7, 6, 5, 4, 3, 2 };

  private static final byte[] CW_EXPECTED = {
      0x11, 0x22, 0x33, 0x66, 0x44, 0x55, 0x66, (byte) 0xFF,
      0x77, (byte) 0x88, (byte) 0x99, (byte) 0x98, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, 0x31
  };

  private Vertx vertx;
  private static Path certDir;
  private Process workerProc;
  private HttpServer stub;
  private NetServer upstreamMock;
  private DatagramSocket udpPeer;
  private final AtomicInteger upstreamEcmCount = new AtomicInteger();
  private final AtomicInteger udpRequestCount = new AtomicInteger();
  private final AtomicBoolean revoked = new AtomicBoolean(false);

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    certDir = Path.of("target/test-certs-native-e2e");
    Files.createDirectories(certDir);
    if (!Files.exists(certDir.resolve("worker-cert.pem"))) {
      runOpenssl("req", "-x509", "-newkey", "rsa:2048", "-keyout", "ca-key.pem",
          "-out", "ca-cert.pem", "-days", "2", "-nodes", "-subj", "/CN=e2e-test-ca");
      // Centrale : SAN IP (le worker verifie le hostname).
      Files.writeString(certDir.resolve("central.cnf"),
          "[req]\ndistinguished_name=dn\nreq_extensions=ext\n[dn]\n[ext]\n"
              + "subjectAltName=IP:127.0.0.1,DNS:localhost\n");
      runOpenssl("req", "-newkey", "rsa:2048", "-keyout", "central-key.pem",
          "-out", "central.csr", "-nodes", "-subj", "/CN=127.0.0.1",
          "-config", "central.cnf");
      runOpenssl("x509", "-req", "-in", "central.csr", "-CA", "ca-cert.pem",
          "-CAkey", "ca-key.pem", "-CAcreateserial", "-out", "central-cert.pem",
          "-days", "2", "-extensions", "ext", "-extfile", "central.cnf");
      // Worker : SAN URI urn:csp:node:<id> (identite verifiee par la centrale).
      Files.writeString(certDir.resolve("worker.cnf"),
          "[req]\ndistinguished_name=dn\nreq_extensions=ext\n[dn]\n[ext]\n"
              + "subjectAltName=URI:urn:csp:node:" + NODE + "\n");
      runOpenssl("req", "-newkey", "rsa:2048", "-keyout", "worker-key.pem",
          "-out", "worker.csr", "-nodes", "-subj", "/CN=" + NODE,
          "-config", "worker.cnf");
      runOpenssl("x509", "-req", "-in", "worker.csr", "-CA", "ca-cert.pem",
          "-CAkey", "ca-key.pem", "-CAcreateserial", "-out", "worker-cert.pem",
          "-days", "2", "-extensions", "ext", "-extfile", "worker.cnf");
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    if (workerProc != null && workerProc.isAlive()) {
      workerProc.destroy();
      workerProc.waitFor(5, TimeUnit.SECONDS);
      if (workerProc.isAlive()) workerProc.destroyForcibly();
    }
    if (upstreamMock != null) {
      CountDownLatch l = new CountDownLatch(1);
      upstreamMock.close(v -> l.countDown());
      l.await(5, TimeUnit.SECONDS);
    }
    if (udpPeer != null) {
      try {
        udpPeer.close();
      } catch (Exception ignored) {
      }
    }
    if (stub != null) {
      CountDownLatch l = new CountDownLatch(1);
      stub.close(v -> l.countDown());
      l.await(5, TimeUnit.SECONDS);
    }
    if (vertx != null) {
      CountDownLatch l = new CountDownLatch(1);
      vertx.close(v -> l.countDown());
      l.await(10, TimeUnit.SECONDS);
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

  private static String sanOf(List<java.security.cert.Certificate> certs) {
    try {
      for (var c : certs) {
        if (c instanceof java.security.cert.X509Certificate x) {
          var sans = x.getSubjectAlternativeNames();
          if (sans == null) continue;
          for (var san : sans) {
            if (san != null && san.size() == 2 && Integer.valueOf(6).equals(san.get(0))) {
              String v = String.valueOf(san.get(1));
              if (v.startsWith("urn:csp:node:")) return v.substring("urn:csp:node:".length());
            }
          }
        }
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private void startUpstreamRadegast() throws Exception {
    CountDownLatch ready = new CountDownLatch(1);
    upstreamMock = vertx.createNetServer();
    upstreamMock.connectHandler(sock -> {
      Buffer[] acc = { Buffer.buffer() };
      sock.handler(data -> {
        acc[0].appendBuffer(data);
        while (acc[0].length() >= 2 && acc[0].getByte(0) == RadegastConstants.CMD_ECM_KEY_ASK) {
          int frameLen = (acc[0].getByte(1) & 0xFF) + 2;
          if (acc[0].length() < frameLen) break;
          acc[0] = Buffer.buffer(acc[0].getBytes(frameLen, acc[0].length()));
          upstreamEcmCount.incrementAndGet();
          byte[] replyFrame = RadegastConstants.buildReplyFrame(CW_EXPECTED);
          sock.write(Buffer.buffer(replyFrame));
        }
      });
    });
    upstreamMock.listen(RADEGAST_UPSTREAM_PORT, "127.0.0.1", ar -> {
      if (ar.succeeded()) ready.countDown();
    });
    assertTrue(ready.await(5, TimeUnit.SECONDS), "Upstream Radegast mock ready");
  }

  @Test
  @DisplayName("Full Native E2E : mTLS + CCcam/CS378x Auth + ECM Upstream + Cache Hit + Revocation")
  void fullNativeEndToEnd() throws Exception {
    Path binary = Path.of("target/csp-worker").toAbsolutePath();
    assumeTrue(Files.isExecutable(binary), "binaire natif requis (build -Pnative)");
    startUpstreamRadegast();

    ObjectMapper mapper = new ObjectMapper();

    var r1 = new RelayWorkerContract.DcwRuleView(1L, "default", CAID, 0, 0, null, null, "none", "require_valid", "enforce checksum on default", true);
    var r2 = new RelayWorkerContract.DcwRuleView(2L, "default", CAID, 0, 0, null, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "block", "inherit", "block known bad cw", true);
    var m1 = new RelayWorkerContract.DcwMappingView(10L, "default", CAID, 0, 0x0002, SRVID, true, true, 0L);
    var dcwPolicy = new RelayWorkerContract.DcwPolicyView(true, false, true, true, "inherit", "off", true, List.of(r1, r2), List.of(m1));
    // Hotfix natif cwCycle : desired transporte explicitement CwCycleView (non-null).
    // Sans reflect-config CwCycleView, le binaire natif rejette le desired (monde fermé
    // GraalVM : InvalidDefinitionException) et l'ack n'est jamais émis.
    var cwCycle = new RelayWorkerContract.CwCycleView(true, List.of(CAID), 500, 15, "drop", true, true);

    var desired = new RelayWorkerContract.DesiredWorkerConfig(
        NODE, 1L, System.currentTimeMillis() + 120000L, 30000L,
        List.of(
            new RelayWorkerContract.ListenerView(7L, "default", "cccam", CCCAM_PORT,
                "127.0.0.1", "manual", 0, 512, 32, 5, 60, null, null),
            new RelayWorkerContract.ListenerView(8L, "default", "cs378x", CS378X_PORT,
                "127.0.0.1", "manual", 0, 512, 32, 5, 60, null, null)),
        List.of(new RelayWorkerContract.CacheView("c1", "local", true, 8000L, 10000, 512)),
        List.of(
            new RelayWorkerContract.LinkView(7L, "c1", true, true, false, true),
            new RelayWorkerContract.LinkView(8L, "c1", true, true, false, true)),
        List.of(
            new RelayWorkerContract.ConnectorView("up-radegast", "radegast", "default", true,
                "127.0.0.1", RADEGAST_UPSTREAM_PORT, null, null, null, null, 5000, null, 0, false, 0, null, null)),
        List.of(
            new RelayWorkerContract.UserAclView(CC_USER, true, List.of("default")),
            new RelayWorkerContract.UserAclView(CS_USER, true, List.of("default"))),
        List.of(
            new RelayWorkerContract.UserSecretView(CC_USER, "cccam", "env:E2E_CC_PASSWD", 1L, true),
            new RelayWorkerContract.UserSecretView(CS_USER, "cs378x", "env:E2E_CS_PASSWD", 1L, true)),
        List.of(new RelayWorkerContract.CatalogView("default", CAID, 0, "published", 1L)),
        List.of(), List.of(), dcwPolicy, cwCycle);
    String desiredJson = mapper.writeValueAsString(desired);
    assertTrue(desiredJson.contains("\"cwCycle\""), "desired natif doit transporter cwCycle");

    CountDownLatch acked = new CountDownLatch(1);
    AtomicReference<JsonNode> lastAck = new AtomicReference<>();
    Map<String, Integer> nodeBySan = new ConcurrentHashMap<>();

    HttpServerOptions opts = new HttpServerOptions()
        .setSsl(true)
        .setClientAuth(ClientAuth.REQUIRED)
        .setPemKeyCertOptions(new PemKeyCertOptions()
            .setCertPath(certDir.resolve("central-cert.pem").toString())
            .setKeyPath(certDir.resolve("central-key.pem").toString()))
        .setPemTrustOptions(new PemTrustOptions()
            .addCertPath(certDir.resolve("ca-cert.pem").toString()));
    stub = vertx.createHttpServer(opts);
    stub.requestHandler(req -> {
      try {
        String san = sanOf(req.connection().peerCertificates());
        if ("GET".equals(req.method().name()) && req.path().startsWith("/api/worker/desired")) {
          String q = req.getParam("nodeId");
          if (san == null || !san.equals(q) || !NODE.equals(san)) {
            req.response().setStatusCode(403).end("{\"code\":\"WORKER_UNAUTHORIZED\"}");
            return;
          }
          if (revoked.get()) {
            req.response().setStatusCode(403).end("{\"code\":\"WORKER_REVOKED\"}");
            return;
          }
          nodeBySan.put(san, 1);
          req.response().putHeader("Content-Type", "application/json")
              .setStatusCode(200).end(desiredJson);
        } else if ("POST".equals(req.method().name()) && "/api/worker/ack".equals(req.path())) {
          req.body().onComplete(bar -> {
            try {
              if (revoked.get()) {
                req.response().setStatusCode(403).end("{\"code\":\"WORKER_REVOKED\"}");
                return;
              }
              var ack = mapper.readTree(bar.result().toString());
              if (san == null || !san.equals(ack.path("nodeId").asText())
                  || ack.path("appliedVersion").asLong() != 1L) {
                req.response().setStatusCode(409).end("{\"code\":\"STALE_ACK\"}");
                return;
              }
              lastAck.set(ack);
              acked.countDown();
              req.response().putHeader("Content-Type", "application/json")
                  .setStatusCode(200).end("{\"appliedVersion\":1}");
            } catch (Exception e) {
              try {
                req.response().setStatusCode(400).end("{\"code\":\"INVALID_INPUT\"}");
              } catch (Exception ignored) {
              }
            }
          });
        } else {
          req.response().setStatusCode(404).end("{\"code\":\"NOT_FOUND\"}");
        }
      } catch (Exception e) {
        try {
          req.response().setStatusCode(500).end("{\"code\":\"INTERNAL_ERROR\"}");
        } catch (Exception ignored) {
        }
      }
    });
    stub.listen(CENTRAL_PORT, "127.0.0.1")
        .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

    ProcessBuilder pb = new ProcessBuilder(binary.toString());
    Map<String, String> env = pb.environment();
    env.put("CSP_WORKER_CENTRAL_URL", "https://127.0.0.1:" + CENTRAL_PORT);
    env.put("CSP_WORKER_NODE_ID", NODE);
    env.put("CSP_WORKER_TLS_CERT_FILE", certDir.resolve("worker-cert.pem").toAbsolutePath().toString());
    env.put("CSP_WORKER_TLS_KEY_FILE", certDir.resolve("worker-key.pem").toAbsolutePath().toString());
    env.put("CSP_WORKER_TLS_TRUST_FILE", certDir.resolve("ca-cert.pem").toAbsolutePath().toString());
    env.put("CSP_WORKER_TTL_MS", "30000");
    env.put("CSP_WORKER_POLL_MS", "2000");
    env.put("CSP_WORKER_CSPV2_PORT", "0");
    env.put("CSP_WORKER_UDP_PORT", "0");
    env.put("E2E_CC_PASSWD", CC_PASS);
    env.put("E2E_CS_PASSWD", CS_PASS);
    pb.redirectErrorStream(true);
    workerProc = pb.start();

    java.nio.file.Path stdoutLog = java.nio.file.Path.of("target/native-worker-stdout.log");
    var logOut = java.nio.file.Files.newOutputStream(stdoutLog,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    Thread drainer = new Thread(() -> {
      try (var r = new java.io.BufferedReader(
          new java.io.InputStreamReader(workerProc.getInputStream()))) {
        String line;
        while ((line = r.readLine()) != null) {
          logOut.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
          logOut.flush();
        }
      } catch (Exception ignored) {
      }
    });
    drainer.setDaemon(true);
    drainer.start();

    try {
      // 1. desired/ack réels mTLS
      assertTrue(acked.await(30, TimeUnit.SECONDS), "ack natif jamais recu");
      JsonNode ack = lastAck.get();
      assertNotNull(ack);
      assertTrue(ack.has("stats"), "ack sans stats");
      // Hotfix natif cwCycle : le binaire a reçu le desired cwCycle (sinon pas d'ack)
      // et émet stats.cwCycle sérialisé en natif (CwCycleStatsView + CacheExPairStatsView
      // enregistrés). Sans eux : ack absent ou stats.cwCycle absent/vide.
      JsonNode stats = ack.path("stats");
      assertTrue(stats.has("cwCycle"), "ack.stats sans cwCycle (natif CwCycleStatsView manquant ?)");
      assertTrue(stats.path("cwCycle").isObject(), "stats.cwCycle doit être un objet");
      assertTrue(stats.path("cwCycle").path("enabled").asBoolean(false),
          "stats.cwCycle.enabled doit refléter le desired (true)");
      assertTrue(stats.has("cacheex"), "ack.stats sans champ cacheex (WorkerStatsView natif incomplet ?)");
      assertTrue(stats.path("cacheex").isArray(), "stats.cacheex doit être un tableau");
      assertEquals(NODE, nodeBySan.keySet().iterator().next(), "identite = SAN mTLS");

      // 2. Client CCcam complet : Auth + ECM #1 (upstream) + ECM #2 (cache hit)
      testCccamClientFlow();

      // 3. Client CS378x complet : Auth + ECM (cache hit ou upstream)
      testCs378xClientFlow();

      // 4. Révocation 403 -> failClosed -> fermeture des listeners
      testRevocationFlow();

    } finally {
      if (workerProc.isAlive()) {
        workerProc.destroy();
        workerProc.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  private void testCccamClientFlow() throws Exception {
    NetClient client = vertx.createNetClient(new NetClientOptions().setConnectTimeout(5000));
    CountDownLatch cwLatch = new CountDownLatch(2);
    AtomicReference<byte[]> cw1 = new AtomicReference<>();
    AtomicReference<byte[]> cw2 = new AtomicReference<>();

    int initialUpstream = upstreamEcmCount.get();

    client.connect(CCCAM_PORT, "127.0.0.1", ar -> {
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

    assertTrue(cwLatch.await(10, TimeUnit.SECONDS), "CCcam: les deux CWs doivent être reçues");
    assertArrayEquals(CW_EXPECTED, cw1.get(), "CCcam CW #1 correcte via upstream Radegast");
    assertArrayEquals(CW_EXPECTED, cw2.get(), "CCcam CW #2 correcte via Cache Hit");
    assertEquals(initialUpstream + 1, upstreamEcmCount.get(),
        "Upstream doit avoir été appelé exactement 1 fois (le 2e ECM est servi par le cache)");

    client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
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

  private void testCs378xClientFlow() throws Exception {
    byte[] ucrc = Camd35Crypto.ucrc(CS_USER);
    byte[] key = Camd35Crypto.aesKey(CS_PASS);

    NetClient client = vertx.createNetClient(new NetClientOptions().setConnectTimeout(5000));
    CountDownLatch replyLatch = new CountDownLatch(1);
    AtomicReference<byte[]> cwReceived = new AtomicReference<>();

    client.connect(CS378X_PORT, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      Camd35FrameCodec.Decoder clientDec = new Camd35FrameCodec.Decoder(key);

      sock.handler(buf -> {
        Camd35FrameCodec.DecodeResult res = clientDec.feed(buf.getBytes());
        if (res.status() == Camd35FrameCodec.Status.FRAME) {
          if (res.frame().cmd() == Camd35Constants.CMD_CW) {
            cwReceived.set(res.frame().data());
            replyLatch.countDown();
          }
        }
      });

      byte[] payload = new byte[40];
      Arrays.fill(payload, (byte) 1);
      byte[] section = new byte[3 + payload.length];
      section[0] = (byte) 0x80;
      section[1] = (byte) (0x70 | ((payload.length >> 8) & 0x0F));
      section[2] = (byte) (payload.length & 0xFF);
      System.arraycopy(payload, 0, section, 3, payload.length);

      Camd35FrameCodec.Camd35Frame req = new Camd35FrameCodec.Camd35Frame(
          ucrc, Camd35Constants.CMD_ECM, SRVID, CAID, 0, 0x1122, 0xFFFF, section, new byte[0]);
      sock.write(Buffer.buffer(Camd35FrameCodec.encode(req, key)));
    });

    assertTrue(replyLatch.await(8, TimeUnit.SECONDS), "CS378x: CW retournée par le serveur");
    assertArrayEquals(CW_EXPECTED, cwReceived.get(), "CS378x: CW identique au cache/upstream");

    client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private void testRevocationFlow() throws Exception {
    revoked.set(true);
    long deadline = System.currentTimeMillis() + 25000;
    boolean closed = false;
    NetClient probe = vertx.createNetClient(new NetClientOptions().setConnectTimeout(1500));
    try {
      while (System.currentTimeMillis() < deadline && !closed) {
        try {
          var s = probe.connect(CCCAM_PORT, "127.0.0.1")
              .toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
          try {
            CountDownLatch cl = new CountDownLatch(1);
            s.closeHandler(v -> cl.countDown());
            s.handler(b -> {});
            if (cl.await(2, TimeUnit.SECONDS)) closed = true;
            else {
              try { s.close(); } catch (Exception ignored) {}
            }
          } catch (Exception ignored) {
          }
        } catch (Exception refused) {
          closed = true;
        }
        if (!closed) Thread.sleep(1000);
      }
    } finally {
      probe.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
    assertTrue(closed, "revocation 403 => listener CCcam ferme");
  }

  private static byte[] sha1(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-1").digest(data);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Natif UDP consult : worker sans connecteur local, pair UDP SIMULE autorise
  // (socket brute allowliste repondant CW), cache hit natif prouve.
  // -----------------------------------------------------------------------------------------------

  private static final int CENTRAL_UDP_PORT = 18643;
  private static final int CCCAM_UDP_PORT = 18651;
  private static final int WORKER_UDP_PORT = 18652;
  private static final int RAW_PEER_PORT = 18653;

  /**
   * Pair UDP simule (socket brute) : repond a chaque TYPE_REQUEST allowliste
   * par une REPLY CW valide (origine {@code udp:u}, checksum DcwValidator OK).
   * Comptabilise les requetes pour prouver le cache hit (1 requete / 2 ECM).
   */
  private void startRawUdpPeer(int port) throws Exception {
    CountDownLatch ready = new CountDownLatch(1);
    DatagramSocket sock = vertx.createDatagramSocket(new DatagramSocketOptions());
    udpPeer = sock;
    sock.handler(pkt -> {
      try {
        byte[] data = pkt.data().getBytes();
        if (data.length == 0 || (data[0] & 0xFF) != ClusterUdpCodec.TYPE_REQUEST) return;
        EcmRequest req = ClusterUdpCodec.decodeRequest(data);
        udpRequestCount.incrementAndGet();
        CwReply reply = new CwReply(req.key(), req.key().commandTag(), CW_EXPECTED,
            "udp:u", "127.0.0.1", System.currentTimeMillis());
        byte[] frame = ClusterUdpCodec.encodeReply(reply);
        sock.send(Buffer.buffer(frame), pkt.sender().port(), pkt.sender().host(),
            ar -> {});
      } catch (Exception ignored) {
        // Paquet malforme : droppe (le pair simule reste muet).
      }
    });
    sock.listen(port, "127.0.0.1", ar -> {
      if (ar.succeeded()) ready.countDown();
    });
    assertTrue(ready.await(5, TimeUnit.SECONDS), "Raw UDP peer ready on " + port);
  }

  @Test
  @DisplayName("Natif UDP consult : listener CCcam -> worker sans connecteur -> pair UDP simule -> CW + cache hit")
  void nativeUdpConsultEndToEnd() throws Exception {
    Path binary = Path.of("target/csp-worker").toAbsolutePath();
    assumeTrue(Files.isExecutable(binary), "binaire natif requis (build -Pnative)");
    startRawUdpPeer(RAW_PEER_PORT);
    ObjectMapper mapper = new ObjectMapper();

    var r1 = new RelayWorkerContract.DcwRuleView(1L, "default", CAID, 0, 0, null, null, "none", "require_valid", "enforce checksum on default", true);
    var m1 = new RelayWorkerContract.DcwMappingView(10L, "default", CAID, 0, 0x0002, SRVID, true, true, 0L);
    var dcwPolicy = new RelayWorkerContract.DcwPolicyView(true, false, true, true, "inherit", "off", true, List.of(r1), List.of(m1));

    var desired = new RelayWorkerContract.DesiredWorkerConfig(
        NODE, 1L, System.currentTimeMillis() + 120000L, 30000L,
        List.of(new RelayWorkerContract.ListenerView(7L, "default", "cccam", CCCAM_UDP_PORT,
            "127.0.0.1", "manual", 0, 512, 32, 5, 60, null, null)),
        List.of(new RelayWorkerContract.CacheView("u", "udp", true, 8000L, 10000, 512, null,
            List.of(new RelayWorkerContract.PeerView("127.0.0.1", RAW_PEER_PORT)))),
        List.of(new RelayWorkerContract.LinkView(7L, "u", true, true, false, true)),
        List.of(),
        List.of(new RelayWorkerContract.UserAclView(CC_USER, true, List.of("default"))),
        List.of(new RelayWorkerContract.UserSecretView(CC_USER, "cccam", "env:E2E_CC_PASSWD", 1L, true)),
        List.of(new RelayWorkerContract.CatalogView("default", CAID, 0, "published", 1L)),
        List.of(), List.of(), dcwPolicy);
    String desiredJson = mapper.writeValueAsString(desired);

    CountDownLatch acked = new CountDownLatch(1);
    HttpServerOptions opts = new HttpServerOptions()
        .setSsl(true)
        .setClientAuth(ClientAuth.REQUIRED)
        .setPemKeyCertOptions(new PemKeyCertOptions()
            .setCertPath(certDir.resolve("central-cert.pem").toString())
            .setKeyPath(certDir.resolve("central-key.pem").toString()))
        .setPemTrustOptions(new PemTrustOptions()
            .addCertPath(certDir.resolve("ca-cert.pem").toString()));
    stub = vertx.createHttpServer(opts);
    stub.requestHandler(req -> {
      try {
        String san = sanOf(req.connection().peerCertificates());
        if ("GET".equals(req.method().name()) && req.path().startsWith("/api/worker/desired")) {
          String q = req.getParam("nodeId");
          if (san == null || !san.equals(q) || !NODE.equals(san)) {
            req.response().setStatusCode(403).end("{\"code\":\"WORKER_UNAUTHORIZED\"}");
            return;
          }
          req.response().putHeader("Content-Type", "application/json")
              .setStatusCode(200).end(desiredJson);
        } else if ("POST".equals(req.method().name()) && "/api/worker/ack".equals(req.path())) {
          req.body().onComplete(bar -> {
            try {
              var ack = mapper.readTree(bar.result().toString());
              if (san == null || !san.equals(ack.path("nodeId").asText())
                  || ack.path("appliedVersion").asLong() != 1L) {
                req.response().setStatusCode(409).end("{\"code\":\"STALE_ACK\"}");
                return;
              }
              acked.countDown();
              req.response().putHeader("Content-Type", "application/json")
                  .setStatusCode(200).end("{\"appliedVersion\":1}");
            } catch (Exception e) {
              try {
                req.response().setStatusCode(400).end("{\"code\":\"INVALID_INPUT\"}");
              } catch (Exception ignored) {
              }
            }
          });
        } else {
          req.response().setStatusCode(404).end("{\"code\":\"NOT_FOUND\"}");
        }
      } catch (Exception e) {
        try {
          req.response().setStatusCode(500).end("{\"code\":\"INTERNAL_ERROR\"}");
        } catch (Exception ignored) {
        }
      }
    });
    stub.listen(CENTRAL_UDP_PORT, "127.0.0.1")
        .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

    ProcessBuilder pb = new ProcessBuilder(binary.toString());
    Map<String, String> env = pb.environment();
    env.put("CSP_WORKER_CENTRAL_URL", "https://127.0.0.1:" + CENTRAL_UDP_PORT);
    env.put("CSP_WORKER_NODE_ID", NODE);
    env.put("CSP_WORKER_TLS_CERT_FILE", certDir.resolve("worker-cert.pem").toAbsolutePath().toString());
    env.put("CSP_WORKER_TLS_KEY_FILE", certDir.resolve("worker-key.pem").toAbsolutePath().toString());
    env.put("CSP_WORKER_TLS_TRUST_FILE", certDir.resolve("ca-cert.pem").toAbsolutePath().toString());
    env.put("CSP_WORKER_TTL_MS", "30000");
    env.put("CSP_WORKER_POLL_MS", "2000");
    env.put("CSP_WORKER_CSPV2_PORT", "0");
    env.put("CSP_WORKER_UDP_PORT", String.valueOf(WORKER_UDP_PORT));
    env.put("E2E_CC_PASSWD", CC_PASS);
    pb.redirectErrorStream(true);
    workerProc = pb.start();

    java.nio.file.Path udpLog = java.nio.file.Path.of("target/native-worker-udp.log");
    var udpLogOut = java.nio.file.Files.newOutputStream(udpLog,
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    Thread udpDrainer = new Thread(() -> {
      try (var r = new java.io.BufferedReader(
          new java.io.InputStreamReader(workerProc.getInputStream()))) {
        String line;
        while ((line = r.readLine()) != null) {
          udpLogOut.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
          udpLogOut.flush();
        }
      } catch (Exception ignored) {
      }
    });
    udpDrainer.setDaemon(true);
    udpDrainer.start();

    try {
      assertTrue(acked.await(30, TimeUnit.SECONDS), "ack natif jamais recu (UDP consult)");
      assertEquals(0, udpRequestCount.get(), "aucune requete UDP avant le client");

      // Client CCcam reel : 2 ECM identiques -> 2 CW (1ere via UDP, 2e cache hit).
      NetClient client = vertx.createNetClient(new NetClientOptions().setConnectTimeout(5000));
      CountDownLatch cwLatch = new CountDownLatch(2);
      AtomicReference<byte[]> cw1 = new AtomicReference<>();
      AtomicReference<byte[]> cw2 = new AtomicReference<>();
      List<CcCardCodec.CcCard> announcedCards = new java.util.concurrent.CopyOnWriteArrayList<>();
      try {
        client.connect(CCCAM_UDP_PORT, "127.0.0.1", ar -> {
          assertTrue(ar.succeeded());
          NetSocket sock = ar.result();
          Buffer[] accHolder = {Buffer.buffer()};
          CcCrypt cliDec = new CcCrypt();
          CcCrypt cliEnc = new CcCrypt();
          int[] step = {0};
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
                } else if (decoded[1] == CcConstants.MSG_NEW_CARD) {
                  announcedCards.add(CcCardCodec.parseNewCard(payload));
                } else if (decoded[1] == CcConstants.MSG_CW_ECM && payload.length >= 16) {
                  byte[] scrambled = Arrays.copyOf(payload, 16);
                  byte[] clear = scrambled.clone();
                  CcCwCrypt.cwCrypt(clear, CLIENT_NODE, CARD_ID);
                  byte[] sync = clear.clone();
                  cliDec.crypt(sync, 16, CcConstants.ENCRYPT);
                  if (cw1.get() == null) {
                    cw1.set(clear);
                    cwLatch.countDown();
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
        assertTrue(cwLatch.await(10, TimeUnit.SECONDS), "CCcam UDP natif: deux CW attendues");
        assertArrayEquals(CW_EXPECTED, cw1.get(), "CW #1 via pair UDP simule (natif)");
        assertArrayEquals(CW_EXPECTED, cw2.get(), "CW #2 via cache hit (natif)");
        assertEquals(1, udpRequestCount.get(),
            "exactement UNE requete UDP : le 2e ECM est servi par le store natif");
        assertEquals(1, announcedCards.size(), "Client CCcam natif doit recevoir l'annonce de carte cluster");
        assertEquals(CAID, announcedCards.get(0).caid);
        client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      } finally {
        try {
          client.close();
        } catch (Exception ignored) {
        }
      }
    } finally {
      if (workerProc.isAlive()) {
        workerProc.destroy();
        workerProc.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Natif Longrun : >3 baux réels renouvelés en heartbeat même version -> sessions/listeners restent
  // ouverts sans reconnexion, arrêt renouvellement -> expiration et fermeture, révocation immédiate.
  // -----------------------------------------------------------------------------------------------

  private static final int CENTRAL_LONGRUN_PORT = 18743;
  private static final int CCCAM_LONGRUN_PORT = 18751;
  private static final int WORKER_LONGRUN_UDP_PORT = 18752;
  private static final int RAW_PEER_LONGRUN_PORT = 18753;

  @Test
  @DisplayName("Natif Longrun : >3 baux réels renouvelés -> listeners/sessions CCcam restent ouverts sans drop -> arrêt central -> failClosed à expiry")
  void nativeLongrunLeasesAndExpiry() throws Exception {
    Path binary = Path.of("target/csp-worker").toAbsolutePath();
    assumeTrue(Files.isExecutable(binary), "binaire natif requis (build -Pnative)");
    startRawUdpPeer(RAW_PEER_LONGRUN_PORT);
    ObjectMapper mapper = new ObjectMapper();

    AtomicBoolean stopRenewing = new AtomicBoolean(false);
    AtomicInteger desiredPollCount = new AtomicInteger();
    AtomicInteger ackCount = new AtomicInteger();
    CountDownLatch initialAcked = new CountDownLatch(1);

    HttpServerOptions opts = new HttpServerOptions()
        .setSsl(true)
        .setClientAuth(ClientAuth.REQUIRED)
        .setPemKeyCertOptions(new PemKeyCertOptions()
            .setCertPath(certDir.resolve("central-cert.pem").toString())
            .setKeyPath(certDir.resolve("central-key.pem").toString()))
        .setPemTrustOptions(new PemTrustOptions()
            .addCertPath(certDir.resolve("ca-cert.pem").toString()));
    stub = vertx.createHttpServer(opts);
    stub.requestHandler(req -> {
      try {
        String san = sanOf(req.connection().peerCertificates());
        if ("GET".equals(req.method().name()) && req.path().startsWith("/api/worker/desired")) {
          String q = req.getParam("nodeId");
          if (san == null || !san.equals(q) || !NODE.equals(san)) {
            req.response().setStatusCode(403).end("{\"code\":\"WORKER_UNAUTHORIZED\"}");
            return;
          }
          if (stopRenewing.get()) {
            req.response().setStatusCode(500).end("{\"code\":\"INTERNAL_ERROR\"}");
            return;
          }
          desiredPollCount.incrementAndGet();
          long now = System.currentTimeMillis();
          var r1 = new RelayWorkerContract.DcwRuleView(1L, "default", CAID, 0, 0, null, null, "none", "require_valid", "enforce checksum on default", true);
          var m1 = new RelayWorkerContract.DcwMappingView(10L, "default", CAID, 0, 0x0002, SRVID, true, true, 0L);
          var dcwPolicy = new RelayWorkerContract.DcwPolicyView(true, false, true, true, "inherit", "off", true, List.of(r1), List.of(m1));

          var desired = new RelayWorkerContract.DesiredWorkerConfig(
              NODE, 1L, now + 5000L, 5000L,
              List.of(new RelayWorkerContract.ListenerView(7L, "default", "cccam", CCCAM_LONGRUN_PORT,
                  "127.0.0.1", "manual", 0, 512, 32, 5, 60, null, null)),
              List.of(new RelayWorkerContract.CacheView("u", "udp", true, 8000L, 10000, 512, null,
                  List.of(new RelayWorkerContract.PeerView("127.0.0.1", RAW_PEER_LONGRUN_PORT)))),
              List.of(new RelayWorkerContract.LinkView(7L, "u", true, true, false, true)),
              List.of(),
              List.of(new RelayWorkerContract.UserAclView(CC_USER, true, List.of("default"))),
              List.of(new RelayWorkerContract.UserSecretView(CC_USER, "cccam", "env:E2E_CC_PASSWD", 1L, true)),
              List.of(new RelayWorkerContract.CatalogView("default", CAID, 0, "published", 1L)),
              List.of(), List.of(), dcwPolicy);
          String dJson = mapper.writeValueAsString(desired);
          req.response().putHeader("Content-Type", "application/json").setStatusCode(200).end(dJson);
        } else if ("POST".equals(req.method().name()) && "/api/worker/ack".equals(req.path())) {
          req.body().onComplete(bar -> {
            try {
              ackCount.incrementAndGet();
              initialAcked.countDown();
              req.response().putHeader("Content-Type", "application/json").setStatusCode(200).end("{\"appliedVersion\":1}");
            } catch (Exception e) {
              req.response().setStatusCode(400).end("{\"code\":\"INVALID_INPUT\"}");
            }
          });
        } else {
          req.response().setStatusCode(404).end("{\"code\":\"NOT_FOUND\"}");
        }
      } catch (Exception e) {
        req.response().setStatusCode(500).end("{\"code\":\"INTERNAL_ERROR\"}");
      }
    });
    stub.listen(CENTRAL_LONGRUN_PORT, "127.0.0.1")
        .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

    ProcessBuilder pb = new ProcessBuilder(binary.toString());
    Map<String, String> env = pb.environment();
    env.put("CSP_WORKER_CENTRAL_URL", "https://127.0.0.1:" + CENTRAL_LONGRUN_PORT);
    env.put("CSP_WORKER_NODE_ID", NODE);
    env.put("CSP_WORKER_TLS_CERT_FILE", certDir.resolve("worker-cert.pem").toAbsolutePath().toString());
    env.put("CSP_WORKER_TLS_KEY_FILE", certDir.resolve("worker-key.pem").toAbsolutePath().toString());
    env.put("CSP_WORKER_TLS_TRUST_FILE", certDir.resolve("ca-cert.pem").toAbsolutePath().toString());
    env.put("CSP_WORKER_TTL_MS", "5000");
    env.put("CSP_WORKER_POLL_MS", "1000");
    env.put("CSP_WORKER_CSPV2_PORT", "0");
    env.put("CSP_WORKER_UDP_PORT", String.valueOf(WORKER_LONGRUN_UDP_PORT));
    env.put("E2E_CC_PASSWD", CC_PASS);
    pb.redirectErrorStream(true);
    workerProc = pb.start();

    java.nio.file.Path longrunLog = java.nio.file.Path.of("target/native-worker-longrun.log");
    var lrLogOut = java.nio.file.Files.newOutputStream(longrunLog,
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    Thread lrDrainer = new Thread(() -> {
      try (var r = new java.io.BufferedReader(
          new java.io.InputStreamReader(workerProc.getInputStream()))) {
        String line;
        while ((line = r.readLine()) != null) {
          lrLogOut.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
          lrLogOut.flush();
        }
      } catch (Exception ignored) {
      }
    });
    lrDrainer.setDaemon(true);
    lrDrainer.start();

    try {
      assertTrue(initialAcked.await(30, TimeUnit.SECONDS), "ack initial jamais reçu");

      // 1. Établit UNE session cliente CCcam persistante
      NetClient client = vertx.createNetClient(new NetClientOptions().setConnectTimeout(5000));
      CountDownLatch readyForEcm = new CountDownLatch(1);
      CountDownLatch clientClosed = new CountDownLatch(1);
      AtomicReference<NetSocket> clientSocketRef = new AtomicReference<>();
      CcCrypt cliEnc = new CcCrypt();
      CcCrypt cliDec = new CcCrypt();

      List<byte[]> receivedCws = new java.util.concurrent.CopyOnWriteArrayList<>();
      AtomicReference<CountDownLatch> ecmLatchRef = new AtomicReference<>(new CountDownLatch(1));

      client.connect(CCCAM_LONGRUN_PORT, "127.0.0.1", ar -> {
        assertTrue(ar.succeeded());
        NetSocket sock = ar.result();
        clientSocketRef.set(sock);
        sock.closeHandler(v -> clientClosed.countDown());
        Buffer[] accHolder = {Buffer.buffer()};
        int[] step = {0};
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
                readyForEcm.countDown();
              } else if (decoded[1] == CcConstants.MSG_CW_ECM && payload.length >= 16) {
                byte[] scrambled = Arrays.copyOf(payload, 16);
                byte[] clear = scrambled.clone();
                CcCwCrypt.cwCrypt(clear, CLIENT_NODE, CARD_ID);
                byte[] sync = clear.clone();
                cliDec.crypt(sync, 16, CcConstants.ENCRYPT);
                receivedCws.add(clear);
                var curLatch = ecmLatchRef.get();
                if (curLatch != null) curLatch.countDown();
              }
            }
          }
        });
      });

      assertTrue(readyForEcm.await(10, TimeUnit.SECONDS), "Handshake CCcam longrun complet");

      // Cycle 1 (t ~ 0s, bail 1) : ECM #1 envoyé sur la session persistante
      sendCccamEcm(clientSocketRef.get(), cliEnc, 1);
      assertTrue(ecmLatchRef.get().await(5, TimeUnit.SECONDS), "CW #1 reçue au bail 1");
      assertArrayEquals(CW_EXPECTED, receivedCws.get(0));

      // Attente active couvrant >1 période de bail (ex: 6 secondes avec TTL=5s)
      // Durant ce temps, le worker effectue ~6 heartbeats avec la centrale
      Thread.sleep(6000);

      // Cycle 2 (t ~ 6s, bail 2) : session TOUJOURS ouverte, ECM #2 sur la MÊME socket
      ecmLatchRef.set(new CountDownLatch(1));
      sendCccamEcm(clientSocketRef.get(), cliEnc, 2);
      assertTrue(ecmLatchRef.get().await(5, TimeUnit.SECONDS), "CW #2 reçue au bail 2 sur la même session persistante");
      assertArrayEquals(CW_EXPECTED, receivedCws.get(1));

      // Attente active couvrant encore >1 période de bail (ex: 6 secondes supplémentaires, total >12s > 2.4 baux)
      Thread.sleep(6000);

      // Cycle 3 (t ~ 12s, bail 3) : ECM #3 sur la MÊME socket
      ecmLatchRef.set(new CountDownLatch(1));
      sendCccamEcm(clientSocketRef.get(), cliEnc, 3);
      assertTrue(ecmLatchRef.get().await(5, TimeUnit.SECONDS), "CW #3 reçue au bail 3 sur la même session persistante");
      assertArrayEquals(CW_EXPECTED, receivedCws.get(2));

      // Attente pour dépasser >3 baux complets (>15s avec TTL=5s)
      Thread.sleep(4000);

      // Vérifie que de multiples acks de heartbeat ont bien eu lieu
      assertTrue(ackCount.get() >= 10, "Au moins 10 heartbeats ACK reçus par la centrale : " + ackCount.get());
      assertFalse(clientClosed.getCount() == 0, "La session cliente doit rester ouverte sans aucune coupure pendant >3 baux");

      // 2. Arrêt du renouvellement central -> expiration du bail local
      stopRenewing.set(true);

      // Le bail expire au bout de TTL=5s max : le worker doit exécuter failClosed("expired")
      assertTrue(clientClosed.await(10, TimeUnit.SECONDS), "La session cliente doit être fermée à l'expiration du bail");

      // Vérification que le listener CCcam a bien été fermé (nouvelle connexion refusée)
      long deadline = System.currentTimeMillis() + 5000;
      boolean portClosed = false;
      NetClient probe = vertx.createNetClient(new NetClientOptions().setConnectTimeout(1000));
      try {
        while (System.currentTimeMillis() < deadline && !portClosed) {
          try {
            var s = probe.connect(CCCAM_LONGRUN_PORT, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
            s.close();
            Thread.sleep(300);
          } catch (Exception e) {
            portClosed = true;
          }
        }
      } finally {
        probe.close();
      }
      assertTrue(portClosed, "Le port listener CCcam doit être fermé après expiration du bail");

    } finally {
      if (workerProc.isAlive()) {
        workerProc.destroy();
        workerProc.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }
}
