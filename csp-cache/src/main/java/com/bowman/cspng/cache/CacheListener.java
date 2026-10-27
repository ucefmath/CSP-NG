package com.bowman.cspng.cache;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmRequest;

/**
 * Hook optionnel permettant à un composant externe d'intercepter ou de forcer les
 * décisions du rendez-vous de cache.
 *
 * <p>Dans le legacy ce rôle est joué par {@code CacheListener} (ex. {@code ClusteredCache}).
 * Les méthodes par défaut retournent des valeurs neutres pour ne pas obliger les implémentations
 * à tout surcharger.
 */
public interface CacheListener {

  /**
   * Permet de forcer un verrou sur une requête, indépendamment du mode {@code alwaysWait}.
   *
   * <p>Legacy : {@code DefaultCache.processRequest} l.81-85.
   *
   * @param successFacteur  facteur de succès du connecteur ayant émis la requête
   * @param request         requête en cours
   * @return {@code true} pour forcer un verrou ({@code alwaysWait = true}), {@code false} sinon
   */
  default boolean lockRequest(int successFacteur, EcmRequest request) {
    return false;
  }

  /**
   * Appelé une seule fois lorsqu'une nouvelle requête devient pending.
   *
   * <p>Legacy : {@code DefaultCache.addRequest} l.216-217.
   */
  default void onRequest(int successFacteur, EcmRequest request) {
  }

  /**
   * Appelé à la réception d'une réponse non vide.
   *
   * <p>Legacy : {@code DefaultCache.processReply} l.147 et l.152.
   */
  default void onReply(EcmRequest request, CwReply reply) {
  }

  /**
   * Appelé à mi-parcours ({@code maxWait / 2}) d'une attente.
   *
   * <p>Legacy : {@code DefaultCache.delayAlert}. En L2 ce n'est qu'un point d'extension ;
   * le lot L3 en fera un {@code TYPE_RESENDREQ} UDP.
   */
  default void onDelayAlert(int successFacteur, EcmRequest request, boolean alwaysWait, long maxWaitMs) {
  }

  /**
   * Appelé lorsqu'une CW contestée est détectée.
   *
   * <p>Legacy : {@code DefaultCache.addReply} l.231.
   */
  default void onContested(EcmRequest request, CwReply reply) {
  }
}
