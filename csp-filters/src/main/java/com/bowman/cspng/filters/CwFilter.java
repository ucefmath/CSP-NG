package com.bowman.cspng.filters;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmRequest;

/**
 * SPI de filtre CW/DCW (plan §3.7).
 *
 * <h3>API de décision (nouvelle)</h3>
 * {@link #evaluateReply(FilterContext, CwReply)} retourne une
 * {@link FilterDecision} explicite plutôt qu'un {@code CwReply} nullable.
 * L'implémentation par défaut délègue à {@link #filterReply(EcmRequest, CwReply)}
 * pour la rétro-compatibilité (null → {@code Drop}, non-null → {@code Accept}).
 *
 * <h3>Fan-out</h3>
 * {@link #requiresMultipleReplies()} indique au {@code CwsConnectorManager}
 * qu'il doit interroger tous les connecteurs du profil au lieu d'un seul.
 */
public interface CwFilter {

  String name();

  int order();

  default EcmRequest filterRequest(EcmRequest req) {
    return req;
  }

  default CwReply filterReply(EcmRequest req, CwReply reply) {
    return reply;
  }

  /**
   * Évalue une réponse via le nouveau contrat de décision.
   *
   * <p>Par défaut, délègue à {@link #filterReply(EcmRequest, CwReply)} :
   * {@code null} → {@link FilterDecision.Drop}, non-null → {@link FilterDecision.Accept}.
   *
   * @param context contexte (requête, connecteur, profil, horodatage)
   * @param reply   réponse brute du connecteur
   * @return une décision explicite (jamais null)
   */
  default FilterDecision evaluateReply(FilterContext context, CwReply reply) {
    CwReply result = filterReply(context.request(), reply);
    if (result == null) return FilterDecision.Drop.INSTANCE;
    return new FilterDecision.Accept(result);
  }

  /**
   * Indique si ce filtre nécessite de voir les réponses de plusieurs
   * connecteurs simultanément (fan-out).
   *
   * <p>Lorsque la chaîne contient au moins un filtre retournant {@code true},
   * le manager envoie la requête à <em>tous</em> les connecteurs connectés
   * du profil au lieu du premier disponible.
   */
  default boolean requiresMultipleReplies() {
    return false;
  }

  default boolean blocking() {
    return false;
  }
}
