package com.bowman.cspng.connectors.cacheex;

import com.bowman.cspng.config.CacheExQuorumConfig;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Quorum CacheEx ciblé (lot 5, révisions B1/B2/R1/R3/R4) :
 * <b>comparaison exacte de CW valides</b>.
 *
 * <p>Contrat de sécurité &amp; robustesse renforcé :
 * <ul>
 *   <li><b>Double exigence de corroboration (B1)</b> : pour atteindre le quorum,
 *       un candidat CW doit être corroboré par au moins {@code minOrigins}
 *       <b>pairs authentifiés distincts</b> ({@code peerId}) <b>ET</b> au moins
 *       {@code minOrigins} <b>sources originaires distinctes</b> ({@code upstreamOrigin}).
 *       Un seul pair ne peut pas fabriquer un quorum en forgeant plusieurs
 *       {@code node_ids} ou en alternant présent/absent. Deux pairs relayant la
 *       même source originaire ne comptent que pour une seule origine.</li>
 *   <li><b>Un pair = un vote par clé ECM (R1)</b> : un pair ne peut voter que
 *       pour un seul candidat à la fois sur une clé ECM donnée. S'il émet une
 *       CW différente, son vote précédent est retiré du candidat antérieur.
 *       Un pair hostile ne peut donc pas saturer les slots candidats.</li>
 *   <li><b>Anti-starvation multi-candidats (R1)</b> : conserve jusqu'à
 *       {@value #MAX_CANDIDATES_PER_KEY} candidats par clé ECM. Si les slots
 *       sont pleins, les candidats sans corroboration active peuvent être
 *       remplacés au profit d'un candidat corroboré.</li>
 *   <li><b>Concurrence fine sans verrou global (R3)</b> : synchronisation par
 *       entrée {@code QuorumEntry}.</li>
 *   <li><b>Mode OBSERVE transparent (B2)</b> : toute CW valide est acceptée
 *       par l'appelant, les métriques sont enregistrées sans altérer le routage.</li>
 *   <li><b>Expiration live (R4)</b> : purge active sur fenêtre live, les
 *       snapshots n'exposent jamais d'entrées expirées.</li>
 * </ul>
 */
public class CacheExQuorumTracker {

  /** Nombre maximum de candidats CW distincts suivis par clé ECM. */
  public static final int MAX_CANDIDATES_PER_KEY = 4;

  /** Décision de quorum pour une observation. */
  public enum Decision {
    /** Quorum désactivé ou hors périmètre : injecter normalement. */
    BYPASSED,
    /** Mode observe : observation comptabilisée, injecter normalement. */
    OBSERVED,
    /** Mode enforce : quorum atteint (pairs distincts ET origines distinctes), injecter. */
    QUORUM,
    /** Mode enforce : en attente de corroborations suffisantes, ne pas injecter. */
    PENDING,
    /** Mode enforce : candidat sans consensus suffisant, ne pas injecter. */
    MISMATCH,
    /** Fenêtre expirée sans quorum : ne pas injecter. */
    EXPIRED
  }

  /** Identité ECM disponible (parité exacte, sans corps ECM — cf. javadoc). */
  public record QuorumKey(String profile, int caid, int provider, int sid, int parity, int dataHash) {}

  private static final class Candidate {
    final byte[] cw;
    // Association pairId -> upstreamOrigin (1 vote par pair)
    final Map<String, String> peerVotes = new LinkedHashMap<>();
    boolean quorumReached;

    Candidate(byte[] cw, String peerId, String upstreamOrigin) {
      this.cw = cw.clone();
      this.peerVotes.put(peerId, upstreamOrigin);
    }

    int distinctPeers() {
      return peerVotes.size();
    }

    int distinctOrigins() {
      return new HashSet<>(peerVotes.values()).size();
    }

    boolean satisfiesQuorum(int minOrigins) {
      return distinctPeers() >= minOrigins && distinctOrigins() >= minOrigins;
    }
  }

  private static final class QuorumEntry {
    final List<Candidate> candidates = new ArrayList<>(MAX_CANDIDATES_PER_KEY);
    final long firstSeenMs;

    QuorumEntry(byte[] cw, String peerId, String upstreamOrigin, long now) {
      this.firstSeenMs = now;
      this.candidates.add(new Candidate(cw, peerId, upstreamOrigin));
    }
  }

  private final ConcurrentHashMap<QuorumKey, QuorumEntry> keys = new ConcurrentHashMap<>();
  private final Clock clock;
  private final AtomicLong observedCount = new AtomicLong();
  private final AtomicLong quorumCount = new AtomicLong();
  private final AtomicLong pendingCount = new AtomicLong();
  private final AtomicLong mismatchCount = new AtomicLong();
  private final AtomicLong expiredCount = new AtomicLong();

  public CacheExQuorumTracker(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public CacheExQuorumTracker() {
    this(Clock.systemUTC());
  }

  /**
   * Observe une CW <b>déjà validée</b> depuis un pair authentifié et une source
   * originaire identifiée (B1).
   *
   * @param key            identité ECM (parité exacte)
   * @param cw             CW valide de 16 octets
   * @param peerId         identité du pair authentifié (ex. connectorName ou session L:user)
   * @param upstreamOrigin identité de la source originaire (ex. node:hex ou fallback transport)
   * @param cfg            configuration live
   */
  public Decision observe(QuorumKey key, byte[] cw, String peerId, String upstreamOrigin,
                          CacheExQuorumConfig cfg) {
    if (cfg == null || key == null || cw == null || cw.length != 16
        || peerId == null || peerId.isBlank()
        || CacheExQuorumConfig.MODE_OFF.equals(cfg.mode())) {
      return Decision.BYPASSED;
    }
    String safeOrigin = (upstreamOrigin != null && !upstreamOrigin.isBlank())
        ? upstreamOrigin : peerId;

    long now = clock.millis();
    QuorumEntry e = keys.get(key);
    if (e == null) {
      if (keys.size() >= cfg.maxKeys()) {
        evictOldest();
      }
      QuorumEntry created = new QuorumEntry(cw, peerId, safeOrigin, now);
      QuorumEntry prev = keys.putIfAbsent(key, created);
      if (prev == null) {
        if (CacheExQuorumConfig.MODE_OBSERVE.equals(cfg.mode())) {
          observedCount.incrementAndGet();
          return Decision.OBSERVED;
        }
        pendingCount.incrementAndGet();
        return Decision.PENDING;
      }
      e = prev;
    }
    return observeEntry(key, e, cw, peerId, safeOrigin, cfg, now);
  }

  private Decision observeEntry(QuorumKey key, QuorumEntry e, byte[] cw,
                                String peerId, String upstreamOrigin,
                                CacheExQuorumConfig cfg, long now) {
    synchronized (e) {
      if (now - e.firstSeenMs > cfg.windowMs()) {
        keys.remove(key, e);
        expiredCount.incrementAndGet();
        return Decision.EXPIRED;
      }

      // R1: Retirer le vote antérieur de ce pair sur d'autres candidats de cette clé ECM
      Candidate matchingCandidate = null;
      for (Candidate c : e.candidates) {
        if (Arrays.equals(c.cw, cw)) {
          matchingCandidate = c;
        } else {
          c.peerVotes.remove(peerId);
        }
      }

      if (matchingCandidate != null) {
        // Enregistrer / rafraîchir le vote du pair pour ce candidat
        matchingCandidate.peerVotes.put(peerId, upstreamOrigin);

        if (matchingCandidate.satisfiesQuorum(cfg.minOrigins())) {
          if (!matchingCandidate.quorumReached) {
            matchingCandidate.quorumReached = true;
            quorumCount.incrementAndGet();
          }
          return Decision.QUORUM;
        }
        if (CacheExQuorumConfig.MODE_OBSERVE.equals(cfg.mode())) {
          observedCount.incrementAndGet();
          return Decision.OBSERVED;
        }
        return Decision.PENDING;
      }

      // Nouveau candidat distinct
      mismatchCount.incrementAndGet();
      if (e.candidates.size() < MAX_CANDIDATES_PER_KEY) {
        e.candidates.add(new Candidate(cw, peerId, upstreamOrigin));
      } else {
        // R1: Si tous les slots sont occupés, tenter d'évincer un candidat sans votes actifs
        int emptyIdx = -1;
        for (int i = 0; i < e.candidates.size(); i++) {
          if (e.candidates.get(i).peerVotes.isEmpty()) {
            emptyIdx = i;
            break;
          }
        }
        if (emptyIdx >= 0) {
          e.candidates.set(emptyIdx, new Candidate(cw, peerId, upstreamOrigin));
        } else {
          // Évincer le candidat ayant le moins de votes (s'il en a moins que 2)
          int minIdx = 0;
          int minVotes = e.candidates.get(0).distinctPeers();
          for (int i = 1; i < e.candidates.size(); i++) {
            int v = e.candidates.get(i).distinctPeers();
            if (v < minVotes) {
              minVotes = v;
              minIdx = i;
            }
          }
          if (minVotes < cfg.minOrigins()) {
            e.candidates.set(minIdx, new Candidate(cw, peerId, upstreamOrigin));
          }
        }
      }

      if (CacheExQuorumConfig.MODE_OBSERVE.equals(cfg.mode())) {
        observedCount.incrementAndGet();
        return Decision.OBSERVED;
      }
      return Decision.MISMATCH;
    }
  }

  private void evictOldest() {
    keys.entrySet().stream()
        .min(Comparator.comparingLong(en -> en.getValue().firstSeenMs))
        .ifPresent(oldest -> keys.remove(oldest.getKey(), oldest.getValue()));
  }

  /**
   * Purge active des clés dont la durée depuis {@code firstSeenMs} dépasse
   * {@code windowMs}.
   */
  public void evictExpired(long windowMs) {
    long now = clock.millis();
    keys.entrySet().removeIf(en -> {
      boolean expired = (now - en.getValue().firstSeenMs) > windowMs;
      if (expired) {
        expiredCount.incrementAndGet();
      }
      return expired;
    });
  }

  public int size() {
    return keys.size();
  }

  public long observedCount() {
    return observedCount.get();
  }

  public long quorumCount() {
    return quorumCount.get();
  }

  public long pendingCount() {
    return pendingCount.get();
  }

  public long mismatchCount() {
    return mismatchCount.get();
  }

  public long expiredCount() {
    return expiredCount.get();
  }

  public QuorumStats stats() {
    return new QuorumStats(keys.size(), observedCount.get(), quorumCount.get(),
        pendingCount.get(), mismatchCount.get(), expiredCount.get());
  }

  public void clear() {
    keys.clear();
  }

  public record QuorumStats(int keys, long observed, long quorum, long pending,
                            long mismatch, long expired) {}

  /**
   * Détail borné des clés suivies (sans CW, sans secret). Exclut les clés
   * expirées selon la fenêtre live {@code liveWindowMs} (R4).
   */
  public List<QuorumKeyView> keySnapshot(int limit, long liveWindowMs) {
    long now = clock.millis();
    List<QuorumKeyView> out = new ArrayList<>();
    int n = 0;
    for (Map.Entry<QuorumKey, QuorumEntry> en : keys.entrySet()) {
      QuorumKey k = en.getKey();
      QuorumEntry e = en.getValue();
      if (now - e.firstSeenMs > liveWindowMs) {
        continue;
      }
      if (n++ >= limit) break;
      Set<String> origins = new HashSet<>();
      boolean reached = false;
      synchronized (e) {
        for (Candidate c : e.candidates) {
          origins.addAll(c.peerVotes.values());
          if (c.quorumReached) reached = true;
        }
      }
      out.add(new QuorumKeyView(k.profile(), k.caid(), k.provider(), k.sid(), k.parity(),
          k.dataHash(), new ArrayList<>(origins), e.firstSeenMs, reached));
    }
    out.sort(Comparator.comparing(QuorumKeyView::firstSeenMs));
    return out;
  }

  /** Compat pour callers sans fenêtre explicite (utilise MAX_WINDOW_MS par défaut). */
  public List<QuorumKeyView> keySnapshot(int limit) {
    return keySnapshot(limit, CacheExQuorumConfig.MAX_WINDOW_MS);
  }

  public record QuorumKeyView(String profile, int caid, int provider, int sid, int parity,
                              int dataHash, List<String> origins, long firstSeenMs, boolean quorumReached) {}
}
