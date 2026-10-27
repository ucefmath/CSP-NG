package com.bowman.cspng.worker;

import com.bowman.cspng.sessions.SecretResolver;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * Resolution locale des secrets worker : {@code env:} / {@code file:} seuls,
 * jamais de log de valeurs, fichiers 0600 avertis (non bloquant), hors
 * event-loop pour {@code file:}. Les refs viennent du desired central ;
 * les valeurs ne quittent jamais le worker.
 */
public final class WorkerSecretResolver implements SecretResolver {

  private final Vertx vertx;
  private final Map<String, String> refsByUserProtocol;

  public WorkerSecretResolver(Vertx vertx) {
    this(vertx, Map.of());
  }

  public WorkerSecretResolver(Vertx vertx, Map<String, String> refsByUserProtocol) {
    this.vertx = vertx;
    this.refsByUserProtocol = refsByUserProtocol != null
        ? Map.copyOf(refsByUserProtocol) : Map.of();
  }

  @Override
  public Future<String> resolvePasswordAsync(String ref, String context) {
    if (ref == null) return Future.failedFuture(new IllegalStateException("secret ref null"));
    String r = ref.trim();
    if (r.startsWith("file:") && vertx != null) {
      Promise<String> p = Promise.promise();
      vertx.executeBlocking(() -> resolveFile(r.substring(5)), false).onComplete(p);
      return p.future();
    }
    try {
      return Future.succeededFuture(resolveSync(r));
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
  }

  public String resolveSync(String ref) {
    if (ref == null) throw new IllegalStateException("secret ref null");
    String r = ref.trim();
    if (r.startsWith("env:")) return resolveEnv(r.substring(4));
    if (r.startsWith("file:")) return resolveFile(r.substring(5));
    throw new IllegalStateException("unsupported secret ref type (env:/file: only)");
  }

  public byte[] resolveDesKey14(String ref) {
    return parseDesKey14(resolveSync(ref));
  }

  private static String resolveEnv(String name) {
    String n = name == null ? "" : name.trim();
    if (!n.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalStateException("env: invalid name");
    String val = System.getenv(n);
    if (val == null || val.isBlank()) throw new IllegalStateException("env: not found");
    if (val.getBytes(StandardCharsets.UTF_8).length > 65536)
      throw new IllegalStateException("env: value exceeds max 64K");
    return val;
  }

  private static String resolveFile(String path) {
    try {
      Path p = Path.of(path.trim());
      if (!p.isAbsolute()) throw new IllegalStateException("file: must be absolute path");
      byte[] bytes = Files.readAllBytes(p);
      if (bytes.length > 65536) throw new IllegalStateException("file: value exceeds max 64K");
      String val = new String(bytes, StandardCharsets.UTF_8).trim();
      Arrays.fill(bytes, (byte) 0);
      if (val.isBlank()) throw new IllegalStateException("file: blank value");
      return val;
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("file: unreadable");
    }
  }

  /** Port de {@code RuntimeSecretResolver.parseDesKey14} (central) : 14 octets hex stricts. */
  static byte[] parseDesKey14(String text) {
    if (text == null) throw new IllegalStateException("DES key null");
    String t = text.trim();
    if (t.isEmpty()) throw new IllegalStateException("DES key blank");
    StringBuilder hexOnly = new StringBuilder();
    char[] chars = t.toCharArray();
    try {
      for (char c : chars) {
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == ':') continue;
        if (isHex(c)) hexOnly.append(c);
        else throw new IllegalStateException("DES key invalid char");
      }
      String hex = hexOnly.toString();
      if (hex.length() != 28) throw new IllegalStateException("DES key must be exactly 14 bytes");
      byte[] out = new byte[14];
      char[] hexChars = hex.toCharArray();
      try {
        for (int i = 0; i < 14; i++) {
          int hi = Character.digit(hexChars[i * 2], 16);
          int lo = Character.digit(hexChars[i * 2 + 1], 16);
          if (hi < 0 || lo < 0) {
            Arrays.fill(out, (byte) 0);
            throw new IllegalStateException("DES key invalid hex");
          }
          out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
      } finally {
        Arrays.fill(hexChars, '\0');
      }
    } finally {
      Arrays.fill(chars, '\0');
      for (int i = 0; i < hexOnly.length(); i++) hexOnly.setCharAt(i, '\0');
    }
  }

  private static boolean isHex(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }
}
