package com.bowman.cspng.app.runtime;

import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ConfigWatcher;
import com.bowman.cspng.config.RelayWorkerContract;
import com.bowman.cspng.connectors.routing.RelayCachePolicy;
import com.bowman.cspng.connectors.routing.RelayCacheStores;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service central de politique relais (ADR 0002, FIX Sonnet #3/#4/#5).
 *
 * <p>Lifecycle reel : abonne a {@link ConfigWatcher#CONFIG_RELOADED_ADDRESS} au
 * demarrage, reconstruit la politique a chaque reload (sessions deja ouvertes
 * voient la nouvelle politique via supplier live, sans refresh opportuniste
 * par connexion). Fail-closed : snapshot absent => politique vide.
 *
 * <p>Expiration configurable bornee : {@code csp.relay.worker-config-ttl-ms}
 * defaut 30000, bornes 5000..300000.
 *
 * <p>ack persistant : {@link #recordAck} reste un garde memoire synchrone pour
 * les tests ; la source de verite est la DB via
 * {@code ConfigRepository.recordWorkerAck} (endpoint worker mTLS W4).
 */
@ApplicationScoped
public class RelayPolicyService {

  @Inject
  ConfigHolder configHolder;

  @Inject
  Vertx vertx;

  @Inject
  @ConfigProperty(name = "csp.relay.worker-config-ttl-ms", defaultValue = "30000")
  long workerConfigTtlMs;

  @Inject
  @ConfigProperty(name = "csp.relay.local-node-id")
  Optional<String> localNodeIdConfig;

  private String localNodeId;

  private final AtomicReference<RelayCachePolicy> current =
      new AtomicReference<>(RelayCachePolicy.empty(""));
  private final AtomicReference<ConfigSnapshot> appliedSnapshot = new AtomicReference<>();
  private final RelayCacheStores sharedStores = new RelayCacheStores();
  private final Map<String, Long> desiredAtMs = new ConcurrentHashMap<>();
  private volatile MessageConsumer<Object> reloadConsumer;

  // CDI
  public RelayPolicyService() {}

  RelayPolicyService(ConfigHolder holder, long ttlMs, String localNode) {
    this.configHolder = holder;
    this.workerConfigTtlMs = RelayWorkerContract.normalizeTtl(ttlMs);
    this.localNodeId = localNode != null ? localNode : "";
    rebuildFromHolder();
  }

  public long effectiveTtlMs() {
    try {
      return RelayWorkerContract.normalizeTtl(workerConfigTtlMs);
    } catch (IllegalArgumentException e) {
      return RelayWorkerContract.TTL_DEFAULT_MS;
    }
  }

  /** Demarrage : snapshot initial + abonnement reload (FIX #3). */
  public void onStart(@Observes @Priority(250) StartupEvent event) {
    rebuildFromHolder();
    if (vertx != null) {
      reloadConsumer = vertx.eventBus().consumer(ConfigWatcher.CONFIG_RELOADED_ADDRESS,
          msg -> onConfigReloaded());
    }
  }

  public void onStop(@Observes @Priority(10) ShutdownEvent event) {
    if (reloadConsumer != null) {
      reloadConsumer.unregister();
      reloadConsumer = null;
    }
  }

  /** Chemin evenementiel : appele sur chaque CONFIG_RELOADED (testable). */
  void onConfigReloaded() {
    rebuildFromHolder();
  }

  private String effectiveLocalNodeId() {
    if (localNodeId != null) return localNodeId;
    return localNodeIdConfig != null ? localNodeIdConfig.orElse("") : "";
  }

  /** Reconstruit depuis le holder ; purge les caches devenus totalement detaches. */
  private synchronized void rebuildFromHolder() {
    String localNode = effectiveLocalNodeId();
    ConfigSnapshot snap = null;
    try {
      if (configHolder != null && configHolder.isInitialized()) snap = configHolder.get();
    } catch (Exception ignored) {
    }
    if (snap == null) {
      current.set(RelayCachePolicy.empty(localNode));
      appliedSnapshot.set(null);
      return;
    }
    // FIX #6 : localNodeId obligatoire si relais actif (fail-closed sinon).
    var nodeErrs = com.bowman.cspng.config.ConfigValidator
        .validateRelayRequiresLocalNode(snap, localNode);
    if (!nodeErrs.isEmpty()) {
      java.util.logging.Logger.getLogger(RelayPolicyService.class.getName()).severe(
          "Relay active without csp.relay.local-node-id — policy forced empty (fail-closed)");
      appliedSnapshot.set(snap);
      current.set(RelayCachePolicy.empty(""));
      return;
    }
    RelayCachePolicy old = current.get();
    RelayCachePolicy next = new RelayCachePolicy(
        localNode,
        snap.relayCaches(), snap.listenerCacheLinks(), snap.relayNodes());
    appliedSnapshot.set(snap);
    current.set(next);
    long now = System.currentTimeMillis();
    for (var n : snap.relayNodes()) desiredAtMs.putIfAbsent(n.nodeName(), now);
    // Purge ciblee : caches sans aucun lien enabled => clear de ce seul cache
    // (detacher c1 ne touche jamais c2). Revocation node => clear de ses caches node.
    if (old != null) {
      Set<String> attached = new HashSet<>();
      for (var l : snap.listenerCacheLinks()) {
        if (l.enabled() && (l.canConsult() || l.canFeed() || l.canExport())) {
          var c = snap.relayCacheByName().get(l.cacheName());
          if (c != null && c.enabled()) attached.add(l.cacheName());
        }
      }
      for (var c : snap.relayCaches()) {
        if (!attached.contains(c.name())) sharedStores.clear(c.name());
        if ("node".equals(c.kind()) && !next.isNodeUsable(c.nodeName())) sharedStores.clear(c.name());
      }
    }
    // Assure les stores bornes pour les caches actifs (mutualises, sur).
    for (var c : snap.relayCaches()) {
      if (c.enabled()) sharedStores.ensure(c.name(), c);
    }
  }

  /**
   * @deprecated Appels opportunistes supprimes (FIX #3). Conserve pour compat
   * de tests existants ; delegue au rebuild evenementiel.
   */
  @Deprecated
  public synchronized void refresh() {
    rebuildFromHolder();
  }

  public RelayCachePolicy policy() {
    return current.get();
  }

  /** Stores partages mutualises (sur : partition par cache + cle eligibleSet). */
  public RelayCacheStores stores() {
    return sharedStores;
  }

  public ConfigSnapshot snapshot() {
    return appliedSnapshot.get();
  }

  /** Node utilisable ? (enabled+authorized+!revoked). Expiration geree separement. */
  public boolean isNodeUsable(String nodeName) {
    RelayCachePolicy p = current.get();
    return p != null && p.isNodeUsable(nodeName);
  }

  /** Config expiree ? (now - desiredAt > ttl) => fail-closed cote worker/central. */
  public boolean isConfigExpired(String nodeName, long nowMs) {
    Long since = desiredAtMs.get(nodeName);
    if (since == null) return true;
    return (nowMs - since) > effectiveTtlMs();
  }

  /**
   * Garde memoire synchrone (tests). La persistance reelle est en DB via
   * {@code ConfigRepository.recordWorkerAck} (endpoint worker mTLS).
   */
  public synchronized Optional<Long> recordAck(String authenticatedSanNodeId, String claimedNodeId,
                                               long appliedVersion, long nowMs) {
    if (authenticatedSanNodeId == null || claimedNodeId == null) return Optional.empty();
    if (!authenticatedSanNodeId.equals(claimedNodeId)) return Optional.empty();
    ConfigSnapshot snap = appliedSnapshot.get();
    if (snap == null) return Optional.empty();
    var node = snap.relayNodeByName().get(claimedNodeId);
    if (node == null || !node.usable()) return Optional.empty();
    if (isConfigExpired(claimedNodeId, nowMs)) return Optional.empty();
    if (appliedVersion != node.desiredVersion()) return Optional.empty();
    return Optional.of(appliedVersion);
  }

  /** Vue status : applied depuis DB (jamais de 0 fictif, null si jamais acke). */
  public record NodeStatus(String nodeName, boolean enabled, boolean authorized, boolean revoked,
                           long desiredVersion, Long appliedVersion, Long appliedAtMs,
                           boolean usable, boolean expired, boolean stale,
                           Long servedResponses, Boolean reachable) {}

  public List<NodeStatus> nodeStatuses(long nowMs) {
    ConfigSnapshot snap = appliedSnapshot.get();
    if (snap == null) return List.of();
    return snap.relayNodes().stream()
        .map(n -> {
          Long effApplied = n.appliedVersion();
          boolean expired = isConfigExpired(n.nodeName(), nowMs);
          boolean stale = effApplied == null || effApplied != n.desiredVersion();
          long served = servedResponses(n.nodeName());
          Boolean reachable = reachable(n.nodeName(), nowMs);
          return new NodeStatus(n.nodeName(), n.enabled(), n.authorized(), n.revoked(),
              n.desiredVersion(), effApplied, null,
              n.usable() && !expired, expired, stale,
              served, reachable);
        })
        .sorted((a, b) -> a.nodeName().compareTo(b.nodeName()))
        .toList();
  }

  /**
   * Utilite reelle (Gemini FIX) : reponses SERVIES depuis les caches
   * (consultHits seuls — les feedPuts mesurent l'alimentation, pas l'utilite).
   * Caches locaux partages non attribuables a un node : comptes via les liens
   * du snapshot restreints aux caches rattaches au node ou consultables par
   * ses listeners affectes... par prudence on ne compte que les caches
   * {@code node} appartenant au node (+ consultHits directs). Les stats
   * par-cache brutes restent exposees dans /status.
   */
  long servedResponses(String nodeName) {
    ConfigSnapshot snap = appliedSnapshot.get();
    if (snap == null) return 0L;
    long total = 0L;
    for (var c : snap.relayCaches()) {
      if (nodeName != null && nodeName.equals(c.nodeName())) {
        total += sharedStores.consultHits(c.name());
      }
    }
    return total;
  }

  /**
   * Joignabilite transport (Gemini FIX) : {@code null} tant qu'aucune vraie
   * observation (last_seen_at DB via desired-pull/ack) ; {@code false} si
   * revoque/desactive/expire ou observation perimee (> 2×TTL) ;
   * {@code true} seulement sur observation fraiche + utilisable.
   * Un ack ancien ne prouve jamais le transport.
   */
  Boolean reachable(String nodeName, long nowMs) {
    ConfigSnapshot snap = appliedSnapshot.get();
    if (snap == null) return null;
    var n = snap.relayNodeByName().get(nodeName);
    if (n == null) return null;
    if (!n.usable()) return false;
    if (isConfigExpired(nodeName, nowMs)) return false;
    if (n.lastSeenAt() == null) return null;
    long ageMs = nowMs - n.lastSeenAt().toEpochMilli();
    if (ageMs < 0) return null;
    if (ageMs > 2L * effectiveTtlMs()) return false;
    return true;
  }

  public Instant expiresAt(String nodeName) {
    Long since = desiredAtMs.get(nodeName);
    if (since == null) return null;
    return Instant.ofEpochMilli(since + effectiveTtlMs());
  }
}
