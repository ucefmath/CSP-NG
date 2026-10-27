package com.bowman.cspng.connectors.routing;

import com.bowman.cspng.config.ListenerCacheLinkConfig;
import com.bowman.cspng.config.RelayCacheConfig;
import com.bowman.cspng.config.RelayNodeConfig;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Politique relais pure (ADR 0002, sans Vert.x) : consultation/alimentation/export
 * separes par listener, fraicheur, anti-boucles, provenance, bornage inflight.
 *
 * <p>Fail-closed : tout lien absent/desactive => refus. Detachement immediat
 * (pas d'attente TTL). {@code udp} consultable uniquement via transport UDP
 * cable avec pairs allowlistes (le lien seul ne suffit pas : sans pair la
 * consultation echoue fermee cote worker).
 */
public final class RelayCachePolicy {

  private final Map<Long, Map<String, ListenerCacheLinkConfig>> linksByListener = new HashMap<>();
  private final Map<String, RelayCacheConfig> cachesByName = new HashMap<>();
  private final Map<String, RelayNodeConfig> nodesByName = new HashMap<>();
  private final String localNodeId;

  public RelayCachePolicy(String localNodeId,
                          Collection<RelayCacheConfig> caches,
                          Collection<ListenerCacheLinkConfig> links,
                          Collection<RelayNodeConfig> nodes) {
    this.localNodeId = localNodeId;
    if (caches != null) for (RelayCacheConfig c : caches) cachesByName.put(c.name(), c);
    if (nodes != null) for (RelayNodeConfig n : nodes) nodesByName.put(n.nodeName(), n);
    if (links != null) for (ListenerCacheLinkConfig l : links)
      linksByListener.computeIfAbsent(l.listenerId(), k -> new HashMap<>()).put(l.cacheName(), l);
  }

  public static RelayCachePolicy empty(String localNodeId) {
    return new RelayCachePolicy(localNodeId, List.of(), List.of(), List.of());
  }

  private ListenerCacheLinkConfig link(long listenerId, String cacheName) {
    Map<String, ListenerCacheLinkConfig> m = linksByListener.get(listenerId);
    return m == null ? null : m.get(cacheName);
  }

  public RelayCacheConfig cache(String name) {
    return cachesByName.get(name);
  }

  public java.util.Set<String> cacheNames() {
    return java.util.Set.copyOf(cachesByName.keySet());
  }

  /**
   * Consultation autorisee ? (fail-closed). Kind {@code udp} consultable
   * uniquement via transport UDP cable avec pairs allowlistes (le lien seul
   * ne suffit pas : sans transport, la consultation echoue fermee).
   */
  public boolean canConsult(long listenerId, String cacheName) {
    ListenerCacheLinkConfig l = link(listenerId, cacheName);
    if (l == null || !l.enabled() || !l.canConsult()) return false;
    RelayCacheConfig c = cachesByName.get(cacheName);
    if (c == null || !c.enabled()) return false;
    if ("node".equals(c.kind()) && !isNodeUsable(c.nodeName())) return false;
    return true;
  }

  /** Alimentation autorisee ? (fail-closed ; udp via replies distantes uniquement). */
  public boolean canFeed(long listenerId, String cacheName) {
    ListenerCacheLinkConfig l = link(listenerId, cacheName);
    if (l == null || !l.enabled() || !l.canFeed()) return false;
    RelayCacheConfig c = cachesByName.get(cacheName);
    if (c == null || !c.enabled()) return false;
    if ("node".equals(c.kind()) && !isNodeUsable(c.nodeName())) return false;
    return true;
  }

  /** Export autorise ? (udp export legacy autorise, node exige node utilisable). */
  public boolean canExport(long listenerId, String cacheName) {
    ListenerCacheLinkConfig l = link(listenerId, cacheName);
    if (l == null || !l.enabled() || !l.canExport()) return false;
    RelayCacheConfig c = cachesByName.get(cacheName);
    if (c == null || !c.enabled()) return false;
    if ("node".equals(c.kind()) && !isNodeUsable(c.nodeName())) return false;
    return true;
  }

  public boolean isNodeUsable(String nodeName) {
    if (nodeName == null) return false;
    RelayNodeConfig n = nodesByName.get(nodeName);
    if (n == null) return false;
    return n.usable();
  }

  /** Fraicheur : cachedAt + maxAge > now (bornes 500..60000 deja validees record). */
  public static boolean isFresh(long cachedAtMs, long nowMs, long maxAgeMs) {
    if (cachedAtMs <= 0 || nowMs < cachedAtMs) return false;
    long age = Math.max(500, Math.min(60000, maxAgeMs));
    return (nowMs - cachedAtMs) <= age;
  }

  /** Anti-boucle : la chaine d'origine contient deja le node local => refus. */
  public boolean isLoop(Collection<String> originChain) {
    if (originChain == null || originChain.isEmpty()) return false;
    if (localNodeId == null || localNodeId.isBlank()) return false;
    for (String o : originChain) {
      if (localNodeId.equals(o)) return true;
      if (o != null && o.equals("self") && localNodeId != null) return true;
    }
    return false;
  }

  /** Provenance : replyConnector doit appartenir a eligibleSet (UNKNOWN => reject). */
  public static boolean checkProvenance(String replyConnector, Collection<String> eligibleSet) {
    if (replyConnector == null || replyConnector.isBlank()) return false;
    if (eligibleSet == null || eligibleSet.isEmpty()) return false;
    return eligibleSet.contains(replyConnector);
  }

  /** Bornage inflight : current < max (1..8192). */
  public static boolean checkInflight(int current, int max) {
    int m = Math.max(1, Math.min(8192, max));
    return current >= 0 && current < m;
  }

  /** Caches consultables effectifs pour un listener (detachement immediat reflété). */
  public List<String> consultableCaches(long listenerId) {
    Map<String, ListenerCacheLinkConfig> m = linksByListener.get(listenerId);
    if (m == null) return List.of();
    return m.values().stream()
        .filter(l -> canConsult(listenerId, l.cacheName()))
        .map(ListenerCacheLinkConfig::cacheName)
        .sorted()
        .toList();
  }

  /** Caches alimentables effectifs pour un listener. */
  public List<String> feedableCaches(long listenerId) {
    Map<String, ListenerCacheLinkConfig> m = linksByListener.get(listenerId);
    if (m == null) return List.of();
    return m.values().stream()
        .filter(l -> canFeed(listenerId, l.cacheName()))
        .map(ListenerCacheLinkConfig::cacheName)
        .sorted()
        .toList();
  }

  /**
   * Lien d'export actif sur un cache (n'importe quel listener) : echange UDP.
   * Sert a decider si l'on repond aux requetes distantes / emet vers les pairs.
   */
  public boolean anyExportLink(String cacheName) {
    for (var m : linksByListener.values()) {
      var l = m.get(cacheName);
      if (l != null && l.enabled() && l.canExport()) {
        var c = cachesByName.get(cacheName);
        if (c != null && c.enabled()) return true;
      }
    }
    return false;
  }

  /** Lien d'alimentation actif sur un cache (n'importe quel listener). */
  public boolean anyFeedLink(String cacheName) {
    for (var m : linksByListener.values()) {
      var l = m.get(cacheName);
      if (l != null && l.enabled() && l.canFeed()) {
        var c = cachesByName.get(cacheName);
        if (c != null && c.enabled()) return true;
      }
    }
    return false;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RelayCachePolicy that)) return false;
    return Objects.equals(linksByListener, that.linksByListener)
        && Objects.equals(cachesByName, that.cachesByName)
        && Objects.equals(nodesByName, that.nodesByName)
        && Objects.equals(localNodeId, that.localNodeId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(linksByListener, cachesByName, nodesByName, localNodeId);
  }
}
