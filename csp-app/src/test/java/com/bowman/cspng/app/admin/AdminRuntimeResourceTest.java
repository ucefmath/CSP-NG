package com.bowman.cspng.app.admin;

import com.bowman.cspng.app.runtime.RuntimeSnapshotService;
import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ProfileConfig;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.sessions.SessionRegistry;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.NoCache;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdminRuntimeResourceTest {

  @Test
  void classHasRequiredAnnotations() {
    var cls = AdminRuntimeResource.class;
    assertTrue(cls.isAnnotationPresent(RolesAllowed.class));
    RolesAllowed ra = cls.getAnnotation(RolesAllowed.class);
    assertTrue(List.of(ra.value()).contains("admin"));
    assertTrue(cls.isAnnotationPresent(jakarta.ws.rs.Produces.class));
    boolean hasNoCache = cls.isAnnotationPresent(NoCache.class) || hasNoCacheViaMethods(cls);
    assertTrue(hasNoCache, "NoCache annotation required on class or methods");
  }

  private boolean hasNoCacheViaMethods(Class<?> cls) {
    for (Method m : cls.getDeclaredMethods()) if (m.isAnnotationPresent(NoCache.class)) return true;
    return false;
  }

  @Test
  void everyResponseHasCacheControl() {
    ConfigHolder holder = new ConfigHolder();
    var svc = new RuntimeSnapshotService("id", Instant.now(), System.nanoTime(), holder, null, null, null, null, null, null);
    var res = new AdminRuntimeResource(svc);
    // 200 ok
    var ok = res.status().await().indefinitely();
    assertTrue(ok.getHeaderString("Cache-Control").contains("no-store"));
    // 400
    var bad = res.profiles("abc","10").await().indefinitely();
    assertEquals(400, bad.getStatus());
    assertTrue(bad.getHeaderString("Cache-Control").contains("no-store"));
    // 503
    var notReady = res.profiles("0","10").await().indefinitely();
    assertEquals(503, notReady.getStatus());
    assertTrue(notReady.getHeaderString("Cache-Control").contains("no-store"));
    // cache 503 when not started
    var cacheCfg = new CacheVerticle();
    var svc2 = new RuntimeSnapshotService("id", Instant.now(), System.nanoTime(), holder, null, cacheCfg, null, null, null, null);
    var res2 = new AdminRuntimeResource(svc2);
    var c503 = res2.cache().await().indefinitely();
    assertTrue(c503.getHeaderString("Cache-Control").contains("no-store"));
    // also check sessions/connectors 200 path with initialized holder
    ProfileConfig pc = new ProfileConfig("p1", true, false, false, 0x0B00, 1, null, null, 5000, null, false, 0, null, null, null, null, 1);
    ConfigSnapshot snap = new ConfigSnapshot(com.bowman.cspng.config.RootConfig.DEFAULT, com.bowman.cspng.config.LoggingConfig.DEFAULT, com.bowman.cspng.config.CaProfilesGlobalConfig.DEFAULT, List.of(pc), List.of(), List.of(), com.bowman.cspng.config.ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(), com.bowman.cspng.config.CacheConfig.DEFAULT, com.bowman.cspng.config.ClusterUdpConfig.DEFAULT, com.bowman.cspng.config.WebUiConfig.DEFAULT, List.of(), List.of(), com.bowman.cspng.config.ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis());
    holder.set(snap);
    var svc3 = new RuntimeSnapshotService("id", Instant.now(), System.nanoTime(), holder, null, new CacheVerticle(), new CwsConnectorManager(io.vertx.core.Vertx.vertx(), new CacheVerticle()), new SessionRegistry(io.vertx.core.Vertx.vertx()), null, null);
    var res3 = new AdminRuntimeResource(svc3);
    var connOk = res3.connectors("0","10").await().indefinitely();
    assertEquals(200, connOk.getStatus());
    assertTrue(connOk.getHeaderString("Cache-Control").contains("no-store"));
    var sessOk = res3.sessions("0","10").await().indefinitely();
    assertTrue(sessOk.getHeaderString("Cache-Control").contains("no-store"));
  }

  @Test
  void paginationValidation() {
    ConfigHolder holder = new ConfigHolder();
    // need profile to avoid NOT_READY for pagination logic - but we test pagination directly via resource helper
    var svc = new RuntimeSnapshotService("id", Instant.now(), System.nanoTime(), holder, null, null, null, null, null, null);
    var res = new AdminRuntimeResource(svc);
    // invalid page string -> 400 via reflection of private method? Test via public endpoints logic: call profiles with invalid query
    var uni = res.profiles("abc", "10");
    var resp = uni.await().indefinitely();
    assertEquals(400, resp.getStatus());
    // size out of range
    var uni2 = res.profiles("0", "0");
    assertEquals(400, uni2.await().indefinitely().getStatus());
    var uni3 = res.profiles("0", "101");
    assertEquals(400, uni3.await().indefinitely().getStatus());
    var uni4 = res.profiles("-1", "10");
    assertEquals(400, uni4.await().indefinitely().getStatus());
    var uni5 = res.profiles("1000001", "10");
    assertEquals(400, uni5.await().indefinitely().getStatus());
  }

  @Test
  void paginationDeterministicSorting() {
    ConfigHolder holder = new ConfigHolder();
    ProfileConfig p2 = new ProfileConfig("zeta", true, false, false, 0x0B00, 1, null, null, 5000, null, false, 0, null, null, null, null, 1);
    ProfileConfig p1 = new ProfileConfig("alpha", true, false, false, 0x1810, 2, null, null, 5000, null, false, 0, null, null, null, null, 1);
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
    var vertx = io.vertx.core.Vertx.vertx();
    var cv = new CacheVerticle();
    var cm = new CwsConnectorManager(vertx, cv);
    var sr = new SessionRegistry(vertx);
    var svc = new RuntimeSnapshotService("id", Instant.now(), System.nanoTime(), holder, null, cv, cm, sr, null, null);
    var res = new AdminRuntimeResource(svc);
    var resp = res.profiles("0", "10").await().indefinitely();
    assertEquals(200, resp.getStatus());
    Object entity = resp.getEntity();
    assertNotNull(entity);
    // envelope payload should contain paged result with sorted items
    var env = (RuntimeSnapshotService.Envelope) entity;
    var paged = (RuntimeSnapshotService.PagedResult) env.payload();
    assertEquals(2, paged.items().size());
    var first = (RuntimeSnapshotService.ProfileDto) paged.items().get(0);
    assertEquals("alpha", first.name());
    vertx.close();
  }

  @Test
  void endpointDirectNotReady() {
    ConfigHolder holder = new ConfigHolder(); // not initialized
    var svc = new RuntimeSnapshotService("id", Instant.now(), System.nanoTime(), holder, null, null, null, null, null, null);
    var res = new AdminRuntimeResource(svc);
    var resp = res.profiles("0", "10").await().indefinitely();
    assertEquals(503, resp.getStatus());
  }

  @Test
  void envelopeStructure() {
    ConfigHolder holder = new ConfigHolder();
    var svc = new RuntimeSnapshotService("fixed-id", Instant.now(), System.nanoTime(), holder, null, null, null, null, null, null);
    var env = svc.envelope(Map.of("k","v"));
    assertEquals("fixed-id", env.instanceId());
    assertEquals("1.0", env.schemaVersion());
    assertNotNull(env.timestamp());
    assertDoesNotThrow(() -> Instant.parse(env.timestamp()));
  }

  @Test
  void paginationSlicing() {
    var list = List.of("a","b","c","d","e");
    var paged = RuntimeSnapshotService.paginate(list, 1, 2);
    assertEquals(2, paged.items().size());
    assertEquals("c", paged.items().get(0));
    assertEquals(5, paged.totalElements());
    assertEquals(3, paged.totalPages());
  }

  @Test
  void noSensitiveFieldsInProfileDto() {
    var comps = RuntimeSnapshotService.ProfileDto.class.getRecordComponents();
    for (var rc : comps) {
      String n = rc.getName().toLowerCase();
      assertFalse(n.contains("password"));
      assertFalse(n.contains("secret"));
      assertFalse(n.contains("providerfilter"));
      assertFalse(n.contains("servicesfile"));
    }
  }

  @Test
  void resourceMethodsReturnUni() throws Exception {
    for (Method m : AdminRuntimeResource.class.getDeclaredMethods()) {
      if (m.getName().equals("status") || m.getName().equals("cache") || m.getName().equals("profiles") || m.getName().equals("connectors") || m.getName().equals("sessions")) {
        assertEquals(io.smallrye.mutiny.Uni.class, m.getReturnType(), m.getName() + " should return Uni");
      }
    }
  }

  @Test
  void jsonWireNoSensitiveLeak() throws Exception {
    var holder = new ConfigHolder();
    var pc = new ProfileConfig("sentinel-profile", true, false, false, 0x0B00, 1, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var snap = new ConfigSnapshot(com.bowman.cspng.config.RootConfig.DEFAULT, com.bowman.cspng.config.LoggingConfig.DEFAULT, com.bowman.cspng.config.CaProfilesGlobalConfig.DEFAULT, List.of(pc), List.of(), List.of(), com.bowman.cspng.config.ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(), com.bowman.cspng.config.CacheConfig.DEFAULT, com.bowman.cspng.config.ClusterUdpConfig.DEFAULT, com.bowman.cspng.config.WebUiConfig.DEFAULT, List.of(), List.of(), com.bowman.cspng.config.ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis());
    holder.set(snap);
    var vertx = io.vertx.core.Vertx.vertx();
    var cv = new CacheVerticle();
    var cm = new CwsConnectorManager(vertx, cv);
    cm.addNewcamdConnector("SENTINEL_CONN", "SENTINEL_HOST", 1234, new byte[14], "SENTINEL_USER", "SENTINEL_SECRET", "sentinel-profile");
    var sr = new SessionRegistry(vertx);
    var sess = new com.bowman.cspng.sessions.AbstractSession(vertx, cv, sr) {
      { username = "admin"; remoteAddress = "192.168.1.100"; authenticated = true; alive = true; }
      @Override public void close() {}
    };
    sr.register(sess);
    var svc = new RuntimeSnapshotService("fixed-instance", Instant.now(), System.nanoTime(), holder, null, cv, cm, sr, null, null);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    // status
    String statusJson = mapper.writeValueAsString(svc.envelope(svc.statusPayload()));
    assertFalse(statusJson.toLowerCase().contains("sentinel"));
    // profiles
    String profJson = mapper.writeValueAsString(svc.envelope(RuntimeSnapshotService.paginate(svc.profileSnapshots(),0,10)));
    assertFalse(profJson.toLowerCase().contains("providerfilter"));
    assertFalse(profJson.toLowerCase().contains("servicesfile"));
    assertFalse(profJson.toLowerCase().contains("customdata"));
    assertFalse(profJson.toLowerCase().contains("secret"));
    // connectors
    String connJson = mapper.writeValueAsString(svc.envelope(RuntimeSnapshotService.paginate(svc.connectorSnapshots(),0,10)));
    String lowConn = connJson.toLowerCase();
    assertFalse(lowConn.contains("sentinel_host"));
    assertFalse(lowConn.contains("sentinel_secret"));
    assertFalse(lowConn.contains("\"host\""));
    assertFalse(lowConn.contains("\"port\""));
    assertFalse(lowConn.contains("password"));
    // sessions
    String sessJson = mapper.writeValueAsString(svc.envelope(RuntimeSnapshotService.paginate(svc.sessionSnapshots(),0,10)));
    assertFalse(sessJson.toLowerCase().contains("password"));
    assertFalse(sessJson.toLowerCase().contains("cw"));
    assertFalse(sessJson.toLowerCase().contains("\"body\""));
    // ensure networkId/cacheOnly not flagged as key leak but ensure no "key" property leak for sensitive
    assertTrue(sessJson.contains("profile") || sessJson.contains("id"));
    vertx.close();
  }

  @Test
  void paginationOverflowLongSafe() {
    var list = java.util.stream.IntStream.range(0, 3).mapToObj(i->"v"+i).toList();
    var p = RuntimeSnapshotService.paginate(list, 1_000_000, 100);
    assertEquals(0, p.items().size());
    assertEquals(3, p.totalElements());
    // ensure copy detached
    var p2 = RuntimeSnapshotService.paginate(list, 0, 2);
    assertEquals(2, p2.items().size());
    assertThrows(UnsupportedOperationException.class, () -> p2.items().add("x"));
  }

  public static class QuarkusStatusProfile implements io.quarkus.test.junit.QuarkusTestProfile {
    @Override public java.util.Map<String,String> getConfigOverrides() {
      return java.util.Map.of(
        "quarkus.http.auth.basic","true",
        "csp.config.pg.enabled","false",
        "csp.admin.bootstrap-required","false",
        "csp.admin.require-https","false",
        "quarkus.flyway.enabled","false",
        "quarkus.datasource.devservices.enabled","false"
      );
    }
  }

}

@io.quarkus.test.junit.QuarkusTest
@io.quarkus.test.junit.TestProfile(AdminRuntimeResourceTest.QuarkusStatusProfile.class)
class AdminRuntimeResourceQuarkusTest {
  @jakarta.inject.Inject com.bowman.cspng.users.UserDirectory userDirectory;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    if (userDirectory instanceof com.bowman.cspng.users.InMemoryUserDirectory dir) {
      var hasher = new com.bowman.cspng.users.crypto.PasswordHasher(4);
      try { dir.add(new com.bowman.cspng.users.UserRecord("admin", "Administrator", hasher.hash("adminPass123456"), null, true, true, false, false, 0, null, -1, java.util.Set.of(), java.util.Map.of(), java.util.Map.of(), java.util.Set.of(), 1L, null, null)); } catch (Exception ignored) {}
    }
  }

  @org.junit.jupiter.api.Test
  void anonymousGets401AndAdminGets200() {
    io.restassured.RestAssured.given().get("/api/admin/status").then().statusCode(401);
    io.restassured.RestAssured.given().auth().basic("admin", "adminPass123456").get("/api/admin/status").then().statusCode(200).header("Cache-Control", org.hamcrest.Matchers.containsString("no-store"));
  }
}
