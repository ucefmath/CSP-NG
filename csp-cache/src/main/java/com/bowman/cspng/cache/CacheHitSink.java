package com.bowman.cspng.cache;

/**
 * Puits d'attribution des hits cache réellement servis (lot 3).
 *
 * <p>Interface locale au module {@code csp-cache} pour éviter toute
 * dépendance circulaire vers {@code csp-connectors} : l'implémentation
 * partagée vit côté connectors et est branchée par référence de méthode.
 * Appelée exactement une fois par réponse servie à un vrai demandeur,
 * jamais sur les probes internes.
 */
@FunctionalInterface
public interface CacheHitSink {

  /** Enregistre un hit servi (adresses/noms seuls, jamais de CW). */
  void recordServedHit(String originAddress, String connectorName);
}
