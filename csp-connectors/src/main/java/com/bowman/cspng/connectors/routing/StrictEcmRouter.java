package com.bowman.cspng.connectors.routing;

import com.bowman.cspng.connectors.AbstractConnector;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.connectors.catalog.CardCapability;
import com.bowman.cspng.connectors.catalog.CardCatalogRepository;
import com.bowman.cspng.connectors.catalog.CardDiscoveryService;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.domain.dcw.DcwQualityPolicy;
import com.bowman.cspng.domain.dcw.DcwValidationResult;
import com.bowman.cspng.users.ProtocolAuthResolver;
import com.bowman.cspng.users.SessionEcmRouter;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Routeur d'ECM strict {@code catalog_strict} (P7).
 *
 * <p>Contrats P7 (docs/INBOUND-CAMS-PLAN.md §P7) :
 * <ul>
 *   <li><b>Routage strict obligatoire</b> : aucun fallback permissif vers un connecteur non éligible.</li>
 *   <li><b>Filtres ACL & profils</b> : vérification des profils autorisés, des services bloqués/autorisés,
 *       et des connecteurs autorisés avant routage ET avant restitution de cache.</li>
 *   <li><b>Tri déterministe</b> : hop ascendant puis priorité de connecteur.</li>
 *   <li><b>Cache unifié partitionné</b> : partition intégrale (corps exact, epoch, ensemble de connecteurs éligibles),
 *       coalescence in-flight avec vérification de provenance obligatoire.</li>
 *   <li><b>Timeout in-flight borné</b> : protection contre les connecteurs muets via maxWaitMs.</li>
 * </ul>
 */
public class StrictEcmRouter implements SessionEcmRouter {

  private static final ScheduledExecutorService TIMEOUT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "strict-ecm-router-timeout");
    t.setDaemon(true);
    return t;
  });

  private final CwsConnectorManager connectorManager;
  private final StrictPartitionedCache cache;
  private final CardCatalogRepository catalogRepository;
  private final CardDiscoveryService discoveryService;
  private final String cardMode;
  private volatile RelayCachePolicy relayPolicy;
  private volatile java.util.function.Supplier<RelayCachePolicy> relayPolicySupplier;
  private volatile long relayListenerId = -1L;
  private volatile java.util.List<String> relayOriginChain = List.of();
  private volatile RelayCacheStores relayStores;
  private volatile DcwQualityPolicy dcwPolicy = DcwQualityPolicy.DEFAULT;
  private volatile java.util.function.Supplier<DcwQualityPolicy> dcwPolicySupplier;
  private volatile DcwQualityPolicy lastSuppliedDcwPolicy;

  /** Stats dispatch reelles par connecteur (ok/echec/timeout/latence). */
  private final DispatchStats dispatchStats = new DispatchStats();
  private volatile AdaptiveRoutingEngine adaptiveEngine = new AdaptiveRoutingEngine();
  private volatile com.bowman.cspng.filters.DcwObservationTracker observationTracker;
  private volatile com.bowman.cspng.filters.InterProviderLinkObserver linkObserver;

  public DispatchStats dispatchStats() {
    return dispatchStats;
  }

  public AdaptiveRoutingEngine adaptiveRoutingEngine() {
    return adaptiveEngine;
  }

  /** Un état de qualité partagé par les sessions d'un même runtime. */
  public void setAdaptiveRoutingEngine(AdaptiveRoutingEngine engine) {
    this.adaptiveEngine = java.util.Objects.requireNonNull(engine, "engine");
  }

  /** CW-cycle check (V15) — rejet RÉEL des mauvais cycles avant alimentation/service. */
  private volatile com.bowman.cspng.filters.CwCycleChecker cwCycleChecker;
  private volatile com.bowman.cspng.config.CwCycleConfig cwCycleConfig;

  public void setCwCycle(com.bowman.cspng.filters.CwCycleChecker checker,
                          com.bowman.cspng.config.CwCycleConfig config) {
    this.cwCycleChecker = checker;
    this.cwCycleConfig = config;
  }

  public void setObservationTracker(com.bowman.cspng.filters.DcwObservationTracker tracker) {
    this.observationTracker = tracker;
  }

  public com.bowman.cspng.filters.DcwObservationTracker observationTracker() {
    return observationTracker;
  }

  public void setLinkObserver(com.bowman.cspng.filters.InterProviderLinkObserver observer) {
    this.linkObserver = observer;
  }

  public com.bowman.cspng.filters.InterProviderLinkObserver linkObserver() {
    return linkObserver;
  }

  /** Tentative en cours par cle (attribution timeout exacte, bornee). */
  private final java.util.concurrent.ConcurrentHashMap<
      com.bowman.cspng.domain.EcmKey, ActiveAttempt> activeDispatch =
      new java.util.concurrent.ConcurrentHashMap<>();

  private record ActiveAttempt(String connector, String profile, int caid, int provider, int sid, long startNs) {}

  private void trackAttempt(com.bowman.cspng.domain.EcmKey key, String connector,
                            String profile, int caid, int provider, int sid) {
    if (key == null || connector == null) return;
    if (activeDispatch.size() > 4096) return;
    activeDispatch.put(key, new ActiveAttempt(connector, profile, caid, provider, sid, System.nanoTime()));
  }

  private void untrackAttempt(com.bowman.cspng.domain.EcmKey key) {
    if (key != null) activeDispatch.remove(key);
  }

  public StrictEcmRouter(CwsConnectorManager connectorManager, StrictPartitionedCache cache,
                         CardCatalogRepository catalogRepository, CardDiscoveryService discoveryService,
                         String cardMode) {
    this.connectorManager = Objects.requireNonNull(connectorManager, "connectorManager");
    this.cache = cache != null ? cache : new StrictPartitionedCache();
    this.catalogRepository = catalogRepository;
    this.discoveryService = discoveryService;
    this.cardMode = cardMode != null ? cardMode : "manual";
  }

  public StrictEcmRouter(CwsConnectorManager connectorManager, StrictPartitionedCache cache,
                         CardCatalogRepository catalogRepository, CardDiscoveryService discoveryService) {
    this(connectorManager, cache, catalogRepository, discoveryService, "manual");
  }

  public StrictEcmRouter(CwsConnectorManager connectorManager, StrictPartitionedCache cache,
                         CardCatalogRepository catalogRepository) {
    this(connectorManager, cache, catalogRepository, null, "manual");
  }

  public StrictEcmRouter(CwsConnectorManager connectorManager) {
    this(connectorManager, new StrictPartitionedCache(), null, null, "manual");
  }

  public StrictPartitionedCache cache() {
    return cache;
  }

  /**
   * Branchement relais ADR 0002 (optionnel, null = comportement P7 inchange).
   * {@code listenerId} <= 0 desactive le gating (detache par defaut conserve).
   */
  public void setRelayPolicy(RelayCachePolicy policy, long listenerId) {
    this.relayPolicy = policy;
    this.relayPolicySupplier = null;
    this.relayListenerId = listenerId;
  }

  /** Politique live (detachement immediat y compris sessions existantes). */
  public void setRelayPolicySupplier(java.util.function.Supplier<RelayCachePolicy> supplier, long listenerId) {
    this.relayPolicySupplier = supplier;
    this.relayListenerId = listenerId;
  }

  private RelayCachePolicy effectivePolicy() {
    java.util.function.Supplier<RelayCachePolicy> s = relayPolicySupplier;
    if (s != null) {
      try {
        RelayCachePolicy p = s.get();
        if (p != null) return p;
      } catch (Exception ignored) {
      }
    }
    return relayPolicy;
  }

  public void setRelayOriginChain(java.util.List<String> chain) {
    this.relayOriginChain = chain == null ? List.of() : List.copyOf(chain);
  }

  public RelayCachePolicy relayPolicy() {
    return relayPolicy;
  }

  /** Stockage distinct par cache nomme (FIX #1). Null = chemin legacy unique. */
  public void setRelayStores(RelayCacheStores stores) {
    this.relayStores = stores;
  }

  public RelayCacheStores relayStores() {
    return relayStores;
  }

  public void setDcwPolicy(DcwQualityPolicy policy) {
    this.dcwPolicy = policy != null ? policy : DcwQualityPolicy.DEFAULT;
    this.dcwPolicySupplier = null;
  }

  public void setDcwPolicySupplier(java.util.function.Supplier<DcwQualityPolicy> supplier) {
    this.dcwPolicySupplier = supplier;
  }

  public DcwQualityPolicy effectiveDcwPolicy() {
    java.util.function.Supplier<DcwQualityPolicy> s = dcwPolicySupplier;
    if (s != null) {
      try {
        DcwQualityPolicy p = s.get();
        if (p != null) { lastSuppliedDcwPolicy = p; return p; }
      } catch (RuntimeException ignored) {
        // fail-closed : dernier bon fourni, jamais un DEFAULT plus permissif
      }
      DcwQualityPolicy last = lastSuppliedDcwPolicy;
      if (last != null) return last;
    }
    return dcwPolicy != null ? dcwPolicy : DcwQualityPolicy.DEFAULT;
  }


  /**
   * Consultation externe (worker : UDP allowliste / CSPv2 distant).
   * Appelee UNE fois par miss, en concurrence avec le dispatch amont ;
   * premier resultat valide gagne ({@code tryComplete}). Null = desactive
   * (comportement central inchange). Le resultat porte le nom du cache
   * distant, jamais confondu avec un cache local.
   */
  public interface ExternalConsult {
    Future<ExternalReply> consult(long listenerId,
                                  EcmRequest request,
                                  ProtocolAuthResolver.AuthResult authContext,
                                  int provider,
                                  java.util.Set<String> eligibleConnectors,
                                  java.util.List<String> consultableCaches);
  }

  /** Reponse distante rattachee a son cache d'origine (provenance explicite). */
  public record ExternalReply(String cacheName, CwReply reply) {}

  private volatile ExternalConsult externalConsult;

  public void setExternalConsult(ExternalConsult externalConsult) {
    this.externalConsult = externalConsult;
  }

  /**
   * Ecoute d'alimentation (worker : export UDP des CW frais).
   * Notifiee pour chaque put effectif dans un store relais.
   */
  public interface CacheFeedListener {
    void onFeed(long listenerId, String cacheName, StrictCacheKey key, CwReply reply);
  }

  private volatile CacheFeedListener cacheFeedListener;

  public void setCacheFeedListener(CacheFeedListener cacheFeedListener) {
    this.cacheFeedListener = cacheFeedListener;
  }

  /**
   * Lookup cacheex par hash (worker C1) : les push reçus (sans corps ECM) sont
   * indexés par (profil, caid, provider, sid, parité, dataHash) — le cache strict
   * exigeant le corps exact ne peut pas les servir. Consulté après les consults
   * stricts, avant in-flight/dispatch. Hit validé par la politique DCW effective
   * (cohérent avec les autres hits) mais SANS check de provenance (le push a
   * déjà été gaté par filtre/hop/checksum à la réception).
   */
  public interface CacheExLookup {
    /** @return CW servable ou null (jamais d'exception — appelant fail-closed). */
    CwReply lookup(String profile, int caid, int provider, int sid, int oddEven, int dataHash);
  }

  private volatile CacheExLookup cacheExLookup;

  public void setCacheExLookup(CacheExLookup lookup) {
    this.cacheExLookup = lookup;
  }

  /**
   * Attribution des hits CacheEx réellement servis (lot 3, nullable).
   * Enregistrée exactement une fois quand un hit lookup est servi au
   * demandeur — jamais sur les probes (lookup sans hit, miss, rejet policy).
   */
  private volatile com.bowman.cspng.cache.CacheHitSink hitAttribution;

  public void setHitAttribution(com.bowman.cspng.cache.CacheHitSink attribution) {
    this.hitAttribution = attribution;
  }

  private boolean relayScoped() {
    return relayListenerId > 0 && effectivePolicy() != null && relayStores != null;
  }

  private boolean relayAllowsConsult() {
    RelayCachePolicy p = effectivePolicy();
    if (p == null || relayListenerId <= 0) return true;
    if (p.isLoop(relayOriginChain)) return false;
    return !p.consultableCaches(relayListenerId).isEmpty();
  }

  private boolean relayAllowsFeed() {
    RelayCachePolicy p = effectivePolicy();
    if (p == null || relayListenerId <= 0) return true;
    if (p.isLoop(relayOriginChain)) return false;
    return !p.feedableCaches(relayListenerId).isEmpty();
  }

  /** Expose pour tests : le put est-il autorise par la politique relais ? */
  boolean relayFeedGated() {
    return relayPolicy != null && relayListenerId > 0;
  }

  @Override
  public Future<CwReply> routeEcm(EcmRequest request, ProtocolAuthResolver.AuthResult authContext) {
    return routeEcm(request, authContext, 0);
  }

  @Override
  public Future<CwReply> routeEcm(EcmRequest request, ProtocolAuthResolver.AuthResult authContext, int provider) {
    if (request == null || authContext == null) {
      return Future.succeededFuture(CwReply.empty(request != null ? request.key() : null));
    }

    String profile = request.profileName();
    int serviceId = request.key().serviceId();
    int caid = request.key().caId();

    // 1. Filtrage ACL Profils (profils vide ou profil non autorisé -> fail closed)
    if (authContext.profiles() == null || authContext.profiles().isEmpty()
        || !authContext.profiles().contains(profile)) {
      return Future.succeededFuture(CwReply.empty(request.key()));
    }

    // 2. Filtrage ACL Services
    if (authContext.blockedServices() != null) {
      Set<Integer> blocked = authContext.blockedServices().get(profile);
      if (blocked != null && blocked.contains(serviceId)) {
        return Future.succeededFuture(CwReply.empty(request.key()));
      }
    }
    if (authContext.allowedServices() != null) {
      Set<Integer> allowed = authContext.allowedServices().get(profile);
      if (allowed != null && !allowed.isEmpty() && !allowed.contains(serviceId)) {
        return Future.succeededFuture(CwReply.empty(request.key()));
      }
    }

    // 2b. Lookup cacheex par hash (worker C1) : après l'ACL (profils/services),
    // avant le gate catalogue — une CW poussée est servable SANS carte (c'est le
    // principe du cache exchange). Un hit est servi directement (sans feed => sans
    // re-export, sans boucle) après validation DCW effective.
    CacheExLookup cxLookup = cacheExLookup;
    if (cxLookup != null) {
      CwReply cxHit = null;
      try {
        cxHit = cxLookup.lookup(profile, caid, provider, serviceId,
            request.key().commandTag(), request.key().dataHash());
      } catch (Exception ignored) {
        // fail-closed : on continue vers le flux normal
      }
      if (cxHit != null && !cxHit.isEmpty() && cxHit.cw() != null && cxHit.cw().length == 16) {
        var cxVal = effectiveDcwPolicy().validate(cxHit.cw(), profile, caid, provider,
            serviceId, cxHit.connectorName(), com.bowman.cspng.domain.dcw.LegacyGuard.PERMISSIVE);
        if (cxVal.isValid()) {
          dispatchStats.recordOk("cacheex", 0);
          com.bowman.cspng.cache.CacheHitSink hits = hitAttribution;
          if (hits != null) hits.recordServedHit(cxHit.originAddress(), cxHit.connectorName(),
              caid, provider, serviceId);
          return Future.succeededFuture(cxHit);
        }
      }
      // Résolution de mapping en un seul saut (mono-saut, jamais A->B->C).
      // Uniquement si le provider est connu (pas UNKNOWN_PROVIDER).
      if (provider != com.bowman.cspng.domain.dcw.DcwRuleScope.UNKNOWN_PROVIDER) {
        DcwQualityPolicy cxPolicy = effectiveDcwPolicy();
        long nowMs = System.currentTimeMillis();
        for (var mapping : cxPolicy.mappings()) {
          if (mapping == null || !mapping.isActive(nowMs)) continue;
          var targetOpt = mapping.resolveTarget(profile, caid, provider, serviceId, nowMs);
          if (targetOpt.isEmpty()) continue;
          var target = targetOpt.get();
          // Vérification ACL sur le SID cible : un mapping ne contourne JAMAIS un service bloqué
          if (!isServiceAllowedByAcl(profile, target.sid(), authContext)) continue;
          CwReply mappedHit = null;
          try {
            mappedHit = cxLookup.lookup(profile, target.caid(), target.provider(), target.sid(),
                request.key().commandTag(), request.key().dataHash());
          } catch (Exception ignored) {
            // fail-closed : on continue vers le mapping suivant
          }
          if (mappedHit != null && !mappedHit.isEmpty() && mappedHit.cw() != null && mappedHit.cw().length == 16) {
            var mappedVal = effectiveDcwPolicy().validate(mappedHit.cw(), profile, caid, provider,
                serviceId, mappedHit.connectorName(), com.bowman.cspng.domain.dcw.LegacyGuard.PERMISSIVE);
            if (mappedVal.isValid()) {
              dispatchStats.recordOk("cacheex", 0);
              com.bowman.cspng.cache.CacheHitSink hits = hitAttribution;
              if (hits != null) hits.recordServedHit(mappedHit.originAddress(), mappedHit.connectorName(),
                  target.caid(), target.provider(), target.sid());
              CwReply rewrapped = new CwReply(request.key(), request.key().commandTag(), mappedHit.cw(),
                  mappedHit.connectorName(), mappedHit.originAddress(), mappedHit.timestamp());
              return Future.succeededFuture(rewrapped);
            }
          }
        }
      }
    }

    // 3. Vérification de la capacité dans le catalogue selon le mode listener (contrat strict P6/P7)
    if (discoveryService != null) {
      CardDiscoveryService.CapabilityRuntimeState cap = discoveryService.getCapability(profile, caid, provider);
      if (cap == null) {
        return Future.succeededFuture(CwReply.empty(request.key())); // Capacité inexistante -> fail closed
      }
      if (cap.state == CardCapability.State.BLOCKED) {
        return Future.succeededFuture(CwReply.empty(request.key())); // Capacité bloquée -> fail closed
      }
      boolean isWildcard = "wildcard".equalsIgnoreCase(cardMode);
      if (!isWildcard && cap.state != CardCapability.State.PUBLISHED) {
        return Future.succeededFuture(CwReply.empty(request.key())); // Mode manual non publié -> fail closed
      }
    }

    // 4. Sélection des candidats connecteurs (sources actives exactes)
    List<AbstractConnector> rawCandidates = selectCandidates(profile, caid, provider, authContext.allowedConnectors());
    var capability = discoveryService != null ? discoveryService.getCapability(profile, caid, provider) : null;
    final List<AbstractConnector> candidates = adaptiveEngine.rankCandidates(profile, caid, provider, serviceId,
        rawCandidates, effectiveDcwPolicy(), c -> {
          if (capability == null) return 0;
          int hop = Integer.MAX_VALUE;
          for (var src : capability.sources.values()) {
            if (c.getName().equals(src.connectorName())) hop = Math.min(hop, src.hop());
          }
          return hop;
        });

    RelayCachePolicy eff = effectivePolicy();
    boolean hasRelayConsultable = relayScoped() && eff != null && !eff.isLoop(relayOriginChain)
        && !eff.consultableCaches(relayListenerId).isEmpty();

    Set<String> eligibleSet = new HashSet<>();
    for (AbstractConnector c : candidates) {
      eligibleSet.add(c.getName());
    }
    if (hasRelayConsultable) {
      for (String cn : eff.consultableCaches(relayListenerId)) {
        var cfg = eff.cache(cn);
        if (cfg != null) {
          if ("node".equals(cfg.kind()) && cfg.nodeName() != null) {
            eligibleSet.add("node:" + cfg.nodeName());
          } else if ("udp".equals(cfg.kind())) {
            eligibleSet.add("udp:" + cn);
          }
        }
      }
    }
    eligibleSet = Collections.unmodifiableSet(eligibleSet);

    // 5. Cache partitionné strict (avec provider explicite)
    StrictCacheKey cacheKey = new StrictCacheKey(
        profile,
        caid,
        provider,
        serviceId,
        request.key().commandTag(),
        request.body(),
        authContext.epoch(),
        eligibleSet
    );

    // ADR 0002 FIX #1 : stockage distinct par cache nomme + scope ACL/provenance.
    // Sans politique/stores ou listener non scope => comportement P7 inchange (cache unique).
    if (eff != null && relayListenerId > 0 && eff.isLoop(relayOriginChain)) {
      return Future.succeededFuture(CwReply.empty(request.key()));
    }
    if (relayScoped()) {
      // Consultation ordonnee deterministe des caches consultables ; scope ACL via
      // eligibleSet dans la cle, provenance verifiee par entree, fraicheur via TTL
      // posee a l'ecriture (maxAge du cache). Detachement immediat : liste live.
      for (String cn : eff.consultableCaches(relayListenerId)) {
        RelayCacheStores st = relayStores;
        if (st == null) break;
        // Garantit l'existence bornee du store (config live).
        var cfg = eff.cache(cn);
        if (cfg != null) st.ensure(cn, cfg);
        CwReply cached = st.peek(cn, cacheKey);
        if (cached != null && RelayCachePolicy.checkProvenance(cached.connectorName(), eligibleSet)) {
          var val = effectiveDcwPolicy().validate(cached.cw(), profile, caid, provider, serviceId, cached.connectorName(), com.bowman.cspng.domain.dcw.LegacyGuard.PERMISSIVE);
          if (val.isValid()) {
            return Future.succeededFuture(cached);
          }
        }
        // Entree presente mais provenance forgee ou CW invalidee => on continue (pas de fallback permissif
        // vers un autre connecteur : les autres caches restent interrogeables).
      }
    } else if (relayAllowsConsult()) {
      CwReply cached = cache.peek(cacheKey);
      if (cached != null) {
        // Vérification de provenance : le connecteur d'origine doit appartenir à eligibleSet
        if (RelayCachePolicy.checkProvenance(cached.connectorName(), eligibleSet)) {
          var val = effectiveDcwPolicy().validate(cached.cw(), profile, caid, provider, serviceId, cached.connectorName(), com.bowman.cspng.domain.dcw.LegacyGuard.PERMISSIVE);
          if (val.isValid()) {
            return Future.succeededFuture(cached);
          }
        }
      }
    }

    // Sonnet FIX #1 & #2 : Anti-boucle & fail-closed immediat.
    // Un ECM entrant depuis un noeud distant (cspv2:...) n'est JAMAIS re-exporte/re-consulte
    // vers d'autres noeuds distants (max hop = 1 sur le cluster CSPv2).
    // Si aucun connecteur local ET aucun chemin de consultation externe reel (externalConsult null
    // ou origine distante), retour immediat empty (0ms, aucun leak in-flight / timer 5s).
    boolean isRemoteOrigin = (request.remoteAddress() != null && request.remoteAddress().startsWith("cspv2:"))
        || (request.originId() != null && request.originId().startsWith("cspv2:"));
    ExternalConsult ext = isRemoteOrigin ? null : externalConsult;
    boolean canResolveExternally = ext != null && hasRelayConsultable;
    if (candidates.isEmpty() && !canResolveExternally) {
      return Future.succeededFuture(CwReply.empty(request.key()));
    }

    // 6. Coalescing in-flight & dispatch
    StrictPartitionedCache.InFlightResult inFlight = cache.joinOrCreateInFlight(cacheKey);
    if (!inFlight.isLeader()) {
      return inFlight.future();
    }

    Promise<CwReply> leaderPromise = inFlight.leaderPromise();

    // Echange distant (worker UDP/CSPv2) : concurrence avec le dispatch amont,
    // premier resultat valide gagne (tryComplete). Provenance : le transport
    // garantit pending-key + lien + origine non vide (pas d'eligibleSet ici :
    // les CW distants portent l'identite d'origine, pas un connecteur local).
    RelayCachePolicy ep = effectivePolicy();
    if (canResolveExternally && ep != null && !ep.isLoop(relayOriginChain)) {
      java.util.List<String> consultable = ep.consultableCaches(relayListenerId);
      if (!consultable.isEmpty()) {
        try {
          ext.consult(relayListenerId, request, authContext, provider, eligibleSet, consultable).onComplete(ar -> {
            try {
              if (ar.succeeded() && ar.result() != null && ar.result().reply() != null
                  && !ar.result().reply().isEmpty()) {
                CwReply extRep = ar.result().reply();
                var val = effectiveDcwPolicy().validate(extRep.cw(), request.profileName(), request.key().caId(), provider, request.key().serviceId(), extRep.connectorName(), com.bowman.cspng.domain.dcw.LegacyGuard.PERMISSIVE);
                if (!val.isValid()) {
                  if (candidates.isEmpty()) {
                    CwReply empty = CwReply.empty(request.key());
                    cache.completeInFlight(cacheKey, empty);
                    leaderPromise.tryComplete(empty);
                  }
                  return;
                }
                String cn = ar.result().cacheName();
                RelayCachePolicy p3 = effectivePolicy();
                RelayCacheStores st3 = relayStores;
                // Re-validation live (revocation/detachement pendant l'echange) :
                // put ET completion refuses si le lien a disparu entre-temps.
                if (p3 == null || st3 == null
                    || !p3.consultableCaches(relayListenerId).contains(cn)) {
                  if (candidates.isEmpty()) {
                    CwReply empty = CwReply.empty(request.key());
                    cache.completeInFlight(cacheKey, empty);
                    leaderPromise.tryComplete(empty);
                  }
                  return;
                }
                if (p3.feedableCaches(relayListenerId).contains(cn)) {
                  var cfg = p3.cache(cn);
                  if (cfg != null) st3.ensure(cn, cfg);
                  st3.put(cn, cacheKey, ar.result().reply(),
                      cfg != null ? cfg.maxAgeMs() : StrictPartitionedCache.DEFAULT_TTL_MS);
                }
                cache.completeInFlight(cacheKey, ar.result().reply());
                leaderPromise.tryComplete(ar.result().reply());
              } else if (candidates.isEmpty()) {
                CwReply empty = CwReply.empty(request.key());
                cache.completeInFlight(cacheKey, empty);
                leaderPromise.tryComplete(empty);
              }
            } catch (Exception ignored) {
              if (candidates.isEmpty()) {
                CwReply empty = CwReply.empty(request.key());
                cache.completeInFlight(cacheKey, empty);
                leaderPromise.tryComplete(empty);
              }
            }
          });
        } catch (Exception e) {
          if (candidates.isEmpty()) {
            CwReply empty = CwReply.empty(request.key());
            cache.completeInFlight(cacheKey, empty);
            leaderPromise.tryComplete(empty);
          }
        }
      } else if (candidates.isEmpty()) {
        CwReply empty = CwReply.empty(request.key());
        cache.completeInFlight(cacheKey, empty);
        leaderPromise.tryComplete(empty);
      }
    }

    long maxWait = request.maxWaitMs() > 0 ? request.maxWaitMs() : 5000L;
    ScheduledFuture<?> timeoutTask = TIMEOUT_SCHEDULER.schedule(() -> {
      // Timeout attribue a la tentative active exacte (pas de fictif).
      ActiveAttempt active = activeDispatch.remove(request.key());
      if (active != null) {
        dispatchStats.recordTimeout(active.connector());
        adaptiveEngine.recordTimeout(active.profile(), active.caid(), active.provider(), active.sid(), active.connector());
      }
      CwReply empty = CwReply.empty(request.key());
      cache.completeInFlight(cacheKey, empty);
      leaderPromise.tryComplete(empty);
    }, maxWait, TimeUnit.MILLISECONDS);

    leaderPromise.future().onComplete(v -> timeoutTask.cancel(false));

    if (!candidates.isEmpty()) {
      dispatchToCandidates(request, authContext, candidates, 0, cacheKey, leaderPromise);
    }
    return leaderPromise.future();
  }

  private List<AbstractConnector> selectCandidates(String profile, int caid, int provider, Set<String> allowedConnectors) {
    if (discoveryService != null) {
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
        if (allowedConnectors != null && !allowedConnectors.isEmpty() && !allowedConnectors.contains(src.connectorName())) {
          continue;
        }
        // Reshare >= 0 (y compris reshare=0 client-only) est éligible pour décoder l'ECM du client
        validSources.add(src);
      }
      if (validSources.isEmpty()) return List.of();

      // Tri déterministe : hop ascendant, puis priorité de connecteur descendante, puis nom
      return validSources.stream()
          .map(src -> connectorManager.getConnector(src.connectorName()))
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

    List<AbstractConnector> connected = connectorManager.getConnectedConnectorsForProfile(profile);
    if (connected == null || connected.isEmpty()) {
      return List.of();
    }

    return connected.stream()
        .filter(AbstractConnector::isConnected)
        .filter(c -> allowedConnectors == null || allowedConnectors.isEmpty() || allowedConnectors.contains(c.getName()))
        .sorted(Comparator.comparingInt(AbstractConnector::getPriority).reversed()
            .thenComparing(AbstractConnector::getName))
        .toList();
  }

  /**
   * Même host:port (et même proxy) = même source, quel que soit le nom.
   * Origine absente du manager, udp: ou node: = non indépendante (provenance amont inconnue).
   */
  private boolean independentOrigin(String candidateName, String referenceName) {
    if (candidateName == null || referenceName == null) return false;
    if (referenceName.startsWith("udp:") || referenceName.startsWith("node:") || referenceName.startsWith("repaired:")) {
      return false;
    }
    AbstractConnector a = connectorManager.getConnector(candidateName);
    AbstractConnector b = connectorManager.getConnector(referenceName);
    if (a == null || b == null) return false;
    return !a.independenceKey().equals(b.independenceKey());
  }

  /**
   * Vérifie si le service est autorisé par l'ACL (blockedServices / allowedServices) pour le profil donné.
   * null authContext => refusé.
   */
  private boolean isServiceAllowedByAcl(String profile, int sid, ProtocolAuthResolver.AuthResult authContext) {
    if (authContext == null) return false;
    if (authContext.blockedServices() != null) {
      Set<Integer> blocked = authContext.blockedServices().get(profile);
      if (blocked != null && blocked.contains(sid)) return false;
    }
    if (authContext.allowedServices() != null) {
      Set<Integer> allowed = authContext.allowedServices().get(profile);
      if (allowed != null && !allowed.isEmpty() && !allowed.contains(sid)) return false;
    }
    return true;
  }

  /**
   * Référence fraîche : même clé stricte, sinon SID lié par mapping approuvé même provider.
   * Le peek lié ne recopie jamais l'entrée sur le SID demandeur.
   * authContext est utilisé pour vérifier que le SID cible est autorisé par l'ACL.
   */
  private CwReply peekCorroboration(StrictCacheKey cacheKey, DcwQualityPolicy policy, int requestSid,
                                    ProtocolAuthResolver.AuthResult authContext) {
    CwReply direct = peekFreshLocal(cacheKey);
    if (direct != null) return direct;
    if (policy == null || cacheKey.provider() == com.bowman.cspng.domain.dcw.DcwRuleScope.UNKNOWN_PROVIDER) return null;
    long now = System.currentTimeMillis();
    for (var mapping : policy.mappings()) {
      if (mapping == null || !mapping.isActive(now)) continue;
      var targetOpt = mapping.resolveTarget(cacheKey.profile(), cacheKey.caid(), cacheKey.provider(), requestSid, now);
      if (targetOpt.isEmpty()) continue;
      var target = targetOpt.get();
      // Uniquement le même caid (corroboration intra-provider, pas de saut inter-provider dans peekCorroboration)
      if (target.caid() != cacheKey.caid()) continue;
      if (!isServiceAllowedByAcl(cacheKey.profile(), target.sid(), authContext)) continue;
      StrictCacheKey linked = new StrictCacheKey(cacheKey.profile(), target.caid(), target.provider(), target.sid(),
          cacheKey.commandTag(), cacheKey.exactBody(), cacheKey.policyEpoch(), cacheKey.eligibleConnectors());
      CwReply hit = peekFreshLocal(linked);
      if (hit != null && !hit.isEmpty()) return hit;
    }
    return null;
  }

  /** Cache partitionné local, puis stores nommés kind=local. node/udp exclus (origine amont inconnue). */
  private CwReply peekFreshLocal(StrictCacheKey key) {
    CwReply direct = cache.peek(key);
    if (direct != null) return direct;
    RelayCacheStores st = relayStores;
    RelayCachePolicy pol = effectivePolicy();
    if (st == null || pol == null) return null;
    for (String name : pol.cacheNames()) {
      var cfg = pol.cache(name);
      if (cfg == null || !"local".equals(cfg.kind())) continue;
      CwReply hit = st.peek(name, key);
      if (hit != null && !hit.isEmpty()) return hit;
    }
    return null;
  }

  private void dispatchToCandidates(EcmRequest request, ProtocolAuthResolver.AuthResult authContext,
                                    List<AbstractConnector> candidates, int index,
                                    StrictCacheKey cacheKey, Promise<CwReply> resultPromise) {
    if (index >= candidates.size()) {
      // Candidats locaux épuisés sans CW valide
      if (externalConsult == null || !relayScoped()) {
        CwReply empty = CwReply.empty(request.key());
        cache.completeInFlight(cacheKey, empty);
        resultPromise.tryComplete(empty);
      }
      return;
    }

    AbstractConnector connector = candidates.get(index);
    String expectedName = connector.getName();
    long attemptStartNs = System.nanoTime();
    trackAttempt(request.key(), expectedName, request.profileName(), request.key().caId(), cacheKey.provider(), request.key().serviceId());
    try {
      connector.sendEcm(request).onComplete(ar -> {
        long latencyMs = (System.nanoTime() - attemptStartNs) / 1_000_000L;
        if (ar.succeeded() && ar.result() != null && !ar.result().isEmpty()) {
          CwReply raw = ar.result();
          // Gemini FIX : identite reelle — la reponse doit porter le nom du
          // connecteur interroge. Nom absent => attribue depuis le connecteur
          // fiable (transport authentifie) ; autre nom eligible => rejet
          // (pas d'usurpation inter-connecteurs), failover candidat suivant.
          String actual = raw.connectorName();
          CwReply reply;
          if (actual == null || actual.isBlank()) {
            reply = new CwReply(raw.key(), raw.commandTag(), raw.cw(),
                expectedName, raw.originAddress(), raw.timestamp());
          } else if (!actual.equals(expectedName)) {
            untrackAttempt(request.key());
            dispatchStats.recordFail(expectedName, latencyMs);
            adaptiveEngine.recordFail(request.profileName(), request.key().caId(), cacheKey.provider(), request.key().serviceId(), expectedName, latencyMs);
            dispatchToCandidates(request, authContext, candidates, index + 1, cacheKey, resultPromise);
            return;
          } else {
            reply = raw;
          }

          final DcwQualityPolicy dcwPol = effectiveDcwPolicy();
          final boolean autoObs = dcwPol.enabled() && dcwPol.autoObserveEnabled();
          var val = dcwPol.validate(reply.cw(), request.profileName(), request.key().caId(), cacheKey.provider(), request.key().serviceId(), expectedName, com.bowman.cspng.domain.dcw.LegacyGuard.PERMISSIVE);
          if (val.isObserved() && observationTracker != null) {
            observationTracker.recordAnomaly(request.profileName(), request.key().caId(), cacheKey.provider(), request.key().serviceId(), expectedName, com.bowman.cspng.filters.DcwObservationTracker.AnomalyType.OBSERVED_RULE_MATCH, reply.cw(), val.message());
          }
          if (!val.isValid()) {
            // Corroboration : référence fraîche (TTL cache) d'un AUTRE connecteur éligible,
            // même identité stricte (clé cache = body/parité/provider). ENFORCE sert la
            // référence sous SON identité. Jamais la CW recalculée, jamais apprise comme
            // preuve (le put ci-dessous n'est atteint que pour une réponse déjà valide).
            if (val.reason() == DcwValidationResult.Reason.CHECKSUM_FAIL
                && effectiveDcwPolicy().repairMode() != com.bowman.cspng.domain.dcw.DcwRepairMode.OFF) {
              CwReply refCached = peekCorroboration(cacheKey, dcwPol, request.key().serviceId(), authContext);
              if (refCached != null && !refCached.isEmpty()
                  && refCached.connectorName() != null
                  && !refCached.connectorName().startsWith("repaired:")
                  && independentOrigin(expectedName, refCached.connectorName())
                  && RelayCachePolicy.checkProvenance(refCached.connectorName(), cacheKey.eligibleConnectors())) {
                var repRes = effectiveDcwPolicy().tryRepair(
                    reply.cw(), refCached.cw(), expectedName, refCached.connectorName(),
                    request.profileName(), request.key().caId(), cacheKey.provider(), request.key().serviceId());
                if (repRes.status() == com.bowman.cspng.domain.dcw.DcwRepairResult.Status.OBSERVED
                    || repRes.status() == com.bowman.cspng.domain.dcw.DcwRepairResult.Status.REPAIRED) {
                  if (observationTracker != null) {
                    observationTracker.recordAnomaly(request.profileName(), request.key().caId(), cacheKey.provider(),
                        request.key().serviceId(), expectedName,
                        com.bowman.cspng.filters.DcwObservationTracker.AnomalyType.REPAIR_OPPORTUNITY, reply.cw(),
                        "checksum corroboration " + repRes.status());
                  }
                  if (repRes.status() == com.bowman.cspng.domain.dcw.DcwRepairResult.Status.REPAIRED) {
                    untrackAttempt(request.key());
                    dispatchStats.recordFail(expectedName, latencyMs);
                    adaptiveEngine.recordInvalid(request.profileName(), request.key().caId(), cacheKey.provider(),
                        request.key().serviceId(), expectedName, latencyMs);
                    CwReply served = new CwReply(request.key(), request.key().commandTag(),
                        refCached.cw(), refCached.connectorName(), refCached.originAddress(), refCached.timestamp());
                    cache.completeInFlight(cacheKey, served);
                    resultPromise.tryComplete(served);
                    return;
                  }
                }
              }
            }
            if (autoObs && observationTracker != null) {
              var atype = val.reason() == DcwValidationResult.Reason.CHECKSUM_FAIL
                  ? com.bowman.cspng.filters.DcwObservationTracker.AnomalyType.CHECKSUM_FAIL
                  : com.bowman.cspng.filters.DcwObservationTracker.AnomalyType.STRUCTURAL_FAIL;
              observationTracker.recordAnomaly(request.profileName(), request.key().caId(), cacheKey.provider(), request.key().serviceId(), expectedName, atype, reply.cw(), val.message());
            }
            untrackAttempt(request.key());
            dispatchStats.recordFail(expectedName, latencyMs);
            adaptiveEngine.recordInvalid(request.profileName(), request.key().caId(), cacheKey.provider(), request.key().serviceId(), expectedName, latencyMs);
            dispatchToCandidates(request, authContext, candidates, index + 1, cacheKey, resultPromise);
            return;
          }

          if (autoObs && linkObserver != null) {
            linkObserver.observeReply(request.profileName(), request.key().caId(), cacheKey.provider(), request.key().serviceId(), request.key().commandTag(), request.key().dataHash(), reply.cw(), reply.timestamp());
          }
          if (autoObs && observationTracker != null) {
            observationTracker.trackCwCycle(request.profileName(), request.key().caId(), cacheKey.provider(), request.key().serviceId(), request.key().commandTag(), request.key().dataHash(), reply.cw());
          }

          // CW-cycle check (V15) : gate enabled/caids, rejet effectif (drop) ou log
          // selon onBad — failover vers le candidat suivant, JAMAIS servi à un client.
          if (cwCycleChecker != null && cwCycleConfig != null && cwCycleConfig.enabled()
              && cwCycleConfig.appliesToCaid(request.key().caId())) {
            com.bowman.cspng.filters.CwCycleChecker.Verdict cycleVerdict =
                cwCycleChecker.check(request.profileName(), request.key().caId(),
                    cacheKey.provider(), request.key().serviceId(),
                    request.key().commandTag(), reply.cw(), 0, false, reply.timestamp());
            if (cwCycleChecker.shouldReject(cycleVerdict)) {
              if (autoObs && observationTracker != null) {
                observationTracker.recordAnomaly(request.profileName(), request.key().caId(),
                    cacheKey.provider(), request.key().serviceId(), expectedName,
                    com.bowman.cspng.filters.DcwObservationTracker.AnomalyType.FROZEN_CW_SUSPICION,
                    reply.cw(), "cw-cycle " + cycleVerdict);
              }
              untrackAttempt(request.key());
              dispatchStats.recordFail(expectedName, latencyMs);
              adaptiveEngine.recordInvalid(request.profileName(), request.key().caId(), cacheKey.provider(),
                  request.key().serviceId(), expectedName, latencyMs);
              dispatchToCandidates(request, authContext, candidates, index + 1, cacheKey, resultPromise);
              return;
            }
          }

          // ADR 0002 FIX #1 : alimentation de chaque cache feedable (contenus distincts).
          // Re-verification live avant put : un in-flight ne repeuple jamais un cache
          // detache entre-temps. TTL = maxAge du cache (fraicheur).
          // ANTI-MASQUAGE : le dispatch local n'alimente que les caches locaux.
          // Les caches node/udp sont alimentes exclusivement par leurs echanges
          // reels (replies CSPv2 du node / replies UDP des pairs), jamais par
          // recopie du dispatch local.
          if (relayScoped()) {
            RelayCachePolicy p2 = effectivePolicy();
            RelayCacheStores st2 = relayStores;
            if (p2 != null && st2 != null && !p2.isLoop(relayOriginChain)) {
              for (String cn : p2.feedableCaches(relayListenerId)) {
                var cfg = p2.cache(cn);
                if (cfg != null && !"local".equals(cfg.kind())) continue;
                if (cfg != null) st2.ensure(cn, cfg);
                long ttl = cfg != null ? cfg.maxAgeMs() : StrictPartitionedCache.DEFAULT_TTL_MS;
                st2.put(cn, cacheKey, reply, ttl);
                CacheFeedListener fl = cacheFeedListener;
                if (fl != null) {
                  try {
                    fl.onFeed(relayListenerId, cn, cacheKey, reply);
                  } catch (Exception ignored) {
                  }
                }
              }
            }
          } else if (relayAllowsFeed()) {
            cache.put(cacheKey, reply, StrictPartitionedCache.DEFAULT_TTL_MS);
          }
          untrackAttempt(request.key());
          dispatchStats.recordOk(expectedName, latencyMs);
          adaptiveEngine.recordSuccess(request.profileName(), request.key().caId(), cacheKey.provider(), request.key().serviceId(), expectedName, latencyMs);
          cache.completeInFlight(cacheKey, reply);
          resultPromise.tryComplete(reply);
        } else {
          // Échec ou pas de CW -> failover vers le candidat suivant
          untrackAttempt(request.key());
          if (ar.failed() && ar.cause() instanceof com.bowman.cspng.connectors.ConnectorBusyException) {
            adaptiveEngine.recordBusy(request.profileName(), request.key().caId(), cacheKey.provider(),
                request.key().serviceId(), expectedName);
          } else {
            dispatchStats.recordFail(expectedName, (System.nanoTime() - attemptStartNs) / 1_000_000L);
            adaptiveEngine.recordFail(request.profileName(), request.key().caId(), cacheKey.provider(),
                request.key().serviceId(), expectedName, (System.nanoTime() - attemptStartNs) / 1_000_000L);
          }
          dispatchToCandidates(request, authContext, candidates, index + 1, cacheKey, resultPromise);
        }
      });
    } catch (Exception e) {
      untrackAttempt(request.key());
      dispatchStats.recordFail(expectedName,
          (System.nanoTime() - attemptStartNs) / 1_000_000L);
      adaptiveEngine.recordFail(request.profileName(), request.key().caId(), cacheKey.provider(), request.key().serviceId(), expectedName, (System.nanoTime() - attemptStartNs) / 1_000_000L);
      dispatchToCandidates(request, authContext, candidates, index + 1, cacheKey, resultPromise);
    }
  }
}
