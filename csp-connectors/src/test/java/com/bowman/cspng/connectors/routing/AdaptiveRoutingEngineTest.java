package com.bowman.cspng.connectors.routing;

import com.bowman.cspng.connectors.AbstractConnector;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.domain.dcw.DcwQualityPolicy;
import com.bowman.cspng.users.ProtocolAuthResolver;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AdaptiveRoutingEngine — EWMA, min samples, TTL, ranking par classe de contraintes")
class AdaptiveRoutingEngineTest {

  private Vertx vertx;
  private AtomicLong clock;
  private AdaptiveRoutingEngine engine;

  private static final byte[] VALID_CW = new byte[]{
      0x01, 0x02, 0x03, 0x06,
      0x04, 0x05, 0x06, 0x0F,
      0x07, 0x08, 0x09, 0x18,
      0x0A, 0x0B, 0x0C, 0x21
  };

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    clock = new AtomicLong(1_000_000L);
    engine = new AdaptiveRoutingEngine(clock::get);
  }

  @AfterEach
  void tearDown() {
    vertx.close();
  }

  private static final class MockConnector extends AbstractConnector {
    MockConnector(Vertx vertx, String name, String profile, int priority) {
      super(vertx, null, name, "127.0.0.1", 10000, profile);
      this.priority = priority;
      this.connected = true;
    }
    @Override public Future<Void> start() { return Future.succeededFuture(); }
    @Override public void stop() { this.connected = false; }
    @Override public Future<LoginState> loginFuture() { return Future.succeededFuture(LoginState.OK); }
    @Override public Future<CwReply> sendEcm(EcmRequest request) {
      return Future.succeededFuture(new CwReply(request.key(), 0x80, VALID_CW, getName(), "127.0.0.1", System.currentTimeMillis()));
    }
  }

  @Test
  @DisplayName("Coldstart : avec moins de 5 échantillons, l'ordre legacy (priorité/nom) est préservé")
  void testColdstartLegacyOrder() {
    MockConnector c1 = new MockConnector(vertx, "c1", "profile1", 50);
    MockConnector c2 = new MockConnector(vertx, "c2", "profile1", 50);

    // 2 échantillons seulement pour c1 (rapide: 20ms) et c2 (lent: 100ms)
    engine.recordSuccess("profile1", 0x1884, 0, 0x2938, "c1", 20);
    engine.recordSuccess("profile1", 0x1884, 0, 0x2938, "c1", 20);
    engine.recordSuccess("profile1", 0x1884, 0, 0x2938, "c2", 100);
    engine.recordSuccess("profile1", 0x1884, 0, 0x2938, "c2", 100);

    List<AbstractConnector> candidates = List.of(c1, c2);
    List<AbstractConnector> ranked = engine.rankCandidates(
        "profile1", 0x1884, 0, 0x2938, candidates, DcwQualityPolicy.DEFAULT.withAdaptiveRouting(true)
    );

    // Ordre inchangé car minSamples (5) non atteint
    assertEquals(List.of(c1, c2), ranked);
  }

  @Test
  @DisplayName("Après 5 échantillons, le connecteur avec meilleure EWMA latence passe en premier dans la même classe")
  void testEwmaRankingWithinSameClass() {
    MockConnector slow = new MockConnector(vertx, "a_slow", "profile1", 50);
    MockConnector fast = new MockConnector(vertx, "b_fast", "profile1", 50);

    for (int i = 0; i < 5; i++) {
      engine.recordSuccess("profile1", 0x1884, 0, 0x2938, "a_slow", 150);
      engine.recordSuccess("profile1", 0x1884, 0, 0x2938, "b_fast", 25);
    }

    List<AbstractConnector> candidates = List.of(slow, fast);
    List<AbstractConnector> ranked = engine.rankCandidates(
        "profile1", 0x1884, 0, 0x2938, candidates, DcwQualityPolicy.DEFAULT.withAdaptiveRouting(true)
    );

    // b_fast doit être premier grâce à sa latence EWMA inférieure
    assertEquals(List.of(fast, slow), ranked);
  }

  @Test
  @DisplayName("Le connecteur le plus rapide mais avec échecs/invalides est pénalisé et ne passe PAS en premier")
  void testFastInvalidIsNotPrioritized() {
    MockConnector fastBad = new MockConnector(vertx, "a_fast_bad", "profile1", 50);
    MockConnector reliable = new MockConnector(vertx, "b_reliable", "profile1", 50);

    for (int i = 0; i < 5; i++) {
      // fastBad répond vite (10ms) mais a des invalid DCW / failures
      engine.recordInvalid("profile1", 0x1884, 0, 0x2938, "a_fast_bad", 10);
      engine.recordSuccess("profile1", 0x1884, 0, 0x2938, "b_reliable", 60);
    }

    List<AbstractConnector> candidates = List.of(fastBad, reliable);
    List<AbstractConnector> ranked = engine.rankCandidates(
        "profile1", 0x1884, 0, 0x2938, candidates, DcwQualityPolicy.DEFAULT.withAdaptiveRouting(true)
    );

    // reliable doit être en tête malgré une latence plus élevée
    assertEquals(List.of(reliable, fastBad), ranked);
  }

  @Test
  @DisplayName("Expiration TTL (5 minutes) : les scores anciens expirent et reviennent au coldstart")
  void testTtlExpiration() {
    MockConnector slow = new MockConnector(vertx, "a_slow", "profile1", 50);
    MockConnector fast = new MockConnector(vertx, "b_fast", "profile1", 50);

    for (int i = 0; i < 5; i++) {
      engine.recordSuccess("profile1", 0x1884, 0, 0x2938, "a_slow", 150);
      engine.recordSuccess("profile1", 0x1884, 0, 0x2938, "b_fast", 25);
    }

    // Avancer le temps de 6 minutes (> 5 min TTL)
    clock.addAndGet(6 * 60 * 1000L);

    List<AbstractConnector> candidates = List.of(slow, fast);
    List<AbstractConnector> ranked = engine.rankCandidates(
        "profile1", 0x1884, 0, 0x2938, candidates, DcwQualityPolicy.DEFAULT.withAdaptiveRouting(true)
    );

    // Retour à l'ordre initial (cold start fallback)
    assertEquals(List.of(slow, fast), ranked);
  }

  @Test
  @DisplayName("Exploration bornée : un candidat coldstart est périodiquement inséré en tête pour être exploré")
  void testColdstartExplorationPeriodically() {
    MockConnector fastTested = new MockConnector(vertx, "fast_tested", "profile1", 50);
    MockConnector cold = new MockConnector(vertx, "cold_candidate", "profile1", 50);

    for (int i = 0; i < 5; i++) {
      engine.recordSuccess("profile1", 0x1884, 0, 0x2938, "fast_tested", 20);
    }

    boolean exploredAtLeastOnce = false;
    for (int i = 0; i < 15; i++) {
      List<AbstractConnector> ranked = engine.rankCandidates(
          "profile1", 0x1884, 0, 0x2938, List.of(fastTested, cold), DcwQualityPolicy.DEFAULT.withAdaptiveRouting(true)
      );
      if (ranked.get(0).getName().equals("cold_candidate")) {
        exploredAtLeastOnce = true;
        break;
      }
    }
    assertTrue(exploredAtLeastOnce, "Cold candidate should be sampled periodically without starvation");
  }

  @Test
  @DisplayName("Adaptatif : un candidat hop 2 plus rapide ne dépasse jamais hop 1, même priorité")
  void testHopClassCannotBeOvertaken() {
    MockConnector near = new MockConnector(vertx, "near", "profile1", 50);
    MockConnector far = new MockConnector(vertx, "far", "profile1", 50);
    for (int i = 0; i < 8; i++) {
      engine.recordSuccess("profile1", 0x1884, 0, 0x2938, "near", 300);
      engine.recordSuccess("profile1", 0x1884, 0, 0x2938, "far", 5);
    }
    assertEquals(List.of(near, far), engine.rankCandidates("profile1", 0x1884, 0, 0x2938,
        List.of(near, far), DcwQualityPolicy.DEFAULT.withAdaptiveRouting(true),
        c -> c.getName().equals("near") ? 1 : 2));
  }

  @Test
  @DisplayName("Exploration : compteur indépendant par profil/CAID/provider/SID, pas d'influence croisée")
  void testExplorationIsScoped() {
    MockConnector warm = new MockConnector(vertx, "warm", "profile1", 50);
    MockConnector cold = new MockConnector(vertx, "cold", "profile1", 50);
    for (int sid : List.of(0x100, 0x200)) {
      for (int i = 0; i < 5; i++) engine.recordSuccess("profile1", 0x1884, 0, sid, "warm", 20);
    }
    var pol = DcwQualityPolicy.DEFAULT.withAdaptiveRouting(true);
    for (int i = 0; i < 9; i++) {
      assertEquals(warm, engine.rankCandidates("profile1", 0x1884, 0, 0x100,
          List.of(warm, cold), pol).get(0));
    }
    assertEquals(warm, engine.rankCandidates("profile1", 0x1884, 0, 0x200,
        List.of(warm, cold), pol).get(0), "première requête sur autre SID ne doit pas explorer");
    assertEquals(cold, engine.rankCandidates("profile1", 0x1884, 0, 0x100,
        List.of(warm, cold), pol).get(0), "dixième requête du premier SID explore");
  }

  @Test
  @DisplayName("Decay : pénalité d'invalidité décroît avec le temps, score exposé dans /stats")
  void penaltyDecaysAndStatsExposeScore() {
    for (int i = 0; i < 5; i++) {
      engine.recordInvalid("profile1", 0x1884, 0, 0x2938, "bad", 10);
      engine.recordSuccess("profile1", 0x1884, 0, 0x2938, "good", 40);
    }
    var view = engine.getStatsView();
    var scores = (List<?>) view.get("scores");
    double before = Double.MAX_VALUE;
    for (Object o : scores) {
      var m = (java.util.Map<?, ?>) o;
      if ("bad".equals(m.get("connector"))) before = ((Number) m.get("effectiveScore")).doubleValue();
    }
    assertTrue(before > 1000, "pénalité initiale exposée, was " + before);
    clock.addAndGet(60_000L);
    engine.rankCandidates("profile1", 0x1884, 0, 0x2938, List.of(), DcwQualityPolicy.DEFAULT.withAdaptiveRouting(true));
    double after = Double.MAX_VALUE;
    for (Object o : (List<?>) engine.getStatsView().get("scores")) {
      var m = (java.util.Map<?, ?>) o;
      if ("bad".equals(m.get("connector"))) after = ((Number) m.get("effectiveScore")).doubleValue();
    }
    assertTrue(after < before, "decay réduit la pénalité " + before + " -> " + after);
    assertTrue(after > 0, "decay ne blanchit pas");
  }
}
