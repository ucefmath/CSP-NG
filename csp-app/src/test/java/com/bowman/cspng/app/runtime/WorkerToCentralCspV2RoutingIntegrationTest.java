package com.bowman.cspng.app.runtime;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.cluster.tcp.CspV2ConnectionListener;
import com.bowman.cspng.cluster.tcp.CspV2Manager;
import com.bowman.cspng.cluster.tcp.CspV2PeerSettings;
import com.bowman.cspng.cluster.tcp.CspV2Settings;
import com.bowman.cspng.cluster.tcp.CspV2TlsMaterial;
import com.bowman.cspng.codec.cspv2.CspV2Payload;
import com.bowman.cspng.connectors.AbstractConnector;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.connectors.catalog.CardCapability;
import com.bowman.cspng.connectors.catalog.CardDiscoveryService;
import com.bowman.cspng.connectors.catalog.CardSource;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkerToCentralCspV2RoutingIntegrationTest — Intégration worker -> centrale multi-connecteurs CSPv2")
class WorkerToCentralCspV2RoutingIntegrationTest {

  private static final int PORT_CENTRAL = 15841;
  private static final int PORT_WORKER = 15842;

  private static final byte[] CW1 = {1, 2, 3, 6, 5, 6, 7, 18, 9, 10, 11, 30, 13, 14, 15, 42};
  private static final byte[] CW2 = {2, 3, 4, 9, 6, 7, 8, 21, 10, 11, 12, 33, 14, 15, 16, 45};

  private static Path certDir;
  private Vertx vertx;
  private CspV2Manager centralManager;
  private CspV2Manager workerManager;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (centralManager != null) {
      try { centralManager.stop(); } catch (Exception ignored) {}
    }
    if (workerManager != null) {
      try { workerManager.stop(); } catch (Exception ignored) {}
    }
    if (vertx != null) {
      CountDownLatch l = new CountDownLatch(1);
      vertx.close(v -> l.countDown());
      l.await(5, TimeUnit.SECONDS);
    }
  }

  @BeforeAll
  static void genCerts() throws Exception {
    certDir = Path.of("target/test-certs-central-worker");
    Files.createDirectories(certDir);
    if (!Files.exists(certDir.resolve("worker-cert.pem"))) {
      runOpenssl("req", "-x509", "-newkey", "rsa:2048", "-keyout", "ca-key.pem",
          "-out", "ca-cert.pem", "-days", "2", "-nodes", "-subj", "/CN=test-ca");
      for (String node : List.of("central", "worker")) {
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

  private CspV2TlsMaterial material(String node) throws Exception {
    byte[] cert = Files.readAllBytes(certDir.resolve(node + "-cert.pem"));
    byte[] key = Files.readAllBytes(certDir.resolve(node + "-key.pem"));
    byte[] ca = Files.readAllBytes(certDir.resolve("ca-cert.pem"));
    return new CspV2TlsMaterial(cert, key, ca);
  }

  private static class DummyTestConnector extends AbstractConnector {
    private final String name;
    private final String profile;
    private final boolean connected;
    private final java.util.function.Function<EcmRequest, Future<CwReply>> handler;

    DummyTestConnector(String name, String profile, boolean connected,
                       java.util.function.Function<EcmRequest, Future<CwReply>> handler) {
      super(null, null, name, "127.0.0.1", 1000, profile);
      this.name = name;
      this.profile = profile;
      this.connected = connected;
      this.handler = handler;
    }

    @Override public boolean isConnected() { return connected; }
    @Override public Future<CwReply> sendEcm(EcmRequest request) {
      return handler != null ? handler.apply(request) : Future.succeededFuture(CwReply.empty(request.key()));
    }
    @Override public Future<Void> start() { return Future.succeededFuture(); }
    @Override public void stop() {}
    @Override public Future<LoginState> loginFuture() { return Future.succeededFuture(LoginState.OK); }
  }

  @Test
  @DisplayName("Worker consulte Centrale via CSPv2 -> Requête CAID 0x0500 résolue par le 2nd connecteur de la Centrale")
  void workerConsultsCentralSecondConnectorServesCw() throws Exception {
    CwsConnectorManager connectorMgr = new CwsConnectorManager(vertx, null);
    CardDiscoveryService discovery = new CardDiscoveryService(vertx);

    AtomicInteger conn1Calls = new AtomicInteger(0);
    AtomicInteger conn2Calls = new AtomicInteger(0);

    // conn1 : gère CAID 0x0100 sur profile "default"
    DummyTestConnector conn1 = new DummyTestConnector("conn1", "default", true, req -> {
      conn1Calls.incrementAndGet();
      if (req.key().caId() == 0x0100) {
        return Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), CW1, "conn1", "127.0.0.1", 1000L));
      }
      return Future.failedFuture("conn1 only supports 0x0100, got: 0x" + Integer.toHexString(req.key().caId()));
    });

    // conn2 : gère CAID 0x0500 / provider 0x032830 sur profile "default"
    DummyTestConnector conn2 = new DummyTestConnector("conn2", "default", true, req -> {
      conn2Calls.incrementAndGet();
      if (req.key().caId() == 0x0500) {
        return Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), CW2, "conn2", "127.0.0.1", 1000L));
      }
      return Future.failedFuture("conn2 only supports 0x0500, got: 0x" + Integer.toHexString(req.key().caId()));
    });

    connectorMgr.addTestConnector(conn1);
    connectorMgr.addTestConnector(conn2);

    discovery.onConnectorConnected("conn1", 1L);
    discovery.onConnectorConnected("conn2", 1L);

    discovery.onCardDiscovered("conn1", "default", 0x0100, 0, 101L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);
    discovery.updateCapabilityState("default", 0x0100, 0, CardCapability.State.PUBLISHED);

    discovery.onCardDiscovered("conn2", "default", 0x0500, 0x032830, 201L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);
    discovery.updateCapabilityState("default", 0x0500, 0x032830, CardCapability.State.PUBLISHED);

    CspV2DomainMapper mapper = new CspV2DomainMapper();
    CacheVerticle stubCache = new CacheVerticle() {
      @Override
      public CwReply peekLocalCache(com.bowman.cspng.domain.EcmKey key) {
        return null; // Force cache miss pour tester la résolution connecteur
      }
    };

    CspV2EcmHandler centralHandler = new CspV2EcmHandler(stubCache, connectorMgr, discovery, mapper, true);

    // 1. Démarrer Central CspV2Manager
    CspV2Settings centralSettings = new CspV2Settings(
        true, "central", "127.0.0.1", PORT_CENTRAL,
        1048576, 1000, 100, 10, 5000, 100,
        5000L, 5000L, 30000L, 10000L, 5000L, 10000L, 5000L, 15000L,
        100L, 30000L, 10000, 60000L, false,
        "file:" + certDir.resolve("central-cert.pem").toAbsolutePath(),
        "file:" + certDir.resolve("central-key.pem").toAbsolutePath(),
        "file:" + certDir.resolve("ca-cert.pem").toAbsolutePath(),
        true,
        List.of(new CspV2PeerSettings(true, "worker", "localhost", PORT_WORKER, List.of("default")))
    );

    CompletableFuture<CspV2Manager> centralFuture = new CompletableFuture<>();
    CspV2TlsMaterial centralMat = material("central");
    vertx.runOnContext(v -> CspV2Manager.start(vertx, centralSettings, centralMat, centralHandler)
        .onComplete(ar -> {
          if (ar.succeeded()) centralFuture.complete(ar.result());
          else centralFuture.completeExceptionally(ar.cause());
        }));
    centralManager = centralFuture.get(10, TimeUnit.SECONDS);

    // 2. Démarrer Worker CspV2Manager (central < worker lexicographiquement -> central dial worker)
    CspV2ConnectionListener workerListener = new CspV2ConnectionListener() {
      @Override public Future<CspV2Payload.DcwReply> onEcmRequest(String peerNodeId, CspV2Payload.EcmRequest wireRequest) {
        return Future.succeededFuture(CspV2EcmHandler.notFoundWire(wireRequest));
      }
      @Override public void onEstablished(String peerNodeId) {}
      @Override public void onClosed(String peerNodeId, String reason) {}
    };

    CspV2Settings workerSettings = new CspV2Settings(
        true, "worker", "127.0.0.1", PORT_WORKER,
        1048576, 1000, 100, 10, 5000, 100,
        5000L, 5000L, 30000L, 10000L, 5000L, 10000L, 5000L, 15000L,
        100L, 30000L, 10000, 60000L, false,
        "file:" + certDir.resolve("worker-cert.pem").toAbsolutePath(),
        "file:" + certDir.resolve("worker-key.pem").toAbsolutePath(),
        "file:" + certDir.resolve("ca-cert.pem").toAbsolutePath(),
        false,
        List.of(new CspV2PeerSettings(true, "central", "localhost", PORT_CENTRAL, List.of("default")))
    );

    CompletableFuture<CspV2Manager> workerFuture = new CompletableFuture<>();
    CspV2TlsMaterial workerMat = material("worker");
    vertx.runOnContext(v -> CspV2Manager.start(vertx, workerSettings, workerMat, workerListener)
        .onComplete(ar -> {
          if (ar.succeeded()) workerFuture.complete(ar.result());
          else workerFuture.completeExceptionally(ar.cause());
        }));
    workerManager = workerFuture.get(10, TimeUnit.SECONDS);

    // 3. Attendre l'établissement de la liaison mTLS CSPv2 et tester la requête pour le second connecteur (CAID 0x0500, Viaccess 0x032830)
    byte[] ecmViaccess = new byte[] {
        (byte) 0x80, (byte) 0x70, (byte) 0x13,
        (byte) 0x40, (byte) 0x02, (byte) 0x00, (byte) 0x00,
        (byte) 0x90, (byte) 0x03, (byte) 0x03, (byte) 0x28, (byte) 0x30,
        (byte) 0x80, (byte) 0x08, 0, 0, 0, 0, 0, 0, 0, 0
    };
    int hashViaccess = CspV2DomainMapper.ecmHash(ecmViaccess, 3, ecmViaccess.length - 3);
    CspV2Payload.EcmRequest reqViaccess = new CspV2Payload.EcmRequest(
        0x80, 0x0002, 0x0000, 0x0500, hashViaccess, "default", ecmViaccess, 5000);

    CspV2Manager.CspV2PeerReply peerReply2 = null;
    Exception lastErr = null;
    long deadline = System.currentTimeMillis() + 15000;
    while (System.currentTimeMillis() < deadline && peerReply2 == null) {
      try {
        peerReply2 = workerManager.requestFromPeer("central", reqViaccess)
            .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
      } catch (Exception e) {
        lastErr = e;
        Thread.sleep(200);
      }
    }
    assertNotNull(peerReply2, "Liaison CSPv2 Central <-> Worker échouée: " + lastErr);
    CspV2Payload.DcwReply dcw2 = peerReply2.reply();
    assertNotNull(dcw2);
    assertEquals(CspV2Payload.DcwStatus.OK, dcw2.status(), "La centrale doit répondre OK pour CAID 0x0500");
    assertArrayEquals(CW2, dcw2.cw(), "La CW retournée doit être CW2 du second connecteur");
    assertEquals("conn2", dcw2.connectorName(), "Le nom du connecteur exposé doit être conn2");
    assertEquals(1, conn2Calls.get(), "conn2 doit avoir été appelé");
    assertEquals(0, conn1Calls.get(), "conn1 ne doit PAS avoir été appelé pour la requête 0x0500");

    // 5. Worker consulte la Centrale pour une chaîne du 1er connecteur (CAID 0x0100)
    byte[] ecmSeca = new byte[] { (byte) 0x80, (byte) 0x70, (byte) 0x03, 0x01, 0x02, 0x03 };
    int hashSeca = CspV2DomainMapper.ecmHash(ecmSeca, 3, ecmSeca.length - 3);
    CspV2Payload.EcmRequest reqSeca = new CspV2Payload.EcmRequest(
        0x80, 0x0001, 0x0000, 0x0100, hashSeca, "default", ecmSeca, 5000);

    CompletableFuture<CspV2Manager.CspV2PeerReply> replyFuture1 = new CompletableFuture<>();
    vertx.runOnContext(v -> workerManager.requestFromPeer("central", reqSeca)
        .onComplete(ar -> {
          if (ar.succeeded()) replyFuture1.complete(ar.result());
          else replyFuture1.completeExceptionally(ar.cause());
        }));

    CspV2Manager.CspV2PeerReply peerReply1 = replyFuture1.get(5, TimeUnit.SECONDS);
    assertNotNull(peerReply1);
    CspV2Payload.DcwReply dcw1 = peerReply1.reply();
    assertEquals(CspV2Payload.DcwStatus.OK, dcw1.status());
    assertArrayEquals(CW1, dcw1.cw());
    assertEquals("conn1", dcw1.connectorName());
    assertEquals(1, conn1Calls.get(), "conn1 doit avoir été appelé exactement 1 fois");
  }
}
