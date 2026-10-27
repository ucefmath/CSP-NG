package com.bowman.cspng.connectors.cacheex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Attribution des <b>hits cache réellement servis</b> aux origines connues (lot 3).
 *
 * <p>Règles :
 * <ul>
 *   <li>Seuls les hits <b>servis à un vrai demandeur</b> sont comptés (hit
 *       instantané servi, waiter réveillé avec une CW non vide). Les probes
 *       internes ({@code peekLocalCache}, lookups spéculatifs, lectures
 *       RESENDREQ) ne sont <b>jamais</b> comptés.</li>
 *   <li>Un hit servi est compté <b>exactement une fois</b> : l'appelant choisit
 *       le point de comptage (instantané <b>ou</b> waiter), jamais les deux
 *       pour la même réponse.</li>
 *   <li>Origine {@code null}/vide = {@value #UNKNOWN} explicite (jamais de
 *       rattachement silencieux, jamais de confusion avec le volume
 *       {@code received/pushed} de {@link CacheExStatsRegistry}).</li>
 * </ul>
 *
 * <p>Mémoire bornée : {@value #MAX_ORIGINS} origines. Concurrence sûre (B3) :
 * l'éviction est effectuée <i>après</i> l'insertion, jamais dans une lambda
 * {@code computeIfAbsent}.
 */
public class CacheHitAttribution implements com.bowman.cspng.cache.CacheHitSink {

  /** Origine explicite quand aucune origine connue n'est disponible. */
  public static final String UNKNOWN = "unknown";
  /** Nombre maximum d'origines suivies. */
  public static final int MAX_ORIGINS = 512;

  private static final class Entry {
    final AtomicLong hits = new AtomicLong();
    volatile Long lastHitAtMs;
    final AtomicLong lastActivityMs = new AtomicLong(System.currentTimeMillis());
  }

  private final ConcurrentHashMap<String, Entry> origins = new ConcurrentHashMap<>();

  /** Normalise l'origine : {@code originAddress} puis {@code connectorName}, sinon {@link #UNKNOWN}. */
  public static String normalize(String originAddress, String connectorName) {
    if (originAddress != null && !originAddress.isBlank()) return originAddress;
    if (connectorName != null && !connectorName.isBlank()) return connectorName;
    return UNKNOWN;
  }

  /** Enregistre un hit réellement servi (exactement une fois par réponse servie). */
  @Override
  public void recordServedHit(String originAddress, String connectorName) {
    String origin = normalize(originAddress, connectorName);
    Entry e = origins.computeIfAbsent(origin, k -> new Entry());
    while (origins.size() > MAX_ORIGINS) {
      evictOldestOrigin();
    }
    e.hits.incrementAndGet();
    long now = System.currentTimeMillis();
    e.lastHitAtMs = now;
    e.lastActivityMs.set(now);
  }

  private void evictOldestOrigin() {
    origins.entrySet().stream()
        .min(Comparator.comparingLong(en -> en.getValue().lastActivityMs.get()))
        .ifPresent(oldest -> origins.remove(oldest.getKey(), oldest.getValue()));
  }

  /** Instantané trié par origine (origines jamais servies absentes, pas de zéros fictifs). */
  public List<HitEntry> snapshot() {
    List<HitEntry> out = new ArrayList<>(origins.size());
    for (Map.Entry<String, Entry> e : origins.entrySet()) {
      out.add(new HitEntry(e.getKey(), e.getValue().hits.get(), e.getValue().lastHitAtMs));
    }
    out.sort(Comparator.comparing(HitEntry::origin));
    return out;
  }

  public int size() {
    return origins.size();
  }

  /** Vue immuable d'une origine servie. */
  public record HitEntry(String origin, long hits, Long lastHitAtMs) {}
}
