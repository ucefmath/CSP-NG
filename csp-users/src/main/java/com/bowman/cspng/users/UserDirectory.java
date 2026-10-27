package com.bowman.cspng.users;

import io.vertx.core.Future;

import java.util.Optional;

/**
 * SPI d'annuaire utilisateur réactif (plan §3.6, lot L5b).
 */
public interface UserDirectory {

  Future<Optional<UserRecord>> lookup(String name);

  /**
   * Consultation fraîche garantissant la lecture directe depuis la source de persistance
   * (sans passer par un cache en mémoire potentiellement obsolète).
   */
  default Future<Optional<UserRecord>> lookupFresh(String name) {
    return lookup(name);
  }

  UserRecord lookupCached(String name);

  Future<Boolean> authenticate(String name, String pass);

  Future<Void> reload();

  default Future<Void> start() {
    return Future.succeededFuture();
  }

  default Future<Void> stop() {
    return Future.succeededFuture();
  }
}
