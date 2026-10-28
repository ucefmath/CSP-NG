package com.bowman.cspng.users.crypto;

import io.quarkus.elytron.security.common.BcryptUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.logging.Logger;

/**
 * Vérification de mot de passe avec temps constant.
 * Supporte {@code $1$} (MD5Crypt legacy) et {@code $2a/$2b/$2y} (bcrypt via BcryptUtil).
 * Refuse {@code $argon2id$} avec log sans données sensibles (fail-closed).
 */
public final class PasswordVerifier {

  private static final Logger LOG = Logger.getLogger(PasswordVerifier.class.getName());

  private PasswordVerifier() {}

  public static boolean verify(String plainPassword, String storedHash) {
    if (plainPassword == null || storedHash == null) return false;
    // Guard fail-fast if on event-loop and bcrypt
    if ((storedHash.startsWith("$2a$") || storedHash.startsWith("$2b$") || storedHash.startsWith("$2y$"))) {
      try {
        if (io.vertx.core.Vertx.currentContext() != null && io.vertx.core.Context.isOnEventLoopThread()) {
          throw new IllegalStateException("Bcrypt verify called on event-loop — use verifyAsync instead");
        }
      } catch (IllegalStateException ise) {
        throw ise;
      } catch (Throwable ignored) {
      }
    }
    if (storedHash.startsWith("$1$")) {
      if (plainPassword.startsWith("$1$") && constantTimeEquals(plainPassword, storedHash)) {
        return true;
      }
      String salt = MD5Crypt.extractSalt(storedHash);
      if (salt == null) return false;
      String computed = MD5Crypt.crypt(plainPassword, salt);
      if (computed == null) return false;
      return constantTimeEquals(computed, storedHash);
    }
    if (storedHash.startsWith("$2a$") || storedHash.startsWith("$2b$") || storedHash.startsWith("$2y$")) {
      try {
        String candidate = plainPassword.startsWith("$1$abcdefgh$")
            ? plainPassword
            : MD5Crypt.crypt(plainPassword, "abcdefgh");
        if (candidate != null && BcryptUtil.matches(candidate, storedHash)) {
          return true;
        }
        if (!plainPassword.startsWith("$1$abcdefgh$")) {
          return BcryptUtil.matches(plainPassword, storedHash);
        }
        return false;
      } catch (Exception e) {
        LOG.fine(() -> "Bcrypt verification failed: " + e.getClass().getSimpleName());
        return false;
      }
    }
    if (storedHash.startsWith("$argon2id$")) {
      LOG.fine("Argon2id password hash — authentication rejected (fail-closed)");
      return false;
    }
    LOG.fine("Unknown password hash format — authentication rejected");
    return false;
  }

  /**
   * Async offload for bcrypt verify — jamais sur event-loop.
   */
  public static io.vertx.core.Future<Boolean> verifyAsync(String plainPassword, String storedHash, io.vertx.core.Vertx vertx) {
    if (vertx == null) return io.vertx.core.Future.succeededFuture(verify(plainPassword, storedHash));
    return vertx.executeBlocking(() -> verify(plainPassword, storedHash), false);
  }

  private static boolean constantTimeEquals(String a, String b) {
    byte[] ab = a.getBytes(StandardCharsets.UTF_8);
    byte[] bb = b.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(ab, bb);
  }
}
