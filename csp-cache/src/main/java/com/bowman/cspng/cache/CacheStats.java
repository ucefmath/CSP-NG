package com.bowman.cspng.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compteurs exposés par {@link CacheVerticle#stats()}.
 *
 * <p>Reproduit la liste et le format de {@code DefaultCache.getUsageStats()} l.187-197 :
 * {@code pending-ecms (peak: N)}, {@code cached-ecms}, {@code timeouts}, {@code instant-hits},
 * {@code wait-hits}, {@code contested}, et {@code remote-hits} uniquement si &gt; 0.
 */
public record CacheStats(
    int pendingEcms,
    int pendingPeak,
    long cachedEcms,
    long timeouts,
    long instantHits,
    long waitHits,
    long contested,
    long remoteHits) {

  public Map<String, String> asMap() {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("pending-ecms", pendingEcms + " (peak: " + pendingPeak + ")");
    p.put("cached-ecms", String.valueOf(cachedEcms));
    p.put("timeouts", String.valueOf(timeouts));
    p.put("instant-hits", String.valueOf(instantHits));
    p.put("wait-hits", String.valueOf(waitHits));
    p.put("contested", String.valueOf(contested));
    if (remoteHits > 0) p.put("remote-hits", String.valueOf(remoteHits));
    return p;
  }
}
