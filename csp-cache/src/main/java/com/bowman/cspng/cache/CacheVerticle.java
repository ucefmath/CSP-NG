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

  public void setServiceResolver(com.bowman.cspng.domain.srvid.ServiceResolver resolver) {
    this.serviceResolver = resolver != null ? resolver : com.bowman.cspng.domain.srvid.ServiceResolver.EMPTY;
  }

  public com.bowman.cspng.domain.srvid.ServiceResolver getServiceResolver() {
    return serviceResolver;
  }

  private void logCacheHit(String type, EcmRequest request, CwReply reply, long elapsedMs) {
    if (!ECM_LOG.isLoggable(Level.INFO)) return;
    int caid = request.key().caId();
    int sid = request.key().serviceId();
    int provid = 0;
    if (request.body() != null && request.body().length > 0) {
      provid = com.bowman.cspng.codec.EcmProviderExtractor.extractProvider(caid, request.body());
    }
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

  private CacheVerticleConfig config = CacheVerticleConfig.defaults();
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
    a.recordServedHit(reply.originAddress(), reply.connectorName());
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

  public void configure(CacheVerticleConfig config) {
    context.runOnContext(v -> {
      this.config = config;
      this.localStore = new LocalCwStore(config);
      this.pendingPeak.set(0);
    });
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
        recordServedHit(reply);
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
          recordServedHit(returned);
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
    int serviceId = request.key().serviceId();
    int networkId = reply.key().networkId() == 0 ? request.key().networkId() : reply.key().networkId();
    EcmKey key;
    if (serviceId != 0) {
      key = new EcmKey(reply.key().commandTag(), serviceId, networkId, reply.key().caId(), reply.key().dataHash());
    } else if (networkId != reply.key().networkId()) {
      key = new EcmKey(reply.key().commandTag(), reply.key().serviceId(), networkId, reply.key().caId(), reply.key().dataHash());
    } else {
      key = reply.key();
    }
    if (key == reply.key()) return reply;
    return new CwReply(key, reply.commandTag(), reply.cw(), reply.connectorName(), reply.originAddress(), reply.timestamp());
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

  public void resetCounters() {
    timeouts.set(0);
    instantHits.set(0);
    waitHits.set(0);
    remoteHits.set(0);
    contestedCount.set(0);
    pendingPeak.set(0);
  }
}
