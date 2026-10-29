package com.bowman.cspng.cache;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Verticle single-writer gérant le cache local L1 et les rendez-vous de cache.
 *
 * <p>Cette classe est le cœur du lot L2. Elle reproduit intégralement la sémantique de
 * {@code DefaultCache.processRequest/processReply/addReply} dans un modèle réactif :
 * <ul>
 *   <li>{@link LocalCwStore} remplace {@code DefaultCache.ecmMap} (cache Caffeine).</li>
 *   <li>{@link PendingRegistry} remplace {@code DefaultCache.pendingEcms} et le mécanisme
 *       {@code wait/notifyAll} par des {@link Promise} Vert.x résolus par timers.</li>
 *   <li>La logique "contested" est portée via {@link DcwValidator} et {@link ContestedRegistry}.</li>
 *   <li>Les hooks {@link CacheListener} / {@link CacheMonitor} permettront à L7 de brancher
 *       {@code CacheCoveragePlugin}.</li>
 *   <li>Le {@link CacheTransport} SPI est notifié des requêtes/réponses à publier sur le
 *       réseau (implémentation L3).</li>
 * </ul>
 *
 * <p>Toute la logique d'état (cache, pending, compteurs) est manipulée depuis le contexte
 * Vert.x unique de ce verticle : il n'y a aucun verrou explicite, conformément à l'architecture
 * single-writer du plan §3.2/§3.4.
 */
public class CacheVerticle extends AbstractVerticle {

  private static final Logger LOG = Logger.getLogger(CacheVerticle.class.getName());
  private static final Logger ECM_LOG = Logger.getLogger("com.bowman.cspng.ecm");

  private volatile com.bowman.cspng.domain.srvid.ServiceResolver serviceResolver = com.bowman.cspng.domain.srvid.ServiceResolver.EMPTY;
  private volatile java.util.function.Supplier<com.bowman.cspng.domain.dcw.DcwQualityPolicy> dcwPolicySupplier;

  /**
   * Durée maximale de fraîcheur d'une CW indexée par service (sans correspondance de dataHash ECM).
   * Bornée sous la durée d'un cycle CW DVB standard (~10 s) pour ne jamais servir la CW du cycle N-2
   * de même parité (t + 20 s) avant l'arrivée du push CacheEx du cycle courant.
   */
  public static final long SERVICE_CW_MAX_AGE_MS = 8_500L;
  private static final int MAX_SERVICE_CW_ENTRIES = 4_096;

  public record ServiceCwKey(int caid, int provider, int sid, int commandTag) {}
  private record ServiceCachedEntry(CachedReply cached, long firstSeenAtMs) {}

  private final java.util.Map<ServiceCwKey, ServiceCachedEntry> serviceStore =
      java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<ServiceCwKey, ServiceCachedEntry> eldest) {
          return size() > MAX_SERVICE_CW_ENTRIES;
        }
      });

  public void setDcwPolicySupplier(java.util.function.Supplier<com.bowman.cspng.domain.dcw.DcwQualityPolicy> supplier) {
    this.dcwPolicySupplier = supplier;
  }

  public java.util.function.Supplier<com.bowman.cspng.domain.dcw.DcwQualityPolicy> getDcwPolicySupplier() {
    return dcwPolicySupplier;
  }

  public void setServiceResolver(com.bowman.cspng.domain.srvid.ServiceResolver resolver) {
    this.serviceResolver = resolver != null ? resolver : com.bowman.cspng.domain.srvid.ServiceResolver.EMPTY;
  }

  public com.bowman.cspng.domain.srvid.ServiceResolver getServiceResolver() {
    return serviceResolver;
  }

  private static int effectiveProvider(EcmRequest request) {
    if (request == null) return 0;
    if (request.provider() != 0) return request.provider();
    if (request.body() != null && request.body().length > 0) {
      return com.bowman.cspng.codec.EcmProviderExtractor.extractProvider(request.key().caId(), request.body());
    }
    return 0;
  }

  private void logCacheHit(String type, EcmRequest request, CwReply reply, long elapsedMs) {
    if (!ECM_LOG.isLoggable(Level.INFO)) return;
    int caid = request.key().caId();
    int sid = request.key().serviceId();
    int provid = effectiveProvider(request);
    String ch = serviceResolver != null ? serviceResolver.resolveChannelName(caid, sid) : null;
    String prov = serviceResolver != null ? serviceResolver.resolveProviderName(caid, provid) : null;
    String origin = reply != null ? (reply.originAddress() != null ? reply.originAddress() : reply.connectorName()) : null;
    if (origin == null) origin = request.originId() != null ? request.originId() : "local";

    StringBuilder sb = new StringBuilder();
    sb.append(String.format("CACHE HIT type=%s profile=%s caid=0x%04X provid=0x%06X sid=0x%04X",
        type, request.profileName(), caid & 0xFFFF, provid & 0xFFFFFF, sid & 0xFFFF));
    if (ch != null && !ch.isBlank()) sb.append(" channel=\"").append(ch).append("\"");
    if (prov != null && !prov.isBlank()) sb.append(" provider=\"").append(prov).append("\"");
    sb.append(" origin=").append(origin);
    sb.append(" time=").append(elapsedMs).append("ms");
    ECM_LOG.info(sb.toString());
  }

  private volatile CacheVerticleConfig config = CacheVerticleConfig.defaults();
  private LocalCwStore localStore = new LocalCwStore(config);
  private PendingRegistry pending;
  private ContestedRegistry contestedRegistry = new ContestedRegistry();

  private final List<CacheListener> listeners = new ArrayList<>();
  private final List<CacheMonitor> monitors = new ArrayList<>();
  private final List<CacheTransport> transports = new CopyOnWriteArrayList<>();

  private final AtomicLong timeouts = new AtomicLong();
  private final AtomicLong instantHits = new AtomicLong();
  private final AtomicLong waitHits = new AtomicLong();
  private final AtomicLong remoteHits = new AtomicLong();
  private final AtomicLong contestedCount = new AtomicLong();
  private final AtomicInteger pendingPeak = new AtomicInteger();
  private final Set<Integer> caids = ConcurrentHashMap.newKeySet();
  private volatile boolean started = false;

  /**
   * Attribution des hits réellement servis (lot 3, nullable = désactivée).
   * Enregistrée exactement une fois par réponse servie à un vrai demandeur
   * (hit instantané servi, waiter réveillé non vide) — jamais sur les probes
   * internes ({@code peekLocalCache}).
   */
  private volatile CacheHitSink hitAttribution;

  public void setHitAttribution(CacheHitSink attribution) {
    this.hitAttribution = attribution;
  }

  private void recordServedHit(CwReply reply) {
    CacheHitSink a = hitAttribution;
    if (a == null || reply == null || reply.isEmpty()) return;
    a.recordServedHit(reply.originAddress(), reply.connectorName(),
        reply.key().caId(), 0, reply.key().serviceId());
  }

  /**
   * Enregistrement enrichi du hit servi avec extraction du provider depuis le corps ECM.
   * Utilisé quand la requête originale est disponible (hit instantané, waiter).
   */
  private void recordServedHit(CwReply reply, EcmRequest request) {
    CacheHitSink a = hitAttribution;
    if (a == null || reply == null || reply.isEmpty()) return;
    int caid = request.key().caId();
    int srvid = request.key().serviceId();
    int provid = effectiveProvider(request);
    a.recordServedHit(reply.originAddress(), reply.connectorName(), caid, provid, srvid);
  }

  @Override
  public void start() {
    this.pending = new PendingRegistry(vertx);
    this.started = true;
  }

  @Override
  public void stop() {
    this.started = false;
  }

  /** Thread-safe started check sans attendre. */
  public boolean isStarted() {
    return started && context != null && pending != null;
  }

  public void configure(CacheVerticleConfig newConfig) {
    if (newConfig == null) return;
    CacheVerticleConfig prev = this.config;
    this.config = newConfig;
    Runnable applyStore = () -> {
      if (this.localStore == null
          || prev.cwMaxAgeMs() != newConfig.cwMaxAgeMs()
          || prev.maximumSize() != newConfig.maximumSize()) {
        this.localStore = new LocalCwStore(newConfig);
      }
      this.pendingPeak.set(0);
    };
    if (context != null) {
      context.runOnContext(v -> applyStore.run());
    } else {
      applyStore.run();
    }
  }

  public CacheVerticleConfig cacheConfig() {
    return config;
  }

  public void addListener(CacheListener listener) {
    if (listener != null) listeners.add(listener);
  }

  public void addMonitor(CacheMonitor monitor) {
    if (monitor != null) monitors.add(monitor);
  }

  public void addTransport(CacheTransport transport) {
    if (transport != null) transports.add(transport);
  }

  public void removeTransport(CacheTransport transport) {
    if (transport != null) transports.remove(transport);
  }

  public int transportCount() {
    return transports.size();
  }

  /**
   * Soumet une tâche sur le contexte du verticle. Public pour permettre aux
   * tests (et aux transports comme {@code UdpLegacyTransport}) de synchroniser
   * avec l'event-loop.
   */
  public void runOnContext(Runnable task) {
    context.runOnContext(v -> task.run());
  }

  /**
   * Rendez-vous de cache réactif — équivalent de {@code DefaultCache.processRequest}.
   *
   * @param successFactor facteur de succès du connecteur ayant émis la requête
   * @param request       requête ECM
   * @param alwaysWait    mode cache-only : pose un verrou immédiatement
   * @param maxCwWait     durée maximale de réponse CW (ms), base du calcul de maxWait
   * @return future résolue par une {@link CacheResult}
   */
  public Future<CacheResult> processRequest(int successFactor, EcmRequest request, boolean alwaysWait, long maxCwWait) {
    Promise<CacheResult> resultPromise = Promise.promise();
    long start = System.currentTimeMillis();
    context.runOnContext(v -> {
      long maxWait = config.getMaxCacheWait(maxCwWait);

      // 1) Hit instantané
      CachedReply cached = localStore.get(request.key());
      if (cached != null) {
        CwReply reply = buildReturnedReply(cached, request);
        instantHits.incrementAndGet();
        if (reply.originAddress() != null) remoteHits.incrementAndGet();
        recordServedHit(reply, request);
        long elapsed = System.currentTimeMillis() - start;
        logCacheHit("INSTANT", request, reply, elapsed);
        resultPromise.complete(new CacheResult(reply, true, false, false, elapsed));
        return;
      }

      // 1b) Hit instantané via DcwServiceMapping (Simulcrypt CAID:Provider ou SID lié)
      CachedReply mappedCached = lookupMappedServiceCw(request, start);
      if (mappedCached != null) {
        CwReply reply = buildMappedReturnedReply(mappedCached, request);
        instantHits.incrementAndGet();
        if (reply.originAddress() != null) remoteHits.incrementAndGet();
        recordServedHit(reply, request);
        long elapsed = System.currentTimeMillis() - start;
        logCacheHit("INSTANT", request, reply, elapsed);
        resultPromise.complete(new CacheResult(reply, true, false, false, elapsed));
        return;
      }

      // 2) Mode alwaysWait / hook lockRequest
      boolean forceLock = false;
      for (CacheListener listener : listeners) {
        if (listener.lockRequest(successFactor, request)) {
          forceLock = true;
          break;
        }
      }
      boolean needLock = alwaysWait || forceLock;
      if (needLock && !pending.contains(request.key())) {
        addRequest(successFactor, request);
      }

      // 3) Boucle d'attente réactive
      PendingRegistry.PendingEntry entry = pending.get(request.key());
      if (entry != null) {
        String origin = entry.originRequest().originId();
        if (origin != null && origin.equals(request.remoteAddress())) {
          // Anti-boucle same-origin : on ne s'attend pas soi-même.
          long elapsed = System.currentTimeMillis() - start;
          resultPromise.complete(new CacheResult(null, false, false, false, elapsed));
          return;
        }

        Runnable onAlert = () -> {
          for (CacheListener listener : listeners) {
            listener.onDelayAlert(successFactor, request, alwaysWait, maxWait);
          }
        };

        PendingRegistry.Waiter[] waiterRef = new PendingRegistry.Waiter[1];
        Handler<CwReply> onTimeout = reply -> {
          timeouts.incrementAndGet();
          pending.removeWaiter(request.key(), waiterRef[0]);
        };

        Function<CwReply, CwReply> replyTransform = reply ->
            buildReturnedReply(new CachedReply(reply, request.profileName()), request);

        Handler<CwReply> onReply = returned -> {
          waitHits.incrementAndGet();
          if (returned.originAddress() != null) remoteHits.incrementAndGet();
          recordServedHit(returned, request);
        };

        PendingRegistry.Waiter waiter = pending.addWaiter(request, maxWait, onAlert, onTimeout, onReply, replyTransform);
        waiterRef[0] = waiter;
        if (waiter == null) {
          // L'entrée a disparu entre temps : miss direct.
          long elapsed = System.currentTimeMillis() - start;
          resultPromise.complete(new CacheResult(null, false, false, false, elapsed));
          return;
        }

        // Chaîner la future du waiter au résultat — c'est ici que la promesse caller est résolue.
        // Les compteurs (timeouts, waitHits) sont déjà mis à jour par les callbacks onTimeout/onReply.
        waiter.future().onComplete(ar -> {
          long elapsed = System.currentTimeMillis() - start;
          if (ar.succeeded() && ar.result() != null && !ar.result().isEmpty()) {
            logCacheHit(ar.result().originAddress() != null ? "REMOTE" : "WAIT", request, ar.result(), elapsed);
            resultPromise.complete(new CacheResult(ar.result(), false, true, false, elapsed));
          } else {
            resultPromise.complete(new CacheResult(null, false, false, true, elapsed));
          }
        });
        return;
      }

      // 4) Miss direct : pas de pending, pas d'attente
      if (needLock) {
        // Toujours pas de pending (rare si addRequest a échoué / race) : le legacy appelle addRequest ici.
        addRequest(successFactor, request);
      }
      long elapsed = System.currentTimeMillis() - start;
      resultPromise.complete(new CacheResult(null, false, false, false, elapsed));
    });
    return resultPromise.future();
  }

  /**
   * Réception d'une réponse CW — équivalent de {@code DefaultCache.processReply}.
   *
   * @param request requête associée
   * @param reply   réponse reçue (peut être vide)
   */
  public void handleReply(EcmRequest request, CwReply reply) {
    context.runOnContext(v -> {
      for (CacheMonitor monitor : monitors) monitor.onReply(request, reply);

      if (reply == null || reply.isEmpty()) {
        List<PendingRegistry.Waiter> waiters = pending.remove(request.key());
        for (PendingRegistry.Waiter w : waiters) w.fireEmpty();
      } else {
        for (CacheListener listener : listeners) listener.onReply(request, reply);
        List<PendingRegistry.Waiter> waiters = pending.remove(request.key());
        if (!waiters.isEmpty()) {
          addReply(request, reply);
          for (PendingRegistry.Waiter w : waiters) w.fireReply(reply);
        } else {
          addReply(request, reply);
        }
        wakeMappedWaiters(request, reply);
      }
    });
  }

  /**
   * Retourne une copie de la réponse avec le {@code serviceId} de la requête appliqué si non nul,
   * et le {@code networkId} hérité de la requête si la réponse a un networkId à 0.
   *
   * <p>Legacy : {@code DefaultCache.processRequest} l.115-116, {@code DefaultCache.addReply} l.223-224.
   */
  private CwReply buildReturnedReply(CachedReply enriched, EcmRequest request) {
    CwReply reply = enriched.reply();
    int serviceId = request.key().serviceId() != 0 ? request.key().serviceId() : reply.key().serviceId();
    int networkId = reply.key().networkId() == 0 ? request.key().networkId() : reply.key().networkId();
    int reqTag = request.key().commandTag();
    int keyTag = (reply.key().commandTag() == 0x80 || reply.key().commandTag() == 0x81)
        ? reply.key().commandTag()
        : (reqTag != 0 ? reqTag : reply.key().commandTag());
    int rplTag = (reply.commandTag() == 0x80 || reply.commandTag() == 0x81)
        ? reply.commandTag()
        : (reqTag != 0 ? reqTag : reply.commandTag());
    EcmKey key = new EcmKey(keyTag, serviceId, networkId, reply.key().caId(), reply.key().dataHash());
    if (key.commandTag() == reply.key().commandTag()
        && key.serviceId() == reply.key().serviceId()
        && key.networkId() == reply.key().networkId()
        && rplTag == reply.commandTag()) {
      return reply;
    }
    return new CwReply(key, rplTag, reply.cw(), reply.connectorName(), reply.originAddress(), reply.timestamp());
  }

  private void addRequest(int successFactor, EcmRequest request) {
    boolean added = pending.addRequest(request);
    if (added) {
      updatePendingPeak();
      caids.add(request.key().caId());
      for (CacheMonitor monitor : monitors) monitor.onRequest(successFactor, request);
      for (CacheListener listener : listeners) listener.onRequest(successFactor, request);
      for (CacheTransport transport : transports) transport.onRequest(request);
    }
  }

  private void updatePendingPeak() {
    int current = pending.size();
    int peak;
    while ((peak = pendingPeak.get()) < current) {
      if (pendingPeak.compareAndSet(peak, current)) break;
    }
  }

  /**
   * Enregistre une réponse CW dans le cache local et gère la logique de contestation.
   *
   * <h3>Détection de CW contestée : comparaison octet-à-octet vs hash du legacy</h3>
   * <p>Le legacy {@code DefaultCache.addReply} l.227 compare {@code !oldReply.equals(reply)}
   * où {@code CamdNetMessage.equals()} compare uniquement {@code dataHash} (hash 32 bits).
   * Pour un message "reply", {@code dataHash} est calculé sur les octets de la CW elle-même
   * (via {@code refreshDataHash()} qui applique {@code 31*h+byte} sur les 16 octets de la CW
   * stockés dans {@code customData}).
   *
   * <p>En cible, nous comparons directement les octets CW avec {@code Arrays.equals(oldReply.cw(), storedReply.cw())}.
   * Cette comparaison est <strong>strictement plus précise</strong> que le hash 32 bits : elle ne
   * souffre d'aucun risque de collision (probabilité ~1/2³² dans le legacy). Deux CW qui seraient
   * considérées "identiques" par collision de hash mais réellement différentes seront correctement
   * détectées comme contestées dans cette implémentation — ce qui est une amélioration, pas une
   * déviation (on est légèrement plus strict sur la détection d'anomalies).
   *
   * <p>Pour le cas nominal (pas de collision), les deux approches sont équivalentes : des CW
   * identiques ont le même hash, des CW différentes ont (presque toujours) des hash différents.
   *
   * <p>Legacy : {@code DefaultCache.addReply} l.221-251.
   */
  private void addReply(EcmRequest request, CwReply reply) {
    if (reply == null || reply.isEmpty()) return;

    int networkId = reply.key().networkId() == 0 ? request.key().networkId() : reply.key().networkId();
    CwReply storedReply;
    if (networkId != reply.key().networkId()) {
      EcmKey key = new EcmKey(reply.key().commandTag(), reply.key().serviceId(), networkId,
          reply.key().caId(), reply.key().dataHash());
      storedReply = new CwReply(key, reply.commandTag(), reply.cw(), reply.connectorName(),
          reply.originAddress(), reply.timestamp());
    } else {
      storedReply = reply;
    }

    CachedReply enriched = new CachedReply(storedReply, request.profileName());
    CachedReply old = localStore.put(request.key(), enriched);
    indexServiceCw(request, storedReply, enriched);

    if (old != null) {
      CwReply oldReply = old.reply();
      if (!Arrays.equals(oldReply.cw(), storedReply.cw())) {
        contestedCount.incrementAndGet();
        boolean added = false;
        try {
          added = contestedRegistry.addCandidate(request.key(), storedReply, oldReply);
        } catch (IllegalStateException e) {
          LOG.log(Level.WARNING, "Could not retain cache reply for " + request.key() + ": " + e.getMessage());
        }
        if (added) {
          for (CacheMonitor monitor : monitors) monitor.onContested(request, storedReply);
        }
        if (!DcwValidator.equalsSingleDcw(oldReply, storedReply)) {
          LOG.log(Level.WARNING,
              "Contested cache reply for " + request.key() + ", both DCW differ - Previous: "
                  + oldReply + " Current: " + storedReply + " (time difference: "
                  + (storedReply.timestamp() - oldReply.timestamp()) + "ms)");
        } else {
          if (DcwValidator.hasZeroDcw(oldReply) || DcwValidator.hasZeroDcw(storedReply)) {
            LOG.log(Level.INFO,
                "Contested cache reply for " + request.key() + ", different opposing DCW - Previous: "
                    + oldReply + " Current: " + storedReply);
          } else {
            LOG.log(Level.WARNING,
                "Contested cache reply for " + request.key() + ", different opposing DCW - Previous: "
                    + oldReply + " Current: " + storedReply);
          }
        }
      } else {
        if (storedReply.originAddress() != null) {
          LOG.log(Level.FINE,
              "Duplicate cache reply for " + request.key() + " late by: "
                  + (storedReply.timestamp() - oldReply.timestamp()) + " ms");
        }
      }
    }

    for (CacheTransport transport : transports) transport.onReply(storedReply);
  }

  public boolean containsCaid(int caId) {
    return caids.contains(caId);
  }

  public CacheStats stats() {
    return new CacheStats(
        pending.size(),
        pendingPeak.get(),
        localStore.size(),
        timeouts.get(),
        instantHits.get(),
        waitHits.get(),
        contestedCount.get(),
        remoteHits.get());
  }

  /**
   * Capture non-bloquante des stats sur le contexte du verticle.
   * Échoue si non démarré (context == null ou pending == null).
   */
  public Future<CacheStats> snapshotStats() {
    Promise<CacheStats> p = Promise.promise();
    if (context == null || pending == null) {
      p.fail("Cache not started");
      return p.future();
    }
    try {
      context.runOnContext(v -> {
        try {
          p.complete(stats());
        } catch (Exception e) {
          p.tryFail(e);
        }
      });
    } catch (Exception e) {
      p.tryFail(e);
    }
    return p.future();
  }

  /**
   * Traite une requête distante reçue du réseau (TYPE_REQUEST entrant).
   *
   * <p><b>NE ré-émet JAMAIS vers le réseau</b> (pas de {@code transport.onRequest()}).
   * Comportement conforme au legacy : {@code ClusteredCache.java:708-709} appelle
   * {@code super.addRequest(-1, request, false)} qui ne fait qu'enregistrer dans le
   * pending local, <b>sans</b> appeler {@code sendMessage()} / {@code writeToAll()}.
   *
   * <p>La distinction est cruciale :
   * <ul>
   *   <li>Requête <b>locale</b> ({@link #addRequest}, appelée par {@link #processRequest}) :
   *       pending + émission réseau ({@code CacheTransport.onRequest}).</li>
   *   <li>Requête <b>distante</b> (cette méthode, appelée quand un TYPE_REQUEST arrive
   *       du réseau) : <b>uniquement</b> pending local — ne jamais ré-émettre,
   *       sinon broadcast storm infinie entre nœuds.</li>
   * </ul>
   *
   * <p>Ajouté en L3a pour le transport UDP.
   */
  public void handleRemoteRequest(EcmRequest request) {
    context.runOnContext(v -> {
      if (pending.addRequest(request)) {
        updatePendingPeak();
        caids.add(request.key().caId());
        for (CacheMonitor monitor : monitors) monitor.onRequest(-1, request);
        // NOTE: pas de transport.onRequest() — cf. javadoc ci-dessus (broadcast storm)
      }
    });
  }

  /**
   * Lecture SANS effet de bord du cache local — pas de verrou, pas de pending,
   * pas de notification de transport. Utilisé par le transport UDP pour honorer
   * un {@code TYPE_RESENDREQ} entrant (regarder si on a déjà la CW sans déclencher
   * un rendez-vous).
   *
   * <p>Équivalent d'un {@code get()} sur la {@code ecmMap} du legacy, sans passer
   * par {@code processRequest}.
   *
   * @param key la clé ECM à chercher
   * @return la réponse CW si présente dans le cache L1 local, {@code null} sinon
   */
  public CwReply peekLocalCache(EcmKey key) {
    CachedReply cached = localStore.get(key);
    return cached != null ? cached.reply() : null;
  }

  /**
   * Lecture sans effet de bord pour une cible résolue par {@code DcwServiceMapping} :
   * tente d'abord {@code peekLocalCache} par {@code dataHash}, puis l'index par service
   * {@code (caid, provider, sid, oddEven)} lorsque les octets d'ECM diffèrent (Simulcrypt).
   */
  public CwReply peekMappedCache(int caid, int provider, int sid, int oddEven, int dataHash) {
    if (dataHash != 0) {
      CachedReply direct = localStore.get(new EcmKey(oddEven, sid, 0, caid, dataHash));
      if (direct != null && direct.reply() != null
          && direct.reply().key().caId() == caid
          && direct.reply().key().serviceId() == sid) {
        return direct.reply();
      }
    }
    CachedReply bySvc = lookupServiceCw(caid, provider, sid, oddEven, System.currentTimeMillis());
    return bySvc != null ? bySvc.reply() : null;
  }

  private long maxServiceCwAgeMs() {
    long cfgAge = config != null && config.cwMaxAgeMs() > 0 ? config.cwMaxAgeMs() : SERVICE_CW_MAX_AGE_MS;
    return Math.min(SERVICE_CW_MAX_AGE_MS, cfgAge);
  }

  private void indexServiceCw(EcmRequest request, CwReply storedReply, CachedReply enriched) {
    int caid = storedReply.key().caId() != 0 ? storedReply.key().caId() : request.key().caId();
    int sid = storedReply.key().serviceId() != 0 ? storedReply.key().serviceId() : request.key().serviceId();
    if (caid <= 0 || sid <= 0) return;
    int prov = effectiveProvider(request);
    int rTag = storedReply.key().commandTag();
    int qTag = request.key().commandTag();
    int tag = (rTag == 0x80 || rTag == 0x81) ? rTag : ((qTag == 0x80 || qTag == 0x81) ? qTag : 0);
    long now = System.currentTimeMillis();

    putServiceCwEntry(new ServiceCwKey(caid, prov, sid, tag), enriched, now);
    if (prov != 0) {
      putServiceCwEntry(new ServiceCwKey(caid, 0, sid, tag), enriched, now);
    }
    if (tag == 0x80 || tag == 0x81) {
      int oppTag = (tag == 0x80) ? 0x81 : 0x80;
      evictPreviousCycleIfAged(new ServiceCwKey(caid, prov, sid, oppTag), now);
      if (prov != 0) {
        evictPreviousCycleIfAged(new ServiceCwKey(caid, 0, sid, oppTag), now);
      }
    }
  }

  private void putServiceCwEntry(ServiceCwKey key, CachedReply enriched, long now) {
    ServiceCachedEntry prev = serviceStore.get(key);
    long firstSeen = (prev != null && prev.cached() != null && prev.cached().reply() != null
        && Arrays.equals(prev.cached().reply().cw(), enriched.reply().cw())
        && (now - prev.firstSeenAtMs()) <= maxServiceCwAgeMs())
        ? prev.firstSeenAtMs()
        : now;
    serviceStore.put(key, new ServiceCachedEntry(enriched, firstSeen));
  }

  private void evictPreviousCycleIfAged(ServiceCwKey key, long now) {
    ServiceCachedEntry opp = serviceStore.get(key);
    if (opp != null && (now - opp.firstSeenAtMs()) > 4_000L) {
      serviceStore.remove(key);
    }
  }

  private CachedReply getFreshServiceCw(ServiceCwKey key, long now) {
    ServiceCachedEntry entry = serviceStore.get(key);
    if (entry == null) return null;
    if ((now - entry.firstSeenAtMs()) > maxServiceCwAgeMs()) {
      serviceStore.remove(key);
      return null;
    }
    return entry.cached();
  }

  private CachedReply lookupServiceCw(int caid, int provider, int sid, int reqTag, long now) {
    if (caid <= 0 || sid <= 0) return null;
    int normTag = (reqTag == 0x80 || reqTag == 0x81) ? reqTag : 0;
    CachedReply exact = getFreshServiceCw(new ServiceCwKey(caid, provider, sid, normTag), now);
    if (exact != null) return exact;
    if (normTag != 0) {
      CachedReply unkParity = getFreshServiceCw(new ServiceCwKey(caid, provider, sid, 0), now);
      if (unkParity != null) return unkParity;
    }
    if (provider != 0) {
      CachedReply zeroProv = getFreshServiceCw(new ServiceCwKey(caid, 0, sid, normTag), now);
      if (zeroProv != null) return zeroProv;
      if (normTag != 0) {
        CachedReply zeroProvUnk = getFreshServiceCw(new ServiceCwKey(caid, 0, sid, 0), now);
        if (zeroProvUnk != null) return zeroProvUnk;
      }
    }
    return null;
  }

  private List<com.bowman.cspng.domain.dcw.DcwServiceMapping.MappedTarget> resolveMappedTargets(
      String profile, int caid, int provider, int sid, long nowMs) {
    var sup = dcwPolicySupplier;
    if (sup == null || caid <= 0 || sid <= 0) return java.util.Collections.emptyList();
    com.bowman.cspng.domain.dcw.DcwQualityPolicy policy;
    try {
      policy = sup.get();
    } catch (Exception e) {
      return java.util.Collections.emptyList();
    }
    if (policy == null || !policy.enabled() || policy.mappings() == null || policy.mappings().isEmpty()) {
      return java.util.Collections.emptyList();
    }
    List<com.bowman.cspng.domain.dcw.DcwServiceMapping.MappedTarget> out = new ArrayList<>();
    for (var mapping : policy.mappings()) {
      if (mapping == null || !mapping.isActive(nowMs)) continue;
      var targetOpt = mapping.resolveTarget(profile, caid, provider, sid, nowMs);
      if (targetOpt.isEmpty()) {
        targetOpt = mapping.resolveTargetCrossProfile(caid, provider, sid, nowMs);
      }
      if (targetOpt.isPresent() && !out.contains(targetOpt.get())) {
        out.add(targetOpt.get());
      }
    }
    return out;
  }

  private CachedReply lookupMappedServiceCw(EcmRequest request, long nowMs) {
    if (request == null || request.key() == null) return null;
    int reqCaid = request.key().caId();
    int reqSid = request.key().serviceId();
    int reqProv = effectiveProvider(request);
    int reqTag = request.key().commandTag();
    var targets = resolveMappedTargets(request.profileName(), reqCaid, reqProv, reqSid, nowMs);
    for (var target : targets) {
      CachedReply hit = lookupServiceCw(target.caid(), target.provider(), target.sid(), reqTag, nowMs);
      if (hit != null && hit.reply() != null && !hit.reply().isEmpty()) {
        return hit;
      }
    }
    return null;
  }

  private CwReply buildMappedReturnedReply(CachedReply enriched, EcmRequest request) {
    CwReply reply = enriched.reply();
    int reqTag = request.key().commandTag();
    int keyTag = (reqTag == 0x80 || reqTag == 0x81)
        ? reqTag
        : ((reply.key().commandTag() == 0x80 || reply.key().commandTag() == 0x81)
            ? reply.key().commandTag() : reqTag);
    int rplTag = (reqTag == 0x80 || reqTag == 0x81)
        ? reqTag
        : ((reply.commandTag() == 0x80 || reply.commandTag() == 0x81)
            ? reply.commandTag() : reqTag);
    EcmKey key = new EcmKey(keyTag, request.key().serviceId(), request.key().networkId(),
        request.key().caId(), request.key().dataHash());
    return new CwReply(key, rplTag, reply.cw(), reply.connectorName(), reply.originAddress(), reply.timestamp());
  }

  private void wakeMappedWaiters(EcmRequest request, CwReply reply) {
    int rCaid = reply.key().caId() != 0 ? reply.key().caId() : request.key().caId();
    int rSid = reply.key().serviceId() != 0 ? reply.key().serviceId() : request.key().serviceId();
    if (rCaid <= 0 || rSid <= 0) return;
    int rProv = effectiveProvider(request);
    int rTag = (reply.key().commandTag() == 0x80 || reply.key().commandTag() == 0x81)
        ? reply.key().commandTag()
        : ((request.key().commandTag() == 0x80 || request.key().commandTag() == 0x81) ? request.key().commandTag() : 0);
    long now = System.currentTimeMillis();

    var targets = resolveMappedTargets(request.profileName(), rCaid, rProv, rSid, now);
    if (targets.isEmpty()) return;

    for (var target : targets) {
      List<PendingRegistry.PendingEntry> matching = pending.findMatchingEntries(originReq -> {
        if (originReq == null || originReq.key() == null) return false;
        if (originReq.key().caId() != target.caid() || originReq.key().serviceId() != target.sid()) return false;
        int oTag = originReq.key().commandTag();
        if (rTag != 0 && (oTag == 0x80 || oTag == 0x81) && oTag != rTag) return false;
        int oProv = effectiveProvider(originReq);
        return oProv == target.provider() || oProv == 0 || target.provider() == 0;
      });
      for (PendingRegistry.PendingEntry pe : matching) {
        EcmRequest originReq = pe.originRequest();
        List<PendingRegistry.Waiter> mappedWaiters = pending.remove(originReq.key());
        if (mappedWaiters.isEmpty()) continue;
        CwReply mappedReply = buildMappedReturnedReply(new CachedReply(reply, originReq.profileName()), originReq);
        localStore.put(originReq.key(), new CachedReply(mappedReply, originReq.profileName()));
        for (PendingRegistry.Waiter w : mappedWaiters) {
          w.fireReply(mappedReply);
        }
      }
    }
  }

  public void resetCounters() {
    timeouts.set(0);
    instantHits.set(0);
    waitHits.set(0);
    remoteHits.set(0);
    contestedCount.set(0);
    pendingPeak.set(0);
  }
}
