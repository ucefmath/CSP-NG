package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.cache.DcwValidator;
import com.bowman.cspng.codec.cccam.CcConstants;
import com.bowman.cspng.connectors.catalog.CardCapability;
import com.bowman.cspng.connectors.catalog.CardDiscoveryService;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.filters.FilterChain;
import com.bowman.cspng.filters.FilterContext;
import com.bowman.cspng.filters.FilterDecision;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Gestionnaire de connecteurs CWS.
 *
 * <p>Supporte le routage simple (premier connecteur connecté) et le fan-out
 * multi-connecteur activé lorsqu'un filtre de réponse exige de voir les
 * réponses de plusieurs sources simultanément.
 *
 * <h3>Agrégation avec timeout</h3>
 * Quand une chaîne de filtres est configurée, l'agrégation utilise une
 * {@code Promise} unique bornée par {@link EcmRequest#maxWaitMs()}.
 * DROP/HOLD ne résolvent pas immédiatement la Promise ; elle reste
 * ouverte jusqu'à une réponse acceptable/libérée ou le timeout.
 *
 * <h3>Canal de release et scheduler</h3>
 * Chaque {@link FilterContext} porte un {@code ReplyRelease} et un
 * {@code FilterScheduler} réels, basés sur {@code vertx.setTimer}.
 * Un filtre peut planifier ses propres TTL métier (forceContinuity 12s,
 * verifyReplies 20s, detectLinks 2s) et libérer une réponse retenue
 * sans repasser par la chaîne de filtres.
 *
 * <h3>Single-writer context</h3>
 * Toutes les évaluations de {@link FilterChain} et les callbacks
 * scheduler/release sont sérialisés sur un {@link io.vertx.core.Context}
 * dédié capturé au constructeur. Les réponses concurrentes de connecteurs
 * différents sont mises en file sur ce contexte.
 *
 * <h3>Absence de lock</h3>
 * Aucun {@code synchronized}, {@code ArrayList} partagé ni concaténation
 * de noms de connecteurs. Coordination via {@code AtomicBoolean}/{@code AtomicInteger}.
 * Les erreurs sont strictement génériques, sans topologie.
 */
public class CwsConnectorManager {

  private static final Logger LOG = Logger.getLogger(CwsConnectorManager.class.getName());
  private static final Logger ECM_LOG = Logger.getLogger("com.bowman.cspng.ecm");

  private volatile com.bowman.cspng.domain.srvid.ServiceResolver serviceResolver = com.bowman.cspng.domain.srvid.ServiceResolver.EMPTY;

  public void setServiceResolver(com.bowman.cspng.domain.srvid.ServiceResolver resolver) {
    this.serviceResolver = resolver != null ? resolver : com.bowman.cspng.domain.srvid.ServiceResolver.EMPTY;
  }

  public com.bowman.cspng.domain.srvid.ServiceResolver getServiceResolver() {
    return serviceResolver;
  }

  private static int extractProvider(int caid, byte[] body) {
    if (body == null || body.length == 0) return 0;
    try {
      return com.bowman.cspng.codec.EcmProviderExtractor.extractProvider(caid, body);
    } catch (Exception e) {
      return 0;
    }
  }

  private void logEcmSend(AbstractConnector connector, EcmRequest request, int provider) {
    if (!ECM_LOG.isLoggable(java.util.logging.Level.FINE)) return;
    int caid = request.key().caId();
    int sid = request.key().serviceId();
    int provid = provider != 0 ? provider : extractProvider(caid, request.body());
    String ch = serviceResolver != null ? serviceResolver.resolveChannelName(caid, sid) : null;
    String prov = serviceResolver != null ? serviceResolver.resolveProviderName(caid, provid) : null;
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("ECM send connector=%s profile=%s caid=0x%04X provid=0x%06X sid=0x%04X",
        connector != null ? connector.getName() : "none", request.profileName(), caid & 0xFFFF, provid & 0xFFFFFF, sid & 0xFFFF));
    if (ch != null && !ch.isBlank()) sb.append(" channel=\"").append(ch).append("\"");
    if (prov != null && !prov.isBlank()) sb.append(" provider=\"").append(prov).append("\"");
    ECM_LOG.fine(sb.toString());
  }

  private void logEcmHit(AbstractConnector connector, EcmRequest request, int provider, long durationMs) {
    if (!ECM_LOG.isLoggable(java.util.logging.Level.INFO)) return;
    int caid = request.key().caId();
    int sid = request.key().serviceId();
    int provid = provider != 0 ? provider : extractProvider(caid, request.body());
    String ch = serviceResolver != null ? serviceResolver.resolveChannelName(caid, sid) : null;
    String prov = serviceResolver != null ? serviceResolver.resolveProviderName(caid, provid) : null;
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("ECM hit connector=%s profile=%s caid=0x%04X provid=0x%06X sid=0x%04X",
        connector != null ? connector.getName() : "unknown", request.profileName(), caid & 0xFFFF, provid & 0xFFFFFF, sid & 0xFFFF));
    if (ch != null && !ch.isBlank()) sb.append(" channel=\"").append(ch).append("\"");
    if (prov != null && !prov.isBlank()) sb.append(" provider=\"").append(prov).append("\"");
    sb.append(" time=").append(durationMs).append("ms");
    ECM_LOG.info(sb.toString());
  }

  private void logEcmFail(AbstractConnector connector, EcmRequest request, int provider, String reason, long durationMs) {
    if (!ECM_LOG.isLoggable(java.util.logging.Level.INFO)) return;
    int caid = request.key().caId();
    int sid = request.key().serviceId();
    int provid = provider != 0 ? provider : extractProvider(caid, request.body());
    String ch = serviceResolver != null ? serviceResolver.resolveChannelName(caid, sid) : null;
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("ECM fail connector=%s profile=%s caid=0x%04X provid=0x%06X sid=0x%04X",
        connector != null ? connector.getName() : "unknown", request.profileName(), caid & 0xFFFF, provid & 0xFFFFFF, sid & 0xFFFF));
    if (ch != null && !ch.isBlank()) sb.append(" channel=\"").append(ch).append("\"");
    sb.append(" reason=\"").append(reason != null ? reason : "unknown").append("\"");
    sb.append(" time=").append(durationMs).append("ms");
    ECM_LOG.info(sb.toString());
  }

  /** Maximum raisonnable pour le timer d'agrégation. */
  static final long MAX_AGGREGATION_TIMEOUT_MS = 60_000;
  /** Minimum de sécurité. */
  static final long MIN_AGGREGATION_TIMEOUT_MS = 50;

  private final Vertx vertx;
  private final CacheVerticle cacheVerticle;
  /** Contexte Vert.x dédié single-writer pour sérialiser les évaluations de filtres. */
  final Context filterContext;
  final Map<String, AbstractConnector> connectors = new ConcurrentHashMap<>();
  private final List<ConnectorListener> listeners = new CopyOnWriteArrayList<>();

  private volatile FilterChain filterChain;
  private volatile com.bowman.cspng.domain.dcw.DcwQualityPolicy dcwPolicy = com.bowman.cspng.domain.dcw.DcwQualityPolicy.DEFAULT;
  private volatile Supplier<com.bowman.cspng.domain.dcw.DcwQualityPolicy> dcwPolicySupplier;
  private volatile com.bowman.cspng.domain.dcw.DcwQualityPolicy lastSuppliedDcwPolicy;

  public void setDcwPolicy(com.bowman.cspng.domain.dcw.DcwQualityPolicy policy) {
    this.dcwPolicy = policy != null ? policy : com.bowman.cspng.domain.dcw.DcwQualityPolicy.DEFAULT;
    this.dcwPolicySupplier = null;
  }

  public void setDcwPolicySupplier(Supplier<com.bowman.cspng.domain.dcw.DcwQualityPolicy> supplier) {
    this.dcwPolicySupplier = supplier;
  }

  public com.bowman.cspng.domain.dcw.DcwQualityPolicy effectiveDcwPolicy() {
    java.util.function.Supplier<com.bowman.cspng.domain.dcw.DcwQualityPolicy> s = dcwPolicySupplier;
    if (s != null) {
      try {
        com.bowman.cspng.domain.dcw.DcwQualityPolicy p = s.get();
        if (p != null) { lastSuppliedDcwPolicy = p; return p; }
      } catch (RuntimeException ignored) {
        // fail-closed : dernier bon fourni, jamais un DEFAULT plus permissif
      }
      com.bowman.cspng.domain.dcw.DcwQualityPolicy last = lastSuppliedDcwPolicy;
      if (last != null) return last;
    }
    return dcwPolicy != null ? dcwPolicy : com.bowman.cspng.domain.dcw.DcwQualityPolicy.DEFAULT;
  }


  public CwsConnectorManager(Vertx vertx, CacheVerticle cacheVerticle) {
    this.vertx = vertx;
    this.cacheVerticle = cacheVerticle;
    this.filterContext = vertx.getOrCreateContext();
  }

  /**
   * Enregistre (ou remplace) la chaîne de filtres de réponse.
   *
   * <p>Prévu pour être appelé depuis {@code csp-app} au câblage,
   * sans dépendance CDI dans ce module. {@code null} désactive le filtrage.
   */
  public void setFilterChain(FilterChain filterChain) {
    this.filterChain = filterChain;
  }

  /** Chaîne de filtres actuelle, ou {@code null} si aucun filtrage. */
  public FilterChain getFilterChain() {
    return filterChain;
  }

  /**
   * Exécute un supplier sur le contexte single-writer dédié aux filtres.
   *
   * <p>Permet à {@code csp-app} ou à la couche persistance de lire
   * {@code DcwFilterStats} / {@code linkGroupsSnapshot()} sans race
   * condition, sans bloquer l'event-loop.
   *
   * <p>Les exceptions du supplier sont propagées dans la {@code Future}
   * échouée (message générique, sans topologie).
   *
   * @param supplier code à exécuter sur le contexte single-writer
   * @param <T>      type de retour
   * @return future contenant le résultat ou l'erreur
   */
  public <T> Future<T> runOnFilterContext(Supplier<T> supplier) {
    Promise<T> promise = Promise.promise();
    filterContext.runOnContext(v -> {
      try {
        promise.tryComplete(supplier.get());
      } catch (Exception e) {
        promise.tryFail("Filter context operation failed");
      }
    });
    return promise.future();
  }

  public void addListener(ConnectorListener listener) {
    if (listener != null) listeners.add(listener);
  }

  /**
   * Ajoute un connecteur Newcamd (legacy, connexion directe).
   */
  public NewcamdConnector addNewcamdConnector(String name, String host, int port,
                                                byte[] desKey14, String username,
                                                String password, String profile) {
    return addNewcamdConnector(name, host, port, desKey14, username, password, profile, 5000, null);
  }

  /**
   * Ajoute un connecteur Newcamd avec timeout et proxy SOCKS5 optionnel.
   */
  public NewcamdConnector addNewcamdConnector(String name, String host, int port,
                                                byte[] desKey14, String username,
                                                String password, String profile,
                                                int connectTimeoutMs,
                                                AbstractConnector.NetworkProxyOptions proxyOptions) {
    NewcamdConnector connector = new NewcamdConnector(vertx, cacheVerticle,
        name, host, port, desKey14, username, password, profile, connectTimeoutMs, proxyOptions);
    connectors.put(name, connector);
    for (ConnectorListener l : listeners) l.onConnectorAdded(name, connector);
    return connector;
  }

  /**
   * Ajoute un connecteur Radegast (legacy, connexion directe).
   */
  public RadegastConnector addRadegastConnector(String name, String host, int port,
                                                   String profile) {
    return addRadegastConnector(name, host, port, profile, 10000, null);
  }

  /**
   * Ajoute un connecteur Radegast avec timeout et proxy SOCKS5 optionnel.
   */
  public RadegastConnector addRadegastConnector(String name, String host, int port,
                                                   String profile,
                                                   int connectTimeoutMs,
                                                   AbstractConnector.NetworkProxyOptions proxyOptions) {
    RadegastConnector connector = new RadegastConnector(vertx, cacheVerticle,
        name, host, port, profile, connectTimeoutMs, proxyOptions);
    connectors.put(name, connector);
    for (ConnectorListener l : listeners) l.onConnectorAdded(name, connector);
    return connector;
  }

  /**
   * Ajoute un connecteur CS378x (camd35 sur TCP) avec CacheEx (V15).
   * mode 2 = nous recevons les push du serveur ; mode 3 = nous émettons vers lui.
   */
  public Camd35TcpConnector addCamd35TcpConnector(String name, String host, int port,
                                                    String username, String password,
                                                    String profile,
                                                    int cacheexMode, int cacheexMaxHop,
                                                    int connectTimeoutMs,
                                                    AbstractConnector.NetworkProxyOptions proxyOptions,
                                                    String cacheexFilterCfg, int cacheexMaxSendQueue,
                                                    com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry statsRegistry) {
    Camd35TcpConnector connector = new Camd35TcpConnector(vertx, cacheVerticle,
        name, host, port, username, password, profile,
        cacheexMode, cacheexMaxHop, connectTimeoutMs, proxyOptions,
        cacheexFilterCfg, cacheexMaxSendQueue, statsRegistry);
    connectors.put(name, connector);
    for (ConnectorListener l : listeners) l.onConnectorAdded(name, connector);
    return connector;
  }

  /**
   * Ajoute un connecteur CCcam (mode ECM uniquement, sans Cache-Exchange, connexion directe).
   */
  public CcCamConnector addCcCamConnector(String name, String host, int port,
                                             String username, String password,
                                             String profile, String version, String build) {
    return addCcCamConnector(name, host, port, username, password, profile, version, build,
        CcConstants.CACHEEX_MODE_OFF, CcConstants.CACHEEX_DEFAULT_MAXHOP,
        5000, null);
  }

  /**
   * Ajoute un connecteur CCcam avec Cache-Exchange (connexion directe).
   */
  public CcCamConnector addCcCamConnector(String name, String host, int port,
                                             String username, String password,
                                             String profile, String version, String build,
                                             int cacheexMode, int cacheexMaxHop) {
    return addCcCamConnector(name, host, port, username, password, profile, version, build,
        cacheexMode, cacheexMaxHop, 5000, null);
  }

  /**
   * Ajoute un connecteur CCcam complet avec timeout, cacheex et proxy SOCKS5 optionnel.
   */
  public CcCamConnector addCcCamConnector(String name, String host, int port,
                                             String username, String password,
                                             String profile, String version, String build,
                                             int cacheexMode, int cacheexMaxHop,
                                             int connectTimeoutMs,
                                             AbstractConnector.NetworkProxyOptions proxyOptions) {
    return addCcCamConnector(name, host, port, username, password, profile, version, build,
        cacheexMode, cacheexMaxHop, connectTimeoutMs, proxyOptions,
        null, CcCamConnector.DEFAULT_MAX_SEND_QUEUE, null);
  }

  /**
   * Ajoute un connecteur CCcam complet V15 : filtre annoncé (mode 2), borne de file
   * push (mode 3), registry de stats par pair (nullable).
   */
  public CcCamConnector addCcCamConnector(String name, String host, int port,
                                             String username, String password,
                                             String profile, String version, String build,
                                             int cacheexMode, int cacheexMaxHop,
                                             int connectTimeoutMs,
                                             AbstractConnector.NetworkProxyOptions proxyOptions,
                                             String cacheexFilterCfg, int cacheexMaxSendQueue,
                                             com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry statsRegistry) {
    CcCamConnector connector = new CcCamConnector(vertx, cacheVerticle,
        name, host, port, username, password, profile, version, build,
        cacheexMode, cacheexMaxHop, connectTimeoutMs, proxyOptions,
        cacheexFilterCfg, cacheexMaxSendQueue, statsRegistry);
    connectors.put(name, connector);
    for (ConnectorListener l : listeners) l.onConnectorAdded(name, connector);
    return connector;
  }

  /**
   * Retourne un connecteur par nom.
   */
  public AbstractConnector getConnector(String name) {
    return connectors.get(name);
  }

  /**
   * Retourne le premier connecteur disponible pour un profil.
   */
  public AbstractConnector getConnectorForProfile(String profile) {
    return connectors.values().stream()
        .filter(c -> profile.equals(c.getProfile()))
        .findFirst().orElse(null);
  }

  /** Retourne tous les connecteurs connectés d'un profil. */
  public List<AbstractConnector> getConnectedConnectorsForProfile(String profile) {
    return connectors.values().stream()
        .filter(c -> profile.equals(c.getProfile()))
        .filter(AbstractConnector::isConnected)
        .toList();
  }

  /** Package-private: enregistre un connecteur pour les tests unitaires. */
  public void addTestConnector(AbstractConnector connector) {
    connectors.put(connector.getName(), connector);
  }

  /**
   * Envoie une requête ECM.
   *
   * <p><b>Sans chaîne de filtres</b> : comportement historique inchangé.
   *
   * <p><b>Avec chaîne de filtres</b> : agrégation Promise-borne avec
   * timeout, canal de release asynchrone, scheduler, DROP/HOLD non bloquants.
   * Fan-out activé uniquement si {@code chain.requiresMultipleReplies()}.
   *
   * @param request requête ECM
   * @return future contenant la CW, ou échouée (message générique, borné)
   */
  public Future<CwReply> sendEcmRequest(EcmRequest request) {
    FilterChain chain = this.filterChain;
    String profile = request.profileName();

    // --- comportement historique (sans chaîne de filtres) ---
    if (chain == null) {
      AbstractConnector connector = getConnectorForProfile(profile);
      if (connector == null || !connector.isConnected()) {
        return Future.failedFuture("No connector available for profile: " + profile);
      }
      int caid = request.key().caId();
      int provid = extractProvider(caid, request.body());
      connector.recordEcmSent(caid, provid);
      logEcmSend(connector, request, provid);
      long startNs = System.nanoTime();
      try {
        return connector.sendEcm(request).map(r -> {
          long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
          if (r != null && !r.isEmpty()) {
            connector.recordHit();
            logEcmHit(connector, request, provid, durationMs);
            return ensureMatchingKey(r, request.key());
          } else {
            connector.recordError();
            logEcmFail(connector, request, provid, "EMPTY", durationMs);
            return r;
          }
        }).onFailure(err -> {
          long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
          connector.recordError();
          logEcmFail(connector, request, provid, err != null ? err.getMessage() : "UNKNOWN", durationMs);
        });
      } catch (Exception e) {
        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        connector.recordError();
        logEcmFail(connector, request, provid, e.getMessage(), durationMs);
        return Future.failedFuture(e);
      }
    }

    // --- avec chaîne de filtres : agrégation Promise + timer ---
    List<AbstractConnector> candidates;
    if (chain.requiresMultipleReplies()) {
      candidates = getConnectedConnectorsForProfile(profile);
    } else {
      AbstractConnector single = getConnectorForProfile(profile);
      if (single == null || !single.isConnected()) {
        return Future.failedFuture("No connector available for profile: " + profile);
      }
      candidates = List.of(single);
    }

    if (candidates.isEmpty()) {
      return Future.failedFuture("No connector available for profile: " + profile);
    }

    return aggregateWithTimer(request, chain, profile, candidates);
  }

  /**
   * Envoie une requête ECM en sélectionnant les candidats selon (profile, caid, provider),
   * puis en dispatchant séquentiellement de manière ordonnée jusqu'à obtention d'une CW valide.
   */
  public Future<CwReply> sendEcmRequest(EcmRequest request, int provider, CardDiscoveryService discoveryService) {
    return sendEcmRequest(request, provider, discoveryService, "manual");
  }

  /**
   * Envoie une requête ECM en sélectionnant les candidats selon (profile, caid, provider) et cardMode.
   */
  public Future<CwReply> sendEcmRequest(EcmRequest request, int provider, CardDiscoveryService discoveryService, String cardMode) {
    return sendEcmRequest(request, provider, discoveryService, cardMode, 0L);
  }

  /**
   * Envoie une requête ECM en sélectionnant les candidats selon (profile, caid, provider) et cardMode,
   * avec identifiant de corrélation opaque optionnel.
   */
  public Future<CwReply> sendEcmRequest(EcmRequest request, int provider, CardDiscoveryService discoveryService,
                                        String cardMode, long reqId) {
    long effectiveReqId = reqId != 0L ? reqId : RelayDiagnostic.nextReqId();
    int caid = request.key().caId();
    int sid = request.key().serviceId();
    long startNs = System.nanoTime();

    List<AbstractConnector> candidates = selectCandidates(
        request.profileName(), caid, provider, discoveryService, cardMode);
    if (candidates.isEmpty()) {
      RelayDiagnostic.logSelectionEmpty(effectiveReqId, caid, provider, sid);
      return Future.failedFuture("No connector candidate available for profile=" + request.profileName()
          + " caid=0x" + Integer.toHexString(caid));
    }
    long maxWait = request.maxWaitMs() > 0 ? request.maxWaitMs() : 5000L;
    if (maxWait > MAX_AGGREGATION_TIMEOUT_MS) maxWait = MAX_AGGREGATION_TIMEOUT_MS;
    if (maxWait < MIN_AGGREGATION_TIMEOUT_MS) maxWait = MIN_AGGREGATION_TIMEOUT_MS;
    final long boundedWait = maxWait;

    RelayDiagnostic.logSelectionCandidates(effectiveReqId, caid, provider, sid, candidates.size(), boundedWait);
    return dispatchToCandidates(request, candidates, effectiveReqId, boundedWait, startNs, provider);
  }

  /**
   * Sélectionne les candidats connecteurs pour un triplet (profile, caid, provider),
   * avec tri déterministe (hop ascendant, priorité descendante, nom) et fallback par CAID.
   */
  public List<AbstractConnector> selectCandidates(String profile, int caid, int provider,
                                                   CardDiscoveryService discoveryService, String cardMode) {
    if (discoveryService != null) {
      CardDiscoveryService.CapabilityRuntimeState capExact = provider != 0 ? discoveryService.getCapability(profile, caid, provider) : null;
      if (capExact != null && capExact.state == CardCapability.State.BLOCKED) {
        return List.of();
      }
      CardDiscoveryService.CapabilityRuntimeState capZero = discoveryService.getCapability(profile, caid, 0);
      if (capZero != null && capZero.state == CardCapability.State.BLOCKED) {
        return List.of();
      }

      // 1. Essai avec le provider exact
      if (provider != 0) {
        List<AbstractConnector> candidates = selectCandidatesFromDiscovery(profile, caid, provider, discoveryService, cardMode);
        if (!candidates.isEmpty()) {
          return candidates;
        }
      }
      // 2. Si provider == 0 ou si non trouvé avec provider exact : essai avec provider = 0
      List<AbstractConnector> candidatesZero = selectCandidatesFromDiscovery(profile, caid, 0, discoveryService, cardMode);
      if (!candidatesZero.isEmpty()) {
        return candidatesZero;
      }
      // 3. Fallback : toutes les capacités du CAID dans le profil
      List<AbstractConnector> candidatesCaid = selectCandidatesFromDiscoveryByCaid(profile, caid, discoveryService, cardMode);
      if (!candidatesCaid.isEmpty()) {
        return candidatesCaid;
      }

      // Si le discovery service catalogue ce CAID dans le profil, ne pas contourner le discovery
      List<CardDiscoveryService.CapabilityRuntimeState> existingCaps = discoveryService.getCapabilitiesForCaid(profile, caid);
      if (!existingCaps.isEmpty()) {
        return List.of();
      }
    }

    // 4. Fallback connecteurs connectés du profil (filtrés par cartes connues si applicables)
    List<AbstractConnector> connected = getConnectedConnectorsForProfile(profile);
    if (connected == null || connected.isEmpty()) {
      return List.of();
    }

    List<AbstractConnector> matching = connected.stream()
        .filter(c -> {
          if (c instanceof CcCamConnector cc) {
            return cc.hasCardForCaid(caid);
          }
          return true;
        })
        .sorted(Comparator.comparingInt(AbstractConnector::getPriority).reversed()
            .thenComparing(AbstractConnector::getName))
        .toList();

    if (!matching.isEmpty()) {
      return matching;
    }

    return connected.stream()
        .sorted(Comparator.comparingInt(AbstractConnector::getPriority).reversed()
            .thenComparing(AbstractConnector::getName))
        .toList();
  }

  private List<AbstractConnector> selectCandidatesFromDiscovery(String profile, int caid, int provider,
                                                                 CardDiscoveryService discoveryService, String cardMode) {
    CardDiscoveryService.CapabilityRuntimeState cap = discoveryService.getCapability(profile, caid, provider);
    if (cap == null || cap.sources.isEmpty()) {
      return List.of();
    }
    if (cap.state == CardCapability.State.BLOCKED) {
      return List.of();
    }
    boolean isWildcard = "wildcard".equalsIgnoreCase(cardMode);
    if (!isWildcard && cap.state != CardCapability.State.PUBLISHED) {
      return List.of();
    }
    List<CardDiscoveryService.SourceRuntimeState> validSources = new ArrayList<>();
    for (CardDiscoveryService.SourceRuntimeState src : cap.sources.values()) {
      if (!discoveryService.isSourceOnline(src.connectorName(), src.generation())) {
        continue;
      }
      validSources.add(src);
    }
    if (validSources.isEmpty()) return List.of();

    return validSources.stream()
        .map(src -> getConnector(src.connectorName()))
        .filter(c -> c != null && c.isConnected())
        .distinct()
        .sorted(Comparator.comparingInt((AbstractConnector c) -> {
              int minHop = 999;
              for (var src : cap.sources.values()) {
                if (src.connectorName().equals(c.getName())) minHop = Math.min(minHop, src.hop());
              }
              return minHop;
            })
            .thenComparing(Comparator.comparingInt(AbstractConnector::getPriority).reversed())
            .thenComparing(AbstractConnector::getName))
        .toList();
  }

  private List<AbstractConnector> selectCandidatesFromDiscoveryByCaid(String profile, int caid,
                                                                       CardDiscoveryService discoveryService, String cardMode) {
    List<CardDiscoveryService.CapabilityRuntimeState> caps = discoveryService.getCapabilitiesForCaid(profile, caid);
    if (caps.isEmpty()) return List.of();

    boolean isWildcard = "wildcard".equalsIgnoreCase(cardMode);
    List<AbstractConnector> allCandidates = new ArrayList<>();
    for (CardDiscoveryService.CapabilityRuntimeState cap : caps) {
      if (cap.state == CardCapability.State.BLOCKED) continue;
      if (!isWildcard && cap.state != CardCapability.State.PUBLISHED) continue;

      for (CardDiscoveryService.SourceRuntimeState src : cap.sources.values()) {
        if (!discoveryService.isSourceOnline(src.connectorName(), src.generation())) continue;
        AbstractConnector conn = getConnector(src.connectorName());
        if (conn != null && conn.isConnected() && !allCandidates.contains(conn)) {
          allCandidates.add(conn);
        }
      }
    }

    allCandidates.sort(Comparator.comparingInt(AbstractConnector::getPriority).reversed()
        .thenComparing(AbstractConnector::getName));
    return allCandidates;
  }

  /**
   * Dispatche séquentiellement la requête aux candidats ordonnés, avec arrêt au premier succès
   * et timeout global borné.
   */
  public Future<CwReply> dispatchToCandidates(EcmRequest request, List<AbstractConnector> candidates) {
    long reqId = RelayDiagnostic.nextReqId();
    long maxWait = request.maxWaitMs() > 0 ? request.maxWaitMs() : 5000L;
    if (maxWait > MAX_AGGREGATION_TIMEOUT_MS) maxWait = MAX_AGGREGATION_TIMEOUT_MS;
    if (maxWait < MIN_AGGREGATION_TIMEOUT_MS) maxWait = MIN_AGGREGATION_TIMEOUT_MS;
    final long boundedWait = maxWait;
    return dispatchToCandidates(request, candidates, reqId, boundedWait, System.nanoTime(),
        com.bowman.cspng.domain.dcw.DcwRuleScope.UNKNOWN_PROVIDER);
  }

  private Future<CwReply> dispatchToCandidates(EcmRequest request, List<AbstractConnector> candidates,
                                               long reqId, long boundedWait, long startNs, int provider) {
    if (candidates == null || candidates.isEmpty()) {
      return Future.failedFuture("No candidates available");
    }
    Promise<CwReply> promise = Promise.promise();
    AtomicBoolean completed = new AtomicBoolean(false);
    java.util.concurrent.atomic.AtomicInteger currentCandIndex = new java.util.concurrent.atomic.AtomicInteger(0);

    long timerId = vertx.setTimer(boundedWait, id -> {
      if (completed.compareAndSet(false, true)) {
        long totalDurationMs = (System.nanoTime() - startNs) / 1_000_000L;
        RelayDiagnostic.logResultTimeout(reqId, totalDurationMs, boundedWait, currentCandIndex.get());
        promise.tryFail("ECM request timeout after " + boundedWait + "ms");
      }
    });

    dispatchNextCandidate(request, candidates, 0, promise, completed, timerId, reqId, boundedWait, startNs, currentCandIndex, provider);
    return promise.future();
  }

  private void dispatchNextCandidate(EcmRequest request, List<AbstractConnector> candidates, int index,
                                     Promise<CwReply> promise, AtomicBoolean completed, long timerId,
                                     long reqId, long boundedWait, long startNs,
                                     java.util.concurrent.atomic.AtomicInteger currentCandIndex, int provider) {
    if (completed.get()) return;
    currentCandIndex.set(index);
    if (index >= candidates.size()) {
      if (completed.compareAndSet(false, true)) {
        vertx.cancelTimer(timerId);
        long totalDurationMs = (System.nanoTime() - startNs) / 1_000_000L;
        RelayDiagnostic.logResultExhausted(reqId, totalDurationMs, boundedWait);
        promise.tryFail("All candidates exhausted without valid CW");
      }
      return;
    }

    AbstractConnector connector = candidates.get(index);
    if (!connector.isConnected()) {
      RelayDiagnostic.logAttemptFailed(reqId, index, index, "DISCONNECTED", 0L);
      dispatchNextCandidate(request, candidates, index + 1, promise, completed, timerId, reqId, boundedWait, startNs, currentCandIndex, provider);
      return;
    }

    RelayDiagnostic.logAttemptStart(reqId, index, index);

    int caid = request.key().caId();
    int provid = provider != 0 ? provider : extractProvider(caid, request.body());
    connector.recordEcmSent(caid, provid);
    logEcmSend(connector, request, provid);

    long attemptStartNs = System.nanoTime();
    Future<CwReply> fut;
    try {
      fut = connector.sendEcm(request);
    } catch (Exception e) {
      long durationMs = (System.nanoTime() - attemptStartNs) / 1_000_000L;
      connector.recordError();
      logEcmFail(connector, request, provid, e.getMessage(), durationMs);
      if (RelayDiagnostic.isEnabled()) {
        String reason = RelayDiagnostic.normalizeError(e);
        RelayDiagnostic.logAttemptFailed(reqId, index, index, reason, durationMs);
      }
      dispatchNextCandidate(request, candidates, index + 1, promise, completed, timerId, reqId, boundedWait, startNs, currentCandIndex, provider);
      return;
    }

    fut.onComplete(ar -> {
      if (completed.get()) return;
      long durationMs = (System.nanoTime() - attemptStartNs) / 1_000_000L;
      boolean validReply = ar.succeeded() && ar.result() != null && !ar.result().isEmpty()
          && effectiveDcwPolicy().validate(ar.result().cw(), request.profileName(), request.key().caId(), provider,
              request.key().serviceId(), connector.getName(), com.bowman.cspng.domain.dcw.LegacyGuard.CHECKSUM_REQUIRED).isValid();
      if (validReply) {
        if (completed.compareAndSet(false, true)) {
          vertx.cancelTimer(timerId);
          connector.recordHit();
          logEcmHit(connector, request, provid, durationMs);
          RelayDiagnostic.logAttemptOk(reqId, index, index, durationMs);
          long totalDurationMs = (System.nanoTime() - startNs) / 1_000_000L;
          RelayDiagnostic.logResultResolved(reqId, totalDurationMs, boundedWait);
          CwReply result = ensureMatchingKey(ar.result(), request.key());
          promise.tryComplete(result);
        }
      } else {
        connector.recordError();
        String reason;
        if (ar.failed()) {
          reason = RelayDiagnostic.normalizeError(ar.cause());
        } else if (ar.result() == null || ar.result().isEmpty()) {
          reason = "EMPTY";
        } else {
          reason = "INVALID";
        }
        logEcmFail(connector, request, provid, reason, durationMs);
        if (RelayDiagnostic.isEnabled()) {
          RelayDiagnostic.logAttemptFailed(reqId, index, index, reason, durationMs);
        }
        dispatchNextCandidate(request, candidates, index + 1, promise, completed, timerId, reqId, boundedWait, startNs, currentCandIndex, provider);
      }
    });
  }

  // --- agrégation avec timer ---

  private Future<CwReply> aggregateWithTimer(EcmRequest request, FilterChain chain,
                                              String profile, List<AbstractConnector> candidates) {
    Promise<CwReply> promise = Promise.promise();
    AtomicBoolean completed = new AtomicBoolean(false);
    AtomicInteger pending = new AtomicInteger(candidates.size());

    long maxWaitMs = request.maxWaitMs();
    if (maxWaitMs <= 0) maxWaitMs = 5000;
    if (maxWaitMs > MAX_AGGREGATION_TIMEOUT_MS) maxWaitMs = MAX_AGGREGATION_TIMEOUT_MS;
    if (maxWaitMs < MIN_AGGREGATION_TIMEOUT_MS) maxWaitMs = MIN_AGGREGATION_TIMEOUT_MS;

    final long boundedWaitMs = maxWaitMs;

    // timeout timer
    long timerId = vertx.setTimer(boundedWaitMs, ignored -> {
      if (completed.compareAndSet(false, true)) {
        promise.tryFail("No ECM response within " + boundedWaitMs + "ms");
      }
    });

    // release callback: serialized on filterContext
    FilterContext.ReplyRelease releaseChannel = (reply, connectorRef) -> {
      filterContext.runOnContext(v -> {
        if (completed.get()) return;
        if (reply == null || reply.isEmpty()) return;
        if (completed.compareAndSet(false, true)) {
          vertx.cancelTimer(timerId);
          promise.tryComplete(ensureMatchingKey(reply, request.key()));
        }
      });
    };

    // scheduler: bound to vertx, callbacks serialized on filterContext
    FilterContext.FilterScheduler scheduler = (delayMs, task) -> {
      long delay = clampDelay(delayMs);
      long scheduledId = vertx.setTimer(delay, ignored ->
          filterContext.runOnContext(v -> task.run()));
      return () -> vertx.cancelTimer(scheduledId);
    };

    for (AbstractConnector connector : candidates) {
      int caid = request.key().caId();
      int provid = extractProvider(caid, request.body());
      connector.recordEcmSent(caid, provid);
      logEcmSend(connector, request, provid);
      long attemptStartNs = System.nanoTime();
      Future<CwReply> ecmFuture;
      try {
        ecmFuture = connector.sendEcm(request);
      } catch (Exception e) {
        long durationMs = (System.nanoTime() - attemptStartNs) / 1_000_000L;
        connector.recordError();
        logEcmFail(connector, request, provid, e.getMessage(), durationMs);
        handleFailure(promise, completed, pending, timerId, candidates.size());
        continue;
      }
      ecmFuture
          .onSuccess(reply -> {
            long durationMs = (System.nanoTime() - attemptStartNs) / 1_000_000L;
            if (reply != null && !reply.isEmpty()) {
              connector.recordHit();
              logEcmHit(connector, request, provid, durationMs);
            } else {
              connector.recordError();
              logEcmFail(connector, request, provid, "EMPTY", durationMs);
            }
            handleReply(request, chain, profile, connector,
                reply, promise, completed, pending, timerId, releaseChannel, scheduler,
                candidates.size());
          })
          .onFailure(err -> {
            long durationMs = (System.nanoTime() - attemptStartNs) / 1_000_000L;
            connector.recordError();
            logEcmFail(connector, request, provid, err.getMessage(), durationMs);
            handleFailure(promise, completed, pending,
                timerId, candidates.size());
          });
    }

    return promise.future();
  }

  /** Borne un délai de scheduling entre 1ms et 60s. */
  static long clampDelay(long delayMs) {
    if (delayMs <= 0) return 1;
    if (delayMs > 60_000) return 60_000;
    return delayMs;
  }

  private void handleReply(EcmRequest request, FilterChain chain, String profile,
                            AbstractConnector connector, CwReply reply,
                            Promise<CwReply> promise, AtomicBoolean completed,
                            AtomicInteger pending, long timerId,
                            FilterContext.ReplyRelease releaseChannel,
                            FilterContext.FilterScheduler scheduler, int total) {
    // sérialiser toutes les évaluations sur le contexte dédié single-writer
    filterContext.runOnContext(v -> {
      if (completed.get()) return;

      FilterContext ctx = new FilterContext(request, connector.getName(),
          profile, System.currentTimeMillis(), releaseChannel, scheduler);
      FilterDecision decision;
      try {
        decision = chain.evaluate(ctx, reply);
      } catch (Exception e) {
        LOG.warning("Filter chain threw for connector " + connector.getName() + ": " + e);
        decision = FilterDecision.Drop.INSTANCE;
      }

      if (completed.get()) return;

      if (decision.isTerminating()) {
        if (completed.compareAndSet(false, true)) {
          vertx.cancelTimer(timerId);
          promise.tryComplete(ensureMatchingKey(resolveReply(decision), request.key()));
        }
        return;
      }

      // DROP, HOLD: keep Promise open
      if (pending.decrementAndGet() == 0 && !completed.get()) {
        LOG.fine(() -> "All " + total + " connectors exhausted"
            + " (no accept yet), waiting for timer or async release");
      }
    });
  }

  private void handleFailure(Promise<CwReply> promise, AtomicBoolean completed,
                              AtomicInteger pending, long timerId, int total) {
    filterContext.runOnContext(v -> {
      if (completed.get()) return;
      if (pending.decrementAndGet() == 0 && !completed.get()) {
        LOG.fine(() -> "All " + total + " connectors exhausted"
            + " (with errors), waiting for timer or async release");
      }
    });
  }

  // --- helpers ---

  /** Résout une décision terminante en {@code CwReply}. */
  static CwReply resolveReply(FilterDecision d) {
    if (d instanceof FilterDecision.Accept(CwReply r)) return r;
    if (d instanceof FilterDecision.Rewrite(CwReply r)) return r;
    if (d instanceof FilterDecision.ReleasePrevious(CwReply r, String ref)) return r;
    throw new IllegalStateException("Not a terminating decision: " + d);
  }

  /**
   * Garantit que le {@code CwReply} retourné porte la clé exacte de la requête.
   * Défense en profondeur contre un connecteur renvoyant une clé factice/incomplète.
   */
  static CwReply ensureMatchingKey(CwReply reply, EcmKey requestKey) {
    if (reply == null || requestKey == null) return reply;
    EcmKey replyKey = reply.key();
    if (replyKey != null
        && replyKey.commandTag() == requestKey.commandTag()
        && replyKey.serviceId() == requestKey.serviceId()
        && replyKey.networkId() == requestKey.networkId()
        && replyKey.caId() == requestKey.caId()
        && replyKey.dataHash() == requestKey.dataHash()) {
      return reply;
    }
    return new CwReply(
        requestKey,
        reply.commandTag() != 0 ? reply.commandTag() : requestKey.commandTag(),
        reply.cw(),
        reply.connectorName(),
        reply.originAddress(),
        reply.timestamp());
  }

  // --- existing methods unchanged below ---

  public AbstractConnector removeConnector(String name) {
    AbstractConnector removed = connectors.remove(name);
    if (removed != null) {
      try {
        removed.stop();
      } catch (Exception e) {
        LOG.warning("Failed to stop connector " + name + ": " + e.getMessage());
      }
      if (removed instanceof com.bowman.cspng.cache.CacheTransport ct) {
        try {
          cacheVerticle.removeTransport(ct);
        } catch (Exception e) {
          LOG.warning("Failed to remove transport for " + name + ": " + e.getMessage());
        }
      }
      for (ConnectorListener l : listeners) {
        try {
          l.onConnectorRemoved(name, removed);
        } catch (Exception e) {
          LOG.warning("Listener onConnectorRemoved failed: " + e.getMessage());
        }
      }
    }
    return removed;
  }

  public int activeCount() {
    return (int) connectors.values().stream().filter(AbstractConnector::isConnected).count();
  }

  public Map<String, AbstractConnector> getConnectors() {
    return java.util.Collections.unmodifiableMap(connectors);
  }

  /** Snapshot immuable trié par name, sanitizé et enrichi. */
  public java.util.List<ConnectorSnapshot> snapshotConnectors() {
    java.util.List<ConnectorSnapshot> list = connectors.values().stream()
        .map(c -> new ConnectorSnapshot(
            c.getName(),
            ConnectorSnapshot.deriveProtocol(c),
            c.getProfile(),
            c.isConnected(),
            ConnectorSnapshot.deriveLoginState(c),
            ConnectorSnapshot.deriveState(c),
            c.getHits(),
            c.getEcmSent(),
            c.getEcmFailed(),
            c.getLastSeenMs(),
            c.getObservedCaids().stream().sorted().map(caid -> String.format("0x%04X", caid)).toList(),
            c.getObservedProviders().stream().sorted().map(prov -> String.format("0x%06X", prov)).toList()))
        .sorted(java.util.Comparator.comparing(ConnectorSnapshot::name))
        .toList();
    return java.util.Collections.unmodifiableList(list);
  }

  public void stopAll() {
    connectors.values().forEach(AbstractConnector::stop);
  }

  /**
   * Interface d'écoute des événements connecteurs.
   */
  public interface ConnectorListener {
    void onConnectorAdded(String name, AbstractConnector connector);

    default void onConnectorRemoved(String name, AbstractConnector connector) {}
  }
}
