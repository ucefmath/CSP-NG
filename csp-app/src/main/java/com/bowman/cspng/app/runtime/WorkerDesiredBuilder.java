package com.bowman.cspng.app.runtime;

import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.RelayWorkerContract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Construit le desired worker depuis le snapshot central (Gemini FIX : filtrage
 * strict par affectations explicites).
 *
 * <ul>
 *   <li>Listeners : uniquement affectes au node ET enabled (les deux gates).</li>
 *   <li>Connecteurs : uniquement affectes au node ET enabled.</li>
 *   <li>Profils derives = profils des listeners affectes.</li>
 *   <li>Caches/liens : uniquement enabled et rattaches aux listeners affectes.</li>
 *   <li>Users : enabled avec intersection profils affectes (profils rognes).</li>
 *   <li>Secrets/catalogue : restreints aux users/profils affectes.</li>
 * </ul>
 * Jamais de secret en valeur (refs {@code env:/file:} seules), jamais de
 * password_hash, jamais de log du JSON (appelant).
 */
public final class WorkerDesiredBuilder {

  private WorkerDesiredBuilder() {}

  public static RelayWorkerContract.DesiredWorkerConfig build(ConfigSnapshot snap,
                                                             String nodeName,
                                                             long ttlMs,
                                                             long nowMs,
                                                             List<RelayWorkerContract.UserAclView> users,
                                                             List<RelayWorkerContract.UserSecretView> secrets,
                                                             List<RelayWorkerContract.CatalogView> catalog) {
    if (snap == null) throw new IllegalArgumentException("snapshot required");
    long ttl = RelayWorkerContract.normalizeTtl(ttlMs);
    var node = snap.relayNodeByName().get(nodeName);
    if (node == null) throw new IllegalArgumentException("unknown node: " + nodeName);

    Set<Long> assignedListeners = new HashSet<>(snap.assignedListenerIds(nodeName));
    Set<String> assignedConnectors = new HashSet<>(snap.assignedConnectorNames(nodeName));

    List<RelayWorkerContract.ListenerView> listeners = new ArrayList<>();
    Set<String> assignedProfiles = new HashSet<>();
    for (var l : snap.profileListeners()) {
      if (!l.enabled()) continue;
      if (!assignedListeners.contains(l.id())) continue;
      String proto = l.protocol() == null ? "" : l.protocol().toLowerCase(java.util.Locale.ROOT);
      if (!("cccam".equals(proto) || "cs378x".equals(proto))) continue;
      listeners.add(new RelayWorkerContract.ListenerView(
          l.id(), l.profileName(), proto, l.port(),
          l.bindAddress() != null ? l.bindAddress() : "0.0.0.0",
          l.cardMode() != null ? l.cardMode() : "manual",
          l.reshareCap() != null ? l.reshareCap() : 0,
          l.maxCards(), l.maxInflight(), l.maxPerIp(), l.idleTimeoutSec(),
          l.allowList(), l.denyList()));
      assignedProfiles.add(l.profileName());
    }

    List<RelayWorkerContract.ConnectorView> connectors = new ArrayList<>();
    java.util.Set<String> neededProxies = new java.util.HashSet<>();
    for (var c : snap.connectors()) {
      if (!c.enabled()) continue;
      if (!assignedConnectors.contains(c.name())) continue;
      if (c.profileName() != null && !assignedProfiles.contains(c.profileName())) continue;
      connectors.add(new RelayWorkerContract.ConnectorView(
          c.name(), c.protocol(), c.profileName(), true,
          c.host(), c.port(), c.username(), c.passwordRef(), c.desKeyRef(),
          c.proxyName(), c.connectTimeoutMs(), c.cccamVersion(), c.reshareCap(),
          c.cardDiscovery(), c.reshareGrant(), c.newcamdDialect(), c.cacheexMode(),
          c.newcamdPasswordEncoding(),
          c.cacheexFilter(), c.cacheexMaxSend(), c.cacheexMaxHop()));
      if (c.proxyName() != null && !c.proxyName().isBlank()) neededProxies.add(c.proxyName());
    }
    List<RelayWorkerContract.ProxyView> proxies = new ArrayList<>();
    for (var p : snap.proxies()) {
      if (!neededProxies.contains(p.name())) continue;
      proxies.add(new RelayWorkerContract.ProxyView(
          p.name(), p.enabled(), p.type(), p.host(), p.port(), p.username(), p.passwordRef()));
    }
    Set<String> allowedConnectorNames = new HashSet<>();
    for (var c : connectors) allowedConnectorNames.add(c.name());

    // Caches/liens restreints aux listeners affectes.
    List<RelayWorkerContract.CacheView> caches = new ArrayList<>();
    Set<String> reachableCaches = new HashSet<>();
    for (var l : snap.listenerCacheLinks()) {
      if (!l.enabled()) continue;
      if (!assignedListeners.contains(l.listenerId())) continue;
      reachableCaches.add(l.cacheName());
    }
    for (var c : snap.relayCaches()) {
      if (!c.enabled()) continue;
      if (!reachableCaches.contains(c.name())) continue;
      // Cache node = peering DISTANT (jamais le worker lui-meme : un cache node
      // dont node_name == ce worker est une erreur de config, ignore fail-closed).
      if ("node".equals(c.kind()) && nodeName.equals(c.nodeName())) continue;
      java.util.List<RelayWorkerContract.PeerView> peers = new java.util.ArrayList<>();
      if ("udp".equals(c.kind())) {
        for (var p : snap.udpPeersForCache(c.name()))
          peers.add(new RelayWorkerContract.PeerView(p.host(), p.port()));
      } else if ("node".equals(c.kind())) {
        // Endpoint CSPv2 distant (dial sortant) ; vide = accept-only.
        var rn = snap.relayNodeByName().get(c.nodeName());
        if (rn != null && rn.cspHost() != null && !rn.cspHost().isBlank() && rn.cspPort() != null)
          peers.add(new RelayWorkerContract.PeerView(rn.cspHost(), rn.cspPort()));
      }
      caches.add(new RelayWorkerContract.CacheView(
          c.name(), c.kind(), true, c.maxAgeMs(), c.maxEntries(), c.maxInflight(),
          "node".equals(c.kind()) ? c.nodeName() : null, peers));
    }
    Set<String> keptCaches = new HashSet<>();
    for (var c : caches) keptCaches.add(c.name());
    List<RelayWorkerContract.LinkView> links = new ArrayList<>();
    for (var l : snap.listenerCacheLinks()) {
      if (!l.enabled()) continue;
      if (!assignedListeners.contains(l.listenerId())) continue;
      if (!keptCaches.contains(l.cacheName())) continue;
      links.add(new RelayWorkerContract.LinkView(
          l.listenerId(), l.cacheName(), l.canConsult(), l.canFeed(), l.canExport(), true));
    }

    List<RelayWorkerContract.UserAclView> u = new ArrayList<>();
    Set<String> keptUsers = new HashSet<>();
    if (users != null) for (var x : users) {
      if (!x.enabled()) continue;
      List<String> inter = new ArrayList<>();
      for (String p : x.profiles()) if (assignedProfiles.contains(p)) inter.add(p);
      if (inter.isEmpty()) continue;
      List<String> conns = new ArrayList<>();
      for (String cn : x.allowedConnectors()) if (allowedConnectorNames.contains(cn)) conns.add(cn);
      java.util.Map<String, List<Integer>> allow = new java.util.HashMap<>();
      x.allowedServices().forEach((k, v) -> { if (assignedProfiles.contains(k)) allow.put(k, v); });
      java.util.Map<String, List<Integer>> block = new java.util.HashMap<>();
      x.blockedServices().forEach((k, v) -> { if (assignedProfiles.contains(k)) block.put(k, v); });
      u.add(new RelayWorkerContract.UserAclView(x.userName(), true, inter, conns, allow, block));
      keptUsers.add(x.userName());
    }

    List<RelayWorkerContract.UserSecretView> secs = new ArrayList<>();
    if (secrets != null) for (var s : secrets) {
      if (!s.enabled()) continue;
      if (!keptUsers.contains(s.userName())) continue;
      secs.add(s);
    }

    List<RelayWorkerContract.CatalogView> cat = new ArrayList<>();
    if (catalog != null) for (var c : catalog) {
      if (!"published".equals(c.state())) continue;
      if (!assignedProfiles.contains(c.profile())) continue;
      cat.add(c);
    }

    // Visibilite nodes distants : uniquement les nodes references par les
    // caches node conserves (+ endpoint CSPv2 si renseigne, sinon accept-only).
    java.util.Set<String> neededNodes = new java.util.HashSet<>();
    for (var c : caches) {
      if ("node".equals(c.kind()) && c.nodeName() != null) neededNodes.add(c.nodeName());
    }
    List<RelayWorkerContract.NodeView> nodeViews = new ArrayList<>();
    var nodesByName = snap.relayNodeByName();
    for (var rn : neededNodes.stream().sorted().toList()) {
      var n = nodesByName.get(rn);
      if (n == null) continue;
      nodeViews.add(new RelayWorkerContract.NodeView(
          n.nodeName(), n.enabled(), n.authorized(), n.revoked()));
    }

    // Policy DCW : regles/mappings filtres sur les profils servis par ce worker (bornes 1000).
    RelayWorkerContract.DcwPolicyView policyView = snap.dcwPolicy() != null
        ? RelayWorkerContract.DcwPolicyView.from(snap.dcwPolicy(), assignedProfiles::contains)
        : RelayWorkerContract.DcwPolicyView.DEFAULT;

    return new RelayWorkerContract.DesiredWorkerConfig(
        nodeName, node.desiredVersion(), nowMs + ttl, ttl,
        listeners, caches, links, connectors, u, secs, cat, proxies, nodeViews, policyView,
        RelayWorkerContract.CwCycleView.from(snap.cwCycle()),
        RelayWorkerContract.PeerQuarantineView.from(snap.quarantine()),
        RelayWorkerContract.CacheExQuorumView.from(snap.quorum()));
  }

  /** Compat sans secrets/catalogue (anciens tests : filtrage assignments actif). */
  public static RelayWorkerContract.DesiredWorkerConfig build(ConfigSnapshot snap,
                                                             String nodeName,
                                                             long ttlMs,
                                                             long nowMs,
                                                             List<RelayWorkerContract.UserAclView> users) {
    return build(snap, nodeName, ttlMs, nowMs, users, List.of(), List.of());
  }
}
