package com.bowman.cspng.worker;

import com.bowman.cspng.config.ListenerCacheLinkConfig;
import com.bowman.cspng.config.RelayCacheConfig;
import com.bowman.cspng.config.RelayNodeConfig;
import com.bowman.cspng.config.CacheExQuorumConfig;
import com.bowman.cspng.config.PeerQuarantineConfig;
import com.bowman.cspng.config.RelayWorkerContract;
import com.bowman.cspng.connectors.routing.RelayCachePolicy;
import com.bowman.cspng.connectors.routing.RelayCacheStores;
import com.bowman.cspng.domain.dcw.DcwQualityPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Etat worker : politique live + stores, expiration monotone safe.
 * Fail-closed : expire => politique vide + stores purges, jamais d'ack stale.
 */
public final class WorkerState {

  private final String nodeId;
  private final AtomicReference<RelayCachePolicy> policy;
  private final AtomicReference<DcwQualityPolicy> dcwPolicy = new AtomicReference<>(DcwQualityPolicy.DEFAULT);
  private final AtomicReference<PeerQuarantineConfig> quarantine =
      new AtomicReference<>(PeerQuarantineConfig.DEFAULT);
  private final AtomicReference<CacheExQuorumConfig> quorum =
      new AtomicReference<>(CacheExQuorumConfig.DEFAULT);
  private final RelayCacheStores stores = new RelayCacheStores();
  private volatile long appliedDesired = -1L;
  private volatile long expiresAtMs = 0L;

  public WorkerState(String nodeId) {
    if (nodeId == null || !nodeId.matches("^[a-z0-9][a-z0-9._-]{0,62}$"))
      throw new IllegalArgumentException("nodeId invalid");
    this.nodeId = nodeId;
    this.policy = new AtomicReference<>(RelayCachePolicy.empty(nodeId));
  }

  public RelayCachePolicy policy() {
    return policy.get();
  }

  public DcwQualityPolicy dcwPolicy() {
    return dcwPolicy.get();
  }

  /** Config quarantaine relayée (V16) ; absente/invalide => DEFAULT (off). */
  public PeerQuarantineConfig quarantine() {
    return quarantine.get();
  }

  /** Config quorum CacheEx relayée (V16) ; absente/invalide => DEFAULT (off). */
  public CacheExQuorumConfig quorum() {
    return quorum.get();
  }

  /** Compile la policy DCW du desired ; null si invalide (absente => DEFAULT gardes historiques). */
  private static DcwQualityPolicy compileDcw(RelayWorkerContract.DesiredWorkerConfig desired) {
    try {
      return desired.dcwPolicy() != null ? desired.dcwPolicy().toDomain() : DcwQualityPolicy.DEFAULT;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  public RelayCacheStores stores() {
    return stores;
  }

  public long appliedDesired() {
    return appliedDesired;
  }

  public long expiresAtMs() {
    return expiresAtMs;
  }

  public boolean expired(long nowMs) {
    return appliedDesired < 0 || nowMs > expiresAtMs;
  }

  /**
   * Renouvelle l'expiration du bail actif lors d'un heartbeat authentifié (même version).
   * Monotone : uniquement si l'état est actif (appliedDesired == version), non expiré à nowMs,
   * et que la nouvelle date d'expiration est valide (>= nowMs).
   */
  public synchronized boolean renewExpiry(long version, long newExpiresAtMs, long nowMs) {
    if (appliedDesired < 0 || version != appliedDesired) return false;
    if (nowMs > newExpiresAtMs) return false;
    if (nowMs > expiresAtMs) return false; // déjà expiré
    if (newExpiresAtMs > this.expiresAtMs) {
      this.expiresAtMs = newExpiresAtMs;
    }
    return true;
  }

  /**
   * Valide un desired sans modifier l'etat interne (pre-check transactionnel).
   */
  public synchronized boolean validateDesired(RelayWorkerContract.DesiredWorkerConfig desired, long nowMs) {
    if (desired == null || !nodeId.equals(desired.nodeId())) return false;
    if (nowMs > desired.expiresAtMs()) return false;
    if (appliedDesired >= 0 && desired.desiredVersion() < appliedDesired) return false;
    if (compileDcw(desired) == null) return false;
    for (var c : desired.caches()) {
      try {
        if ("node".equals(c.kind()) && nodeId.equals(c.nodeName())) return false;
        new RelayCacheConfig(c.name(), c.kind(), c.nodeName(),
            c.enabled(), c.maxAgeMs(), c.maxEntries(), c.maxInflight(), 1L);
      } catch (IllegalArgumentException ignored) {
        return false;
      }
    }
    for (var l : desired.links()) {
      try {
        new ListenerCacheLinkConfig(l.listenerId(), l.cacheName(),
            l.canConsult(), l.canFeed(), l.canExport(), l.enabled(), 1L);
      } catch (IllegalArgumentException ignored) {
        return false;
      }
    }
    return true;
  }

  /**
   * Applique un desired authentifie (SAN deja verifie par le client mTLS).
   * Monotone : desired strictement inferieur refuse (pas de rollback silencieux) ;
   * expire refuse. Purge ciblee des caches detaches.
   */
  public synchronized boolean applyDesired(RelayWorkerContract.DesiredWorkerConfig desired, long nowMs) {
    if (desired == null || !nodeId.equals(desired.nodeId())) return false;
    if (nowMs > desired.expiresAtMs()) return false;
    if (desired.desiredVersion() <= appliedDesired) return false;
    // Policy DCW invalide => desired rejete entier (fail-closed, dernier bon conserve).
    DcwQualityPolicy nextDcw = compileDcw(desired);
    if (nextDcw == null) return false;
    // Configs V16 invalides => desired rejeté entier (même fail-closed).
    PeerQuarantineConfig nextQuarantine;
    CacheExQuorumConfig nextQuorum;
    try {
      nextQuarantine = desired.quarantine() != null
          ? desired.quarantine().toConfig() : PeerQuarantineConfig.DEFAULT;
      nextQuorum = desired.quorum() != null
          ? desired.quorum().toConfig() : CacheExQuorumConfig.DEFAULT;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
    List<RelayCacheConfig> caches = new ArrayList<>();
    for (var c : desired.caches()) {
      try {
        // ANTI-MASQUAGE : le nodeName d'un cache kind=node est le node DISTANT
        // (vu centrale), jamais le worker lui-meme. Un cache node pointant ce
        // worker est une erreur de config => desired rejete entier (fail-closed).
        if ("node".equals(c.kind()) && nodeId.equals(c.nodeName())) return false;
        caches.add(new RelayCacheConfig(c.name(), c.kind(), c.nodeName(),
            c.enabled(), c.maxAgeMs(), c.maxEntries(), c.maxInflight(), 1L));
      } catch (IllegalArgumentException ignored) {
        return false;
      }
    }
    List<ListenerCacheLinkConfig> links = new ArrayList<>();
    for (var l : desired.links()) {
      try {
        links.add(new ListenerCacheLinkConfig(l.listenerId(), l.cacheName(),
            l.canConsult(), l.canFeed(), l.canExport(), l.enabled(), 1L));
      } catch (IllegalArgumentException ignored) {
        return false;
      }
    }
    // Utilisabilite des nodes DISTANTS vue centrale (la policy gate les caches
    // kind=node dessus). Le worker lui-meme n'y figure jamais : pas d'auto-
    // peering, pas de masquage local-sous-nom-distant.
    List<RelayNodeConfig> remotes = new ArrayList<>();
    if (desired.nodes() != null) {
      for (var n : desired.nodes()) {
        if (nodeId.equals(n.nodeName())) continue;
        remotes.add(new RelayNodeConfig(n.nodeName(), null, n.enabled(),
            n.authorized(), n.revoked(), desired.desiredVersion(), null, 1L));
      }
    }
    RelayCachePolicy old = policy.get();
    RelayCachePolicy next = new RelayCachePolicy(nodeId, caches, links, remotes);
    // Purge ciblee des caches disparus/detaches (sans toucher les autres).
    if (old != null) {
      java.util.Set<String> keep = new java.util.HashSet<>();
      for (var l : links) if (l.enabled()) keep.add(l.cacheName());
      for (var c : caches) {
        if (!keep.contains(c.name())) stores.clear(c.name());
      }
    }
    for (var c : caches) if (c.enabled()) stores.ensure(c.name(), c);
    policy.set(next);
    dcwPolicy.set(nextDcw);
    quarantine.set(nextQuarantine);
    quorum.set(nextQuorum);
    appliedDesired = desired.desiredVersion();
    expiresAtMs = desired.expiresAtMs();
    return true;
  }

  /** Fail-closed manuel (revocation/expiry) : vide + purge. */
  public synchronized void failClosed() {
    policy.set(RelayCachePolicy.empty(nodeId));
    stores.clearAll();
    appliedDesired = -1L;
    expiresAtMs = 0L;
  }
}
