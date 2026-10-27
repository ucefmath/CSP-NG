package com.bowman.cspng.connectors.routing;

import com.bowman.cspng.config.RelayCacheConfig;
import com.bowman.cspng.domain.CwReply;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stockage distinct par cache nomme (FIX Sonnet #1).
 *
 * <p>Chaque cache relais possede son {@link StrictPartitionedCache} borne
 * (maxEntries/maxInflight issus de {@link RelayCacheConfig}). Aucun partage
 * de contenu entre caches : detacher {@code c1} ne touche jamais {@code c2}.
 * Le scope ACL est porte par {@link StrictCacheKey#eligibleConnectors()} ;
 * la provenance est verifiee par l'appelant via
 * {@link RelayCachePolicy#checkProvenance}. Compteurs reels de served/puts
 * par cache (FIX #5, jamais de zero fictif : absents = null cote status).
 */
public final class RelayCacheStores {

  private final Map<String, StrictPartitionedCache> stores = new ConcurrentHashMap<>();
  private final Map<String, RelayCacheConfig> configs = new ConcurrentHashMap<>();
  private final Map<String, AtomicLong> consultHits = new ConcurrentHashMap<>();
  private final Map<String, AtomicLong> feedPuts = new ConcurrentHashMap<>();

  public StrictPartitionedCache ensure(String cacheName, RelayCacheConfig cfg) {
    int maxEntries = cfg != null ? cfg.maxEntries() : StrictPartitionedCache.DEFAULT_MAX_CAPACITY;
    int maxInflight = cfg != null ? cfg.maxInflight() : StrictPartitionedCache.DEFAULT_MAX_INFLIGHT;
    if (cfg != null) configs.put(cacheName, cfg);
    return stores.computeIfAbsent(cacheName,
        k -> new StrictPartitionedCache(maxEntries, maxInflight));
  }

  public StrictPartitionedCache get(String cacheName) {
    return stores.get(cacheName);
  }

  public CwReply peek(String cacheName, StrictCacheKey key) {
    StrictPartitionedCache s = stores.get(cacheName);
    if (s == null) return null;
    CwReply r = s.peek(key);
    if (r != null) consultHits.computeIfAbsent(cacheName, k -> new AtomicLong()).incrementAndGet();
    return r;
  }

  public void put(String cacheName, StrictCacheKey key, CwReply reply, long ttlMs) {
    StrictPartitionedCache s = stores.get(cacheName);
    if (s == null) return;
    s.put(key, reply, ttlMs);
    feedPuts.computeIfAbsent(cacheName, k -> new AtomicLong()).incrementAndGet();
  }

  /** Purge contenu + in-flight d'un cache detache/revoque (reset sessions). */
  public void clear(String cacheName) {
    StrictPartitionedCache s = stores.get(cacheName);
    if (s != null) s.clear();
  }

  public void clearAll() {
    for (StrictPartitionedCache s : stores.values()) s.clear();
  }

  public long consultHits(String cacheName) {
    AtomicLong v = consultHits.get(cacheName);
    return v == null ? 0L : v.get();
  }

  public long feedPuts(String cacheName) {
    AtomicLong v = feedPuts.get(cacheName);
    return v == null ? 0L : v.get();
  }

  public boolean hasStats(String cacheName) {
    return consultHits.containsKey(cacheName) || feedPuts.containsKey(cacheName);
  }

  public int storeCount() {
    return stores.size();
  }
}
