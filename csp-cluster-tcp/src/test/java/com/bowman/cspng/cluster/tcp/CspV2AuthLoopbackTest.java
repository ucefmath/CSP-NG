package com.bowman.cspng.cluster.tcp;

import com.bowman.cspng.codec.cspv2.CspV2Payload;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Flux CSPv2 authentifie reel en loopback : CA openssl + certs avec SAN URI
 * {@code urn:csp:node:<id>}, mTLS mutuel, round-trip ECM allowliste, rejet
 * d'un pair inconnu (cert CA valide mais node non allowliste), echec ferme
 * apres stop (revocation/timeout). Generes sous {@code target/} (non commites).
 */
@ExtendWith(VertxExtension.class)
@DisplayName("CspV2AuthLoopback — mTLS reel, allowlist, rejet inconnu, stop")
class CspV2AuthLoopbackTest {

  // Ports loopback fixes documentes (tests uniquement).
  private static final int PORT_A = 14641;
  private static final int PORT_B = 14642;

  private static final byte[] CW = {1, 2, 3, 6, 5, 6, 7, 18, 9, 10, 11, 30, 13, 14, 15, 42};

  private static Path certDir;
  private Vertx vertx;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (vertx != null) {
      var l = new java.util.concurrent.CountDownLatch(1);
      vertx.close(v -> l.countDown());
      assertTrue(l.await(10, TimeUnit.SECONDS));
    }
  }

  @BeforeAll
  static void genCerts() throws Exception {
    certDir = Path.of("target/test-certs-cspv2");
    Files.createDirectories(certDir);
    if (Files.exists(certDir.resolve("node-a-cert.pem"))) return;
    runOpenssl("req", "-x509", "-newkey", "rsa:2048", "-keyout", "ca-key.pem",
        "-out", "ca-cert.pem", "-days", "2", "-nodes", "-subj", "/CN=test-ca");
    for (String node : List.of("node-a", "node-b", "node-evil")) {
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

  private static void runOpenssl(String... args) throws Exception {
    List<String> cmd = new java.util.ArrayList<>();
    cmd.add("openssl");
    cmd.addAll(List.of(args));
    Process p = new ProcessBuilder(cmd).directory(certDir.toFile())
        .redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    int rc = p.waitFor();
    assertEquals(0, rc, "openssl failed: " + out);
  }

  private static CspV2TlsMaterial material(String node) throws Exception {
    return new CspV2TlsMaterial(
        Files.readAllBytes(certDir.resolve(node + "-cert.pem")),
        Files.readAllBytes(certDir.resolve(node + "-key.pem")),
        Files.readAllBytes(certDir.resolve("ca-cert.pem")));
  }

  private static CspV2Settings settings(String nodeId, int port, List<CspV2PeerSettings> peers) {
    String dir = certDir.toAbsolutePath().toString();
    return new CspV2Settings(true, nodeId, "127.0.0.1", port,
        1048576, 1000, 100, 10, 5000, 100,
        5000L, 5000L, 30000L, 10000L, 5000L, 10000L, 5000L, 15000L,
        100L, 30000L, 10000, 60000L, false,
        "file:" + dir + "/" + nodeId + "-cert.pem",
        "file:" + dir + "/" + nodeId + "-key.pem",
        "file:" + dir + "/ca-cert.pem", false, peers);
  }

  private CspV2Manager startManager(CspV2Settings s, CspV2TlsMaterial m,
                                    CspV2ConnectionListener l) throws Exception {
    CompletableFuture<CspV2Manager> f = new CompletableFuture<>();
    vertx.runOnContext(v -> CspV2Manager.start(vertx, s, m, l).onComplete(ar -> {
      if (ar.succeeded()) f.complete(ar.result());
      else f.completeExceptionally(ar.cause());
    }));
    return f.get(15, TimeUnit.SECONDS);
  }

  /** Miroir de CspV2Codec.ecmHash (package-prive) : le protocole exige dataHash coherent. */
  private static int ecmHash(byte[] body) {
    int h = 0;
    for (int i = 3; i < body.length; i++) h = 31 * h + (body[i] & 0xFF);
    return h;
  }

  private static CspV2Payload.EcmRequest wireReq() {
    byte[] ecm = {0x11, 0x22, 0x33, 0x44, 0x55};
    return new CspV2Payload.EcmRequest(0x80, 0x0100, 0, 0x0100, ecmHash(ecm),
        "default", ecm, 8000L);
  }

  @Test
  @DisplayName("Round-trip allowliste + rejet inconnu + echec apres stop")
  void authFlow() throws Exception {
    CspV2ConnectionListener answerer = new CspV2ConnectionListener() {
      @Override
      public Future<CspV2Payload.DcwReply> onEcmRequest(String peer, CspV2Payload.EcmRequest req) {
        return Future.succeededFuture(new CspV2Payload.DcwReply(
            req.commandTag(), req.serviceId(), req.networkId(), req.caId(), req.dataHash(),
            req.commandTag(), CspV2Payload.DcwStatus.OK, CW.clone(), ""));
      }
    };
    CspV2ConnectionListener silent = new CspV2ConnectionListener() {
      @Override
      public Future<CspV2Payload.DcwReply> onEcmRequest(String peer, CspV2Payload.EcmRequest req) {
        return Future.failedFuture("denied");
      }
    };

    CspV2Manager mgrA = null;
    CspV2Manager mgrB = null;
    CspV2Manager mgrEvil = null;
    try {
      mgrA = startManager(
          settings("node-a", PORT_A, List.of(
              new CspV2PeerSettings(true, "node-b", "localhost", PORT_B, List.of("default")))),
          material("node-a"), answerer);
      mgrB = startManager(
          settings("node-b", PORT_B, List.of(
              new CspV2PeerSettings(true, "node-a", "localhost", PORT_A, List.of("default")))),
          material("node-b"), silent);

      // Attente connexion etablie (A dial B : node-a < node-b), puis round-trip.
      CspV2Manager.CspV2PeerReply reply = null;
      Exception lastErr = null;
      long deadline = System.currentTimeMillis() + 15000;
      final CspV2Manager fB = mgrB;
      while (System.currentTimeMillis() < deadline && reply == null) {
        try {
          reply = fB.requestFromPeer("node-a", wireReq())
              .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
          lastErr = e;
          Thread.sleep(300);
        }
      }
      assertNotNull(reply, "pair allowliste injoignable: " + lastErr);
      assertEquals("node-a", reply.peerNodeId());
      assertEquals(CspV2Payload.DcwStatus.OK, reply.reply().status());
      assertArrayEquals(CW, reply.reply().cw());

      // Pair inconnu (cert CA valide, node non allowliste) : echec ferme.
      mgrEvil = startManager(
          settings("node-evil", 14643, List.of(
              new CspV2PeerSettings(true, "node-a", "localhost", PORT_A, List.of("default")))),
          material("node-evil"), silent);
      final CspV2Manager fEvil = mgrEvil;
      try {
        fEvil.requestFromPeer("node-a", wireReq())
            .toCompletionStage().toCompletableFuture().get(6, TimeUnit.SECONDS);
        fail("pair inconnu doit etre rejete");
      } catch (Exception expected) {
        // Echec ferme : A a ferme la connexion (SAN non allowliste).
      }

      // Revocation/timeout : stop A => plus de connexion etablie.
      mgrA.stop().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
      mgrA = null;
      Thread.sleep(500);
      try {
        fB.requestFromPeer("node-a", wireReq())
            .toCompletionStage().toCompletableFuture().get(6, TimeUnit.SECONDS);
        fail("apres stop, plus de connexion etablie");
      } catch (Exception expected) {
        // Echec ferme attendu.
      }
    } finally {
      if (mgrA != null) try {
        mgrA.stop().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      } catch (Exception ignored) {
      }
      if (mgrB != null) try {
        mgrB.stop().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      } catch (Exception ignored) {
      }
      if (mgrEvil != null) try {
        mgrEvil.stop().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      } catch (Exception ignored) {
      }
    }
  }
}
