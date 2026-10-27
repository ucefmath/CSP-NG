package com.bowman.cspng.app.runtime;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Matérialise des secrets en fichiers sous une racine dédiée.
 *
 * <p>Racine par défaut : {@code /etc/csp/secrets/imported} — doit être
 * incluse dans {@code csp.secrets.allowed-file-roots} (contrainte documentée).
 * Création du répertoire en 0700 au premier appel, fichiers en 0600 via
 * {@code vertx.executeBlocking} (non-bloquant event-loop).
 */
@ApplicationScoped
public class SecretMaterializer {

  private static final Logger LOG = Logger.getLogger(SecretMaterializer.class.getName());

  private static final Set<PosixFilePermission> DIR_0700 = EnumSet.of(
      PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
  private static final Set<PosixFilePermission> FILE_0600 = EnumSet.of(
      PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private final Vertx vertx;
  private final Path secretRoot;
  private final String secretRootStr;
  /** Racines autorisées résolues (R4, best-effort) — comparées aux chemins réels. */
  private final List<Path> allowedRealPaths;

  @Inject
  public SecretMaterializer(Vertx vertx,
                            @ConfigProperty(name = "csp.import.secret-root", defaultValue = "/etc/csp/secrets/imported") String secretRootStr,
                            @ConfigProperty(name = "csp.secrets.allowed-file-roots", defaultValue = "/run/secrets,/etc/csp/secrets") String allowedRootsStr) {
    this.vertx = vertx;
    this.secretRootStr = secretRootStr;
    this.secretRoot = Paths.get(secretRootStr);
    if (!isSubPathOfAllowed(secretRoot, allowedRootsStr)) {
      throw new IllegalStateException("csp.import.secret-root " + secretRootStr + " is not under csp.secrets.allowed-file-roots " + allowedRootsStr);
    }
    this.allowedRealPaths = resolveAllowedRealPaths(allowedRootsStr, secretRootStr);
  }

  // Test constructor
  public SecretMaterializer(Vertx vertx, Path secretRoot) {
    this.vertx = vertx;
    this.secretRoot = secretRoot;
    this.secretRootStr = secretRoot.toString();
    this.allowedRealPaths = resolveAllowedRealPaths(secretRoot.toString(), secretRootStr);
  }

  /** Résout chaque racine autorisée déclarée (jamais la racine elle-même en plus :
   * s'auto-autoriser neutraliserait la détection d'évasion par symlink — R4). */
  private static List<Path> resolveAllowedRealPaths(String allowedRootsCsv, String secretRootStr) {
    List<Path> out = new ArrayList<>();
    if (allowedRootsCsv != null) {
      for (String part : allowedRootsCsv.split(",")) {
        String s = part.trim();
        if (!s.isEmpty()) out.add(realpathOrNormalize(s));
      }
    }
    return List.copyOf(out);
  }

  public Future<String> materializePassword(String connectorName, String plainPassword) {
    String slug;
    try {
      slug = normalizeSlug(connectorName);
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
    if (plainPassword == null) {
      return Future.failedFuture(new IllegalArgumentException("plainPassword null for connector " + slug));
    }
    byte[] bytes = plainPassword.getBytes(StandardCharsets.UTF_8);
    String fileName = "csp-connector-" + slug + ".pwd";
    return writeSecretFile(fileName, bytes);
  }

  /**
   * Matérialise un secret dans un fichier NOUVEAU au nom opaque imprévisible.
   *
   * <p>Contrat v2 §1 (arbitrage sécurité) : AUCUN overwrite idempotent — un fichier
   * référencé en DB ne peut jamais être modifié par un appel ultérieur. Chaque appel
   * crée un fichier distinct :
   * <ul>
   *   <li>nom : {@code <prefix>-<ownerSlug>-<16 hex SecureRandom>.<ext>} — aucune collision
   *       prévisible, aucun overwrite possible ;</li>
   *   <li>création atomique {@code CREATE_NEW} + {@code NOFOLLOW_LINKS} (refus si symlink ou
   *       si le fichier existe déjà) ;</li>
   *   <li>permissions 0600, parent 0700 (POSIX), écriture hors event-loop ;</li>
   *   <li>racine sous {@code csp.secrets.allowed-file-roots} (contrainte constructeur).</li>
   * </ul>
   *
   * @param prefix préfixe du nom de fichier (ex. "csp-connector")
   * @param owner  propriétaire lisible (slug-normalisé ; jamais la valeur du secret)
   * @param ext    extension ("pwd" / "des")
   * @param content contenu binaire du secret (jamais loggé)
   * @return référence {@code file:/abs/path} du fichier créé
   */
  public Future<String> materializeUnique(String prefix, String owner, String ext, byte[] content) {
    if (prefix == null || prefix.isBlank() || !prefix.matches("[a-z0-9-]{1,32}")) {
      return Future.failedFuture(new IllegalArgumentException("invalid materialize prefix"));
    }
    if (ext == null || !ext.matches("[a-z0-9]{1,8}")) {
      return Future.failedFuture(new IllegalArgumentException("invalid materialize extension"));
    }
    if (content == null || content.length == 0) {
      return Future.failedFuture(new IllegalArgumentException("materialize content empty"));
    }
    if (content.length > 64 * 1024) {
      return Future.failedFuture(new IllegalArgumentException("materialize content exceeds 64K"));
    }
    String slug;
    try {
      slug = normalizeSlug(owner);
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
    if (vertx != null) {
      Promise<String> p = Promise.promise();
      vertx.executeBlocking(() -> {
        // CREATE_NEW : collision (astronomiquement improbable) => nouveau tirage, 3 essais
        for (int attempt = 0; attempt < 3; attempt++) {
          String rand = randomToken();
          Path target = secretRoot.resolve(prefix + "-" + slug + "-" + rand + "." + ext);
          if (!target.normalize().startsWith(secretRoot.normalize())) {
            throw new IOException("target path escapes secret root");
          }
          try {
            doWriteNew(target, content);
            return "file:" + target.toAbsolutePath();
          } catch (java.nio.file.FileAlreadyExistsException alreadyExists) {
            // retry with a fresh random token
          }
        }
        throw new IOException("unique secret file creation failed after 3 attempts");
      }, false).onComplete(ar -> {
        if (ar.succeeded()) p.complete(ar.result());
        else p.fail(ar.cause());
      });
      return p.future();
    }
    try {
      String rand = randomToken();
      Path target = secretRoot.resolve(prefix + "-" + slug + "-" + rand + "." + ext);
      doWriteNew(target, content);
      return Future.succeededFuture("file:" + target.toAbsolutePath());
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
  }

  private static String randomToken() {
    byte[] raw = new byte[8];
    new java.security.SecureRandom().nextBytes(raw);
    StringBuilder sb = new StringBuilder(16);
    for (byte b : raw) {
      sb.append(String.format("%02x", b & 0xFF));
    }
    return sb.toString();
  }

  /**
   * Écriture d'un fichier NOUVEAU : répertoire 0700 (création si absent), refus symlink,
   * {@code CREATE_NEW} (jamais d'overwrite), chmod 0600.
   * Vérification symlink-aware (R4) : le parent réel (chaîne de parents résolue,
   * {@code toRealPath} — pas seulement {@code normalize()} lexical) doit être
   * exactement la racine réelle, elle-même sous une racine autorisée réelle.
   */
  private void doWriteNew(Path target, byte[] content) throws IOException {
    try {
      Files.createDirectories(secretRoot);
      try {
        Files.setPosixFilePermissions(secretRoot, DIR_0700);
      } catch (UnsupportedOperationException ignored) {
        // FS non POSIX (tests Windows) — ignoré
      }
    } catch (IOException e) {
      throw new IOException("Failed to create secret directory " + secretRoot + ": " + e.getMessage(), e);
    }
    if (!Files.isWritable(secretRoot)) {
      throw new IOException("Secret directory not writable: " + secretRoot);
    }
    verifyTargetInsideAllowedRoot(target);
    if (Files.isSymbolicLink(target)) {
      throw new IOException("Secret file is symlink (rejected): " + target);
    }
    try {
      Files.write(target, content,
          java.nio.file.StandardOpenOption.CREATE_NEW,
          java.nio.file.StandardOpenOption.WRITE,
          java.nio.file.LinkOption.NOFOLLOW_LINKS);
    } catch (java.nio.file.FileAlreadyExistsException e) {
      throw e;
    } catch (IOException e) {
      throw new IOException("Failed to create unique secret file: " + e.getMessage(), e);
    }
    try {
      Files.setPosixFilePermissions(target, FILE_0600);
    } catch (UnsupportedOperationException ignored) {
    }
  }

  public Future<String> materializeDesKey(String connectorName, byte[] desKey14) {
    String slug;
    try {
      slug = normalizeSlug(connectorName);
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
    if (desKey14 == null || desKey14.length != 14) {
      return Future.failedFuture(new IllegalArgumentException("desKey14 must be 14 bytes for connector " + slug));
    }
    // Encode as hex spaced (consistent with RuntimeSecretResolver parse ability)
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < desKey14.length; i++) {
      if (i > 0) sb.append(' ');
      sb.append(String.format("%02X", desKey14[i] & 0xFF));
    }
    byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
    String fileName = "csp-connector-" + slug + ".des";
    return writeSecretFile(fileName, bytes);
  }

  public Future<Void> cleanup(String connectorName) {
    String slug;
    try {
      slug = normalizeSlug(connectorName);
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
    Path pwd = secretRoot.resolve("csp-connector-" + slug + ".pwd");
    Path des = secretRoot.resolve("csp-connector-" + slug + ".des");
    if (vertx != null) {
      Promise<Void> p = Promise.promise();
      vertx.executeBlocking(() -> {
        deleteIfExists(pwd);
        deleteIfExists(des);
        return null;
      }, false).onComplete(ar -> {
        if (ar.succeeded()) p.complete();
        else p.fail(ar.cause());
      });
      return p.future();
    } else {
      try {
        deleteIfExists(pwd);
        deleteIfExists(des);
        return Future.succeededFuture();
      } catch (Exception e) {
        return Future.failedFuture(e);
      }
    }
  }

  private Future<String> writeSecretFile(String fileName, byte[] content) {
    Path target = secretRoot.resolve(fileName);
    // Ensure target stays under secretRoot (prevent traversal via slug — already normalized)
    if (!target.normalize().startsWith(secretRoot.normalize())) {
      return Future.failedFuture(new IllegalArgumentException("target path escapes secret root: " + fileName));
    }

    if (vertx != null) {
      Promise<String> p = Promise.promise();
      vertx.executeBlocking(() -> {
        doWrite(target, content);
        return "file:" + target.toAbsolutePath().toString();
      }, false).onComplete(ar -> {
        if (ar.succeeded()) p.complete(ar.result());
        else p.fail(ar.cause());
      });
      return p.future();
    } else {
      try {
        doWrite(target, content);
        return Future.succeededFuture("file:" + target.toAbsolutePath().toString());
      } catch (Exception e) {
        return Future.failedFuture(e);
      }
    }
  }

  private void doWrite(Path target, byte[] content) throws IOException {
    // Create directory with 0700 if needed
    try {
      Files.createDirectories(secretRoot);
      // Enforce 0700 (POSIX)
      try {
        Files.setPosixFilePermissions(secretRoot, DIR_0700);
      } catch (UnsupportedOperationException ignored) {
        // Non-POSIX filesystem (Windows) — ignore
      }
    } catch (IOException e) {
      throw new IOException("Failed to create secret directory " + secretRoot + ": " + e.getMessage(), e);
    }

    // Verify directory is writable
    if (!Files.isWritable(secretRoot)) {
      throw new IOException("Secret directory not writable: " + secretRoot);
    }

    // TOCTOU-safe: refuse if target is already a symlink BEFORE writing
    if (Files.isSymbolicLink(target)) {
      throw new IOException("Secret file is symlink (rejected): " + target);
    }

    Files.write(target, content, java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
        java.nio.file.StandardOpenOption.WRITE,
        java.nio.file.LinkOption.NOFOLLOW_LINKS);

    // Set permissions 0600
    try {
      Files.setPosixFilePermissions(target, FILE_0600);
    } catch (UnsupportedOperationException ignored) {
    }
  }

  private void deleteIfExists(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException ignored) {
      // Silent if absent; log if cannot delete?
      LOG.fine("Failed to delete secret file " + p + ": " + ignored.getMessage());
    }
  }

  public static String normalizeSlug(String connectorName) {
    if (connectorName == null || connectorName.isBlank()) {
      throw new IllegalArgumentException("connectorName blank");
    }
    String lower = connectorName.toLowerCase(java.util.Locale.ROOT);
    StringBuilder sb = new StringBuilder();
    for (char c : lower.toCharArray()) {
      if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_') {
        sb.append(c);
      } else if (c == ' ' || c == '.' || c == '/') {
        sb.append('-');
      } else {
        sb.append('-');
      }
    }
    // Collapse consecutive hyphens? Keep simple, just replace invalid with hyphen
    String slug = sb.toString();
    // Trim leading/trailing hyphens/underscores
    slug = slug.replaceAll("^[-_]+", "").replaceAll("[-_]+$", "");
    // Collapse multiple hyphens
    slug = slug.replaceAll("-{2,}", "-");
    // Remove characters not in [a-z0-9-_]? Already done, but ensure
    slug = slug.replaceAll("[^a-z0-9-_]", "");
    if (slug.isEmpty()) {
      throw new IllegalArgumentException("connectorName produces empty slug after normalization: " + connectorName);
    }
    if (slug.length() > 64) slug = slug.substring(0, 64);
    // Final trim again after truncation
    slug = slug.replaceAll("[-_]+$", "");
    if (slug.isEmpty()) throw new IllegalArgumentException("slug truncated to empty");
    return slug;
  }

  private static boolean isSubPathOfAllowed(Path secretRoot, String allowedRootsCsv) {
    if (allowedRootsCsv == null || allowedRootsCsv.isBlank()) return false;
    Path normalizedRoot = secretRoot.toAbsolutePath().normalize();
    for (String part : allowedRootsCsv.split(",")) {
      String s = part.trim();
      if (s.isEmpty()) continue;
      try {
        Path allowed = Paths.get(s).toAbsolutePath().normalize();
        if (normalizedRoot.startsWith(allowed)) return true;
      } catch (Exception ignored) {}
    }
    return false;
  }

  /**
   * Vérifie symlink-aware (R4) que {@code target} est DIRECTEMENT dans la racine
   * des secrets, chaîne de parents résolue ({@code toRealPath}, hors boucle) :
   * un lien symbolique dans un parent (racine elle-même, ou ancêtre) redirigeant
   * hors des racines autorisées est REFUSÉ — ce que {@code normalize()} lexical
   * seul ne détecte pas. Les racines autorisées sont elles aussi résolues en
   * chemins réels (best-effort : repli lexical si inexistantes).
   */
  private void verifyTargetInsideAllowedRoot(Path target) throws IOException {
    Path parent = target.toAbsolutePath().getParent();
    if (parent == null) throw new IOException("Secret target has no parent: " + target);
    Path parentReal;
    try {
      parentReal = parent.toRealPath();
    } catch (IOException e) {
      throw new IOException("Secret parent not resolvable (possible symlink loop): "
          + parent + ": " + e.getMessage(), e);
    }
    Path rootReal;
    try {
      rootReal = secretRoot.toAbsolutePath().normalize().toRealPath();
    } catch (IOException e) {
      throw new IOException("Secret root not resolvable: " + secretRoot, e);
    }
    if (!parentReal.equals(rootReal)) {
      throw new IOException("Secret target escapes secret root (symlink parent?): " + target);
    }
    for (Path allowedReal : allowedRealPaths) {
      if (rootReal.startsWith(allowedReal)) return;
    }
    throw new IOException("Secret root escapes allowed roots (symlink?): " + secretRoot);
  }

  /** Résout best-effort en chemin réel (repli lexical si inexistant). */
  private static Path realpathOrNormalize(String p) {
    try {
      return Paths.get(p).toAbsolutePath().normalize().toRealPath();
    } catch (Exception e) {
      try {
        return Paths.get(p).toAbsolutePath().normalize();
      } catch (Exception ignored) {
        return Paths.get(p);
      }
    }
  }
}
