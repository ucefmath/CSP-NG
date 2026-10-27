package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ConfigValidationException;
import com.bowman.cspng.config.NetworkProxyConfig;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires REST de {@link AdminProxyResource} (Plan P2 §3) :
 * upsert happy path, nom immutable (path=body), auth appariée, passwordRef env:/file:,
 * 409 PROXY_IN_USE sur DELETE, 400 mismatch, usage endpoint, DTO sans mot de passe clair.
 */
class AdminProxyResourceTest {

  private AdminProxyResource resourceWithMocks(ConfigRepository repo, ConfigHolder holder) {
    AdminProxyResource r = new AdminProxyResource();
    try {
      var f1 = AdminProxyResource.class.getDeclaredField("configRepository");
      f1.setAccessible(true); f1.set(r, repo);
      var f2 = AdminProxyResource.class.getDeclaredField("configHolder");
      f2.setAccessible(true); f2.set(r, holder);
      var f3 = AdminProxyResource.class.getDeclaredField("securityContext");
      f3.setAccessible(true);
      SecurityContext sc = new SecurityContext() {
        public Principal getUserPrincipal() { return () -> "tester"; }
        public boolean isUserInRole(String role) { return true; }
        public boolean isSecure() { return false; }
        public String getAuthenticationScheme() { return "BASIC"; }
      };
      f3.set(r, sc);
    } catch (Exception e) { throw new RuntimeException(e); }
    return r;
  }

  private static class StubProxyRepo extends ConfigRepository {
    private final java.util.function.Function<NetworkProxyConfig, Future<Long>> upsertHandler;
    private final java.util.function.BiFunction<String, Long, Future<Void>> deleteHandler;
    private final java.util.function.Function<String, Future<Long>> usageHandler;
    private final List<NetworkProxyConfig> proxies;

    StubProxyRepo(java.util.function.Function<NetworkProxyConfig, Future<Long>> upsertHandler,
                  java.util.function.BiFunction<String, Long, Future<Void>> deleteHandler,
                  java.util.function.Function<String, Future<Long>> usageHandler,
                  List<NetworkProxyConfig> proxies) {
      super((Pool) null);
      this.upsertHandler = upsertHandler;
      this.deleteHandler = deleteHandler;
      this.usageHandler = usageHandler;
      this.proxies = proxies;
    }

    @Override public Future<Long> upsertProxy(NetworkProxyConfig proxy, String actor) {
      return upsertHandler != null ? upsertHandler.apply(proxy)
          : Future.failedFuture(new IllegalStateException("not stubbed"));
    }

    @Override public Future<Void> deleteProxy(String name, long expectedVersion, String actor) {
      return deleteHandler != null ? deleteHandler.apply(name, expectedVersion)
          : Future.failedFuture(new IllegalStateException("not stubbed"));
    }

    @Override public Future<java.util.List<NetworkProxyConfig>> loadAllProxies() {
      return Future.succeededFuture(proxies != null ? proxies : List.of());
    }

    @Override public Future<Long> countProxyUsage(String proxyName) {
      return usageHandler != null ? usageHandler.apply(proxyName) : Future.succeededFuture(0L);
    }
  }

  private static AdminProxyResource.ProxyDto dto(String name, boolean enabled, String host, int port,
                                                 String username, String passwordRef, long version) {
    return new AdminProxyResource.ProxyDto(name, enabled, host, port, username, passwordRef,
        null, version);
  }

  // -- upsert --

  @Test
  void upsertSuccessCreatesProxy() {
    AtomicReference<NetworkProxyConfig> saved = new AtomicReference<>();
    ConfigRepository repo = new StubProxyRepo(cfg -> {
      saved.set(cfg);
      return Future.succeededFuture(2L);
    }, null, null, null);
    AdminProxyResource res = resourceWithMocks(repo, new ConfigHolder());
    var resp = res.upsertProxy("px1", dto("px1", true, "172.20.0.1", 1080, null, null, 1L))
        .await().indefinitely();
    assertEquals(200, resp.getStatus());
    var body = (AdminProxyResource.VersionResponse) resp.getEntity();
    assertEquals(2L, body.version());
    assertEquals("px1", saved.get().name());
    assertTrue(saved.get().enabled());
    assertEquals("socks5", saved.get().type());
    assertNull(saved.get().username());
  }

  @Test
  void upsertNameMismatch400() {
    ConfigRepository repo = new StubProxyProxyFailHelper();
    AdminProxyResource res = resourceWithMocks(repo, new ConfigHolder());
    var resp = res.upsertProxy("other", dto("px1", true, "172.20.0.1", 1080, null, null, 1L))
        .await().indefinitely();
    assertEquals(400, resp.getStatus());
    var err = (com.bowman.cspng.web.admin.dto.ErrorResponse) resp.getEntity();
    assertEquals("INVALID_INPUT", err.code());
    assertTrue(err.message().contains("immutable"));
  }

  @Test
  void upsertAuthUnpaired400() {
    ConfigRepository repo = new StubProxyProxyFailHelper();
    AdminProxyResource res = resourceWithMocks(repo, new ConfigHolder());
    // username sans passwordRef
    var resp = res.upsertProxy("px1", dto("px1", true, "172.20.0.1", 1080, "user", null, 1L))
        .await().indefinitely();
    assertEquals(400, resp.getStatus());
    // passwordRef sans username
    var resp2 = res.upsertProxy("px1", dto("px1", true, "172.20.0.1", 1080, null, "env:X", 1L))
        .await().indefinitely();
    assertEquals(400, resp2.getStatus());
  }

  @Test
  void upsertBadPasswordRef400() {
    ConfigRepository repo = new StubProxyProxyFailHelper();
    AdminProxyResource res = resourceWithMocks(repo, new ConfigHolder());
    // mot de passe en clair interdit : refs env:/file: seulement
    var resp = res.upsertProxy("px1", dto("px1", true, "172.20.0.1", 1080, "user", "plainsecret", 1L))
        .await().indefinitely();
    assertEquals(400, resp.getStatus());
    // vault: interdit aussi
    var resp2 = res.upsertProxy("px1", dto("px1", true, "172.20.0.1", 1080, "user", "vault:xyz", 1L))
        .await().indefinitely();
    assertEquals(400, resp2.getStatus());
  }

  @Test
  void upsertVersionZero400() {
    ConfigRepository repo = new StubProxyProxyFailHelper();
    AdminProxyResource res = resourceWithMocks(repo, new ConfigHolder());
    var resp = res.upsertProxy("px1", dto("px1", true, "172.20.0.1", 1080, null, null, 0L))
        .await().indefinitely();
    assertEquals(400, resp.getStatus());
  }

  @Test
  void upsertInvalidHost400() {
    ConfigRepository repo = new StubProxyProxyFailHelper();
    AdminProxyResource res = resourceWithMocks(repo, new ConfigHolder());
    // host avec URI → rejet record → 400
    var resp = res.upsertProxy("px1", dto("px1", true, "socks5://h", 1080, null, null, 1L))
        .await().indefinitely();
    assertEquals(400, resp.getStatus());
  }

  @Test
  void upsertAuthEnvRefAccepted() {
    AtomicReference<NetworkProxyConfig> saved = new AtomicReference<>();
    ConfigRepository repo = new StubProxyRepo(cfg -> { saved.set(cfg); return Future.succeededFuture(1L); },
        null, null, null);
    AdminProxyResource res = resourceWithMocks(repo, new ConfigHolder());
    var resp = res.upsertProxy("px1", dto("px1", true, "proxy.lan", 1080, "u", "env:PROXY_PW", 1L))
        .await().indefinitely();
    assertEquals(200, resp.getStatus());
    assertEquals("u", saved.get().username());
    assertEquals("env:PROXY_PW", saved.get().passwordRef());
  }

  // -- delete --

  @Test
  void deleteProxyInUse409() {
    ConfigRepository repo = new StubProxyRepo(null, (name, version) ->
        Future.failedFuture(new ConfigValidationException(
            "PROXY_IN_USE: proxy 'px1' is referenced by 2 connector(s)")), null, null);
    AdminProxyResource res = resourceWithMocks(repo, new ConfigHolder());
    var resp = res.deleteProxy("px1", 3L).await().indefinitely();
    assertEquals(409, resp.getStatus());
    var err = (com.bowman.cspng.web.admin.dto.ErrorResponse) resp.getEntity();
    assertEquals("PROXY_IN_USE", err.code());
  }

  @Test
  void deleteValidationOther400() {
    ConfigRepository repo = new StubProxyRepo(null, (name, version) ->
        Future.failedFuture(new ConfigValidationException("some other validation error")), null, null);
    AdminProxyResource res = resourceWithMocks(repo, new ConfigHolder());
    var resp = res.deleteProxy("px1", 3L).await().indefinitely();
    assertEquals(400, resp.getStatus());
    var err = (com.bowman.cspng.web.admin.dto.ErrorResponse) resp.getEntity();
    assertEquals("INVALID_INPUT", err.code());
  }

  @Test
  void deleteSuccess204() {
    AtomicReference<String> deletedName = new AtomicReference<>();
    AtomicReference<Long> deletedVersion = new AtomicReference<>();
    ConfigRepository repo = new StubProxyRepo(null, (name, version) -> {
      deletedName.set(name); deletedVersion.set(version);
      return Future.succeededFuture();
    }, null, null);
    AdminProxyResource res = resourceWithMocks(repo, new ConfigHolder());
    var resp = res.deleteProxy("px1", 5L).await().indefinitely();
    assertEquals(204, resp.getStatus());
    assertEquals("px1", deletedName.get());
    assertEquals(5L, deletedVersion.get());
  }

  @Test
  void deleteMissingVersion400() {
    ConfigRepository repo = new StubProxyProxyFailHelper();
    AdminProxyResource res = resourceWithMocks(repo, new ConfigHolder());
    var resp = res.deleteProxy("px1", null).await().indefinitely();
    assertEquals(400, resp.getStatus());
  }

  // -- list / detail / usage --

  @Test
  void listProxiesWithUsageCounts() {
    NetworkProxyConfig p1 = new NetworkProxyConfig("a", true, "socks5", "h1", 1080, null, null, null, 1L);
    NetworkProxyConfig p2 = new NetworkProxyConfig("b", false, "socks5", "h2", 1080, "u", "env:X", "desc", 2L);
    ConfigRepository repo = new StubProxyRepo(null, null, name -> "a".equals(name) ? Future.succeededFuture(3L) : Future.succeededFuture(0L), List.of(p1, p2));
    AdminProxyResource res = resourceWithMocks(repo, new ConfigHolder());
    var resp = res.listProxies().await().indefinitely();
    assertEquals(200, resp.getStatus());
    @SuppressWarnings("unchecked")
    List<java.util.Map<String, Object>> body = (List<java.util.Map<String, Object>>) resp.getEntity();
    assertEquals(2, body.size());
    assertEquals("a", body.get(0).get("name"));
    assertEquals(3L, ((Number) body.get(0).get("connectorCount")).longValue());
    assertEquals(0L, ((Number) body.get(1).get("connectorCount")).longValue());
    // passwordRef (ref seulement) exposé, jamais de password
    assertEquals("env:X", body.get(1).get("passwordRef"));
    assertFalse(body.get(1).containsKey("password"));
  }

  @Test
  void getProxy404WhenUnknown() {
    ConfigHolder holder = new ConfigHolder();
    holder.set(ConfigSnapshot.empty());
    ConfigRepository repo = new StubProxyRepo(null, null, null, null);
    AdminProxyResource res = resourceWithMocks(repo, holder);
    var resp = res.getProxy("ghost").await().indefinitely();
    assertEquals(404, resp.getStatus());
  }

  @Test
  void getProxyDetailWithUsage() {
    NetworkProxyConfig p = new NetworkProxyConfig("px", true, "socks5", "h", 1080, "u", "file:/run/secrets/pw",
        "d", 4L);
    ConfigSnapshot base = ConfigSnapshot.empty();
    ConfigSnapshot snap = new ConfigSnapshot(base.root(), base.logging(), base.caProfilesGlobal(),
        base.profiles(), base.profileListeners(), base.profileCards(),
        base.connectionManager(), base.serviceMap(), base.connectors(), base.connectorServices(),
        base.cache(), base.clusterUdp(), base.webui(),
        base.plugins(), base.sidCacheLinks(), base.extendedNewcamd(),
        base.connectorCaidProfiles(), base.profileProviderIdents(),
        List.of(p), System.currentTimeMillis());
    ConfigHolder holder = new ConfigHolder();
    holder.set(snap);
    ConfigRepository repo = new StubProxyRepo(null, null, name -> Future.succeededFuture(7L), null);
    AdminProxyResource res = resourceWithMocks(repo, holder);
    var resp = res.getProxy("px").await().indefinitely();
    assertEquals(200, resp.getStatus());
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> body = (java.util.Map<String, Object>) resp.getEntity();
    assertEquals("px", body.get("name"));
    assertEquals(4L, ((Number) body.get("version")).longValue());
    assertEquals(7L, ((Number) body.get("connectorCount")).longValue());
    assertEquals("file:/run/secrets/pw", body.get("passwordRef"));
    assertFalse(body.containsKey("password"));
  }

  @Test
  void usageEndpoint() {
    NetworkProxyConfig p = new NetworkProxyConfig("px1", true, "socks5", "h", 1080, null, null, null, 1L);
    var base = ConfigSnapshot.empty();
    ConfigSnapshot snap = new ConfigSnapshot(base.root(), base.logging(), base.caProfilesGlobal(),
        base.profiles(), base.profileListeners(), base.profileCards(),
        base.connectionManager(), base.serviceMap(), base.connectors(), base.connectorServices(),
        base.cache(), base.clusterUdp(), base.webui(),
        base.plugins(), base.sidCacheLinks(), base.extendedNewcamd(),
        base.connectorCaidProfiles(), base.profileProviderIdents(),
        List.of(p), System.currentTimeMillis());
    ConfigHolder holder = new ConfigHolder();
    holder.set(snap);
    ConfigRepository repo = new StubProxyRepo(null, null, name -> Future.succeededFuture(2L), null);
    AdminProxyResource res = resourceWithMocks(repo, holder);
    var resp = res.proxyUsage("px1").await().indefinitely();
    assertEquals(200, resp.getStatus());
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> body = (java.util.Map<String, Object>) resp.getEntity();
    assertEquals("px1", body.get("proxyName"));
    assertEquals(2L, ((Number) body.get("connectorCount")).longValue());
  }

  @Test
  void usageUnknownProxy404() {
    ConfigHolder holder = new ConfigHolder();
    holder.set(ConfigSnapshot.empty()); // initialisé mais sans le proxy
    ConfigRepository repo = new StubProxyRepo(null, null, name -> Future.succeededFuture(2L), null);
    AdminProxyResource res = resourceWithMocks(repo, holder);
    var resp = res.proxyUsage("ghost").await().indefinitely();
    assertEquals(404, resp.getStatus());
    var err = (com.bowman.cspng.web.admin.dto.ErrorResponse) resp.getEntity();
    assertEquals("NOT_FOUND", err.code());
  }

  // -- DTO ne doit jamais porter de mot de passe en clair --

  @Test
  void proxyDtoHasNoPasswordField() {
    java.util.Set<String> names = java.util.Arrays.stream(AdminProxyResource.ProxyDto.class.getRecordComponents())
        .map(java.lang.reflect.RecordComponent::getName)
        .collect(java.util.stream.Collectors.toSet());
    assertFalse(names.contains("password"));
    assertTrue(names.contains("passwordRef"));
  }

  /** Repo qui fail tout upsert (pour les tests 400 — upsert ne doit jamais être appelé). */
  private static class StubProxyProxyFailHelper extends StubProxyRepo {
    StubProxyProxyFailHelper() {
      super(cfg -> { fail("upsertProxy must not be called on invalid input"); return null; },
          null, null, null);
    }
  }
}
