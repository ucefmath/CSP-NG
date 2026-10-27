package com.bowman.cspng.app.runtime;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.sessions.SessionRegistry;
import com.bowman.cspng.users.UserDirectory;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runtime core CDI partagé : CacheVerticle singleton, SessionRegistry, CwsConnectorManager.
 * <p>
 * Déploie CacheVerticle une fois au startup et undeploy au shutdown avec timeout borné hors event-loop.
 * Ordre observers : après ConfigHolder (CspBootstrap @Priority 10), avant ListenerManager (@Priority 300).
 * </p>
 */
@ApplicationScoped
public class RuntimeCore {

  private static final Logger LOG = Logger.getLogger(RuntimeCore.class.getName());
  static final long DEPLOY_TIMEOUT_SECONDS = 10;
  static final long UNDEPLOY_TIMEOUT_SECONDS = 5;

  private final Vertx vertx;
  private final UserDirectory userDirectory;

  @Inject
  @ConfigProperty(name = "csp.runtime.enabled", defaultValue = "true")
  boolean runtimeEnabled;

  @Inject
  @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
  String quarkusProfile;

  @Inject
  com.bowman.cspng.config.ConfigHolder configHolder;

  @Inject
  com.bowman.cspng.domain.srvid.ServiceResolver serviceResolver;

  private volatile CacheVerticle cacheVerticle;
  private volatile String cacheDeploymentId;
  private volatile SessionRegistry sessionRegistry;
  private volatile CwsConnectorManager connectorManager;
  private volatile boolean started = false;
  /**
   * Balayage périodique TTL qualité/quarantaine/quorum (lots 2/4/5) : sans ce
   * timer, une entrée jamais retouchée (ex. clé quorum jamais reconfirmée)
   * n'est purgée QUE paresseusement au prochain accès — jamais si aucun
   * accès ultérieur ne survient (« TTL sans quorum » resterait indéfiniment
   * visible en admin). Intervalle indépendant de la config live (fenêtre
   * utilisée = plafond absolu {@link com.bowman.cspng.config.CacheExQuorumConfig#MAX_WINDOW_MS},
   * sûr même si la config courante a une fenêtre plus courte : la purge
   * paresseuse au prochain accès reste, elle, exacte sur la fenêtre live).
   */
  static final long PEER_QUALITY_SWEEP_INTERVAL_MS = 30_000L;
  private volatile Long peerQualitySweepTimerId;

  @Inject
  public RuntimeCore(Vertx vertx, UserDirectory userDirectory) {
    this.vertx = vertx;
    this.userDirectory = userDirectory;
    // Eager init ensures producers never return null even before onStart
    // (disabled = objects exist but not deployed)
    this.cacheVerticle = new CacheVerticle();
    this.cacheVerticle.setHitAttribution(cacheHitAttribution);
    this.sessionRegistry = new SessionRegistry(vertx, userDirectory);
    this.connectorManager = new CwsConnectorManager(vertx, cacheVerticle);
  }

  private synchronized void ensureInitialized() {
    if (cacheVerticle == null) cacheVerticle = new CacheVerticle();
    cacheVerticle.setHitAttribution(cacheHitAttribution);
    if (serviceResolver != null) cacheVerticle.setServiceResolver(serviceResolver);
    if (sessionRegistry == null) sessionRegistry = new SessionRegistry(vertx, userDirectory);
    if (connectorManager == null) connectorManager = new CwsConnectorManager(vertx, cacheVerticle);
    if (serviceResolver != null) connectorManager.setServiceResolver(serviceResolver);
  }

  public void onStart(@Observes @Priority(200) StartupEvent event) {
    ensureInitialized();
    if (started && cacheDeploymentId != null) {
      LOG.info("RuntimeCore already started id=" + cacheDeploymentId + " — idempotent skip");
      return;
    }
    if (!runtimeEnabled) {
      LOG.info("RuntimeCore disabled (csp.runtime.enabled=false) — skipping deploy (singleton stable)");
      return;
    }
    if (Context.isOnEventLoopThread()) {
      LOG.warning("RuntimeCore.onStart on event-loop — deploy may stall");
    }
    LOG.info("RuntimeCore starting — deploying CacheVerticle");
    // Reuse eager singleton if already created, deploy it
    CacheVerticle cv = cacheVerticle;
    try {
      String id = vertx.deployVerticle(cv)
          .toCompletionStage().toCompletableFuture()
          .get(DEPLOY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      this.cacheDeploymentId = id;
      this.started = true;
      LOG.info("CacheVerticle deployed id=" + id);
      peerQualitySweepTimerId = vertx.setPeriodic(PEER_QUALITY_SWEEP_INTERVAL_MS, tid -> sweepPeerQualityState());
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Failed to deploy CacheVerticle", e);
      // fail-fast (tous profils)
      throw new IllegalStateException("FAIL-FAST: CacheVerticle deploy failed", e);
    }
  }

  /**
   * Purge active des trackers qualité/quarantaine/quorum (timer périodique).
   * Petits calculs bornés (scans de maps &lt;= quelques milliers d'entrées),
   * jamais d'E/S, jamais de blocage de l'event-loop. Chaque tracker est
   * isolé par try/catch : un échec ne doit jamais interrompre les autres ni
   * remonter au timer Vert.x.
   */
  private void sweepPeerQualityState() {
    try {
      peerQualityTracker.evictExpired();
    } catch (Exception e) {
      LOG.log(Level.FINE, "peerQualityTracker sweep failed", e);
    }
    try {
      peerQuarantine.evictExpired();
    } catch (Exception e) {
      LOG.log(Level.FINE, "peerQuarantine sweep failed", e);
    }
    try {
      long windowMs = com.bowman.cspng.config.CacheExQuorumConfig.DEFAULT.windowMs();
      if (configHolder != null && configHolder.isInitialized()) {
        var snap = configHolder.get();
        if (snap != null && snap.quorum() != null) {
          windowMs = snap.quorum().windowMs();
        }
      }
      quorumTracker.evictExpired(windowMs);
    } catch (Exception e) {
      LOG.log(Level.FINE, "quorumTracker sweep failed", e);
    }
  }

  public void onStop(@Observes @Priority(200) ShutdownEvent event) {
    Long sweepId = peerQualitySweepTimerId;
    if (sweepId != null) {
      peerQualitySweepTimerId = null;
      try {
        vertx.cancelTimer(sweepId);
      } catch (Exception ignored) {
      }
    }
    if (!started || cacheDeploymentId == null) {
      LOG.fine("RuntimeCore stop: nothing to undeploy");
      return;
    }
    String id = cacheDeploymentId;
    if (Context.isOnEventLoopThread()) {
      LOG.info("RuntimeCore stop on event-loop — async undeploy");
      vertx.undeploy(id).onComplete(ar -> {
        started = false;
        cacheDeploymentId = null;
        if (ar.failed()) LOG.log(Level.WARNING, "CacheVerticle undeploy async failed", ar.cause());
        else LOG.info("CacheVerticle undeployed async");
      });
      return;
    }
    try {
      vertx.undeploy(id)
          .toCompletionStage().toCompletableFuture()
          .get(UNDEPLOY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      LOG.info("CacheVerticle undeployed");
    } catch (Exception e) {
      LOG.log(Level.WARNING, "CacheVerticle undeploy timeout/failed", e);
    } finally {
      started = false;
      cacheDeploymentId = null;
    }
  }

  private final com.bowman.cspng.filters.DcwObservationTracker observationTracker = new com.bowman.cspng.filters.DcwObservationTracker(200);
  private final com.bowman.cspng.filters.InterProviderLinkObserver linkObserver = new com.bowman.cspng.filters.InterProviderLinkObserver(200);

  @Produces
  @Singleton
  public com.bowman.cspng.filters.DcwObservationTracker observationTracker() {
    return observationTracker;
  }

  @Produces
  @Singleton
  public com.bowman.cspng.filters.InterProviderLinkObserver linkObserver() {
    return linkObserver;
  }

  private final com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry cacheExStatsRegistry =
      new com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry();
  /**
   * Registry des stats cacheex par pair (contrat v2 §4) — singleton partagé
   * connecteurs sortants / sessions entrantes / API admin.
   */
  @Produces
  @Singleton
  public com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry cacheExStatsRegistry() {
    return cacheExStatsRegistry;
  }

  // ---- Qualité pairs / hits / quarantaine / quorum (lots 2-5) : singletons partagés ----

  private final com.bowman.cspng.connectors.cacheex.PeerQualityTracker peerQualityTracker =
      new com.bowman.cspng.connectors.cacheex.PeerQualityTracker();
  private final com.bowman.cspng.connectors.cacheex.CacheHitAttribution cacheHitAttribution =
      new com.bowman.cspng.connectors.cacheex.CacheHitAttribution();
  private final com.bowman.cspng.connectors.cacheex.PeerQuarantine peerQuarantine =
      new com.bowman.cspng.connectors.cacheex.PeerQuarantine();
  private final com.bowman.cspng.connectors.cacheex.CacheExQuorumTracker quorumTracker =
      new com.bowman.cspng.connectors.cacheex.CacheExQuorumTracker();

  /** Fenêtre temporelle bornée : accepted+invalid éligibles, drops routage exclus. */
  @Produces
  @Singleton
  public com.bowman.cspng.connectors.cacheex.PeerQualityTracker peerQualityTracker() {
    return peerQualityTracker;
  }

  /** Hits réellement servis par origine connue (unknown explicite). */
  @Produces
  @Singleton
  public com.bowman.cspng.connectors.cacheex.CacheHitAttribution cacheHitAttribution() {
    return cacheHitAttribution;
  }

  /** Quarantaine mémoire temporaire bornée (opt-in, off défaut). */
  @Produces
  @Singleton
  public com.bowman.cspng.connectors.cacheex.PeerQuarantine peerQuarantine() {
    return peerQuarantine;
  }

  /** Quorum CacheEx (comparaison exacte, off défaut). */
  @Produces
  @Singleton
  public com.bowman.cspng.connectors.cacheex.CacheExQuorumTracker quorumTracker() {
    return quorumTracker;
  }

  @Produces
  @Singleton
  public CacheVerticle cacheVerticle() {
    ensureInitialized();
    return cacheVerticle;
  }

  @Produces
  @Singleton
  public SessionRegistry sessionRegistry() {
    ensureInitialized();
    return sessionRegistry;
  }

  @Produces
  @Singleton
  public CwsConnectorManager cwsConnectorManager() {
    ensureInitialized();
    return connectorManager;
  }

  private volatile com.bowman.cspng.config.DcwPolicyProvider dcwPolicyProvider;
  private final com.bowman.cspng.connectors.routing.AdaptiveRoutingEngine adaptiveRoutingEngine =
      new com.bowman.cspng.connectors.routing.AdaptiveRoutingEngine();

  @Produces
  @Singleton
  public com.bowman.cspng.connectors.routing.AdaptiveRoutingEngine adaptiveRoutingEngine() {
    return adaptiveRoutingEngine;
  }

  /**
   * Fournisseur DCW partagé (compilation memoïsée par snapshot, dernier bon conservé), branché
   * une fois sur le CwsConnectorManager central indépendamment de l'activation CSPv2.
   */
  public synchronized com.bowman.cspng.config.DcwPolicyProvider dcwPolicyProvider(
      com.bowman.cspng.config.ConfigHolder holder) {
    if (dcwPolicyProvider == null && holder != null) {
      dcwPolicyProvider = new com.bowman.cspng.config.DcwPolicyProvider(
          () -> holder.isInitialized() ? holder.get().dcwPolicy() : null);
      ensureInitialized();
      connectorManager.setDcwPolicySupplier(dcwPolicyProvider);
    }
    return dcwPolicyProvider;
  }

  // For Interop reuse
  public CacheVerticle getCacheVerticle() { return cacheVerticle; }
  public boolean isStarted() { return started; }
  public String getDeploymentId() { return cacheDeploymentId; }
  public boolean isEnabled() { return runtimeEnabled; }
}
