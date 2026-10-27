package com.bowman.cspng.users;

import com.bowman.cspng.users.crypto.PasswordVerifier;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implémentation mémoire réactive — cache case-insensitive, auth réelle via PasswordVerifier.
 * En runtime produit, un Vertx est injecté pour offloader bcrypt via executeBlocking.
 * Les tests peuvent utiliser le constructeur sans Vertx (synchrone hors event-loop).
 */
public class InMemoryUserDirectory implements UserDirectory {

  private final Map<String, UserRecord> users = new ConcurrentHashMap<>();
  private final Vertx vertx;

  public InMemoryUserDirectory() {
    this.vertx = null;
  }

  public InMemoryUserDirectory(Vertx vertx) {
    this.vertx = vertx;
  }

  private static String key(String name) {
    return name == null ? null : name.toLowerCase(Locale.ROOT);
  }

  public void add(UserRecord user) {
    users.put(key(user.name()), user);
  }

  /** Helper test : crée un user avec hash $1$ à partir d'un mot de passe en clair. */
  public void addWithPlainPassword(String name, String plainPassword, boolean enabled, boolean admin, int maxConnections) {
    String hash = com.bowman.cspng.users.crypto.MD5Crypt.crypt(plainPassword, "salt1234");
    UserRecord rec = new UserRecord(name, name, hash, enabled, admin, java.util.Set.of("default"), maxConnections);
    add(rec);
  }

  @Override
  public Future<Optional<UserRecord>> lookup(String name) {
    if (name == null) return Future.succeededFuture(Optional.empty());
    UserRecord rec = users.get(key(name));
    return Future.succeededFuture(Optional.ofNullable(rec));
  }

  @Override
  public UserRecord lookupCached(String name) {
    if (name == null) return null;
    return users.get(key(name));
  }

  @Override
  public Future<Boolean> authenticate(String name, String pass) {
    if (name == null || pass == null) return Future.succeededFuture(false);
    UserRecord rec = users.get(key(name));
    if (rec == null) return Future.succeededFuture(false);
    if (!rec.enabled()) return Future.succeededFuture(false);
    Instant now = Instant.now();
    if (!rec.isEnabledEffective(now)) return Future.succeededFuture(false);
    // Offload bcrypt si Vertx disponible et hash bcrypt
    String hash = rec.passwordHash();
    boolean isBcrypt = hash != null && (hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$"));
    if (isBcrypt && vertx != null) {
      return vertx.executeBlocking(() -> PasswordVerifier.verify(pass, hash), false);
    }
    // Sinon synchrone (tests ou MD5)
    boolean ok = PasswordVerifier.verify(pass, hash);
    return Future.succeededFuture(ok);
  }

  @Override
  public Future<Void> reload() {
    return Future.succeededFuture();
  }
}
