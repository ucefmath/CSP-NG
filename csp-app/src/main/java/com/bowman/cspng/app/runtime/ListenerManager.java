package com.bowman.cspng.app.runtime;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ConfigWatcher;
import com.bowman.cspng.config.ExtendedNewcamdConfig;
import com.bowman.cspng.config.ProfileListenerConfig;
import com.bowman.cspng.connectors.catalog.CardDiscoveryService;
import com.bowman.cspng.connectors.routing.StrictEcmRouter;
import com.bowman.cspng.sessions.AbstractSession;
import com.bowman.cspng.sessions.AuthAudit;
import com.bowman.cspng.sessions.Camd35TcpSession;
import com.bowman.cspng.sessions.CcCamSession;
import com.bowman.cspng.sessions.ExtNewcamdSession;
import com.bowman.cspng.sessions.NewcamdSession;
import com.bowman.cspng.sessions.RadegastSession;
import com.bowman.cspng.sessions.SessionRegistry;
import com.bowman.cspng.users.IpMatcher;
import com.bowman.cspng.users.ProtocolAuthResolver;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class ListenerManager {

  private static final Logger LOG = Logger.getLogger(ListenerManager.class.getName());

  private final Vertx vertx;
  private final ConfigHolder holder;
  private final RuntimeCore runtimeCore;
  private final RuntimeSecretResolver secretResolver;

  @Inject
  ProtocolAuthResolver authResolver;

  @Inject
  CardDiscoveryService cardDiscoveryService;

  @Inject
  RelayPolicyService relayPolicyService;

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
  com.bowman.cspng.connectors.cacheex.CacheHitAttribution cacheHitAttribution;

  @Inject
  @ConfigProperty(name = "csp.runtime.enabled", defaultValue = "true")
  boolean runtimeEnabled;

  @Inject
  @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
  String quarkusProfile;

  private final Map<String, ManagedServer> active = new ConcurrentHashMap<>();
  private final Set<ExtNewcamdSession> extSessions = Collections.newSetFromMap(new WeakHashMap<>());
  private MessageConsumer<Object> reloadConsumer;

  // Coalescing
  private final AtomicBoolean reconcileInFlight = new AtomicBoolean(false);
  private final AtomicBoolean reconcileDirty = new AtomicBoolean(false);
  private volatile ConfigSnapshot appliedSnapshot;
  private volatile boolean degraded = false;
  private volatile boolean shutdown = false;

  @Inject
  public ListenerManager(Vertx vertx, ConfigHolder holder, RuntimeCore runtimeCore, RuntimeSecretResolver secretResolver) {
    this.vertx = vertx;
    this.holder = holder;
    this.runtimeCore = runtimeCore;
    this.secretResolver = secretResolver;
  }

  public void onStart(@Observes @Priority(300) StartupEvent event) {
    if (!runtimeEnabled) {
      LOG.info("ListenerManager disabled (csp.runtime.enabled=false)");
      return;
    }
    if (!holder.isInitialized()) {
      throw new IllegalStateException("ListenerManager: ConfigHolder not initialized before core");
    }
    if (runtimeCore != null && !runtimeCore.isStarted() && runtimeCore.isEnabled()) {
      throw new IllegalStateException("ListenerManager: RuntimeCore not started before ListenerManager");
    }
    if (runtimeCore != null) runtimeCore.dcwPolicyProvider(holder);
    LOG.info("ListenerManager starting — reconciling listeners");
    try {
      reconcileBlocking(holder.get());
      appliedSnapshot = holder.get();
      degraded = false;
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "ListenerManager startup failed", e);
      degraded = true;
      throw new IllegalStateException("FAIL-FAST: ListenerManager startup failed: " + e.getMessage(), e);
    }
    reloadConsumer = vertx.eventBus().consumer(ConfigWatcher.CONFIG_RELOADED_ADDRESS, msg -> scheduleReconcile());
    LOG.info("ListenerManager started with " + active.size() + " listeners");
  }

  public void onStop(@Observes @Priority(10) ShutdownEvent event) {
    shutdown = true;
    LOG.info("ListenerManager stopping — closing servers");
    if (reloadConsumer != null) {
      reloadConsumer.unregister();
      reloadConsumer = null;
    }
    for (ManagedServer ms : new ArrayList<>(active.values())) {
      try {
        if (io.vertx.core.Context.isOnEventLoopThread()) {
          ms.server.close().onComplete(ar -> LOG.fine("Server closed async " + ms.key));
          closeSessionsForServer(ms);
        } else {
          ms.server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
          closeSessionsForServer(ms);
        }
      } catch (Exception e) { LOG.warning("Close server failed: " + e.getMessage()); closeSessionsForServer(ms); }
    }
    active.clear();
    synchronized (extSessions) {
      for (ExtNewcamdSession s : new ArrayList<>(extSessions)) {
        try { s.close(); } catch (Exception ignored) {}
      }
      extSessions.clear();
    }
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
          LOG.log(Level.WARNING, "ListenerManager reload failed — keeping old", e);
          degraded = true;
          throw e;
        }
        return null;
      }, false).onComplete(ar -> {
        reconcileInFlight.set(false);
        if (reconcileDirty.compareAndSet(true, false)) {
          scheduleReconcile();
        } else if (ar.succeeded()) {
          refreshExtSessions();
        }
      });
    } else {
      reconcileDirty.set(true);
    }
  }

  // For tests direct call
  void reconcileBlocking() throws Exception {
    reconcileBlocking(holder.get());
  }

  private void reconcileBlocking(ConfigSnapshot snap) throws Exception {
    if (shutdown) throw new IllegalStateException("shutdown in progress — abort reconcile");
    if (snap == null) snap = holder.get();
    if (shutdown) throw new IllegalStateException("shutdown in progress — abort reconcile");
    Map<String, DesiredListener> desired = buildDesired(snap);
    // Staged commit: prepare new servers first, then commit
    // For simplicity, we apply incrementally but with rollback on failure to avoid partial state
    // If any bind fails, we keep old and throw, appliedSnapshot not updated
    // Already handled per-listener rollback, but need global atomicity for multiple listeners
    // We use try/catch to ensure if second listener fails, first already added is rolled back to old
    Map<String, ManagedServer> backup = new HashMap<>(active);
    List<ManagedServer> newlyCreated = new ArrayList<>();
    try {
      // removal
      Set<String> toRemove = new HashSet<>(active.keySet());
      toRemove.removeAll(desired.keySet());
      for (String key : toRemove) {
        ManagedServer old = active.remove(key);
        if (old != null) {
          LOG.info("Removing listener " + key);
          try { old.server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS); }
          catch (Exception e) { LOG.warning("Close removed listener failed: " + e.getMessage()); }
          // sessions remain tracked in old (not cleared) until close
        }
      }
      // add/update
      for (Map.Entry<String, DesiredListener> e : desired.entrySet()) {
        String key = e.getKey();
        DesiredListener dl = e.getValue();
        ManagedServer existing = active.get(key);
        if (existing != null) {
          if (existing.equalsDesired(dl)) {
            LOG.fine("Listener unchanged " + key);
            continue;
          }
          boolean portChanged = existing.port != dl.port || !Objects.equals(existing.bindAddress, dl.bindAddress);
          if (shutdown) throw new IllegalStateException("shutdown abort");
          if (portChanged) {
            if (shutdown) throw new IllegalStateException("shutdown abort");
            ManagedServer newServer = bindNew(dl);
            if (shutdown) { try { newServer.server.close(); } catch (Exception ignored) {} throw new IllegalStateException("shutdown during bind — abort"); }
            newlyCreated.add(newServer);
            active.put(key, newServer);
            try { existing.server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS); }
            catch (Exception ex) { LOG.warning("Close old port failed: " + ex.getMessage()); }
          } else {
            ManagedServer old = active.remove(key);
            try { old.server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS); }
            catch (Exception ex) { LOG.warning("Close for rebind failed: " + ex.getMessage()); }
            if (shutdown) { // do not rebind during shutdown
              LOG.info("shutdown — skip rebind for " + key);
              throw new IllegalStateException("shutdown abort");
            }
            ManagedServer newServer = bindNew(dl);
            if (shutdown) { try { newServer.server.close(); } catch (Exception ignored) {} throw new IllegalStateException("shutdown during bind — abort"); }
            newlyCreated.add(newServer);
            active.put(key, newServer);
            if (!Objects.equals(old.desKeyRef, dl.desKeyRef) || !Objects.equals(old.allowList, dl.allowList) || !Objects.equals(old.denyList, dl.denyList)) {
              closeSessionsForServer(old);
              synchronized (extSessions) { extSessions.removeIf(s -> old.sessions.contains(s)); }
            }
          }
        } else {
          if (shutdown) throw new IllegalStateException("shutdown abort");
          ManagedServer ns = bindNew(dl);
          if (shutdown) { try { ns.server.close(); } catch (Exception ignored) {} throw new IllegalStateException("shutdown during bind — abort"); }
          newlyCreated.add(ns);
          active.put(key, ns);
        }
      }
    } catch (Exception ex) {
      // Rollback newly created servers
      for (ManagedServer ns : newlyCreated) {
        try { ns.server.close(); } catch (Exception ignored) {}
        active.remove(ns.key);
      }
      // Restore backup for those that were removed (toRemove) or updated
      // For simplicity, re-add backup entries that are not already present
      for (Map.Entry<String, ManagedServer> be : backup.entrySet()) {
        if (shutdown) break;
        if (!active.containsKey(be.getKey())) {
          if (shutdown) break;
          try {
            ManagedServer rb = bindNew(be.getValue().toDesired());
            if (shutdown) { try { rb.server.close(); } catch (Exception ignored) {} break; }
            active.put(be.getKey(), rb);
          } catch (Exception rbEx) {
            LOG.log(Level.SEVERE, "Rollback failed for " + be.getKey(), rbEx);
          }
        }
      }
      throw ex;
    }
  }

  private Map<String, DesiredListener> buildDesired(ConfigSnapshot snap) {
    Map<String, DesiredListener> map = new LinkedHashMap<>();
    for (ProfileListenerConfig l : snap.profileListeners()) {
      if (!l.enabled()) continue;
      String proto = l.protocol() == null ? "" : l.protocol().toLowerCase(Locale.ROOT);
      if (!("newcamd".equals(proto) || "radegast".equals(proto) || "cccam".equals(proto) || "cs378x".equals(proto))) continue;
      String key = "profile:" + l.profileName() + ":" + proto;
      if (map.containsKey(key)) {
        LOG.warning("Duplicate logical listener key " + key + " — keeping first, validator should reject duplicates");
        continue;
      }
      DesiredListener dl = new DesiredListener();
      dl.key = key;
      dl.dbId = l.id();
      dl.isExtended = false;
      dl.profileName = l.profileName();
      dl.protocol = proto;
      dl.port = l.port();
      dl.bindAddress = l.bindAddress();
      dl.desKeyRef = l.desKeyRef();
      dl.allowList = l.allowList();
      dl.denyList = l.denyList();
      dl.maxMsgSize = 2048;
      dl.cardMode = l.cardMode();
      dl.strictPolicy = l.strictPolicy();
      dl.reshareCap = l.reshareCap();
      dl.maxCards = l.maxCards();
      dl.maxInflight = l.maxInflight();
      dl.maxPerIp = l.maxPerIp();
      dl.nodeId = l.nodeId();
      dl.idleTimeoutSec = l.idleTimeoutSec();
      dl.extConfig = null;
      map.put(key, dl);
    }
    ExtendedNewcamdConfig ext = snap.extendedNewcamd();
    if (ext != null && ext.enabled()) {
      String key = "extended-newcamd";
      DesiredListener dl = new DesiredListener();
      dl.key = key;
      dl.isExtended = true;
      dl.profileName = null;
      dl.protocol = "ext-newcamd";
      dl.port = ext.listenPort();
      dl.bindAddress = ext.bindAddress();
      dl.desKeyRef = ext.desKeyRef();
      dl.allowList = ext.allowList();
      dl.denyList = ext.denyList();
      dl.maxMsgSize = ext.maxMsgSize();
      dl.extConfig = ext;
      map.put(key, dl);
    }
    return map;
  }

  private ManagedServer bindNew(DesiredListener dl) throws Exception {
    if (shutdown) throw new IllegalStateException("shutdown in progress — abort bind");
    if (io.vertx.core.Context.isOnEventLoopThread()) {
      LOG.severe("bindNew called on event-loop — would block; failing fast");
      throw new IllegalStateException("bindNew must not be called on event-loop");
    }
    byte[] desKey14 = null;
    if (dl.desKeyRef != null && !dl.desKeyRef.isBlank()) {
      desKey14 = secretResolver.resolveDesKey(dl.desKeyRef, dl.key);
    } else {
      if (dl.isExtended || "newcamd".equals(dl.protocol)) {
        throw new IllegalStateException("Listener " + dl.key + " requires desKeyRef");
      }
    }
    NetServerOptions opts = new NetServerOptions();
    opts.setHost(dl.bindAddress);
    opts.setPort(dl.port);
    if ("::".equals(dl.bindAddress.trim())) {
      try {
        var m = NetServerOptions.class.getMethod("setIpv6Only", boolean.class);
        m.invoke(opts, true);
      } catch (NoSuchMethodException | IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
        throw new IllegalStateException("FAIL-FAST: bindAddress '::' requires IPv6-only (IPV6_V6ONLY) but Vert.x/Netty does not expose it — use explicit '0.0.0.0' or specific IPv6 address", e);
      }
    }
    NetServer server = vertx.createNetServer(opts);
    List<String> allow = parseList(dl.allowList);
    List<String> deny = parseList(dl.denyList);
    CacheVerticle cv = runtimeCore != null ? runtimeCore.getCacheVerticle() : null;
    SessionRegistry registry = runtimeCore != null ? runtimeCore.sessionRegistry() : null;
    var cm = runtimeCore != null ? runtimeCore.cwsConnectorManager() : null;
    if (cv == null) LOG.warning("ListenerManager: no CacheVerticle available — using placeholder");
    ManagedServer ms = new ManagedServer();
    ms.key = dl.key;
    ms.port = dl.port;
    ms.bindAddress = dl.bindAddress;
    ms.protocol = dl.protocol;
    ms.profileName = dl.profileName;
    ms.desKeyRef = dl.desKeyRef;
    ms.allowList = dl.allowList;
    ms.denyList = dl.denyList;
    ms.isExtended = dl.isExtended;
    ms.desKey14 = desKey14;
    ms.maxMsgSize = dl.maxMsgSize;
    ms.cardMode = dl.cardMode;
    ms.strictPolicy = dl.strictPolicy;
    ms.reshareCap = dl.reshareCap;
    ms.maxCards = dl.maxCards;
    ms.maxInflight = dl.maxInflight;
    ms.maxPerIp = dl.maxPerIp;
    ms.nodeId = dl.nodeId;
    ms.idleTimeoutSec = dl.idleTimeoutSec;
    ms.extConfig = dl.extConfig;
    ms.server = server;
    final byte[] finalDesKey14 = desKey14;
    final DesiredListener finalDl = dl;
    final ManagedServer finalMs = ms;
    server.connectHandler(socket -> {
      String remoteIp = socket.remoteAddress() != null ? socket.remoteAddress().host() : null;
      if (!isIpAllowed(remoteIp, allow, deny)) {
        LOG.fine("ACL deny for " + remoteIp + " on " + dl.key);
        AuthAudit.preauthReject(remoteIp, "listener-acl");
        socket.close();
        return;
      }
      if (registry != null && !registry.tryAcquirePreauthIp(remoteIp, finalDl.maxPerIp)) {
        LOG.warning("Preauth IP limit reached for " + remoteIp + " on " + dl.key);
        socket.close();
        return;
      }
      AbstractSession.PreauthLease lease = new AbstractSession.PreauthLease(registry, remoteIp);
      try {
        if (finalDl.isExtended) {
          ExtNewcamdSession session = new ExtNewcamdSession(vertx, cv, registry, finalDesKey14, holder, finalDl.port, finalDl.extConfig);
          session.setSnapshotSupplier(() -> appliedSnapshot != null ? appliedSnapshot : holder.get());
          session.setConnectorManager(cm);
          session.setPreauthLease(lease);
          finalMs.sessions.add(session);
          synchronized (extSessions) { extSessions.add(session); }
          session.addCloseHook(() -> {
            finalMs.sessions.remove(session);
            synchronized (extSessions) { extSessions.remove(session); }
          });
          session.accept(socket);
        } else if ("newcamd".equals(finalDl.protocol)) {
          NewcamdSession session = new NewcamdSession(vertx, cv, registry, finalDesKey14);
          session.setProfileName(finalDl.profileName);
          try {
            ConfigSnapshot snapForNet = appliedSnapshot != null ? appliedSnapshot : holder.get();
            if (snapForNet != null) {
              for (var p : snapForNet.profiles()) {
                if (p.name().equals(finalDl.profileName)) {
                  session.setProfileNetworkId(p.networkId());
                  session.setProfileCaId(p.caId());
                  break;
                }
              }
              var idents = snapForNet.providerIdentsFor(finalDl.profileName);
              if (idents != null && !idents.isEmpty()) {
                int[] provs = idents.stream().mapToInt(pi -> pi.providerIdent()).toArray();
                session.setProfileProviders(provs);
              }
            }
          } catch (Exception ignored) {}
          session.setConnectorManager(cm);
          session.setMaxMsgSize(finalDl.maxMsgSize);
          session.setPreauthLease(lease);
          finalMs.sessions.add(session);
          session.addCloseHook(() -> finalMs.sessions.remove(session));
          session.accept(socket);
        } else if ("radegast".equals(finalDl.protocol)) {
          RadegastSession session = new RadegastSession(vertx, cv, registry);
          session.setProfileName(finalDl.profileName);
          session.setConnectorManager(cm);
          session.setPreauthLease(lease);
          finalMs.sessions.add(session);
          session.addCloseHook(() -> finalMs.sessions.remove(session));
          session.accept(socket);
        } else if ("cccam".equals(finalDl.protocol)) {
          byte[] srvNodeId = null;
          if (finalDl.nodeId != null && !finalDl.nodeId.isBlank()) {
            try {
              srvNodeId = hexToBytes8(finalDl.nodeId);
            } catch (Exception ignore) {}
          }
          StrictEcmRouter strictRouter = new StrictEcmRouter(cm, null, null, cardDiscoveryService, finalDl.cardMode);
           if (runtimeCore != null) {
             strictRouter.setAdaptiveRoutingEngine(runtimeCore.adaptiveRoutingEngine());
             var dcwProvider = runtimeCore.dcwPolicyProvider(holder);
            if (dcwProvider != null) strictRouter.setDcwPolicySupplier(dcwProvider);
            strictRouter.setObservationTracker(runtimeCore.observationTracker());
            strictRouter.setLinkObserver(runtimeCore.linkObserver());
          }
          if (relayPolicyService != null) {
            // FIX #3 : pas de refresh opportuniste ; politique live via event-bus.
            strictRouter.setRelayPolicySupplier(() -> relayPolicyService.policy(), finalDl.dbId);
            strictRouter.setRelayStores(relayPolicyService.stores());
          }
          if (cwCycleService != null) {
            strictRouter.setCwCycle(cwCycleService.checker(), cwCycleService.config());
          }
          if (cacheHitAttribution != null) {
            strictRouter.setHitAttribution(cacheHitAttribution);
          }
          CcCamSession session = new CcCamSession(
              vertx, cv, registry, authResolver,
              (ref, ctx) -> secretResolver.resolvePasswordAsync(ref, ctx),
              strictRouter, cardDiscoveryService, srvNodeId,
              finalDl.cardMode, finalDl.reshareCap != null ? finalDl.reshareCap : 0,
              finalDl.maxCards > 0 ? finalDl.maxCards : 512,
              finalDl.maxInflight > 0 ? finalDl.maxInflight : 32,
              finalDl.maxPerIp > 0 ? finalDl.maxPerIp : 5,
              finalDl.idleTimeoutSec > 0 ? finalDl.idleTimeoutSec : 60,
              cacheExStatsRegistry
          );
          session.setProfileName(finalDl.profileName);
          session.setListenerId(finalDl.dbId);
          if (cwCycleService != null) {
            session.setCwCycle(cwCycleService.checker(), cwCycleService.config());
          }
          session.setIngressGate(ingressGate());
          session.setPreauthLease(lease);
          finalMs.sessions.add(session);
          session.addCloseHook(() -> finalMs.sessions.remove(session));
          session.accept(socket);
        } else if ("cs378x".equals(finalDl.protocol)) {
          StrictEcmRouter strictRouter = new StrictEcmRouter(cm, null, null, cardDiscoveryService, finalDl.cardMode);
           if (runtimeCore != null) {
             strictRouter.setAdaptiveRoutingEngine(runtimeCore.adaptiveRoutingEngine());
             var dcwProvider = runtimeCore.dcwPolicyProvider(holder);
            if (dcwProvider != null) strictRouter.setDcwPolicySupplier(dcwProvider);
            strictRouter.setObservationTracker(runtimeCore.observationTracker());
            strictRouter.setLinkObserver(runtimeCore.linkObserver());
          }
          if (relayPolicyService != null) {
            // FIX #3 : pas de refresh opportuniste ; politique live via event-bus.
            strictRouter.setRelayPolicySupplier(() -> relayPolicyService.policy(), finalDl.dbId);
            strictRouter.setRelayStores(relayPolicyService.stores());
          }
          if (cwCycleService != null) {
            strictRouter.setCwCycle(cwCycleService.checker(), cwCycleService.config());
          }
          if (cacheHitAttribution != null) {
            strictRouter.setHitAttribution(cacheHitAttribution);
          }
          Camd35TcpSession session = new Camd35TcpSession(
              vertx, cv, registry, authResolver,
              (ref, ctx) -> secretResolver.resolvePasswordAsync(ref, ctx),
              strictRouter,
              finalDl.maxInflight > 0 ? finalDl.maxInflight : 32,
              finalDl.maxPerIp > 0 ? finalDl.maxPerIp : 5,
              finalDl.idleTimeoutSec > 0 ? finalDl.idleTimeoutSec : 60,
              cacheExStatsRegistry
          );
          session.setProfileName(finalDl.profileName);
          session.setListenerId(finalDl.dbId);
          if (cwCycleService != null) {
            session.setCwCycle(cwCycleService.checker(), cwCycleService.config());
          }
          session.setIngressGate(ingressGate());
          session.setPreauthLease(lease);
          finalMs.sessions.add(session);
          session.addCloseHook(() -> finalMs.sessions.remove(session));
          session.accept(socket);
        } else {
          lease.release();
          socket.close();
        }
      } catch (Exception ex) {
        lease.release();
        LOG.log(Level.WARNING, "Failed to accept session on " + dl.key, ex);
        try { socket.close(); } catch (Exception ignored) {}
      }
    });
    try {
      server.listen().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new IllegalStateException("Failed to bind listener " + dl.key + " on " + dl.bindAddress + ":" + dl.port + " : " + cause.getMessage(), cause);
    }
    return ms;
  }

  private boolean hasIpv6OnlySupport() {
    try { NetServerOptions.class.getMethod("setIpv6Only", boolean.class); return true; } catch (NoSuchMethodException e) { return false; }
  }

  private static List<String> parseList(String s) {
    if (s == null || s.isBlank()) return List.of();
    List<String> out = new ArrayList<>();
    for (String part : s.split(",")) { String t = part.trim(); if (!t.isEmpty()) out.add(t); }
    return out;
  }

  static boolean isIpAllowed(String remoteIp, List<String> allow, List<String> deny) {
    if (remoteIp == null) {
      // fail-closed: if allow non-empty requires explicit allow, null never allowed
      return false;
    }
    for (String m : deny) if (IpMatcher.isAllowed(remoteIp, m)) return false;
    if (allow.isEmpty()) return true;
    for (String m : allow) if (IpMatcher.isAllowed(remoteIp, m)) return true;
    return false;
  }

  private void refreshExtSessions() {
    Set<ExtNewcamdSession> toRefresh = new HashSet<>();
    synchronized (extSessions) { toRefresh.addAll(extSessions); }
    for (ManagedServer ms : active.values()) if (ms.isExtended) synchronized (ms.sessions) { for (AbstractSession s : ms.sessions) if (s instanceof ExtNewcamdSession es) toRefresh.add(es); }
    for (ExtNewcamdSession s : toRefresh) try { s.refreshMappingAsync(); } catch (Exception e) { LOG.warning("refresh ext session failed: " + e.getMessage()); }
  }

  private void closeExtSessions() {
    synchronized (extSessions) { for (ExtNewcamdSession s : new ArrayList<>(extSessions)) try { s.close(); } catch (Exception ignored) {} extSessions.clear(); }
    for (ManagedServer ms : active.values()) if (ms.isExtended) closeSessionsForServer(ms);
  }

  private void closeSessionsForServer(ManagedServer ms) {
    synchronized (ms.sessions) { for (AbstractSession s : new ArrayList<>(ms.sessions)) try { s.close(); } catch (Exception ignored) {} ms.sessions.clear(); }
  }

  private static String safeType(String ref) {
    if (ref == null) return "null";
    if (ref.startsWith("vault:")) return "vault";
    if (ref.startsWith("env:")) return "env";
    if (ref.startsWith("file:")) return "file";
    return "unknown";
  }

  private static byte[] hexToBytes8(String hex) {
    if (hex == null || hex.length() < 16) return null;
    byte[] out = new byte[8];
    for (int i = 0; i < 8; i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }

  private static class DesiredListener {
    String key; long dbId; boolean isExtended; String profileName; String protocol; int port; String bindAddress; String desKeyRef; String allowList; String denyList; int maxMsgSize; ExtendedNewcamdConfig extConfig;
    String cardMode; boolean strictPolicy; Integer reshareCap; int maxCards; int maxInflight; int maxPerIp; String nodeId; int idleTimeoutSec;
  }

  public static class ManagedServer {
    public String key; public NetServer server; public int port; public String bindAddress; public String protocol; public String profileName; public String desKeyRef; public String allowList; public String denyList; public boolean isExtended; public byte[] desKey14; public int maxMsgSize; public ExtendedNewcamdConfig extConfig;
    public String cardMode; public boolean strictPolicy; public Integer reshareCap; public int maxCards; public int maxInflight; public int maxPerIp; public String nodeId; public int idleTimeoutSec;
    public final Set<AbstractSession> sessions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    boolean equalsDesired(DesiredListener dl) {
      return port == dl.port && Objects.equals(bindAddress, dl.bindAddress) && Objects.equals(protocol, dl.protocol)
          && Objects.equals(profileName, dl.profileName) && Objects.equals(desKeyRef, dl.desKeyRef)
          && Objects.equals(allowList, dl.allowList) && Objects.equals(denyList, dl.denyList)
          && isExtended == dl.isExtended && maxMsgSize == dl.maxMsgSize
          && Objects.equals(cardMode, dl.cardMode) && strictPolicy == dl.strictPolicy
          && Objects.equals(reshareCap, dl.reshareCap) && maxCards == dl.maxCards
          && maxInflight == dl.maxInflight && maxPerIp == dl.maxPerIp
          && Objects.equals(nodeId, dl.nodeId) && idleTimeoutSec == dl.idleTimeoutSec;
    }
    DesiredListener toDesired() {
      DesiredListener dl = new DesiredListener(); dl.key = key; dl.isExtended = isExtended; dl.profileName = profileName; dl.protocol = protocol; dl.port = port; dl.bindAddress = bindAddress; dl.desKeyRef = desKeyRef; dl.allowList = allowList; dl.denyList = denyList; dl.maxMsgSize = maxMsgSize; dl.extConfig = extConfig;
      dl.cardMode = cardMode; dl.strictPolicy = strictPolicy; dl.reshareCap = reshareCap; dl.maxCards = maxCards; dl.maxInflight = maxInflight; dl.maxPerIp = maxPerIp; dl.nodeId = nodeId; dl.idleTimeoutSec = idleTimeoutSec;
      return dl;
    }
  }

  public Map<String, ManagedServer> getActive() { return Collections.unmodifiableMap(active); }
  public int activeCount() { return active.size(); }
  public NetServer getServer(String key) { ManagedServer ms = active.get(key); return ms != null ? ms.server : null; }
  public boolean isDegraded() { return degraded; }
  public ConfigSnapshot getAppliedSnapshot() { return appliedSnapshot; }
}
