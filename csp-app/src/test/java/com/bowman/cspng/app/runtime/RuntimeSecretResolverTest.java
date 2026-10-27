package com.bowman.cspng.app.runtime;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeSecretResolverTest {

  @Test
  void envValid() {
    var resolver = new RuntimeSecretResolver(name -> "mySecret", List.of());
    assertEquals("mySecret", resolver.resolve("env:MY_VAR", "ctx"));
  }

  @Test
  void envMissing() {
    var resolver = new RuntimeSecretResolver(name -> null, List.of());
    var ex = assertThrows(RuntimeSecretResolver.SecretResolutionException.class, () -> resolver.resolve("env:MISSING", "ctx"));
    assertTrue(ex.getMessage().contains("env"));
    assertFalse(ex.getMessage().contains("mySecret"));
  }

  @Test
  void envBlank() {
    var resolver = new RuntimeSecretResolver(name -> "   ", List.of());
    assertThrows(RuntimeSecretResolver.SecretResolutionException.class, () -> resolver.resolve("env:BLANK", "ctx"));
  }

  @Test
  void envOversize() {
    String big = "a".repeat(70_000);
    var resolver = new RuntimeSecretResolver(name -> big, List.of());
    assertThrows(RuntimeSecretResolver.SecretResolutionException.class, () -> resolver.resolve("env:BIG", "ctx"));
  }

  @Test
  void envInvalidName() {
    var resolver = new RuntimeSecretResolver(name -> "x", List.of());
    assertThrows(RuntimeSecretResolver.SecretResolutionException.class, () -> resolver.resolve("env:123bad", "ctx"));
  }

  private void setPerm0600IfPosix(Path p) throws Exception {
    try {
      var view = Files.getFileAttributeView(p, java.nio.file.attribute.PosixFileAttributeView.class);
      if (view != null) {
        view.setPermissions(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
      }
    } catch (Exception ignored) {}
  }
  @Test
  void fileValidAndNewlineTrim(@TempDir Path tmp) throws Exception {
    Path root = tmp.resolve("secrets");
    Files.createDirectories(root);
    Path file = root.resolve("my.key");
    Files.writeString(file, "secret123\n", StandardCharsets.UTF_8);
    setPerm0600IfPosix(file);
    // also ensure root perms are restrictive? root perms not checked, only file
    var resolver = new RuntimeSecretResolver(name -> null, List.of(root));
    assertEquals("secret123", resolver.resolve("file:" + file.toString(), "ctx"));
  }
  @Test
  void posixPermissionsCheck(@TempDir Path tmp) throws Exception {
    Path root = tmp.resolve("secrets");
    Files.createDirectories(root);
    var viewTest = Files.getFileAttributeView(root, java.nio.file.attribute.PosixFileAttributeView.class);
    if (viewTest == null) return; // skip on non-POSIX
    Path fileOk = root.resolve("ok");
    Files.writeString(fileOk, "ok");
    Files.setPosixFilePermissions(fileOk, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    var resolverOk = new RuntimeSecretResolver(name -> null, List.of(root));
    assertEquals("ok", resolverOk.resolve("file:" + fileOk, "ctx"));
    Path fileBad = root.resolve("bad");
    Files.writeString(fileBad, "bad");
    Files.setPosixFilePermissions(fileBad, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ));
    var resolverBad = new RuntimeSecretResolver(name -> null, List.of(root));
    var ex = assertThrows(RuntimeSecretResolver.SecretResolutionException.class, () -> resolverBad.resolve("file:" + fileBad, "ctx"));
    assertTrue(ex.getMessage().contains("permissions"));
    assertFalse(ex.getMessage().contains("bad"));
  }

  @Test
  void fileEmpty(@TempDir Path tmp) throws Exception {
    Path root = tmp.resolve("secrets");
    Files.createDirectories(root);
    Path file = root.resolve("empty");
    Files.writeString(file, "\n");
    setPerm0600IfPosix(file);
    var resolver = new RuntimeSecretResolver(name -> null, List.of(root));
    assertThrows(RuntimeSecretResolver.SecretResolutionException.class, () -> resolver.resolve("file:" + file, "ctx"));
  }

  @Test
  void fileOversize(@TempDir Path tmp) throws Exception {
    Path root = tmp.resolve("secrets");
    Files.createDirectories(root);
    Path file = root.resolve("big");
    byte[] big = new byte[70_000];
    java.util.Arrays.fill(big, (byte)'a');
    Files.write(file, big);
    setPerm0600IfPosix(file);
    var resolver = new RuntimeSecretResolver(name -> null, List.of(root));
    assertThrows(RuntimeSecretResolver.SecretResolutionException.class, () -> resolver.resolve("file:" + file, "ctx"));
  }

  @Test
  void fileTraversalBlocked(@TempDir Path tmp) throws Exception {
    Path root = tmp.resolve("secrets");
    Files.createDirectories(root);
    Path file = root.resolve("good");
    Files.writeString(file, "ok");
    var resolver = new RuntimeSecretResolver(name -> null, List.of(root));
    String traversal = root.resolve("../secrets/good").toString(); // contains ..
    // Our resolver checks .. segment
    // Use path with .. segment explicitly
    Path withDotDot = Path.of(root.toString() + "/../secrets/good");
    assertThrows(RuntimeSecretResolver.SecretResolutionException.class, () -> resolver.resolve("file:" + withDotDot, "ctx"));
  }

  @Test
  void fileSymlinkEscape(@TempDir Path tmp) throws Exception {
    Path root = tmp.resolve("secrets");
    Files.createDirectories(root);
    Path outside = tmp.resolve("outside");
    Files.createDirectories(outside);
    Path secret = outside.resolve("secret");
    Files.writeString(secret, "outside");
    Path link = root.resolve("link");
    Files.createSymbolicLink(link, secret);
    var resolver = new RuntimeSecretResolver(name -> null, List.of(root));
    assertThrows(RuntimeSecretResolver.SecretResolutionException.class, () -> resolver.resolve("file:" + link, "ctx"));
  }

  @Test
  void fileRootMissingFail(@TempDir Path tmp) throws Exception {
    Path root = tmp.resolve("nonexist");
    // not created
    Path file = tmp.resolve("file");
    Files.writeString(file, "x");
    var resolver = new RuntimeSecretResolver(name -> null, List.of(root));
    assertThrows(RuntimeSecretResolver.SecretResolutionException.class, () -> resolver.resolve("file:" + file, "ctx"));
  }

  @Test
  void fileEventLoopSyncFailAsyncSucceeds(@TempDir Path tmp) throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      Path root = tmp.resolve("secrets");
      Files.createDirectories(root);
      Path file = root.resolve("k");
      Files.writeString(file, "hello");
      setPerm0600IfPosix(file);
      var resolver = new RuntimeSecretResolver(vertx, name -> null, List.of(root));
      // sync on event-loop should fail
      CountDownLatch latch = new CountDownLatch(1);
      Exception[] ex = new Exception[1];
      vertx.runOnContext(v -> {
        try { resolver.resolve("file:" + file, "ctx"); } catch (Exception e) { ex[0]=e; } latch.countDown();
      });
      assertTrue(latch.await(2, TimeUnit.SECONDS));
      assertNotNull(ex[0]);
      assertTrue(ex[0].getMessage().contains("event-loop"));
      // async should succeed via executeBlocking
      CountDownLatch latch2 = new CountDownLatch(1);
      String[] result = new String[1];
      resolver.resolveAsync("file:" + file, "ctx").onComplete(ar -> {
        if (ar.succeeded()) result[0]=ar.result(); latch2.countDown();
      });
      assertTrue(latch2.await(2, TimeUnit.SECONDS));
      assertEquals("hello", result[0]);
    } finally { vertx.close(); }
  }

  @Test
  void vaultFailFast() {
    var resolver = new RuntimeSecretResolver(name -> null, List.of());
    var ex = assertThrows(RuntimeSecretResolver.SecretResolutionException.class, () -> resolver.resolve("vault:secret/foo", "ctx"));
    assertTrue(ex.getMessage().contains("vault"));
  }

  @Test
  void desValidSeparators() {
    String hex = "01 02:03 04:05 06 07 08 09 0A 0B 0C 0D 0E";
    byte[] out = RuntimeSecretResolver.parseDesKey14(hex, "ctx");
    assertEquals(14, out.length);
    assertEquals(0x01, out[0] & 0xFF);
    assertEquals(0x0E, out[13] & 0xFF);
  }

  @Test
  void desInvalidLength() {
    assertThrows(RuntimeSecretResolver.SecretResolutionException.class, () -> RuntimeSecretResolver.parseDesKey14("010203", "ctx"));
  }

  @Test
  void desInvalidChar() {
    assertThrows(RuntimeSecretResolver.SecretResolutionException.class, () -> RuntimeSecretResolver.parseDesKey14("01 ZZ 03 04 05 06 07 08 09 0A 0B 0C 0D 0E", "ctx"));
  }

  @Test
  void fileRaceSwapBeforeOpenFails(@TempDir Path tmp) throws Exception {
    Path root = tmp.resolve("secrets");
    Files.createDirectories(root);
    Path file = root.resolve("race");
    Files.writeString(file, "good");
    setPerm0600IfPosix(file);
    Path outside = tmp.resolve("outside2");
    Files.createDirectories(outside);
    Path evil = outside.resolve("evil");
    Files.writeString(evil, "evil");
    var resolver = new RuntimeSecretResolver(name -> null, List.of(root));
    resolver.beforeOpenHook = () -> {
      try {
        Files.deleteIfExists(file);
        Files.createSymbolicLink(file, evil);
      } catch (Exception e) { throw new RuntimeException(e); }
    };
    var ex = assertThrows(RuntimeSecretResolver.SecretResolutionException.class, () -> resolver.resolve("file:" + file, "ctx"));
    assertFalse(ex.getMessage().contains("evil"));
    assertFalse(ex.getMessage().contains("good"));
  }

  @Test
  void noSecretInException() {
    var resolver = new RuntimeSecretResolver(name -> "GOOD".equals(name) ? "supersecretvalue" : null, List.of());
    try { resolver.resolve("env:BAD", "myCtx"); fail(); } catch (RuntimeSecretResolver.SecretResolutionException e) {
      assertFalse(e.getMessage().contains("supersecretvalue"));
      assertTrue(e.getMessage().contains("env"));
    }
  }
}
