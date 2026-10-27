package com.bowman.cspng.worker;

import com.bowman.cspng.config.RelayCacheConfig;
import com.bowman.cspng.config.RelayWorkerContract;
import com.bowman.cspng.connectors.routing.RelayCachePolicy;
import com.bowman.cspng.connectors.routing.RelayCachePolicy;
import com.bowman.cspng.connectors.routing.RelayCacheStores;
import com.bowman.cspng.connectors.routing.StrictCacheKey;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Vertx;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Echange cache UDP reel entre deux workers en loopback (vrais datagrammes,
 * allowlist stricte, pas de mocks transport).
 */
@DisplayName("WorkerUdpExchange — echange reel 2 noeuds, detach, timeout, strict, revocation")
class WorkerUdpExchangeTest {

  // Ports de test fixes, loopback uniquement, documentes.
  private static final int PORT_A = 14541;
  private static final int PORT_B = 14542;

  private static final byte[] CW = {1, 2, 3, 6, 5, 6, 7, 18, 9, 10, 11, 30, 13, 14, 15, 42};

  private Vertx vertx;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (vertx != null) {
      CountDownLatch l = new CountDownLatch(1);
      vertx.close(v -> l.countDown());
      assertTrue(l.await(10, TimeUnit.SECONDS));
    }
  }

  private static WorkerState newState(String nodeId, boolean linkEnabled) {
    WorkerState st = new WorkerState(nodeId);
    var caches = List.of(
        new RelayWorkerContract.CacheView("u", "udp", true, 8000L, 10000, 512, null,
            List.of(new RelayWorkerContract.PeerView("127.0.0.1",
                "node-a".equals(nodeId) ? PORT_B : PORT_A))));
    var links = List.of(
        new RelayWorkerContract.LinkView(7L, "u", true, true, true, linkEnabled));
    var desired = new RelayWorkerContract.DesiredWorkerConfig(
        nodeId, 1L, System.currentTimeMillis() + 60000L, 30000L,
        List.of(), caches, links, List.of());
    assertTrue(st.applyDesired(desired, System.currentTimeMillis()));
    return st;
  }

  private static EcmKey key(int dataHash) {
    return new EcmKey(0x80, 0x0100, 0, 0x0100, dataHash);
  }

  private static EcmRequest req(EcmKey key, long maxWaitMs) {
    return new EcmRequest(key, new byte[]{0x11, 0x22}, "default",
        "127.0.0.1", "test", System.currentTimeMillis(), null, maxWaitMs);
  }

  private static StrictCacheKey skey(EcmRequest r) {
    return new StrictCacheKey("default", 0x0100, 0, 0x0100, 0x80,
        r.body(), 1L, Set.of("up1"));
  }

  private static <T> T join(io.vertx.core.Future<T> f, long timeoutMs) throws Exception {
    return f.toCompletionStage().toCompletableFuture().get(timeoutMs, TimeUnit.MILLISECONDS);
  }

  @Test
  @DisplayName("A exporte vers B, B consulte via UDP et remplit son store ; detach coupe tout")
  void twoNodeExchangeThenDetach() throws Exception {
    WorkerState stateA = newState("node-a", true);
    WorkerState stateB = newState("node-b", true);
    WorkerUdpExchange exA = new WorkerUdpExchange(vertx, stateA);
    WorkerUdpExchange exB = new WorkerUdpExchange(vertx, stateB);
    try {
      assertEquals(PORT_A, (int) join(exA.start(PORT_A), 5000));
      assertEquals(PORT_B, (int) join(exB.start(PORT_B), 5000));
      var peersA = new RelayWorkerContract.DesiredWorkerConfig(
          "node-a", 1L, System.currentTimeMillis() + 60000L, 30000L, List.of(),
          List.of(new RelayWorkerContract.CacheView("u", "udp", true, 8000L, 10000, 512, null,
              List.of(new RelayWorkerContract.PeerView("127.0.0.1", PORT_B)))),
          List.of(new RelayWorkerContract.LinkView(7L, "u", true, true, true, true)), List.of());
      var peersB = new RelayWorkerContract.DesiredWorkerConfig(
          "node-b", 1L, System.currentTimeMillis() + 60000L, 30000L, List.of(),
          List.of(new RelayWorkerContract.CacheView("u", "udp", true, 8000L, 10000, 512, null,
              List.of(new RelayWorkerContract.PeerView("127.0.0.1", PORT_A)))),
          List.of(new RelayWorkerContract.LinkView(7L, "u", true, true, true, true)), List.of());
      exA.updateBindings(peersA, Set.of("default"));
      exB.updateBindings(peersB, Set.of("default"));

      // A detient une CW fraiche (dispatch local simule) : index + export.
      EcmKey k = key(777001);
      EcmRequest reqA = req(k, 3000L);
      CwReply replyA = new CwReply(k, 0x80, CW.clone(), "up1", "127.0.0.1",
          System.currentTimeMillis());
      stateA.stores().ensure("u", new RelayCacheConfig("u", "udp", null, true, 8000L, 10000, 512, 1L));
      exA.onLocalFeed(7L, "u", replyA);

      // B consulte : miss locale -> requete UDP -> A sert depuis son index -> B remplit.
      EcmRequest reqB = req(k, 3000L);
      CwReply got = join(exB.consult(reqB, Set.of("up1"), "u", 7L, 1L, 0, 3000L), 5000);
      assertNotNull(got);
      assertFalse(got.isEmpty());
      assertArrayEquals(CW, got.cw());
      assertNotNull(stateB.stores().peek("u", skey(reqB)), "store B rempli par l'echange");
      assertNotNull(exB.avgRttMs(), "RTT mesure reel");

      // Timeout : cle inconnue, aucun pair ne repond (lien encore actif).
      EcmRequest reqUnknown = req(key(999002), 400L);
      CwReply missed = join(exB.consult(reqUnknown, Set.of("up1"), "u", 7L, 1L, 0, 400L), 3000);
      assertTrue(missed == null || missed.isEmpty(), "timeout => vide, pas de blocage");

      // Detachement LIVE cote B (lien coupe, version bump) : plus de consult.
      var noLink = new RelayWorkerContract.DesiredWorkerConfig(
          "node-b", 2L, System.currentTimeMillis() + 60000L, 30000L, List.of(),
          List.of(new RelayWorkerContract.CacheView("u", "udp", true, 8000L, 10000, 512, null,
              List.of(new RelayWorkerContract.PeerView("127.0.0.1", PORT_A)))),
          List.of(new RelayWorkerContract.LinkView(7L, "u", true, true, true, false)), List.of());
      assertTrue(stateB.applyDesired(noLink, System.currentTimeMillis()));
      exB.updateBindings(noLink, Set.of("default"));
      RelayCachePolicy pol = stateB.policy();
      assertFalse(pol.canConsult(7L, "u"));
      assertFalse(pol.canFeed(7L, "u"));
      try {
        join(exB.consult(reqB, Set.of("up1"), "u", 7L, 1L, 0, 500L), 2000);
        fail("consult apres detachement doit echouer");
      } catch (Exception expected) {
        // Echec ferme immediat : plus d'emission vers les pairs.
      }
    } finally {
      exA.failClosed();
      exB.failClosed();
    }
  }

  @Test
  @DisplayName("Allowlist stricte : expediteur inconnu droppe sans reponse")
  void strictAllowlistDropsUnknown() throws Exception {
    WorkerState stateA = newState("node-a", true);
    WorkerUdpExchange exA = new WorkerUdpExchange(vertx, stateA);
    DatagramSocket evil = vertx.createDatagramSocket(new DatagramSocketOptions());
    try {
      assertEquals(PORT_A, (int) join(exA.start(PORT_A), 5000));
      var peersA = new RelayWorkerContract.DesiredWorkerConfig(
          "node-a", 1L, System.currentTimeMillis() + 60000L, 30000L, List.of(),
          List.of(new RelayWorkerContract.CacheView("u", "udp", true, 8000L, 10000, 512, null,
              List.of(new RelayWorkerContract.PeerView("127.0.0.1", PORT_B)))),
          List.of(new RelayWorkerContract.LinkView(7L, "u", true, true, true, true)), List.of());
      exA.updateBindings(peersA, Set.of("default"));

      long before = exA.stats().notAllowlisted();
      // Trame REQUEST forgée depuis un port non allowliste.
      byte[] frame = com.bowman.cspng.codec.cluster.ClusterUdpCodec.encodeRequest(req(key(555), 1000L));
      evil.send(io.vertx.core.buffer.Buffer.buffer(frame), PORT_A, "127.0.0.1")
          .toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
      long deadline = System.currentTimeMillis() + 2000;
      while (exA.stats().notAllowlisted() == before && System.currentTimeMillis() < deadline) {
        Thread.sleep(50);
      }
      assertTrue(exA.stats().notAllowlisted() > before, "paquet inconnu comptabilise et droppe");
    } finally {
      try {
        evil.close();
      } catch (Exception ignored) {
      }
      exA.failClosed();
    }
  }

  @Test
  @DisplayName("Revocation : failClosed echoue les pendings et ferme la socket")
  void failClosedRevokes() throws Exception {
    WorkerState stateA = newState("node-a", true);
    WorkerUdpExchange exA = new WorkerUdpExchange(vertx, stateA);
    try {
      join(exA.start(PORT_A), 5000);
      var peersA = new RelayWorkerContract.DesiredWorkerConfig(
          "node-a", 1L, System.currentTimeMillis() + 60000L, 30000L, List.of(),
          List.of(new RelayWorkerContract.CacheView("u", "udp", true, 8000L, 10000, 512, null,
              List.of(new RelayWorkerContract.PeerView("127.0.0.1", PORT_B)))),
          List.of(new RelayWorkerContract.LinkView(7L, "u", true, true, true, true)), List.of());
      exA.updateBindings(peersA, Set.of("default"));
      // Pending vivant (aucun pair n'ecoute en 14542 ici... B absent => timeout long).
      var fut = exA.consult(req(key(31337), 10000L), Set.of("up1"), "u", 7L, 1L, 0, 10000L);
      exA.failClosed();
      CwReply r = join(fut, 3000);
      assertTrue(r == null || r.isEmpty(), "pending echoue au failClosed");
      // Plus de socket : consult refusee.
      var after = exA.consult(req(key(31338), 500L), Set.of("up1"), "u", 7L, 1L, 0, 500L);
      try {
        CwReply r2 = join(after, 2000);
        assertTrue(r2 == null || r2.isEmpty());
      } catch (Exception expected) {
        // Echec ferme egalement acceptable (transport arrete).
      }
    } finally {
      exA.failClosed();
    }
  }
}
