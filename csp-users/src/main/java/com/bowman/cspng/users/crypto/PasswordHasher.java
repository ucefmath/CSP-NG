package com.bowman.cspng.users.crypto;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.logging.Logger;

/**
 * Hashage de mot de passe — toujours bcrypt pour les nouveaux comptes.
 * Le coût est configurable (défaut 12).
 * Ne jamais utiliser MD5Crypt pour les nouvelles créations.
 *
 * <p>Les opérations bcrypt sont CPU-intensives et NE DOIVENT JAMAIS tourner sur l'event-loop.
 * Les méthodes synchrones sont conservées pour les tests hors event-loop, mais vérifient
 * {@code Vertx.currentContext()} quand possible. Les appels runtime doivent utiliser
 * {@link #hashAsync(String, Vertx)} / {@link #verifyAsync(String, String, Vertx)}.
 */
public final class PasswordHasher {

  private static final Logger LOG = Logger.getLogger(PasswordHasher.class.getName());

  /** Bcrypt cost factor (default 12, min 10 recommended). */
  private final int cost;

  public PasswordHasher() {
    this(12);
  }

  public PasswordHasher(int cost) {
    if (cost < 10) {
      LOG.warning(() -> "Bcrypt cost " + cost + " is below recommended minimum 10 — using 10");
      this.cost = 10;
    } else {
      this.cost = cost;
    }
  }

  static String toBcryptInput(String plainPassword) {
    if (plainPassword.startsWith("$1$abcdefgh$")) {
      return plainPassword;
    }
    return MD5Crypt.crypt(plainPassword, "abcdefgh");
  }

  /**
   * Hash synchrone — réservé aux tests hors event-loop ou aux contextes déjà offloadés.
   * Fail-fast si bcrypt et appelé depuis l'event-loop Vert.x.
   */
  public String hash(String plainPassword) {
    if (plainPassword == null || plainPassword.isEmpty()) {
      throw new IllegalArgumentException("Password must not be null or empty");
    }
    try {
      if (io.vertx.core.Vertx.currentContext() != null
          && io.vertx.core.Context.isOnEventLoopThread()) {
        throw new IllegalStateException("Bcrypt hash called on event-loop — use hashAsync instead");
      }
    } catch (IllegalStateException ise) {
      throw ise;
    } catch (Throwable ignored) {
    }
    return BcryptUtil.bcryptHash(toBcryptInput(plainPassword), cost);
  }

  /**
   * Hash asynchrone non-bloquant — offload CPU sur worker thread via Vertx.executeBlocking.
   * Ne bloque jamais l'event-loop.
   */
  public Future<String> hashAsync(String plainPassword, Vertx vertx) {
    if (plainPassword == null || plainPassword.isEmpty()) {
      return Future.failedFuture(new IllegalArgumentException("Password must not be null or empty"));
    }
    if (vertx == null) {
      // Fallback synchrone si Vertx null (tests)
      try {
        return Future.succeededFuture(hash(plainPassword));
      } catch (Exception e) {
        return Future.failedFuture(e);
      }
    }
    return vertx.executeBlocking(() -> BcryptUtil.bcryptHash(toBcryptInput(plainPassword), cost), false);
  }

  /**
   * Verify synchrone — legacy, eviter sur event-loop.
   */
  public boolean verify(String plainPassword, String storedHash) {
    return PasswordVerifier.verify(plainPassword, storedHash);
  }

  /**
   * Verify asynchrone non-bloquant via executeBlocking.
   */
  public Future<Boolean> verifyAsync(String plainPassword, String storedHash, Vertx vertx) {
    if (vertx == null) {
      try {
        return Future.succeededFuture(PasswordVerifier.verify(plainPassword, storedHash));
      } catch (Exception e) {
        return Future.failedFuture(e);
      }
    }
    return vertx.executeBlocking(() -> PasswordVerifier.verify(plainPassword, storedHash), false);
  }

  public int cost() {
    return cost;
  }
}
