package com.bowman.cspng.app.runtime;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Résolveur de secrets runtime — env: / file: / vault:.
 *
 * <p>Contraintes :
 * <ul>
 *   <li>env:NAME → lecture env, non-blank, max 64K</li>
 *   <li>file:/abs → allowlist racines {@code csp.secrets.allowed-file-roots}, realpath sous racine
 *       après résolution symlinks, fichier régulier, max 64K, lecture hors event-loop,
 *       trim d'un unique newline terminal seulement.</li>
 *   <li>vault: → fail-fast unsupported, jamais fallback silencieux</li>
 *   <li>jamais log value ; exceptions nomment seulement type et contexte</li>
 *   <li>DES key 14 bytes depuis hex espaces/":" ; wipe buffers temporaires</li>
 * </ul>
 */
@ApplicationScoped
public class RuntimeSecretResolver {

  private static final Logger LOG = Logger.getLogger(RuntimeSecretResolver.class.getName());
  public static final int MAX_SECRET_BYTES = 64 * 1024;

  private final Vertx vertx;
  private final EnvProvider envProvider;
  private final List<Path> allowedRoots;
  // Hook for tests to simulate TOCTOU race before open (package-private)
  Runnable beforeOpenHook;

  // CDI constructor
  @Inject
  public RuntimeSecretResolver(Vertx vertx,
                              @ConfigProperty(name = "csp.secrets.allowed-file-roots",
                                  defaultValue = "/run/secrets,/etc/csp/secrets") String allowedRootsStr) {
    this(vertx, System::getenv, parseRoots(allowedRootsStr));
  }

  // Test constructor with injectable env + roots
  public RuntimeSecretResolver(Vertx vertx, EnvProvider envProvider, List<Path> allowedRoots) {
    this.vertx = vertx;
    this.envProvider = envProvider != null ? envProvider : System::getenv;
    this.allowedRoots = allowedRoots != null ? List.copyOf(allowedRoots) : List.of();
  }

  // Alternative test constructor without Vertx (sync tests)
  public RuntimeSecretResolver(EnvProvider envProvider, List<Path> allowedRoots) {
    this(null, envProvider, allowedRoots);
  }

  public interface EnvProvider {
    String getEnv(String name);
  }

  public static class SecretResolutionException extends RuntimeException {
    public SecretResolutionException(String message) { super(message); }
    public SecretResolutionException(String message, Throwable cause) { super(message, cause); }
  }

  // -- public API --

  /**
   * Résout une référence secrète de manière synchrone (ne pas appeler sur event-loop pour file:).
   * @param ref référence canonique env: / file: / vault:
   * @param context contexte pour message d'erreur (ex: "ExtendedNewcamd desKey")
   * @return valeur résolue (trim newline terminal pour file)
   */
  public String resolve(String ref, String context) {
    if (ref == null || ref.isBlank()) throw new SecretResolutionException(context + ": secret ref blank (type missing)");
    String r = ref.trim();
    if (r.startsWith("vault:")) {
      throw new SecretResolutionException(context + ": vault: unsupported (ref type vault)");
    }
    if (r.startsWith("env:")) {
      return resolveEnv(r.substring(4), context);
    }
    if (r.startsWith("file:")) {
      if (Context.isOnEventLoopThread()) {
        throw new SecretResolutionException(context + ": file secret resolve must not run on event-loop (ref type file)");
      }
      return resolveFile(r.substring(5), context);
    }
    // allow legacy ${env:NAME} etc? spec says secrets env/file seulement, vault fail-fast
    // but ConfigValidator also accepts legacy ${...}. For resolver we strictly support canonical.
    throw new SecretResolutionException(context + ": unsupported secret ref type (must be env: or file:) for " + safeRefType(r));
  }

  /** Overload sans contexte explicite. */
  public String resolve(String ref) { return resolve(ref, "secret"); }

  /** Async variant using executeBlocking for file:. */
  public Future<String> resolveAsync(String ref, String context) {
    if (ref == null) return Future.failedFuture(new SecretResolutionException(context + ": secret ref null"));
    String r = ref.trim();
    if (r.startsWith("file:")) {
      if (vertx == null) {
        // fallback sync
        try { return Future.succeededFuture(resolveFile(r.substring(5), context)); }
        catch (Exception e) { return Future.failedFuture(e); }
      }
      Promise<String> p = Promise.promise();
      vertx.executeBlocking(() -> resolveFile(r.substring(5), context), false)
          .onComplete(ar -> {
            if (ar.succeeded()) p.complete(ar.result());
            else p.fail(ar.cause());
          });
      return p.future();
    }
    try { return Future.succeededFuture(resolve(ref, context)); }
    catch (Exception e) { return Future.failedFuture(e); }
  }

  /** Résout et parse une clé DES 14 bytes depuis hex (espaces/":" tolérés). */
  public byte[] resolveDesKey(String ref, String context) {
    String text = resolve(ref, context);
    try {
      return parseDesKey14(text, context);
    } finally {
      // wipe text char array? String immutable — we rely on caller to not log.
      // We zero temporary char[] inside parse method.
    }
  }

  public Future<byte[]> resolveDesKeyAsync(String ref, String context) {
    return resolveAsync(ref, context).map(text -> parseDesKey14(text, context));
  }

  /** Helper pour futur connector password (texte brut, max64K). */
  public String resolvePassword(String ref, String context) {
    return resolve(ref, context);
  }

  public Future<String> resolvePasswordAsync(String ref, String context) {
    return resolveAsync(ref, context);
  }

  // -- env --

  private String resolveEnv(String name, String context) {
    if (name == null || name.isBlank()) {
      throw new SecretResolutionException(context + ": env: name blank (ref type env)");
    }
    String n = name.trim();
    if (!n.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      throw new SecretResolutionException(context + ": env: invalid name (ref type env)");
    }
    String val = envProvider.getEnv(n);
    if (val == null) throw new SecretResolutionException(context + ": env: not found (ref type env)");
    if (val.isBlank()) throw new SecretResolutionException(context + ": env: blank value (ref type env)");
    if (val.getBytes(StandardCharsets.UTF_8).length > MAX_SECRET_BYTES) {
      throw new SecretResolutionException(context + ": env: value exceeds max 64K (ref type env)");
    }
    return val;
  }

  // -- file --

  private String resolveFile(String pathStr, String context) {
    if (pathStr == null || pathStr.isBlank()) throw new SecretResolutionException(context + ": file: path blank (ref type file)");
    if (pathStr.contains("\0")) throw new SecretResolutionException(context + ": file: NUL in path (ref type file)");
    Path p;
    try { p = Paths.get(pathStr); } catch (Exception e) { throw new SecretResolutionException(context + ": file: invalid path (ref type file)", e); }
    if (!p.isAbsolute()) throw new SecretResolutionException(context + ": file: not absolute (ref type file)");
    // normalize but not yet realpath; check segments ..
    for (Path part : p) {
      if ("..".equals(part.toString())) throw new SecretResolutionException(context + ": file: .. segment not allowed (ref type file)");
    }
    // Resolve realpath - single follow (resolves symlinks, prevents escape)
    Path real;
    try {
      real = p.toRealPath();
    } catch (IOException e) {
      throw new SecretResolutionException(context + ": file: cannot resolve realpath (ref type file)", e);
    }
    // Allowlist check: real path must be under one of allowed roots (realpath of root)
    boolean allowed = false;
    for (Path root : allowedRoots) {
      try {
        Path realRoot = root.toRealPath();
        if (real.startsWith(realRoot)) { allowed = true; break; }
      } catch (IOException e) {
        // root must exist/be realpath — ignore this root (fail-closed, no fallback normalize)
      }
    }
    if (!allowed) {
      throw new SecretResolutionException(context + ": file: path not under allowed roots (ref type file)");
    }
    // Hook for TOCTOU test
    if (beforeOpenHook != null) {
      try { beforeOpenHook.run(); } catch (Exception ignored) {}
    }
    // POSIX permission check (if supported): reject group/other READ/WRITE/EXECUTE
    // If view exists but readAttributes fails → fail-closed (only view==null means non-POSIX skip)
    PosixFileAttributeView view = Files.getFileAttributeView(real, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (view != null) {
      try {
        Set<PosixFilePermission> perms = view.readAttributes().permissions();
        boolean bad = perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.GROUP_WRITE) || perms.contains(PosixFilePermission.GROUP_EXECUTE)
            || perms.contains(PosixFilePermission.OTHERS_READ) || perms.contains(PosixFilePermission.OTHERS_WRITE) || perms.contains(PosixFilePermission.OTHERS_EXECUTE);
        if (bad) {
          throw new SecretResolutionException(context + ": file: permissions too permissive (ref type file)");
        }
      } catch (IOException e) {
        throw new SecretResolutionException(context + ": file: POSIX check failed (ref type file)", e);
      }
    }
    // Open without following symlink (TOCTOU-safe) and read via descriptor
    try (InputStream in = Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS)) {
      // Verify regular file via channel if possible
      // We already ensured not symlink via NOFOLLOW, but double-check via attributes
      var attrs = Files.readAttributes(real, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attrs.isRegularFile()) {
        throw new SecretResolutionException(context + ": file: not regular file (ref type file)");
      }
      // Read at most MAX+1 to detect oversize
      byte[] buf = in.readNBytes(MAX_SECRET_BYTES + 1);
      if (buf.length == 0) throw new SecretResolutionException(context + ": file: empty (ref type file)");
      if (buf.length > MAX_SECRET_BYTES) {
        Arrays.fill(buf, (byte) 0);
        throw new SecretResolutionException(context + ": file: exceeds max 64K (ref type file)");
      }
      try {
        String content = new String(buf, StandardCharsets.UTF_8);
        if (content.endsWith("\r\n")) content = content.substring(0, content.length() - 2);
        else if (content.endsWith("\n")) content = content.substring(0, content.length() - 1);
        if (content.isEmpty()) throw new SecretResolutionException(context + ": file: empty after trim (ref type file)");
        return content;
      } finally {
        Arrays.fill(buf, (byte) 0);
      }
    } catch (SecretResolutionException e) { throw e; }
    catch (IOException e) { throw new SecretResolutionException(context + ": file: read failed (ref type file)", e); }
  }

  // -- DES key parse --

  /**
   * Parse DES key exactement 14 bytes depuis hex avec espaces/":" .
   * Wipe buffers temporaires.
   */
  public static byte[] parseDesKey14(String text, String context) {
    if (text == null) throw new SecretResolutionException(context + ": DES key null (ref type des)");
    String t = text.trim();
    if (t.isEmpty()) throw new SecretResolutionException(context + ": DES key blank (ref type des)");
    // Remove spaces, colons, also allow newline? spec says spaces/":"
    // Keep only hex chars
    StringBuilder hexOnly = new StringBuilder();
    char[] chars = t.toCharArray();
    try {
      for (char c : chars) {
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == ':') continue;
        if (isHex(c)) hexOnly.append(c);
        else throw new SecretResolutionException(context + ": DES key invalid char (ref type des)");
      }
      String hex = hexOnly.toString();
      if (hex.length() != 28) {
        throw new SecretResolutionException(context + ": DES key must be exactly 14 bytes (28 hex chars) got " + hex.length()/2 + " bytes (ref type des)");
      }
      byte[] out = new byte[14];
      char[] hexChars = hex.toCharArray();
      try {
        for (int i = 0; i < 14; i++) {
          int hi = Character.digit(hexChars[i*2], 16);
          int lo = Character.digit(hexChars[i*2+1], 16);
          if (hi < 0 || lo < 0) {
            Arrays.fill(out, (byte)0);
            throw new SecretResolutionException(context + ": DES key invalid hex (ref type des)");
          }
          out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
      } finally {
        Arrays.fill(hexChars, '\0');
        // out returned directly — no extra copy to wipe
      }
    } finally {
      Arrays.fill(chars, '\0');
      // wipe hexOnly buffer
      for (int i = 0; i < hexOnly.length(); i++) hexOnly.setCharAt(i, '\0');
    }
  }

  private static boolean isHex(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private static String safeRefType(String ref) {
    if (ref == null) return "null";
    if (ref.startsWith("vault:")) return "vault";
    if (ref.startsWith("env:")) return "env";
    if (ref.startsWith("file:")) return "file";
    if (ref.startsWith("${")) return "legacy";
    int colon = ref.indexOf(':');
    if (colon > 0) return ref.substring(0, colon);
    return "unknown";
  }

  private static List<Path> parseRoots(String csv) {
    if (csv == null || csv.isBlank()) return List.of();
    List<Path> list = new ArrayList<>();
    for (String part : csv.split(",")) {
      String s = part.trim();
      if (!s.isEmpty()) {
        try { list.add(Paths.get(s)); } catch (Exception e) { LOG.warning("Invalid allowed root: " + s); }
      }
    }
    return list;
  }
}
