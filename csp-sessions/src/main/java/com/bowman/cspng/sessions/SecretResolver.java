package com.bowman.cspng.sessions;

import io.vertx.core.Future;

/**
 * Résolution asynchrone d'un secret référencé (sur worker thread, 0600).
 */
@FunctionalInterface
public interface SecretResolver {

  /**
   * Résout le mot de passe depuis sa référence syntaxique (env:VAR ou file:/path).
   *
   * @param ref     référence du secret
   * @param context contexte audit/log (ex: "cs378x:user1")
   * @return future complété avec la valeur résolue
   */
  Future<String> resolvePasswordAsync(String ref, String context);
}
