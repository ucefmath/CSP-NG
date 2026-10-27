package com.bowman.cspng.users;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Future;

/**
 * Routeur d'ECM pour les sessions entrantes — strict policy (P7).
 * <p>
 * En production, en l'absence de catalogue/connecteur éligible, le routeur
 * doit toujours FAIL CLOSED (retourner {@link CwReply#isEmpty()} ou future échoué).
 */
@FunctionalInterface
public interface SessionEcmRouter {

  /**
   * Route une requête ECM selon la politique stricte et l'identité/permissions du client.
   *
   * @param request     requête ECM canonique (body = ecm amputé)
   * @param authContext contexte d'authentification et permissions du client
   * @return future complété avec la réponse CW (vide si introuvable / non autorisé)
   */
  Future<CwReply> routeEcm(EcmRequest request, ProtocolAuthResolver.AuthResult authContext);

  /**
   * Route avec provider explicite (DVB / CAM wire).
   */
  default Future<CwReply> routeEcm(EcmRequest request, ProtocolAuthResolver.AuthResult authContext, int provider) {
    return routeEcm(request, authContext);
  }

  /**
   * Routeur par défaut : fail-closed (aucune route permissive sans catalogue/connecteur).
   */
  static SessionEcmRouter failClosed() {
    return (req, auth) -> Future.succeededFuture(
        CwReply.empty(req != null ? req.key() : null));
  }
}
