package com.bowman.cspng.cache;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmRequest;

/**
 * Observateur passif du cache, utilisé par exemple par {@code CacheCoveragePlugin} en L7.
 *
 * <p>Le legacy mélangeait {@code listener} et {@code monitor} dans la même interface
 * {@code CacheListener}. En cible on sépare proprement les deux rôles : le {@link CacheListener}
 * peut influencer le comportement (forcer un verrou) ; le {@link CacheMonitor} est purement
 * observationnel.
 */
public interface CacheMonitor {

  default void onRequest(int successFacteur, EcmRequest request) {
  }

  default void onReply(EcmRequest request, CwReply reply) {
  }

  default void onContested(EcmRequest request, CwReply reply) {
  }
}
