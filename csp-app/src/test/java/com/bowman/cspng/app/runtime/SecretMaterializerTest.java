package com.bowman.cspng.app.runtime;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SecretMaterializerTest {

  @Test
  void materializePasswordSuccessAndPermissions(@TempDir Path tmp) throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      SecretMaterializer sm = new SecretMaterializer(vertx, tmp);
      var fut = sm.materializePassword("MyConnector-01", "s3cr3tPass");
      String ref = fut.toCompletionStage().toCompletableFuture().get();
      assertTrue(ref.startsWith("file:"));
      Path file = Path.of(ref.substring(5));
      assertTrue(Files.exists(file));
      assertEquals("s3cr3tPass", Files.readString(file).trim());
      // Check permissions 0600 on POSIX
      try {
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
        assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
        assertFalse(perms.contains(PosixFilePermission.GROUP_READ));
        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
      } catch (UnsupportedOperationException ignored) {}
      // Directory 0700
      try {
        Set<PosixFilePermission> dirPerms = Files.getPosixFilePermissions(tmp);
        assertTrue(dirPerms.contains(PosixFilePermission.OWNER_READ));
        assertTrue(dirPerms.contains(PosixFilePermission.OWNER_WRITE));
        assertTrue(dirPerms.contains(PosixFilePermission.OWNER_EXECUTE));
      } catch (UnsupportedOperationException ignored) {}
    } finally {
      vertx.close().toCompletionStage().toCompletableFuture().get();
    }
  }

  @Test
  void materializeDesKeySuccess(@TempDir Path tmp) throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      SecretMaterializer sm = new SecretMaterializer(vertx, tmp);
      byte[] key = new byte[14];
      for (int i = 0; i < 14; i++) key[i] = (byte) i;
      var fut = sm.materializeDesKey("Conn", key);
      String ref = fut.toCompletionStage().toCompletableFuture().get();
      assertTrue(ref.endsWith(".des"));
      Path file = Path.of(ref.substring(5));
      String content = Files.readString(file).trim();
      // Should be hex spaced
      assertTrue(content.contains("00"));
      assertTrue(content.contains("0D"));
    } finally {
      vertx.close().toCompletionStage().toCompletableFuture().get();
    }
  }

  @Test
  void invalidConnectorNameProducesFailure(@TempDir Path tmp) {
    SecretMaterializer sm = new SecretMaterializer(null, tmp);
    var fut = sm.materializePassword("---", "pass");
    assertTrue(fut.failed());
    assertTrue(fut.cause().getMessage().contains("empty slug") || fut.cause().getMessage().contains("blank"));

    var fut2 = sm.materializePassword("", "pass");
    assertTrue(fut2.failed());
  }

  @Test
  void cleanupDeletesFiles(@TempDir Path tmp) throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      SecretMaterializer sm = new SecretMaterializer(vertx, tmp);
      sm.materializePassword("TestConn", "pwd").toCompletionStage().toCompletableFuture().get();
      sm.materializeDesKey("TestConn", new byte[14]).toCompletionStage().toCompletableFuture().get();
      var slugs = SecretMaterializer.normalizeSlug("TestConn");
      Path pwd = tmp.resolve("csp-connector-" + slugs + ".pwd");
      Path des = tmp.resolve("csp-connector-" + slugs + ".des");
      assertTrue(Files.exists(pwd));
      assertTrue(Files.exists(des));
      sm.cleanup("TestConn").toCompletionStage().toCompletableFuture().get();
      assertFalse(Files.exists(pwd));
      assertFalse(Files.exists(des));
      // Second cleanup silent
      sm.cleanup("TestConn").toCompletionStage().toCompletableFuture().get();
    } finally {
      vertx.close().toCompletionStage().toCompletableFuture().get();
    }
  }

  @Test
  void rootNotAccessibleFails(@TempDir Path tmp) {
    // Create a file where directory should be, to cause failure
    Path fileAsDir = tmp.resolve("fileAsDir");
    try { Files.writeString(fileAsDir, "blocking"); } catch (Exception e) { fail(e); }
    Path secretRoot = fileAsDir.resolve("subdir");
    SecretMaterializer sm = new SecretMaterializer(null, secretRoot);
    var fut = sm.materializePassword("Conn", "pass");
    assertTrue(fut.failed(), "Expected failure when secret root parent is a file");
    assertTrue(fut.cause().getMessage().contains("Failed") || fut.cause().getMessage().contains("secret directory"));
  }

  @Test
  void cdiConstructorFailsFastIfNotUnderAllowedRoots(@TempDir Path tmp) {
    Vertx vertx = Vertx.vertx();
    try {
      Path allowed = tmp.resolve("allowed");
      Path outside = tmp.resolve("outside").resolve("imported");
      assertThrows(IllegalStateException.class, () ->
          new SecretMaterializer(vertx, outside.toString(), allowed.toString()));
      // Valid subpath should not throw
      Path inside = allowed.resolve("imported");
      assertDoesNotThrow(() -> new SecretMaterializer(vertx, inside.toString(), allowed.toString()));
    } finally {
      vertx.close().toCompletionStage().toCompletableFuture().join();
    }
  }
}
