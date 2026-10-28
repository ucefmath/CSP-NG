package com.bowman.cspng.cache;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Registre des requêtes en attente de CW, remplaçant {@code DefaultCache.pendingEcms}.
 *
 * <p>Contrairement au legacy qui réveille TOUS les threads sur TOUTES les clés avec
 * {@code notifyAll()}, cette implémentation regroupe les attenteurs par clé ECM :
 * <ul>
 *   <li>une seule entrée {@link PendingEntry} par {@link EcmKey} ;</li>
 *   <li>chaque nouvelle requête sur la même clé ajoute un {@link Waiter} dans l'entrée ;</li>
 *   <li>la réception d'une réponse résout UNIQUEMENT les promesses de cette clé.</li>
 * </ul>
 *
 * <p>C'est une amélioration par rapport au legacy (moins de réveils parasites) tout en
 * conservant la sémantique du rendez-vous : plusieurs sessions sur la même clé sont
 * résolues ensemble.
 */
public class PendingRegistry {

  private final Map<EcmKey, PendingEntry> entries = new ConcurrentHashMap<>();
  private final Vertx vertx;

  public PendingRegistry(Vertx vertx) {
    this.vertx = vertx;
  }

  /**
   * Crée une entrée pending si elle n'existe pas déjà. La requête fournie devient la
   * requête "origine" de l'entrée ; son {@code originId} est utilisé pour l'anti-boucle
   * same-origin.
   *
   * @return {@code true} si une nouvelle entrée a été créée, {@code false} si elle existait déjà
   */
  public boolean addRequest(EcmRequest request) {
    PendingEntry previous = entries.putIfAbsent(request.key(), new PendingEntry(request));
    return previous == null;
  }

  public boolean contains(EcmKey key) {
    return entries.containsKey(key);
  }

  public PendingEntry get(EcmKey key) {
    return entries.get(key);
  }

  public int size() {
    return entries.size();
  }

  /**
   * Retire l'entrée pending et retourne la liste des waiters encore présents pour être
   * résolus par l'appelant.
   */
  public List<Waiter> remove(EcmKey key) {
    PendingEntry entry = entries.remove(key);
    if (entry == null) return Collections.emptyList();
    List<Waiter> snapshot = entry.drainWaiters();
    for (Waiter w : snapshot) w.cancelTimers();
    return snapshot;
  }

  /**
   * Ajoute un waiter à une entrée existante.
   *
   * @param request        requête qui attend
   * @param maxWaitMs      durée maximale d'attente pour cette requête
   * @param onAlert        callback déclenché une seule fois à {@code maxWaitMs / 2}
   * @param onTimeout      callback déclenché à expiration
   * @param onReply        callback déclenché à la réception de la réponse
   * @param replyTransform fonction de transformation de la réponse avant complétion
   * @return le waiter créé, ou {@code null} si l'entrée n'existe plus
   */
  public Waiter addWaiter(
      EcmRequest request,
      long maxWaitMs,
      Runnable onAlert,
      Handler<CwReply> onTimeout,
      Handler<CwReply> onReply,
      Function<CwReply, CwReply> replyTransform) {
    PendingEntry entry = entries.get(request.key());
    if (entry == null) return null;
    Waiter waiter = new Waiter(vertx, request, maxWaitMs, onAlert, onTimeout, onReply, replyTransform);
    entry.addWaiter(waiter);
    return waiter;
  }

  /**
   * Retire un waiter spécifique de son entrée (typiquement après expiration). Si l'entrée
   * devient vide, elle est retirée.
   *
   * @return {@code true} si l'entrée a été supprimée car vide
   */
  public boolean removeWaiter(EcmKey key, Waiter waiter) {
    PendingEntry entry = entries.get(key);
    if (entry == null) return false;
    waiter.cancelTimers();
    boolean empty = entry.removeWaiter(waiter);
    if (empty) entries.remove(key);
    return empty;
  }

  public static class PendingEntry {

    private final EcmRequest originRequest;
    private final List<Waiter> waiters = new ArrayList<>();

    PendingEntry(EcmRequest originRequest) {
      this.originRequest = originRequest;
    }

    public EcmRequest originRequest() {
      return originRequest;
    }

    synchronized void addWaiter(Waiter waiter) {
      waiters.add(waiter);
    }

    synchronized boolean removeWaiter(Waiter waiter) {
      waiters.remove(waiter);
      return waiters.isEmpty();
    }

    synchronized List<Waiter> drainWaiters() {
      List<Waiter> snapshot = new ArrayList<>(waiters);
      waiters.clear();
      return snapshot;
    }
  }

  public static class Waiter {

    private final Vertx vertx;
    private final Promise<CwReply> promise = Promise.promise();
    private final EcmRequest request;
    private final long maxWaitMs;
    private final Runnable onAlert;
    private final Handler<CwReply> onTimeout;
    private final Handler<CwReply> onReply;
    private final Function<CwReply, CwReply> replyTransform;
    private final long alertTimerId;
    private final long timeoutTimerId;
    private boolean completed;
    private boolean alertFired;

    Waiter(
        Vertx vertx,
        EcmRequest request,
        long maxWaitMs,
        Runnable onAlert,
        Handler<CwReply> onTimeout,
        Handler<CwReply> onReply,
        Function<CwReply, CwReply> replyTransform) {
      this.vertx = vertx;
      this.request = request;
      this.maxWaitMs = maxWaitMs;
      this.onAlert = onAlert != null ? onAlert : () -> {
      };
      this.onTimeout = onTimeout != null ? onTimeout : r -> {
      };
      this.onReply = onReply != null ? onReply : r -> {
      };
      this.replyTransform = replyTransform;
      long clampedWait = Math.max(1L, maxWaitMs);
      long half = Math.max(1L, clampedWait / 2);
      this.alertTimerId = vertx.setTimer(half, id -> fireAlert());
      this.timeoutTimerId = vertx.setTimer(clampedWait, id -> fireTimeout(null));
    }

    public EcmRequest request() {
      return request;
    }

    public io.vertx.core.Future<CwReply> future() {
      return promise.future();
    }

    public boolean isCompleted() {
      return completed;
    }

    synchronized void fireAlert() {
      if (!completed && !alertFired) {
        alertFired = true;
        onAlert.run();
      }
    }

    synchronized void fireTimeout(CwReply reply) {
      if (!completed) {
        completed = true;
        cancelTimers();
        onTimeout.handle(reply);
        promise.complete(reply);
      }
    }

    synchronized void fireReply(CwReply reply) {
      if (!completed) {
        completed = true;
        cancelTimers();
        CwReply toComplete = replyTransform != null ? replyTransform.apply(reply) : reply;
        onReply.handle(toComplete);
        promise.complete(toComplete);
      }
    }

    synchronized void fireEmpty() {
      fireTimeout(null);
    }

    public void cancelTimers() {
      vertx.cancelTimer(alertTimerId);
      vertx.cancelTimer(timeoutTimerId);
    }
  }
}
