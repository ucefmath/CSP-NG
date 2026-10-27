package com.bowman.cspng.connectors.routing;

import com.bowman.cspng.connectors.AbstractConnector;
import com.bowman.cspng.domain.dcw.DcwQualityPolicy;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Moteur de routage adaptatif basé sur EWMA, seuil d'échantillons et TTL.
 *
 * <p>Caractéristiques :
 * <ul>
 *   <li>Scope complet : profile, CAID, provider, serviceId, connectorName</li>
 *   <li>Séparation stricte entre qualité (succès/invalide/échec/timeout) et charge (BUSY)</li>
 *   <li>Réordonnancement uniquement parmi les candidats éligibles de même classe de contraintes</li>
 *   <li>Coldstart et expiration TTL sûrs (repli déterministe sur l'ordre historique)</li>
 *   <li>Inviolabilité : aucune amplification, 1 requête in-flight par connecteur</li>
 * </ul>
 */
public class AdaptiveRoutingEngine {

  private static final double EWMA_ALPHA = 0.2;
  private static final int MIN_SAMPLES = 5;
  private static final long TTL_MS = 300_000L; // 5 minutes
  private static final int MAX_TRACKED_SCOPES = 4096;

  private final Supplier<Long> clock;
  private record Exploration(long count, long lastSeenMs) {}
  private final ConcurrentHashMap<ScopeKey, Exploration> exploreByScope = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<ScopeKey, ConnectorScore> scores = new ConcurrentHashMap<>();

  public record ScopeKey(
      String profile,
      int caid,
      int provider,
      int sid,
      String connector
  ) {
    public ScopeKey {
      profile = profile != null ? profile.trim() : "";
      connector = connector != null ? connector.trim() : "";
    }
  }

  public static final class ConnectorScore {
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong invalidCount = new AtomicLong();
    private final AtomicLong failCount = new AtomicLong();
    private final AtomicLong timeoutCount = new AtomicLong();
    private final AtomicLong busyCount = new AtomicLong();
    /** Poids décroissants des erreurs ; les compteurs bruts restent pour l'audit. */
    private volatile double invalidWeight = 0;
    private volatile double failWeight = 0;
    private volatile double timeoutWeight = 0;
    private volatile double ewmaLatency = 0.0;
    private volatile long lastUpdatedMs = 0L;
    /** Dernier événement réel (succès/échec). Le decay ne le rafraîchit pas. */
    private volatile long lastEventMs = 0L;

    public long successCount() { return successCount.get(); }
    public long invalidCount() { return invalidCount.get(); }
    public long failCount() { return failCount.get(); }
    public long timeoutCount() { return timeoutCount.get(); }
    public long busyCount() { return busyCount.get(); }
    public double ewmaLatency() { return ewmaLatency; }
    public long lastUpdatedMs() { return lastUpdatedMs; }

    public long sampleCount() {
      return successCount.get() + invalidCount.get() + failCount.get() + timeoutCount.get();
    }

    public synchronized void updateSuccess(long latencyMs, long now) {
      double lat = Math.max(0, latencyMs);
      if (successCount.get() == 0 && ewmaLatency == 0.0) {
        ewmaLatency = lat;
      } else {
        ewmaLatency = (1.0 - EWMA_ALPHA) * ewmaLatency + EWMA_ALPHA * lat;
      }
      successCount.incrementAndGet();
      lastUpdatedMs = now;
      lastEventMs = now;
    }

    public synchronized void updateInvalid(long latencyMs, long now) {
      invalidCount.incrementAndGet();
      invalidWeight += 1.0;
      lastUpdatedMs = now;
      lastEventMs = now;
    }

    public synchronized void updateFail(long latencyMs, long now) {
      failCount.incrementAndGet();
      failWeight += 1.0;
      lastUpdatedMs = now;
      lastEventMs = now;
    }

    public synchronized void updateTimeout(long now) {
      timeoutCount.incrementAndGet();
      timeoutWeight += 1.0;
      lastUpdatedMs = now;
      lastEventMs = now;
    }

    /** Demi-vie 60s sur les poids d'erreur. Ne remet jamais un compteur brut à zéro. */
    public synchronized void decayToward(long now) {
      if (lastUpdatedMs <= 0 || now <= lastUpdatedMs) return;
      double steps = (now - lastUpdatedMs) / 60_000.0;
      double factor = Math.pow(0.5, steps);
      invalidWeight *= factor;
      failWeight *= factor;
      timeoutWeight *= factor;
      lastUpdatedMs = now;
    }

    public void updateBusy(long now) {
      busyCount.incrementAndGet();
      lastEventMs = now;
      // BUSY est une métrique de charge, pas une dégradation de la qualité intrinsèque du connecteur
    }

    public boolean isExpired(long now, long ttlMs) {
      return (now - lastEventMs) > ttlMs;
    }

    public double computePenalty() {
      long total = sampleCount();
      if (total == 0) return 0.0;
      double invRate = invalidWeight / total;
      double failRate = failWeight / total;
      double toRate = timeoutWeight / total;
      return invRate * 5000.0 + failRate * 2000.0 + toRate * 10000.0;
    }

    public double effectiveScore() {
      return ewmaLatency + computePenalty();
    }
  }

  public AdaptiveRoutingEngine() {
    this(System::currentTimeMillis);
  }

  public AdaptiveRoutingEngine(Supplier<Long> clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  private synchronized ConnectorScore getOrCreateScore(String profile, int caid, int provider, int sid, String connector) {
    ScopeKey key = new ScopeKey(profile, caid, provider, sid, connector);
    ConnectorScore score = scores.get(key);
    if (score != null) return score;
    if (scores.size() >= MAX_TRACKED_SCOPES) {
      // Purge des scores expirés si la table est pleine
      long now = clock.get();
      scores.entrySet().removeIf(e -> e.getValue().isExpired(now, TTL_MS));
      if (scores.size() >= MAX_TRACKED_SCOPES) return null;
    }
    ConnectorScore created = new ConnectorScore();
    ConnectorScore existing = scores.putIfAbsent(key, created);
    return existing != null ? existing : created;
  }

  public void recordSuccess(String profile, int caid, int provider, int sid, String connector, long latencyMs) {
    ConnectorScore s = getOrCreateScore(profile, caid, provider, sid, connector);
    if (s != null) s.updateSuccess(latencyMs, clock.get());
  }

  public void recordInvalid(String profile, int caid, int provider, int sid, String connector, long latencyMs) {
    ConnectorScore s = getOrCreateScore(profile, caid, provider, sid, connector);
    if (s != null) s.updateInvalid(latencyMs, clock.get());
  }

  public void recordFail(String profile, int caid, int provider, int sid, String connector, long latencyMs) {
    ConnectorScore s = getOrCreateScore(profile, caid, provider, sid, connector);
    if (s != null) s.updateFail(latencyMs, clock.get());
  }

  public void recordTimeout(String profile, int caid, int provider, int sid, String connector) {
    ConnectorScore s = getOrCreateScore(profile, caid, provider, sid, connector);
    if (s != null) s.updateTimeout(clock.get());
  }

  public void recordBusy(String profile, int caid, int provider, int sid, String connector) {
    ConnectorScore s = getOrCreateScore(profile, caid, provider, sid, connector);
    if (s != null) s.updateBusy(clock.get());
  }

  public Map<String, Object> getStatsView() {
    Map<String, Object> res = new LinkedHashMap<>();
    long now = clock.get();
    res.put("trackedScopes", scores.size());
    List<Map<String, Object>> list = new ArrayList<>();
    for (var entry : scores.entrySet()) {
      if (list.size() >= 100) break; // admin response bounded (scores internes restent bornés à 4096)
      ScopeKey k = entry.getKey();
      ConnectorScore s = entry.getValue();
      if (!s.isExpired(now, TTL_MS)) {
        list.add(Map.of(
            "scope", k.profile() + ":" + k.caid() + ":" + k.provider() + ":" + k.sid(),
            "connector", k.connector(),
            "ewmaLatencyMs", Math.round(s.ewmaLatency() * 10.0) / 10.0,
            "samples", s.sampleCount(),
            "success", s.successCount(),
            "invalid", s.invalidCount(),
            "fail", s.failCount(),
            "timeout", s.timeoutCount(),
            "busy", s.busyCount(),
            "effectiveScore", Math.round(s.effectiveScore() * 10.0) / 10.0
        ));
      }
    }
    res.put("scores", list);
    return res;
  }

  private boolean shouldExplore(String profile, int caid, int provider, int sid, long now) {
    ScopeKey key = new ScopeKey(profile, caid, provider, sid, "");
    if (exploreByScope.size() >= MAX_TRACKED_SCOPES && !exploreByScope.containsKey(key)) {
      exploreByScope.entrySet().removeIf(e -> now - e.getValue().lastSeenMs() > TTL_MS);
      if (exploreByScope.size() >= MAX_TRACKED_SCOPES) return false;
    }
    Exploration e = exploreByScope.compute(key, (k, old) ->
        new Exploration(old == null || now - old.lastSeenMs() > TTL_MS ? 1 : old.count() + 1, now));
    return e != null && e.count() % 10 == 0;
  }

  /**
   * Trie les candidats connecteurs éligibles. Si adaptive routing est inactif ou en cas de
   * cold start (<5 échantillons), l'ordre d'entrée est préservé.
   */
  public List<AbstractConnector> rankCandidates(
      String profile, int caid, int provider, int sid,
      List<AbstractConnector> candidates,
      DcwQualityPolicy policy
  ) {
    return rankCandidates(profile, caid, provider, sid, candidates, policy, c -> 0);
  }

  /** Respecte l'ordre de base des classes (hop, priorité) ; réordonne uniquement dans la classe. */
  public List<AbstractConnector> rankCandidates(
      String profile, int caid, int provider, int sid,
      List<AbstractConnector> candidates, DcwQualityPolicy policy,
      java.util.function.ToIntFunction<AbstractConnector> hopOf
  ) {
    long now = clock.get();
    if (policy != null && policy.adaptiveRoutingEnabled()) {
      for (ConnectorScore s : scores.values()) s.decayToward(now);
    }
    if (candidates == null || candidates.size() <= 1) return candidates;
    if (policy == null || !policy.adaptiveRoutingEnabled()) return candidates;

    boolean exploreThisRequest = shouldExplore(profile, caid, provider, sid, now);

    // Regrouper par classe de priorité pour ne réordonner qu'à l'intérieur de la même classe
    record ConstraintClass(int hop, int priority) {}
    Map<ConstraintClass, List<AbstractConnector>> byPriority = new LinkedHashMap<>();
    for (AbstractConnector c : candidates) {
      byPriority.computeIfAbsent(new ConstraintClass(hopOf.applyAsInt(c), c.getPriority()), k -> new ArrayList<>()).add(c);
    }

    List<AbstractConnector> result = new ArrayList<>(candidates.size());
    for (List<AbstractConnector> group : byPriority.values()) {
      if (group.size() <= 1) {
        result.addAll(group);
        continue;
      }

      // Pour les membres du groupe, évaluer si éligibles au classement adaptatif
      List<ScoredConnector> scored = new ArrayList<>();
      List<AbstractConnector> coldstart = new ArrayList<>();

      for (AbstractConnector c : group) {
        ScopeKey key = new ScopeKey(profile, caid, provider, sid, c.getName());
        ConnectorScore score = scores.get(key);
        if (score != null && !score.isExpired(now, TTL_MS) && score.sampleCount() >= MIN_SAMPLES) {
          scored.add(new ScoredConnector(c, score.effectiveScore(), score.invalidCount() > 0));
        } else {
          coldstart.add(c);
        }
      }

      // Trier les connecteurs scorés par score effectif (latence + pénalités)
      scored.sort(Comparator.comparingDouble(ScoredConnector::score));

      boolean exploreColdstart = !coldstart.isEmpty() && exploreThisRequest;
      if (exploreColdstart) {
        // Exploration déterministe bornée : insérer 1 candidat coldstart en tête pour collecter ses échantillons
        result.add(coldstart.get(0));
        for (ScoredConnector sc : scored) {
          if (!sc.hasInvalids()) result.add(sc.connector());
        }
        for (int i = 1; i < coldstart.size(); i++) {
          result.add(coldstart.get(i));
        }
      } else {
        for (ScoredConnector sc : scored) {
          if (!sc.hasInvalids()) result.add(sc.connector());
        }
        result.addAll(coldstart);
      }
      // Insertion en dernier des candidats avec invalides pénalisés
      for (ScoredConnector sc : scored) {
        if (sc.hasInvalids()) {
          result.add(sc.connector());
        }
      }
    }

    return result;
  }

  private record ScoredConnector(AbstractConnector connector, double score, boolean hasInvalids) {}
}
