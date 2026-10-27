package com.bowman.cspng.connectors.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compteurs dispatch reels par connecteur (ok / echec / timeout + latence).
 * Mesures, jamais de fictif : absence de trafic => absence d'entree (pas de
 * zero plaque). Borne (128 connecteurs max, au-dela ignores).
 */
public final class DispatchStats {

  static final int MAX_CONNECTORS = 128;

  private static final class Agg {
    final AtomicLong ok = new AtomicLong();
    final AtomicLong fail = new AtomicLong();
    final AtomicLong timeout = new AtomicLong();
    final AtomicLong latSumMs = new AtomicLong();
    final AtomicLong latCount = new AtomicLong();
  }

  /** Instantane borne pour remontee (fusion multi-routeurs par nom). */
  public record Snapshot(String name, long ok, long fail, long timeout,
                         long latSumMs, long latCount) {
    /** Latence moyenne mesuree, null si aucune mesure. */
    public Long avgLatencyMs() {
      if (latCount <= 0) return null;
      return latSumMs / latCount;
    }
  }

  private final ConcurrentHashMap<String, Agg> byConnector = new ConcurrentHashMap<>();

  private Agg agg(String connector) {
    Agg a = byConnector.get(connector);
    if (a != null) return a;
    if (byConnector.size() >= MAX_CONNECTORS) return null;
    Agg created = new Agg();
    Agg prev = byConnector.putIfAbsent(connector, created);
    return prev != null ? prev : created;
  }

  public void recordOk(String connector, long latencyMs) {
    if (connector == null) return;
    Agg a = agg(connector);
    if (a == null) return;
    a.ok.incrementAndGet();
    a.latSumMs.addAndGet(Math.max(0, latencyMs));
    a.latCount.incrementAndGet();
  }

  public void recordFail(String connector, long latencyMs) {
    if (connector == null) return;
    Agg a = agg(connector);
    if (a == null) return;
    a.fail.incrementAndGet();
    a.latSumMs.addAndGet(Math.max(0, latencyMs));
    a.latCount.incrementAndGet();
  }

  public void recordTimeout(String connector) {
    if (connector == null) return;
    Agg a = agg(connector);
    if (a == null) return;
    a.timeout.incrementAndGet();
  }

  public List<Snapshot> snapshot() {
    List<Snapshot> out = new ArrayList<>(byConnector.size());
    for (Map.Entry<String, Agg> e : byConnector.entrySet()) {
      Agg a = e.getValue();
      out.add(new Snapshot(e.getKey(), a.ok.get(), a.fail.get(), a.timeout.get(),
          a.latSumMs.get(), a.latCount.get()));
    }
    out.sort((x, y) -> x.name().compareTo(y.name()));
    return out;
  }

  public void clear() {
    byConnector.clear();
  }
}
