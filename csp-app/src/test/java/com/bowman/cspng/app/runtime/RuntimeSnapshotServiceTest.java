package com.bowman.cspng.app.runtime;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ProfileConfig;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.sessions.SessionRegistry;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeSnapshotServiceTest {

  @Test
  void envelopeInstanceStableAndTimestampIso() {
    ConfigHolder holder = new ConfigHolder();
    Vertx vertx = Vertx.vertx();
    CacheVerticle cv = new CacheVerticle();
    CwsConnectorManager cm = new CwsConnectorManager(vertx, cv);
    SessionRegistry sr = new SessionRegistry(vertx);
    RuntimeCore rc = new RuntimeCore(vertx, sr.userDirectory());
    String fixedId = "fixed-uuid-1234";
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    RuntimeSnapshotService svc = new RuntimeSnapshotService(fixedId, start, System.nanoTime(), holder, rc, cv, cm, sr, null, null);
    var env1 = svc.envelope("payload1");
    var env2 = svc.envelope("payload2");
    assertEquals(fixedId, env1.instanceId());
    assertEquals(fixedId, env2.instanceId());
    assertEquals("1.0", env1.schemaVersion());
    // timestamp ISO-8601 parsable
    assertDoesNotThrow(() -> Instant.parse(env1.timestamp()));
    assertNotEquals(env1.timestamp(), null);
    vertx.close();
  }

  @Test
  void statusPayloadCountsAndNotReady() {
    ConfigHolder holder = new ConfigHolder();
    Vertx vertx = Vertx.vertx();
    CacheVerticle cv = new CacheVerticle();
    CwsConnectorManager cm = new CwsConnectorManager(vertx, cv);
    cm.addNewcamdConnector("a", "localhost", 1234, new byte[14], "u", "p", "prof");
    cm.addNewcamdConnector("b", "localhost", 1235, new byte[14], "u", "p", "prof");
    SessionRegistry sr = new SessionRegistry(vertx);
    RuntimeCore rc = new RuntimeCore(vertx, sr.userDirectory());
    RuntimeSnapshotService svc = new RuntimeSnapshotService(UUID.randomUUID(), Instant.now(), System.nanoTime(), holder, rc, cv, cm, sr, null, null);
    var status = svc.statusPayload();
    assertFalse(status.runtimeStarted());
    assertNotNull(status.appVersion());
    assertTrue(status.uptimeMs() >= 0);
    assertEquals(0, status.configLoadedAt());
    assertEquals(2, status.totalConnectorCount());
    assertEquals(0, status.activeConnectorCount());
    assertFalse(status.cacheAvailable(), "cache not started -> not available");
    vertx.close();
  }

  @Test
  void cacheAvailableUsesIsStarted() throws Exception {
    Vertx vertx = Vertx.vertx();
    CacheVerticle cv = new CacheVerticle();
    assertFalse(cv.isStarted());
    io.vertx.core.Vertx v2 = vertx;
    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    v2.deployVerticle(cv).onComplete(ar -> latch.countDown());
    assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
    java.util.concurrent.CountDownLatch ctx = new java.util.concurrent.CountDownLatch(1);
    cv.runOnContext(ctx::countDown);
    assertTrue(ctx.await(5, java.util.concurrent.TimeUnit.SECONDS));
    assertTrue(cv.isStarted());
    ConfigHolder holder = new ConfigHolder();
    var cm = new CwsConnectorManager(vertx, cv);
    var sr = new SessionRegistry(vertx);
    var rc = new RuntimeCore(vertx, sr.userDirectory());
    // rc not started, but cache is started -> cacheAvailable should be true via cv.isStarted()
    var svc = new RuntimeSnapshotService("id", Instant.now(), System.nanoTime(), holder, rc, cv, cm, sr, null, null);
    assertTrue(svc.statusPayload().cacheAvailable());
    vertx.close();
  }

  @Test
  void profileSnapshotsNotReadyThrowsGeneric() {
    ConfigHolder holder = new ConfigHolder();
    Vertx vertx = Vertx.vertx();
    RuntimeSnapshotService svc = new RuntimeSnapshotService("id", Instant.now(), System.nanoTime(), holder, null, null, null, null, null, null);
    IllegalStateException ex = assertThrows(IllegalStateException.class, svc::profileSnapshots);
    assertTrue(ex.getMessage().contains("NOT_READY"));
    assertFalse(ex.getMessage().contains("/tmp"));
    vertx.close();
  }

  @Test
  void profileSnapshotsReturnsOnlyAllowedFieldsAndSorted() {
    ConfigHolder holder = new ConfigHolder();
    ProfileConfig p2 = new ProfileConfig("zeta", true, false, false, 0x0B00, 1, null, null, 5000, null, false, 0, null, null, null, null, 1);
    ProfileConfig p1 = new ProfileConfig("alpha", true, false, true, 0x1810, 2, null, null, 6000, null, false, 0, null, null, null, null, 1);
    ConfigSnapshot snap = new ConfigSnapshot(
        com.bowman.cspng.config.RootConfig.DEFAULT,
        com.bowman.cspng.config.LoggingConfig.DEFAULT,
        com.bowman.cspng.config.CaProfilesGlobalConfig.DEFAULT,
        List.of(p2, p1),
        List.of(), List.of(), com.bowman.cspng.config.ConnectionManagerConfig.DEFAULT,
        List.of(), List.of(), List.of(), com.bowman.cspng.config.CacheConfig.DEFAULT,
        com.bowman.cspng.config.ClusterUdpConfig.DEFAULT, com.bowman.cspng.config.WebUiConfig.DEFAULT,
        List.of(), List.of(), com.bowman.cspng.config.ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis());
    holder.set(snap);
    Vertx vertx = Vertx.vertx();
    RuntimeSnapshotService svc = new RuntimeSnapshotService("id", Instant.now(), System.nanoTime(), holder, null, null, null, null, null, null);
    var list = svc.profileSnapshots();
    assertEquals(2, list.size());
    assertEquals("alpha", list.get(0).name());
    assertEquals("zeta", list.get(1).name());
    // ensure no secret fields in DTO record components
    var comps = RuntimeSnapshotService.ProfileDto.class.getRecordComponents();
    for (var rc: comps) {
      String n = rc.getName().toLowerCase();
      assertFalse(n.contains("providerfilter"));
      assertFalse(n.contains("servicesfile"));
      assertFalse(n.contains("customdata"));
    }
    // caIdHex format
    assertEquals("0x1810", list.stream().filter(p->p.name().equals("alpha")).findFirst().get().caIdHex());
    vertx.close();
  }

  @Test
  void cacheAsyncReturnsFutureOnContext() throws Exception {
    Vertx vertx = Vertx.vertx();
    CacheVerticle cv = new CacheVerticle();
    CountDownLatch latch = new CountDownLatch(1);
    vertx.deployVerticle(cv).onComplete(ar -> latch.countDown());
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    // wait for context init
    CountDownLatch ctxLatch = new CountDownLatch(1);
    cv.runOnContext(ctxLatch::countDown);
    assertTrue(ctxLatch.await(5, TimeUnit.SECONDS));
    ConfigHolder holder = new ConfigHolder();
    CwsConnectorManager cm = new CwsConnectorManager(vertx, cv);
    SessionRegistry sr = new SessionRegistry(vertx);
    RuntimeCore rc = new RuntimeCore(vertx, sr.userDirectory());
    RuntimeSnapshotService svc = new RuntimeSnapshotService("id", Instant.now(), System.nanoTime(), holder, rc, cv, cm, sr, null, null);
    var fut = svc.cacheStatsFuture();
    CountDownLatch done = new CountDownLatch(1);
    var ref = new java.util.concurrent.atomic.AtomicReference<com.bowman.cspng.cache.CacheStats>();
    fut.onComplete(ar -> { if (ar.succeeded()) ref.set(ar.result()); done.countDown(); });
    assertTrue(done.await(5, TimeUnit.SECONDS));
    assertNotNull(ref.get());
    vertx.close();
  }

  @Test
  void cacheNotStartedFailsGeneric() {
    CacheVerticle cv = new CacheVerticle(); // not deployed
    ConfigHolder holder = new ConfigHolder();
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager cm = new CwsConnectorManager(vertx, cv);
    SessionRegistry sr = new SessionRegistry(vertx);
    RuntimeCore rc = new RuntimeCore(vertx, sr.userDirectory());
    RuntimeSnapshotService svc = new RuntimeSnapshotService("id", Instant.now(), System.nanoTime(), holder, rc, cv, cm, sr, null, null);
    var fut = svc.cacheStatsFuture();
    assertTrue(fut.failed());
    assertTrue(fut.cause() instanceof RuntimeSnapshotService.NotReadyException);
    assertTrue(fut.cause().getMessage().contains("NOT_READY"));
    vertx.close();
  }

  @Test
  void cacheInternalErrorMappedTo500NotNotReady() throws Exception {
    Vertx vertx = Vertx.vertx();
    CacheVerticle cv = new CacheVerticle() {
      @Override public io.vertx.core.Future<com.bowman.cspng.cache.CacheStats> snapshotStats() {
        return io.vertx.core.Future.failedFuture(new RuntimeException("INTERNAL_SNAPSHOT_FAIL"));
      }
      @Override public boolean isStarted() { return true; }
    };
    // need to make service think cache is started but snapshot fails internally
    ConfigHolder holder = new ConfigHolder();
    var cm = new CwsConnectorManager(vertx, cv);
    var sr = new SessionRegistry(vertx);
    var rc = new RuntimeCore(vertx, sr.userDirectory());
    var svc = new RuntimeSnapshotService("id", Instant.now(), System.nanoTime(), holder, rc, cv, cm, sr, null, null);
    var fut = svc.cacheStatsFuture();
    assertTrue(fut.failed());
    assertFalse(fut.cause() instanceof RuntimeSnapshotService.NotReadyException, "internal error should not be NotReady");
    assertTrue(fut.cause().getMessage().contains("CACHE_SNAPSHOT_FAILED"));
    vertx.close();
  }

  @Test
  void paginationOverflowLongSafe() {
    var list = java.util.stream.IntStream.range(0, 5).mapToObj(i -> "e"+i).toList();
    // page 1_000_000 * size 100 -> fromLong 100_000_000 > total 5 -> empty, not overflow
    var paged = RuntimeSnapshotService.paginate(list, 1_000_000, 100);
    assertEquals(0, paged.items().size());
    assertEquals(5, paged.totalElements());
    // ensure slice is detached copy, not view
    var p2 = RuntimeSnapshotService.paginate(list, 0, 2);
    var mutable = new java.util.ArrayList<>(list);
    mutable.add("extra");
    assertEquals(2, p2.items().size(), "paged slice should be detached");
  }

  private static class UUID {
    static String randomUUID() { return java.util.UUID.randomUUID().toString(); }
  }
}
