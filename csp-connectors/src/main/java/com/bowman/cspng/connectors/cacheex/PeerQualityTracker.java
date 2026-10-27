package com.bowman.cspng.connectors.cacheex;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Qualité des pairs sur fenêtre glissante <b>temporelle</b> bornée par
 * <b>buckets circulaires</b> (lot 2, révision B3/B4).
 *
 * <p>Architecture mémoire &amp; complexité :
 * <ul>
 *   <li><b>Structure</b> : ring buffer de {@value #NUM_BUCKETS} buckets de
 *       {@value #BUCKET_SIZE_MS} ms couvrant une rétention plafond de
 *       {@value #RETENTION_MS} ms (1 heure).</li>
 *   <li><b>Mémoire constante</b> : {@value #MAX_PEERS} pairs maximum. Chaque
 *       pair alloue 3 tableaux primitifs de {@value #NUM_BUCKETS} {@code long}
 *       (~17 KB/pair, soit &lt; 9 MB total pour 512 pairs au lieu des ~200 MB
 *       d'un modèle à événements individuels).</li>
 *   <li><b>Chemin chaud O(1)</b> : aucun objet alloué lors de
 *       {@link #recordAccepted} ou {@link #recordInvalid} ; simple incrément
 *       dans le slot du bucket courant.</li>
 *   <li><b>Capacité</b> : supporte des volumes et des seuils
 *       {@code minSamples} jusqu'à 10 000 (et au-delà) sans aucune allocation
 *       ni dérive mémoire.</li>
 *   <li><b>Concurrence sûre (B3)</b> : l'éviction des pairs surnuméraires est
 *       effectuée <i>après</i> l'insertion, jamais à l'intérieur d'une lambda
 *       {@code computeIfAbsent}.</li>
 * </ul>
 *
 * <p>Population <b>explicitement éligible</b> : seuls les événements
 * {@code accepted} (CW acceptée et injectée) et {@code invalid} (CW rejetée pour
 * cause imputable au contenu : checksum, longueur, tout-zéro, demi-nulle,
 * digest bloqué) alimentent les compteurs. Les drops de routage/protocole
 * ({@code loop}, {@code hop}, {@code queue}, {@code flood}, {@code filtered},
 * {@code quarantine}, {@code quorum}) ne sont <b>jamais</b> enregistrés ici.
 */
public class PeerQualityTracker {

  /** Nombre maximum de pairs suivis. */
  public static final int MAX_PEERS = 512;
  /** Granularité temporelle d'un bucket (5 secondes). */
  public static final long BUCKET_SIZE_MS = 5_000L;
  /** Rétention maximale couverte par le ring buffer (1 heure). */
  public static final long RETENTION_MS = 3_600_000L;
  /** Nombre de buckets dans le ring buffer (3600s / 5s = 720). */
  public static final int NUM_BUCKETS = (int) (RETENTION_MS / BUCKET_SIZE_MS);

  private static final class PeerWindow {
    final long[] bucketEpoch = new long[NUM_BUCKETS];
    final long[] accepted = new long[NUM_BUCKETS];
    final long[] invalid = new long[NUM_BUCKETS];
    volatile long lastActivityMs;

    PeerWindow(long nowMs) {
      this.lastActivityMs = nowMs;
    }
  }

  private final ConcurrentHashMap<String, PeerWindow> peers = new ConcurrentHashMap<>();
  private final Clock clock;
  private final long defaultWindowMs;

  /**
   * @param clock           horloge (injectable en test, jamais null)
   * @param defaultWindowMs fenêtre par défaut pour {@link #quality(String)} (&gt; 0, &lt;= {@link #RETENTION_MS})
   */
  public PeerQualityTracker(Clock clock, long defaultWindowMs) {
    this.clock = Objects.requireNonNull(clock, "clock");
    if (defaultWindowMs <= 0) throw new IllegalArgumentException("defaultWindowMs must be > 0");
    this.defaultWindowMs = Math.min(defaultWindowMs, RETENTION_MS);
  }

  /** Rétention 1h, fenêtre par défaut 5 minutes, horloge système. */
  public PeerQualityTracker() {
    this(Clock.systemUTC(), 300_000L);
  }

  public long windowMs() {
    return defaultWindowMs;
  }

  /** CW acceptée et injectée au cache (population éligible). */
  public void recordAccepted(String peerId) {
    record(peerId, false);
  }

  /** CW rejetée pour cause imputable au contenu (population éligible). */
  public void recordInvalid(String peerId) {
    record(peerId, true);
  }

  private void record(String peerId, boolean isInvalid) {
    if (peerId == null || peerId.isBlank()) return;
    long now = clock.millis();
    PeerWindow w = peers.computeIfAbsent(peerId, k -> new PeerWindow(now));
    while (peers.size() > MAX_PEERS) {
      evictOldestPeer();
    }
    long epoch = now / BUCKET_SIZE_MS;
    int slot = (int) Math.floorMod(epoch, NUM_BUCKETS);
    synchronized (w) {
      if (w.bucketEpoch[slot] != epoch) {
        w.bucketEpoch[slot] = epoch;
        w.accepted[slot] = 0;
        w.invalid[slot] = 0;
      }
      if (isInvalid) {
        w.invalid[slot]++;
      } else {
        w.accepted[slot]++;
      }
      w.lastActivityMs = now;
    }
  }

  private void evictOldestPeer() {
    peers.entrySet().stream()
        .min(Comparator.comparingLong(e -> e.getValue().lastActivityMs))
        .ifPresent(oldest -> peers.remove(oldest.getKey(), oldest.getValue()));
  }

  /**
   * Purge les pairs inactifs depuis plus de {@value #RETENTION_MS} ms.
   * Appelée périodiquement par le timer propriétaire.
   */
  public void evictExpired() {
    long now = clock.millis();
    long cutoff = now - RETENTION_MS;
    peers.entrySet().removeIf(e -> e.getValue().lastActivityMs < cutoff);
  }

  /** Qualité calculée sur la fenêtre par défaut. */
  public Optional<QualitySnapshot> quality(String peerId) {
    return quality(peerId, defaultWindowMs);
  }

  /**
   * Qualité sur une fenêtre explicite (ms), plafonnée à {@value #RETENTION_MS}.
   * Utilisée notamment par la quarantaine avec le {@code windowMs} de sa config.
   */
  public Optional<QualitySnapshot> quality(String peerId, long queryWindowMs) {
    if (peerId == null || peerId.isBlank()) return Optional.empty();
    PeerWindow w = peers.get(peerId);
    if (w == null) return Optional.empty();
    long now = clock.millis();
    long effectiveWindow = Math.max(1, Math.min(queryWindowMs, RETENTION_MS));
    long currentEpoch = now / BUCKET_SIZE_MS;
    long minEpoch = (now - effectiveWindow) / BUCKET_SIZE_MS;
    long retentionMinEpoch = (now - RETENTION_MS) / BUCKET_SIZE_MS;

    long sumAccepted = 0;
    long sumInvalid = 0;
    synchronized (w) {
      for (int i = 0; i < NUM_BUCKETS; i++) {
        long ep = w.bucketEpoch[i];
        if (ep >= minEpoch && ep <= currentEpoch && ep >= retentionMinEpoch) {
          sumAccepted += w.accepted[i];
          sumInvalid += w.invalid[i];
        }
      }
    }
    long total = sumAccepted + sumInvalid;
    if (total == 0) return Optional.empty();
    int ratioBps = (int) ((sumInvalid * 10_000L) / total);
    return Optional.of(new QualitySnapshot(peerId, sumAccepted, sumInvalid, total, ratioBps, effectiveWindow));
  }

  /** Instantané trié par peerId (uniquement les pairs ayant du trafic actif dans la fenêtre). */
  public List<QualitySnapshot> snapshot() {
    List<QualitySnapshot> out = new ArrayList<>();
    for (String peerId : peers.keySet()) {
      quality(peerId).ifPresent(out::add);
    }
    out.sort(Comparator.comparing(QualitySnapshot::peerId));
    return out;
  }

  /** Remise à zéro totale (tests / action admin). */
  public void clear() {
    peers.clear();
  }

  public int size() {
    return peers.size();
  }

  /**
   * Vue immuable : {@code total = accepted + invalid} (population éligible),
   * {@code invalidRatioBps} en points de base (0..10000).
   */
  public record QualitySnapshot(String peerId, long accepted, long invalid, long total,
                                int invalidRatioBps, long windowMs) {}
}
