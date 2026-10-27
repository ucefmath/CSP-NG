package com.bowman.cspng.app.runtime;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ConfigWatcher;
import com.bowman.cspng.config.ConnectorConfig;
import com.bowman.cspng.config.NetworkProxyConfig;
import com.bowman.cspng.connectors.AbstractConnector;
import com.bowman.cspng.connectors.Camd35TcpConnector;
import com.bowman.cspng.connectors.CcCamConnector;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.connectors.NewcamdConnector;
import com.bowman.cspng.connectors.catalog.CardDiscoveryService;
import com.bowman.cspng.codec.cccam.CcConstants;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gestionnaire de cycle de vie des connecteurs CWS.
 *
 * <p>Modélisé sur {@link ListenerManager} (même pattern de coalescing).
 * Réconcilie les connecteurs déclarés dans {@link ConfigSnapshot#connectors()} où {@code enabled==true}.
 *
 * <p><b>Périmètre</b> : tous les connecteurs avec {@code enabled==true} sont démarrés, quel que soit
 * {@code profileName}. Les connecteurs dynamiques ({@code profileName == null}, routage par CAID via
 * {@code csp_connector_caid_profile}) sont démarrés comme les autres, mais leur routage à la demande
 * (résolution amont par CAID plutôt que par profil) N'EST PAS dans le périmètre de ce lot —
 * {@link CwsConnectorManager#getConnectorForProfile} reste basé sur le profil uniquement. Ce routage
 * dynamique sera traité dans un lot ultérieur.
 *
 * <p><b>Ordre de démarrage</b> : {@code @Priority(250)} entre {@link RuntimeCore} (200) et
 * {@link ListenerManager} (300).
 * <b>Ordre d'arrêt</b> : {@code @Priority(150)} — après {@link ListenerManager} (10) mais avant
 * {@link RuntimeCore} (200). Les connecteurs restent actifs pendant que ListenerManager ferme les
 * sessions qui pourraient encore les utiliser ; ils sont arrêtés avant que le CacheVerticle ne soit
 * undeployé par RuntimeCore.
 */
@ApplicationScoped
public class ConnectorLifecycleManager {

  private static final Logger LOG = Logger.getLogger(ConnectorLifecycleManager.class.getName());

  private final Vertx vertx;
  private final ConfigHolder holder;
  private final RuntimeCore runtimeCore;
  private final RuntimeSecretResolver secretResolver;

  @Inject
  CardDiscoveryService cardDiscoveryService;

  /** Stats cacheex par pair (contrat v2 §4) — registre borné partagé. */
  @Inject
  com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry cacheExStatsRegistry;

  @Inject
  CwCycleService cwCycleService;

  /** Trackers qualité/quarantaine/quorum partagés (lots 2/4/5) — porte lazy. */
  @Inject
  com.bowman.cspng.connectors.cacheex.PeerQualityTracker peerQualityTracker;

  @Inject
  com.bowman.cspng.connectors.cacheex.PeerQuarantine peerQuarantine;

  @Inject
  com.bowman.cspng.connectors.cacheex.CacheExQuorumTracker quorumTracker;

  private volatile com.bowman.cspng.connectors.cacheex.CacheExIngressGate ingressGate;

  /** Porte d'ingress centrale (construite une fois, fournisseurs live sur snapshot). */
  com.bowman.cspng.connectors.cacheex.CacheExIngressGate ingressGate() {
    var g = ingressGate;
    if (g == null) {
      g = com.bowman.cspng.connectors.cacheex.CacheExIngressGate.central(
          holder, peerQualityTracker, peerQuarantine, quorumTracker);
      ingressGate = g;
    }
    return g;
  }

  @Inject
  @ConfigProperty(name = "csp.runtime.enabled", defaultValue = "true")
  boolean runtimeEnabled;

  private MessageConsumer<Object> reloadConsumer;
  private final AtomicBoolean reconcileInFlight = new AtomicBoolean(false);
  private final AtomicBoolean reconcileDirty = new AtomicBoolean(false);
  private volatile ConfigSnapshot appliedSnapshot;
  private volatile boolean shutdown = false;
  private volatile boolean degraded = false;

  // Garde l'état désiré appliqué pour diff (détection changement)
  private final Map<String, ConnectorConfig> activeConfigs = new ConcurrentHashMap<>();
  /** Signature proxy appliquée par connecteur (name -> proxy signature) — pour diff proxy version/config/enabled. */
  private final Map<String, String> appliedProxySignatures = new ConcurrentHashMap<>();

  @Inject
  public ConnectorLifecycleManager(Vertx vertx, ConfigHolder holder, RuntimeCore runtimeCore,
                                  RuntimeSecretResolver secretResolver) {
    this.vertx = vertx;
    this.holder = holder;
    this.runtimeCore = runtimeCore;
    this.secretResolver = secretResolver;
  }

  public void onStart(@Observes @Priority(250) StartupEvent event) {
    if (!runtimeEnabled) {
      LOG.info("ConnectorLifecycleManager disabled (csp.runtime.enabled=false)");
      return;
    }
    if (!holder.isInitialized()) {
      throw new IllegalStateException("ConnectorLifecycleManager: ConfigHolder not initialized");
    }
    if (runtimeCore != null && !runtimeCore.isStarted() && runtimeCore.isEnabled()) {
      throw new IllegalStateException("ConnectorLifecycleManager: RuntimeCore not started");
    }
    LOG.info("ConnectorLifecycleManager starting — reconciling connectors");
    try {
      reconcileBlocking(holder.get());
      appliedSnapshot = holder.get();
      degraded = false;
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "ConnectorLifecycleManager startup reconcile failed", e);
      degraded = true;
      // Non fail-fast: connectors are additive, not critical. Log but don't block startup.
      // Still store snapshot to allow future reconciles.
      appliedSnapshot = holder.get();
    }
    reloadConsumer = vertx.eventBus().consumer(ConfigWatcher.CONFIG_RELOADED_ADDRESS, msg -> scheduleReconcile());
    LOG.info("ConnectorLifecycleManager started with " + activeCount() + " connectors");
  }

  public void onStop(@Observes @Priority(150) ShutdownEvent event) {
    shutdown = true;
    LOG.info("ConnectorLifecycleManager stopping — stopping connectors");
    if (reloadConsumer != null) {
      reloadConsumer.unregister();
      reloadConsumer = null;
    }
    CwsConnectorManager cm = runtimeCore != null ? runtimeCore.cwsConnectorManager() : null;
    if (cm != null) {
      for (String name : new ArrayList<>(activeConfigs.keySet())) {
        try {
          cm.removeConnector(name);
        } catch (Exception e) {
          LOG.log(Level.WARNING, "Failed to remove connector on shutdown: " + name, e);
        }
      }
    }
    activeConfigs.clear();
  }

  private void scheduleReconcile() {
    if (shutdown) return;
    if (reconcileInFlight.compareAndSet(false, true)) {
      vertx.executeBlocking(() -> {
        try {
          ConfigSnapshot snap = holder.get();
          reconcileBlocking(snap);
          appliedSnapshot = snap;
          degraded = false;
        } catch (Exception e) {
          LOG.log(Level.WARNING, "ConnectorLifecycleManager reload failed — keeping old", e);
          degraded = true;
          throw e;
        }
        return null;
      }, false).onComplete(ar -> {
        reconcileInFlight.set(false);
        if (reconcileDirty.compareAndSet(true, false)) {
          scheduleReconcile();
        }
      });
    } else {
      reconcileDirty.set(true);
    }
  }

  // For tests
  void reconcileBlocking() throws Exception {
    reconcileBlocking(holder.get());
  }

  private void reconcileBlocking(ConfigSnapshot snap) throws Exception {
    if (shutdown) throw new IllegalStateException("shutdown in progress — abort reconcile");
    if (io.vertx.core.Context.isOnEventLoopThread()) {
      LOG.severe("reconcileBlocking called on event-loop — would block; failing fast");
      throw new IllegalStateException("reconcileBlocking must not be called on event-loop");
    }
    if (snap == null) snap = holder.get();
    if (shutdown) throw new IllegalStateException("shutdown in progress — abort reconcile");

    CwsConnectorManager cm = runtimeCore != null ? runtimeCore.cwsConnectorManager() : null;
    if (cm == null) {
      LOG.warning("ConnectorLifecycleManager: no CwsConnectorManager available");
      return;
    }

    // Build desired map: only enabled connectors
    Map<String, ConnectorConfig> desired = new LinkedHashMap<>();
    for (ConnectorConfig cfg : snap.connectors()) {
      if (!cfg.enabled()) continue;
      String proto = cfg.protocol() == null ? "" : cfg.protocol().toLowerCase(Locale.ROOT);
      // Normalize protocol
      if (!("newcamd".equals(proto) || "cccam".equals(proto) || "radegast".equals(proto)
          || "cs378x".equals(proto))) {
        LOG.warning("Unknown connector protocol for " + cfg.name() + ": " + cfg.protocol());
        continue;
      }
      // Use normalized protocol in stored config? Keep original but compare with normalized?
      // For diff equality, the record's equals includes protocol raw — normalize to lowercase for consistency
      // We'll treat desired as the raw config but build key with name.
      desired.put(cfg.name(), cfg);
    }

    // Proxy signature per desired connector: restart attached connectors ONLY when
    // their referenced proxy (version/config/enabled) changes. null/blank proxyName = "direct".
    Map<String, NetworkProxyConfig> proxiesByName = snap.proxyByName();
    Map<String, String> desiredProxySigs = new LinkedHashMap<>();
    for (Map.Entry<String, ConnectorConfig> e : desired.entrySet()) {
      desiredProxySigs.put(e.getKey(), proxySignature(e.getValue().proxyName(), proxiesByName));
    }

    // Diff: removed
    Set<String> toRemove = new HashSet<>(activeConfigs.keySet());
    toRemove.removeAll(desired.keySet());
    for (String name : toRemove) {
      LOG.info("Removing connector " + name);
      try {
        cm.removeConnector(name);
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Failed to remove connector " + name, e);
      }
      if (cacheExStatsRegistry != null) {
        cacheExStatsRegistry.unregister(name);
      }
      activeConfigs.remove(name);
      appliedProxySignatures.remove(name);
    }

    // Also handle disabled or protocol-changed that are in active but not desired -> already handled as remove.
    // For remaining, diff add/update (config change OR referenced proxy change)
    List<String> failures = new ArrayList<>();
    int successCount = 0;
    for (Map.Entry<String, ConnectorConfig> e : desired.entrySet()) {
      String name = e.getKey();
      ConnectorConfig cfg = e.getValue();
      ConnectorConfig existing = activeConfigs.get(name);
      boolean configUnchanged = existing != null && existing.equals(cfg);
      boolean proxyUnchanged = existing != null
          && java.util.Objects.equals(appliedProxySignatures.get(name), desiredProxySigs.get(name));
      if (configUnchanged && proxyUnchanged) {
        LOG.fine("Connector unchanged " + name);
        continue;
      }
      if (existing != null && configUnchanged && !proxyUnchanged) {
        LOG.info("Proxy for connector " + name + " changed — restarting attached connector only");
      }
      // Changed or new: remove old first if exists
      if (existing != null) {
        LOG.info("Connector changed, restarting " + name);
        try {
          cm.removeConnector(name);
        } catch (Exception ex) {
          LOG.log(Level.WARNING, "Failed to remove old connector " + name + " before restart", ex);
        }
        if (cacheExStatsRegistry != null) {
          cacheExStatsRegistry.unregister(name);
        }
        activeConfigs.remove(name);
        appliedProxySignatures.remove(name);
      }
      // Build new
      try {
        AbstractConnector connector = buildAndStartConnector(cm, cfg, proxiesByName);
        activeConfigs.put(name, cfg);
        appliedProxySignatures.put(name, desiredProxySigs.get(name));
        successCount++;
        LOG.info("Connector started: " + name + " protocol=" + cfg.protocol() + " host=" + cfg.host() + ":" + cfg.port()
            + (cfg.proxyName() != null && !cfg.proxyName().isBlank() ? " via proxy " + cfg.proxyName() : " direct"));
      } catch (Exception ex) {
        LOG.log(Level.WARNING, "Failed to start connector " + name + ": " + ex.getMessage(), ex);
        failures.add(name + ": " + ex.getMessage());
      }
    }

    if (!failures.isEmpty()) {
      LOG.log(Level.WARNING, "Connector reconcile completed with failures: " + String.join("; ", failures));
      if (successCount == 0 && !desired.isEmpty()) {
        LOG.severe("All connectors failed to start — degraded");
        degraded = true;
      }
    }
  }

  /**
   * Signature d'état d'un proxy référencé — utilisée par le diff lifecycle.
   * Un changement de version, enabled, host, port ou auth du proxy
   * déclenche le restart des SEULS connecteurs attachés.
   * (Pas de timeout dans la signature : le proxy n'en porte pas — Plan P2.)
   */
  static String proxySignature(String proxyName, Map<String, NetworkProxyConfig> proxiesByName) {
    if (proxyName == null || proxyName.isBlank()) return "direct";
    NetworkProxyConfig p = proxiesByName.get(proxyName);
    if (p == null) return "missing:" + proxyName;
    return p.name() + ":v" + p.version() + ":enabled=" + p.enabled()
        + ":" + p.host() + ":" + p.port()
        + ":auth=" + (p.username() != null) + ":" + (p.username() != null ? p.username() : "")
        + ":" + (p.passwordRef() != null ? p.passwordRef() : "");
  }

  /**
   * Résout le proxy référencé par un connecteur en {@link AbstractConnector.NetworkProxyOptions}.
   *
   * <p><b>Fail-closed strict</b> :
   * <ul>
   *   <li>proxy référencé absent du snapshot → échec (jamais de direct)</li>
   *   <li>proxy disabled → échec (kill switch : sessions fermées, reconnect bloqué)</li>
   *   <li>secret proxy non résoluble → échec (jamais de direct)</li>
   * </ul>
   * Appelé sur worker thread — résolution synchrone des secrets AVANT enregistrement manager.
   *
   * @return null si le connecteur n'utilise pas de proxy (connexion directe)
   */
  private AbstractConnector.NetworkProxyOptions resolveProxyOptions(ConnectorConfig cfg,
                                                                    Map<String, NetworkProxyConfig> proxiesByName) {
    String proxyName = cfg.proxyName();
    if (proxyName == null || proxyName.isBlank()) return null;
    NetworkProxyConfig p = proxiesByName.get(proxyName);
    if (p == null) {
      throw new IllegalStateException("Connector " + cfg.name()
          + " references unknown proxy '" + proxyName + "' — fail-closed, not starting");
    }
    if (!p.enabled()) {
      throw new IllegalStateException("Connector " + cfg.name()
          + " references disabled proxy '" + proxyName + "' — kill switch, not starting");
    }
    String username = null;
    String password = null;
    if (p.hasAuth()) {
      if (p.passwordRef() == null || p.passwordRef().isBlank()) {
        throw new IllegalStateException("Proxy " + proxyName
            + " has username but no passwordRef — fail-closed, not starting");
      }
      // Résolution du secret proxy sur worker thread, avant enregistrement manager
      password = secretResolver.resolvePassword(p.passwordRef(), "proxy:" + proxyName);
      username = p.username();
    }
    return new AbstractConnector.NetworkProxyOptions(
        p.host(), p.port(), username, password);
  }

  /** Clamp le budget T du connecteur sur le chemin proxy : [1000..300000] ms (Plan P2). */
  static int clampConnectTimeout(int t) {
    if (t < 1000) return 1000;
    if (t > 300_000) return 300_000;
    return t;
  }

  private AbstractConnector buildAndStartConnector(CwsConnectorManager cm, ConnectorConfig cfg,
                                                   Map<String, NetworkProxyConfig> proxiesByName) throws Exception {
    String proto = cfg.protocol() == null ? "" : cfg.protocol().toLowerCase(Locale.ROOT);
    String profile = cfg.profileName();
    // Resolve proxy (fail-closed) and secrets synchronously (we are on worker thread)
    AbstractConnector.NetworkProxyOptions proxyOpts = resolveProxyOptions(cfg, proxiesByName);
    // T = budget du CONNECTEUR (cfg.connectTimeoutMs) — PAS du proxy (Plan P2).
    // Sur le chemin proxy, T est clampé 1000..300000 et appliqué aux deux phases.
    // Sur le chemin DIRECT, ce paramètre est IGNORÉ par buildNetClientOptions :
    // les connecteurs directs gardent les DÉFAUTS historiques Vert.x (60 s).
    int connectTimeout = proxyOpts != null
        ? clampConnectTimeout(cfg.connectTimeoutMs())
        : cfg.connectTimeoutMs();
    if ("newcamd".equals(proto)) {
      if (cfg.passwordRef() == null || cfg.passwordRef().isBlank()) {
        throw new IllegalStateException("newcamd connector " + cfg.name() + " requires passwordRef");
      }
      if (cfg.desKeyRef() == null || cfg.desKeyRef().isBlank()) {
        throw new IllegalStateException("newcamd connector " + cfg.name() + " requires desKeyRef");
      }
      if ("oscam_ncd525".equalsIgnoreCase(cfg.newcamdDialect())) {
        throw new UnsupportedOperationException(
            "newcamd connector " + cfg.name() + " dialect 'oscam_ncd525' requires full Eurocrypt cipher engine; use 'legacy'");
      }
      String password = secretResolver.resolvePassword(cfg.passwordRef(), "connector:" + cfg.name());
      byte[] desKey14 = secretResolver.resolveDesKey(cfg.desKeyRef(), "connector:" + cfg.name());
      String username = cfg.username() != null ? cfg.username() : "";
      AbstractConnector c = cm.addNewcamdConnector(cfg.name(), cfg.host(), cfg.port(),
          desKey14, username, password, profile, connectTimeout, proxyOpts);
      if (c instanceof NewcamdConnector nc) {
        nc.setCardDiscovery(cfg.cardDiscovery());
        nc.setReshareGrant(cfg.reshareGrant());
        nc.setNewcamdDialect(cfg.newcamdDialect());
        nc.setNewcamdPasswordEncoding(cfg.newcamdPasswordEncoding());
        nc.setReshareCap(cfg.reshareCap() != null ? cfg.reshareCap() : 0);
        nc.setCardDiscoveryService(cardDiscoveryService);
      }
      c.start().onComplete(ar -> {
        if (ar.succeeded()) LOG.info("Newcamd connector " + cfg.name() + " TCP connected");
        else LOG.log(Level.WARNING, "Newcamd connector " + cfg.name() + " connect failed: " + ar.cause().getMessage());
      });
      return c;
    } else if ("cccam".equals(proto)) {
      if (cfg.passwordRef() == null || cfg.passwordRef().isBlank()) {
        throw new IllegalStateException("cccam connector " + cfg.name() + " requires passwordRef");
      }
      String password = secretResolver.resolvePassword(cfg.passwordRef(), "connector:" + cfg.name());
      String username = cfg.username() != null ? cfg.username() : "";
      String version = cfg.cccamVersion() != null ? cfg.cccamVersion() : CcConstants.DEFAULT_VERSION;
      int cacheexMode = cfg.cacheexMode() != null ? cfg.cacheexMode() : CcConstants.CACHEEX_MODE_OFF;
      // V15 : hop EXPLICITE depuis la config (fin du hardcodage CACHEEX_DEFAULT_MAXHOP)
      int cacheexMaxHop = cfg.effectiveCacheexMaxHop(CcConstants.CACHEEX_DEFAULT_MAXHOP);
      // V15 (D3) : borne de file push = NOMBRE de messages (défaut 2000), pas un débit
      int cacheexMaxSend = cfg.effectiveCacheexMaxSend();
      AbstractConnector c;
      if (cfg.cacheexMode() != null && cfg.cacheexMode() != CcConstants.CACHEEX_MODE_OFF) {
        c = cm.addCcCamConnector(cfg.name(), cfg.host(), cfg.port(), username, password, profile,
            version, CcConstants.DEFAULT_BUILD, cacheexMode, cacheexMaxHop, connectTimeout, proxyOpts,
            cfg.cacheexFilter(), cacheexMaxSend, cacheExStatsRegistry);
        // (Enregistrement de la paire fait par le connecteur lui-même au start.)
      } else {
        c = cm.addCcCamConnector(cfg.name(), cfg.host(), cfg.port(), username, password, profile,
            version, CcConstants.DEFAULT_BUILD, CcConstants.CACHEEX_MODE_OFF,
            CcConstants.CACHEEX_DEFAULT_MAXHOP, connectTimeout, proxyOpts);
      }
      if (c instanceof CcCamConnector cc) {
        cc.setReshareCap(cfg.reshareCap() != null ? cfg.reshareCap() : 0);
        cc.setCardDiscoveryService(cardDiscoveryService);
        cc.setIngressGate(ingressGate());
        if (cwCycleService != null) {
          cc.setCwCycle(cwCycleService.checker(), cwCycleService.config());
        }
      }
      c.start().onComplete(ar -> {
        if (ar.succeeded()) LOG.info("CCcam connector " + cfg.name() + " TCP connected");
        else LOG.log(Level.WARNING, "CCcam connector " + cfg.name() + " connect failed: " + ar.cause().getMessage());
      });
      return c;
    } else if ("radegast".equals(proto)) {
      AbstractConnector c = cm.addRadegastConnector(cfg.name(), cfg.host(), cfg.port(), profile,
          connectTimeout, proxyOpts);
      c.setReshareCap(cfg.reshareCap() != null ? cfg.reshareCap() : 0);
      c.setCardDiscoveryService(cardDiscoveryService);
      c.start().onComplete(ar -> {
        if (ar.succeeded()) LOG.info("Radegast connector " + cfg.name() + " TCP connected");
        else LOG.log(Level.WARNING, "Radegast connector " + cfg.name() + " connect failed: " + ar.cause().getMessage());
      });
      return c;
    } else if ("cs378x".equals(proto)) {
      if (cfg.passwordRef() == null || cfg.passwordRef().isBlank()) {
        throw new IllegalStateException("cs378x connector " + cfg.name() + " requires passwordRef");
      }
      String password = secretResolver.resolvePassword(cfg.passwordRef(), "connector:" + cfg.name());
      String username = cfg.username() != null ? cfg.username() : "";
      int cacheexMode = cfg.cacheexMode() != null ? cfg.cacheexMode() : 0;
      int cacheexMaxHop = cfg.effectiveCacheexMaxHop(CcConstants.CACHEEX_DEFAULT_MAXHOP);
      int cacheexMaxSend = cfg.effectiveCacheexMaxSend();
      AbstractConnector c = cm.addCamd35TcpConnector(cfg.name(), cfg.host(), cfg.port(),
          username, password, profile, cacheexMode, cacheexMaxHop,
          connectTimeout, proxyOpts, cfg.cacheexFilter(), cacheexMaxSend, cacheExStatsRegistry);
      // (Enregistrement de la paire fait par le connecteur lui-même au start.)
      if (c instanceof Camd35TcpConnector c35 && cwCycleService != null) {
        c35.setCwCycle(cwCycleService.checker(), cwCycleService.config());
      }
      if (c instanceof Camd35TcpConnector c35gate) {
        c35gate.setIngressGate(ingressGate());
      }
      c.setReshareCap(cfg.reshareCap() != null ? cfg.reshareCap() : 0);
      c.setCardDiscoveryService(cardDiscoveryService);
      c.start().onComplete(ar -> {
        if (ar.succeeded()) LOG.info("CS378x connector " + cfg.name() + " TCP connected (cacheex=" + cacheexMode + ")");
        else LOG.log(Level.WARNING, "CS378x connector " + cfg.name() + " connect failed: " + ar.cause().getMessage());
      });
      return c;
    } else {
      throw new IllegalStateException("Unknown protocol: " + proto);
    }
  }

  public Map<String, AbstractConnector> getActiveConnectors() {
    CwsConnectorManager cm = runtimeCore != null ? runtimeCore.cwsConnectorManager() : null;
    if (cm == null) return Collections.emptyMap();
    return cm.getConnectors();
  }

  public int activeCount() {
    return activeConfigs.size();
  }

  public boolean isDegraded() { return degraded; }
  public ConfigSnapshot getAppliedSnapshot() { return appliedSnapshot; }
}
