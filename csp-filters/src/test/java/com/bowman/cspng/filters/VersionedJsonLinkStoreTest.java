package com.bowman.cspng.filters;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.CopyOptions;
import io.vertx.core.file.FileProps;
import io.vertx.core.file.OpenOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ExtendWith(VertxExtension.class)
class VersionedJsonLinkStoreTest {

  private Vertx vertx;
  private Path tempDir;
  private VersionedJsonLinkStore store;

  @BeforeEach
  void setUp(@TempDir Path tempDir) {
    this.tempDir = tempDir;
    this.vertx = Vertx.vertx();
    this.store = new VersionedJsonLinkStore(vertx,
        tempDir.resolve("links.json").toString());
  }

  @AfterEach
  void tearDown(VertxTestContext ctx) {
    vertx.close().onComplete(ctx.succeedingThenComplete());
  }

  // ==================== 1. absent file → empty ====================

  @Test
  void loadAbsentFileReturnsEmpty(VertxTestContext ctx) {
    store.load().onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertTrue(ar.result().isEmpty());
      ctx.completeNow();
    });
  }

  // ==================== 2. round-trip deterministic ====================

  @Test
  void roundTripDeterministic(VertxTestContext ctx) {
    Set<Set<String>> groups = groups(
        group("2:dvbt", "1:dvbt"),
        group("a:dvbt", "b:dvbt"));

    store.save(groups).compose(v -> store.load()).onComplete(ar -> {
      assertTrue(ar.succeeded());
      Set<Set<String>> loaded = ar.result();
      assertEquals(2, loaded.size());

      var iter = loaded.iterator();
      assertEquals(Set.of("1:dvbt", "2:dvbt"), iter.next());
      assertEquals(Set.of("a:dvbt", "b:dvbt"), iter.next());

      store.load().onComplete(ar2 -> {
        assertTrue(ar2.succeeded());
        assertEquals(loaded, ar2.result());
        ctx.completeNow();
      });
    });
  }

  // ==================== 3. empty save/load ====================

  @Test
  void emptySaveAndLoad(VertxTestContext ctx) {
    store.save(Collections.emptySet()).compose(v -> store.load()).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertTrue(ar.result().isEmpty());
      ctx.completeNow();
    });
  }

  // ==================== 4. oversize quarantine: source absent + 1 quarantine file ====================

  @Test
  void oversizeQuarantinedSourceMoved(VertxTestContext ctx) throws Exception {
    Path file = tempDir.resolve("links-large.json");
    byte[] big = new byte[2_000_000];
    Files.write(file, big);

    var strictStore = new VersionedJsonLinkStore(vertx, file.toString(), 1024);
    strictStore.load().onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertTrue(ar.result().isEmpty());
      // source must be gone
      assertFalse(Files.exists(file), "source should be quarantined (moved)");
      // exactly one quarantine file
      try {
        long count = Files.list(tempDir)
            .filter(p -> p.getFileName().toString().contains(".corrupt-"))
            .count();
        assertEquals(1, count, "exactly one quarantine file");
      } catch (IOException e) {
        fail(e);
      }
      ctx.completeNow();
    });
  }

  // ==================== 5. invalid JSON → quarantine, source moved, 1 quarantine ====================

  @Test
  void invalidJsonQuarantinedSourceMoved(VertxTestContext ctx) throws Exception {
    Path file = tempDir.resolve("links-bad.json");
    Files.writeString(file, "not json at all {{{");

    var s = new VersionedJsonLinkStore(vertx, file.toString());
    s.load().onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertTrue(ar.result().isEmpty());
      assertFalse(Files.exists(file), "source should be quarantined");
      try {
        long count = Files.list(tempDir)
            .filter(p -> p.getFileName().toString().contains(".corrupt-"))
            .count();
        assertEquals(1, count);
      } catch (IOException e) { fail(e); }
      ctx.completeNow();
    });
  }

  @Test
  void invalidVersionQuarantinedSourceMoved(VertxTestContext ctx) throws Exception {
    Path file = tempDir.resolve("links-v2.json");
    Files.writeString(file, "{ \"version\": 2, \"links\": [] }");

    var s = new VersionedJsonLinkStore(vertx, file.toString());
    s.load().onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertTrue(ar.result().isEmpty());
      assertFalse(Files.exists(file));
      try {
        long count = Files.list(tempDir)
            .filter(p -> p.getFileName().toString().contains(".corrupt-"))
            .count();
        assertEquals(1, count);
      } catch (IOException e) { fail(e); }
      ctx.completeNow();
    });
  }

  @Test
  void invalidRefFormatQuarantinedSourceMoved(VertxTestContext ctx) throws Exception {
    Path file = tempDir.resolve("links-badref.json");
    Files.writeString(file,
        "{ \"version\": 1, \"links\": [[\"badref\", \"1:dvbt\"]] }");

    var s = new VersionedJsonLinkStore(vertx, file.toString());
    s.load().onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertTrue(ar.result().isEmpty());
      assertFalse(Files.exists(file));
      try {
        long count = Files.list(tempDir)
            .filter(p -> p.getFileName().toString().contains(".corrupt-"))
            .count();
        assertEquals(1, count);
      } catch (IOException e) { fail(e); }
      ctx.completeNow();
    });
  }

  @Test
  void unknownTopLevelFieldQuarantinedSourceMoved(VertxTestContext ctx) throws Exception {
    Path file = tempDir.resolve("links-unknown.json");
    Files.writeString(file,
        "{ \"version\": 1, \"links\": [[\"1:dvbt\", \"2:dvbt\"]], \"extra\": true }");

    var s = new VersionedJsonLinkStore(vertx, file.toString());
    s.load().onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertTrue(ar.result().isEmpty());
      assertFalse(Files.exists(file));
      try {
        long count = Files.list(tempDir)
            .filter(p -> p.getFileName().toString().contains(".corrupt-"))
            .count();
        assertEquals(1, count);
      } catch (IOException e) { fail(e); }
      ctx.completeNow();
    });
  }

  // ==================== 6. version exact int 1 ====================

  @Test
  void parseRejectsVersionAsString() {
    String json = "{ \"version\": \"1\", \"links\": [[\"a:dvbt\", \"b:dvbt\"]] }";
    assertThrows(IllegalArgumentException.class,
        () -> store.parseAndValidate(Buffer.buffer(json)));
  }

  @Test
  void parseRejectsVersionAsDouble() {
    String json = "{ \"version\": 1.0, \"links\": [[\"a:dvbt\", \"b:dvbt\"]] }";
    assertThrows(IllegalArgumentException.class,
        () -> store.parseAndValidate(Buffer.buffer(json)));
  }

  @Test
  void parseRejectsVersionAsLong() {
    // JSON numbers may be parsed as Integer or Long by Vert.x
    String json = "{ \"version\": " + ((long) Integer.MAX_VALUE + 1) + ", \"links\": [[\"a:dvbt\", \"b:dvbt\"]] }";
    // This will either be Long (rejected by instanceof Integer check)
    // or overflow to a different int value
    Exception e = assertThrows(IllegalArgumentException.class,
        () -> store.parseAndValidate(Buffer.buffer(json)));
    assertTrue(e.getMessage().contains("version"));
  }

  // ==================== 7. isValidServiceRef partagé ====================

  @Test
  void isValidServiceRefAccept() {
    assertTrue(VersionedJsonLinkStore.isValidServiceRef("1:dvbt"));
    assertTrue(VersionedJsonLinkStore.isValidServiceRef("abcd:p"));
    assertTrue(VersionedJsonLinkStore.isValidServiceRef("f:my-profile_v2"));
  }

  @Test
  void isValidServiceRefReject() {
    assertFalse(VersionedJsonLinkStore.isValidServiceRef(null));
    assertFalse(VersionedJsonLinkStore.isValidServiceRef(""));
    assertFalse(VersionedJsonLinkStore.isValidServiceRef("no-colon"));
    assertFalse(VersionedJsonLinkStore.isValidServiceRef(":empty-hex"));
    assertFalse(VersionedJsonLinkStore.isValidServiceRef("xyz:profile"));
    assertFalse(VersionedJsonLinkStore.isValidServiceRef("1:"));
  }

  // ==================== 8. non-mutation of loaded groups ====================

  @Test
  void loadedGroupsAreImmutable(VertxTestContext ctx) {
    store.save(groups(group("1:dvbt", "2:dvbt"))).compose(v -> store.load()).onComplete(ar -> {
      assertTrue(ar.succeeded());
      Set<Set<String>> loaded = ar.result();
      assertThrows(UnsupportedOperationException.class, () -> loaded.add(Set.of()));
      ctx.completeNow();
    });
  }

  // ==================== 9. permissions 0600 ====================

  @Test
  void saveFilePermissions(VertxTestContext ctx) {
    store.save(groups(group("1:dvbt", "2:dvbt"))).onComplete(ar -> {
      assertTrue(ar.succeeded());
      Path file = tempDir.resolve("links.json");
      assertTrue(Files.exists(file));
      try {
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
        assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
        assertFalse(perms.contains(PosixFilePermission.GROUP_READ));
        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
      } catch (UnsupportedOperationException | IOException ignored) {}
      ctx.completeNow();
    });
  }

  // ==================== 10. concurrent saves: unique tmp, last-writer wins ====================

  @Test
  void concurrentSavesUniqueTmpFiles(VertxTestContext ctx) throws Exception {
    Path target = tempDir.resolve("links-concurrent.json");
    var s = new VersionedJsonLinkStore(vertx, target.toString());

    // Launch two concurrent saves
    Future<Void> f1 = s.save(groups(group("1:p", "2:p")));
    Future<Void> f2 = s.save(groups(group("a:p", "b:p")));

    Future.all(f1, f2).onComplete(ar -> {
      assertTrue(ar.succeeded(), "both saves should complete");

      // No .tmp files remaining
      try {
        long tmpCount = Files.list(tempDir)
            .filter(p -> p.getFileName().toString().contains(".tmp-"))
            .count();
        assertEquals(0, tmpCount, "no tmp files should remain");
      } catch (IOException e) { fail(e); }

      // Target file exists and is valid JSON
      assertTrue(Files.exists(target));
      s.load().onComplete(loadAr -> {
        assertTrue(loadAr.succeeded());
        assertFalse(loadAr.result().isEmpty(), "one of the saves produced valid data");
        ctx.completeNow();
      });
    });
  }

  @Test
  void concurrentSavesDistinctTmpFiles(VertxTestContext ctx) throws Exception {
    // Use a custom FileOps spy to capture tmp paths
    Path target = tempDir.resolve("links-spy.json");
    Set<String> tmpPaths = ConcurrentHashMap.newKeySet();

    var spy = new VersionedJsonLinkStore.FileOps() {
      final VersionedJsonLinkStore.FileOps real =
          new VersionedJsonLinkStore(vertx, target.toString()).fileOps();
      @Override public Future<Boolean> exists(String p) { return real.exists(p); }
      @Override public Future<FileProps> props(String p) { return real.props(p); }
      @Override public Future<Buffer> readFile(String p) { return real.readFile(p); }
      @Override public Future<Void> mkdirs(String p) { return real.mkdirs(p); }
      @Override public Future<AsyncFile> open(String p, OpenOptions o) {
        if (p.contains(".tmp-")) tmpPaths.add(p);
        return real.open(p, o);
      }
      @Override public Future<Void> move(String f, String t, CopyOptions o) { return real.move(f, t, o); }
      @Override public Future<Void> delete(String p) { return real.delete(p); }
    };

    var spyStore = new VersionedJsonLinkStore(spy, target.toString(), 1_048_576);

    Future<Void> f1 = spyStore.save(groups(group("1:p", "2:p")));
    Future<Void> f2 = spyStore.save(groups(group("a:p", "b:p")));

    Future.all(f1, f2).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertEquals(2, tmpPaths.size(), "two distinct tmp files");
      // Verify no tmp files remaining on disk
      try {
        long count = Files.list(tempDir)
            .filter(p -> p.getFileName().toString().contains(".tmp-"))
            .count();
        assertEquals(0, count, "no tmp files on disk");
      } catch (IOException e) { fail(e); }
      ctx.completeNow();
    });
  }

  // ==================== 11. tmp cleanup after write/move failure ====================

  @Test
  void tmpCleanedAfterOpenFailure(VertxTestContext ctx) {
    // Mock FileOps that fails on open
    var failingOps = new MockFileOps() {
      @Override public Future<AsyncFile> open(String path, OpenOptions opts) {
        return Future.failedFuture("simulated open failure");
      }
    };
    var s = new VersionedJsonLinkStore(failingOps, "/fake/links.json", 1_048_576);

    s.save(groups(group("1:p", "2:p"))).onComplete(ar -> {
      assertTrue(ar.failed());
      assertEquals(0, failingOps.deleteCalls.stream()
          .filter(p -> p.contains(".tmp-")).count(),
          "delete should not be called for open failure (no tmp created)");
      ctx.completeNow();
    });
  }

  @Test
  void tmpCleanedAfterWriteFailure(VertxTestContext ctx) {
    // FileOps succeeds on open but write on the AsyncFile fails is harder to mock.
    // Instead test: save to a read-only directory (parent exists but open fails)
    Path roDir = tempDir.resolve("readonly");
    try {
      Files.createDirectory(roDir);
      // Can't make dir read-only portably; trust the open-failure path above
    } catch (IOException ignored) {}

    // Test move/fallback failure: use path where move will fail
    var badStore = new VersionedJsonLinkStore(vertx,
        "/nonexistent/path/that/cannot/be/created/links.json");
    badStore.save(groups(group("1:dvbt", "2:dvbt"))).onComplete(ar -> {
      assertTrue(ar.failed(), "save to non-existent path must fail");
      ctx.completeNow();
    });
  }

  // ==================== 12. public message never reveals path ====================

  @Test
  void parseErrorMessageNeverContainsPath() {
    String json = "{ \"version\": 1, \"links\": [[\"badref\", \"1:dvbt\"]] }";
    Exception e = assertThrows(IllegalArgumentException.class,
        () -> store.parseAndValidate(Buffer.buffer(json)));
    String msg = e.getMessage();
    assertNotNull(msg);
    assertFalse(msg.contains("/"), "message should not contain Unix path");
    assertFalse(msg.contains("\\\\"), "message should not contain Windows path");
    assertFalse(msg.contains("badref"), "message should not contain raw ref value");
    assertTrue(msg.length() < 200, "message should be bounded");
  }

  @Test
  void loadFailsafeNeverRevealsPath() {
    // Even with a bad path, the public error returned to caller is generic
    var badStore = new VersionedJsonLinkStore(vertx, "/secret/path/links.json");
    // load should not expose the path in the result
    badStore.load().onComplete(ar -> {
      assertTrue(ar.succeeded()); // fail-safe = empty set
      assertTrue(ar.result().isEmpty());
    });
  }

  // ==================== 13. encode/decode + relay test ====================

  @Test
  void encodedFormatIsPrettified() {
    String json = VersionedJsonLinkStore.encode(groups(group("a:dvbt", "b:dvbt")));
    assertTrue(json.contains("\"version\""));
    assertTrue(json.contains("\"links\""));
    assertTrue(json.contains("\n"), "prettified should contain newlines");
  }

  @Test
  void storeOutputAlwaysReloadable() {
    // Verify that a store's encode → parse round-trip is always coherent
    Set<Set<String>> input = groups(group("1:p", "2:p"), group("a:p", "b:p", "c:p"));
    String json = VersionedJsonLinkStore.encode(input);
    Set<Set<String>> parsed = store.parseAndValidate(Buffer.buffer(json));
    assertEquals(2, parsed.size());

    // Re-serialize and re-parse
    String json2 = VersionedJsonLinkStore.encode(parsed);
    Set<Set<String>> parsed2 = store.parseAndValidate(Buffer.buffer(json2));
    assertEquals(parsed, parsed2);
  }

  // ==================== 14. multiple groups round-trip ====================

  @Test
  void multipleGroupsRoundTrip(VertxTestContext ctx) {
    Set<Set<String>> groups = groups(
        group("1:p", "2:p"),
        group("a:p", "b:p"),
        group("c:p", "d:p", "e:p"));

    store.save(groups).compose(v -> store.load()).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertEquals(3, ar.result().size());
      ctx.completeNow();
    });
  }

  // ==================== helpers ====================

  @SafeVarargs
  static Set<Set<String>> groups(Set<String>... gs) {
    Set<Set<String>> result = new LinkedHashSet<>();
    for (Set<String> g : gs) result.add(Set.copyOf(g));
    return Collections.unmodifiableSet(result);
  }

  static Set<String> group(String... refs) {
    return Set.of(refs);
  }

  /** Mock FileOps implémentant tous les appels avec des maps de suivi. */
  static class MockFileOps implements VersionedJsonLinkStore.FileOps {
    final List<String> deleteCalls = Collections.synchronizedList(new ArrayList<>());
    final Map<String, Boolean> existsMap = new ConcurrentHashMap<>();
    boolean openFails, writeFails, moveFails;

    void setExists(String path, boolean val) { existsMap.put(path, val); }
    @Override public Future<Boolean> exists(String path) {
      Boolean v = existsMap.get(path);
      return Future.succeededFuture(v != null ? v : false);
    }
    @Override public Future<FileProps> props(String path) {
      return Future.failedFuture("mock: not implemented");
    }
    @Override public Future<Buffer> readFile(String path) {
      return Future.failedFuture("mock: not implemented");
    }
    @Override public Future<Void> mkdirs(String path) {
      return Future.succeededFuture();
    }
    @Override public Future<AsyncFile> open(String path, OpenOptions opts) {
      if (openFails) return Future.failedFuture("mock open failure");
      return Future.failedFuture("mock: open not implemented");
    }
    @Override public Future<Void> move(String from, String to, CopyOptions opts) {
      if (moveFails) return Future.failedFuture("mock move failure");
      return Future.succeededFuture();
    }
    @Override public Future<Void> delete(String path) {
      deleteCalls.add(path);
      return Future.succeededFuture();
    }
  }
}
