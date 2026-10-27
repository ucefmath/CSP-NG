package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.*;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.Test;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class V3ExtendedApiTest {

    private AdminConfigResource resourceWithMocks(ConfigRepository repo, ConfigHolder holder) {
        AdminConfigResource r = new AdminConfigResource();
        try {
            var f1 = AdminConfigResource.class.getDeclaredField("configRepository");
            f1.setAccessible(true); f1.set(r, repo);
            var f2 = AdminConfigResource.class.getDeclaredField("configHolder");
            f2.setAccessible(true); f2.set(r, holder);
            var f3 = AdminConfigResource.class.getDeclaredField("securityContext");
            f3.setAccessible(true);
            SecurityContext sc = new SecurityContext() {
                public Principal getUserPrincipal(){ return () -> "tester"; }
                public boolean isUserInRole(String role){ return true; }
                public boolean isSecure(){ return false; }
                public String getAuthenticationScheme(){ return "BASIC"; }
            };
            f3.set(r, sc);
        } catch (Exception e) { throw new RuntimeException(e); }
        return r;
    }

    private static class StubRepo extends ConfigRepository {
        private final java.util.function.Function<ExtendedNewcamdConfig, Future<Long>> extHandler;
        private final java.util.function.BiFunction<ConnectorConfig, List<ConnectorCaidProfileConfig>, Future<Long>> dynHandler;
        private final java.util.function.Function<ConnectorConfig, Future<Long>> fixedHandler;
        StubRepo(Pool pool, java.util.function.Function<ExtendedNewcamdConfig, Future<Long>> extHandler,
                 java.util.function.BiFunction<ConnectorConfig, List<ConnectorCaidProfileConfig>, Future<Long>> dynHandler,
                 java.util.function.Function<ConnectorConfig, Future<Long>> fixedHandler) {
            super(pool);
            this.extHandler = extHandler; this.dynHandler = dynHandler; this.fixedHandler = fixedHandler;
        }
        StubRepo(){ super((Pool)null); this.extHandler=null; this.dynHandler=null; this.fixedHandler=null; }
        @Override public Future<Long> updateExtendedNewcamd(ExtendedNewcamdConfig cfg, String actor){ return extHandler!=null?extHandler.apply(cfg):Future.failedFuture(new IllegalStateException("not stubbed")); }
        @Override public Future<Long> upsertConnectorWithCaidMap(ConnectorConfig c, List<ConnectorCaidProfileConfig> m, String actor){ return dynHandler!=null?dynHandler.apply(c,m):Future.failedFuture(new IllegalStateException("not stubbed")); }
        @Override public Future<Long> upsertConnector(ConnectorConfig c, String actor){ return fixedHandler!=null?fixedHandler.apply(c):Future.failedFuture(new IllegalStateException("not stubbed")); }
    }

    @Test
    void extendedInvalidPortReturns400() {
        ConfigRepository repo = new StubRepo(){ @Override public Future<Long> updateExtendedNewcamd(ExtendedNewcamdConfig cfg, String actor){ fail("should not be called"); return null; }};
        ConfigHolder holder = new ConfigHolder();
        holder.set(ConfigSnapshot.empty());
        AdminConfigResource res = resourceWithMocks(repo, holder);
        var dto = new AdminConfigResource.ExtendedNewcamdDto(true, 99999, "0.0.0.0", false, false, "env:DES", null, null, 2048, 1L);
        var resp = res.updateExtended(dto).await().indefinitely();
        assertEquals(400, resp.getStatus());
    }

    @Test
    void extendedMissingDesWhenEnabled400() {
        ConfigRepository repo = new StubRepo(){ @Override public Future<Long> updateExtendedNewcamd(ExtendedNewcamdConfig cfg, String actor){ fail("should not be called"); return null; }};
        ConfigHolder holder = new ConfigHolder();
        holder.set(ConfigSnapshot.empty());
        AdminConfigResource res = resourceWithMocks(repo, holder);
        var dto = new AdminConfigResource.ExtendedNewcamdDto(true, 2233, "0.0.0.0", false, false, null, null, null, 2048, 1L);
        var resp = res.updateExtended(dto).await().indefinitely();
        assertEquals(400, resp.getStatus());
    }

    @Test
    void extendedCidrReturns400() {
        ConfigRepository repo = new StubRepo(){ @Override public Future<Long> updateExtendedNewcamd(ExtendedNewcamdConfig cfg, String actor){ fail("should not be called"); return null; }};
        ConfigHolder holder = new ConfigHolder();
        holder.set(ConfigSnapshot.empty());
        AdminConfigResource res = resourceWithMocks(repo, holder);
        var dto = new AdminConfigResource.ExtendedNewcamdDto(true, 2233, "192.168.1.1/24", false, false, "env:DES", null, null, 2048, 1L);
        var resp = res.updateExtended(dto).await().indefinitely();
        assertEquals(400, resp.getStatus());
        var dto2 = new AdminConfigResource.ExtendedNewcamdDto(true, 2233, "0", false, false, "env:DES", null, null, 2048, 1L);
        var resp2 = res.updateExtended(dto2).await().indefinitely();
        assertEquals(400, resp2.getStatus());
    }

    @Test
    void extendedSuccessCallsRepo() {
        ConfigRepository repo = new StubRepo(null, cfg -> Future.succeededFuture(2L), null, null);
        ConfigHolder holder = new ConfigHolder();
        holder.set(ConfigSnapshot.empty());
        AdminConfigResource res = resourceWithMocks(repo, holder);
        var dto = new AdminConfigResource.ExtendedNewcamdDto(true, 2233, "0.0.0.0", true, false, "env:DES", null, null, 2048, 1L);
        var resp = res.updateExtended(dto).await().indefinitely();
        assertEquals(200, resp.getStatus());
    }

    @Test
    void dynamicConnectorEmptyMap400() {
        ConfigRepository repo = new StubRepo(){ @Override public Future<Long> upsertConnectorWithCaidMap(ConnectorConfig c, List<ConnectorCaidProfileConfig> m, String actor){ fail("should not be called"); return null; }};
        ConfigHolder holder = new ConfigHolder();
        holder.set(ConfigSnapshot.empty());
        AdminConfigResource res = resourceWithMocks(repo, holder);
        var dto = new AdminConfigResource.ConnectorDto(null, "newcamd", true, false, "127.0.0.1", 15000, null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L, null,List.of());
        var resp = res.upsertConnector("c1", dto).await().indefinitely();
        assertEquals(400, resp.getStatus());
    }

    @Test
    void dynamicConnectorDuplicateCaId400() {
        ConfigRepository repo = new StubRepo(){ @Override public Future<Long> upsertConnectorWithCaidMap(ConnectorConfig c, List<ConnectorCaidProfileConfig> m, String actor){ fail(); return null; }};
        ConfigHolder holder = new ConfigHolder();
        holder.set(ConfigSnapshot.empty());
        AdminConfigResource res = resourceWithMocks(repo, holder);
        var map = List.of(
            new AdminConfigResource.CaidProfileEntryDto(0, 100, "p1"),
            new AdminConfigResource.CaidProfileEntryDto(1, 100, "p1")
        );
        var dto = new AdminConfigResource.ConnectorDto(null, "newcamd", true, false, "127.0.0.1", 15000, null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L, null,map);
        var resp = res.upsertConnector("c1", dto).await().indefinitely();
        assertEquals(400, resp.getStatus());
    }

    @Test
    void dynamicConnectorCallsAtomic() {
        final boolean[] called = {false};
        ConfigRepository repo = new StubRepo(null, null, (c,m) -> { called[0]=true; return Future.succeededFuture(2L); }, null);
        ConfigHolder holder = new ConfigHolder();
        holder.set(ConfigSnapshot.empty());
        AdminConfigResource res = resourceWithMocks(repo, holder);
        var map = List.of(new AdminConfigResource.CaidProfileEntryDto(0, 100, "p1"));
        var dto = new AdminConfigResource.ConnectorDto(null, "newcamd", true, false, "127.0.0.1", 15000, null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L, null,map);
        var resp = res.upsertConnector("dyn", dto).await().indefinitely();
        assertEquals(200, resp.getStatus());
        assertTrue(called[0]);
    }

    @Test
    void fixedConnectorWithMap400() {
        ConfigRepository repo = new StubRepo(){ @Override public Future<Long> upsertConnector(ConnectorConfig c, String actor){ fail(); return null; }};
        ConfigHolder holder = new ConfigHolder();
        holder.set(ConfigSnapshot.empty());
        AdminConfigResource res = resourceWithMocks(repo, holder);
        var map = List.of(new AdminConfigResource.CaidProfileEntryDto(0, 100, "p1"));
        var dto = new AdminConfigResource.ConnectorDto("p1", "newcamd", true, false, "127.0.0.1", 15000, null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L, null,map);
        var resp = res.upsertConnector("c1", dto).await().indefinitely();
        assertEquals(400, resp.getStatus());
    }

    @Test
    void fixedConnectorCallsUpsertFixed() {
        final boolean[] called={false};
        ConfigRepository repo = new StubRepo(null,null,null, c->{ called[0]=true; return Future.succeededFuture(2L);});
        ConfigHolder holder = new ConfigHolder();
        holder.set(ConfigSnapshot.empty());
        AdminConfigResource res = resourceWithMocks(repo, holder);
        var dto = new AdminConfigResource.ConnectorDto("p1", "newcamd", true, false, "127.0.0.1", 15000, null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L, null,null);
        var resp = res.upsertConnector("c1", dto).await().indefinitely();
        assertEquals(200, resp.getStatus());
        assertTrue(called[0]);
    }

    @Test
    void getMap404() {
        ConfigRepository repo = new StubRepo();
        ConfigHolder holder = new ConfigHolder();
        holder.set(ConfigSnapshot.empty());
        AdminConfigResource res = resourceWithMocks(repo, holder);
        var resp = res.getCaidMap("noexist").await().indefinitely();
        assertEquals(404, resp.getStatus());
    }

    @Test
    void replaceMapFixed400() {
        ConfigRepository repo = new ConfigRepository(null) {
            @Override public Future<Long> replaceCaidProfileMap(String connectorName, List<ConnectorCaidProfileConfig> entries, long expectedConnectorVersion, String actor){
                return Future.failedFuture(new ConfigValidationException("Fixed connector cannot have CAID map"));
            }
        };
        ConfigHolder holder = new ConfigHolder();
        ProfileConfig p = new ProfileConfig("p1", true,false,false,0,0,null,null,5000L,null,false,0,null,null,null,null,1L);
        ConnectorConfig c = new ConnectorConfig("c1","p1","newcamd",true,false,"h",15000,null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L);
        holder.set(new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(c), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis()));
        AdminConfigResource res = resourceWithMocks(repo, holder);
        var dto = new AdminConfigResource.CaidProfileMapDto(List.of(new AdminConfigResource.CaidProfileEntryDto(0,100,"p1")), 1L);
        var resp = res.replaceCaidMap("c1", dto).await().indefinitely();
        assertEquals(400, resp.getStatus());
    }

    @Test
    void snapshotNotInitializedReturns503() {
        ConfigRepository repo = new StubRepo();
        ConfigHolder holder = new ConfigHolder(); // not initialized
        AdminConfigResource res = resourceWithMocks(repo, holder);
        assertEquals(503, res.snapshot().await().indefinitely().getStatus());
        assertEquals(503, res.getExtended().await().indefinitely().getStatus());
        assertEquals(503, res.getProviderIdents("p1").await().indefinitely().getStatus());
        assertEquals(503, res.getCaidMap("c1").await().indefinitely().getStatus());
    }

    @Test
    void staleSnapshotDoesNotBlockReplaceWhenRepoSucceeds() {
        ConfigRepository repo = new ConfigRepository(null) {
            @Override public Future<Long> replaceCaidProfileMap(String connectorName, List<ConnectorCaidProfileConfig> entries, long expectedConnectorVersion, String actor){
                return Future.succeededFuture(5L);
            }
        };
        ConfigHolder holder = new ConfigHolder();
        // stale snapshot says c1 is fixed, but DB (repo) says dynamic and will succeed
        ProfileConfig p = new ProfileConfig("p1", true,false,false,0,0,null,null,5000L,null,false,0,null,null,null,null,1L);
        ConnectorConfig fixed = new ConnectorConfig("c1","p1","newcamd",true,false,"h",15000,null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,2L);
        holder.set(new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(fixed), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis()));
        AdminConfigResource res = resourceWithMocks(repo, holder);
        var dto = new AdminConfigResource.CaidProfileMapDto(List.of(new AdminConfigResource.CaidProfileEntryDto(0,100,"p1")), 2L);
        var resp = res.replaceCaidMap("c1", dto).await().indefinitely();
        assertEquals(200, resp.getStatus());
    }

    @Test
    void providerGetMapAnd404() {
        ConfigRepository repo = new StubRepo();
        ConfigHolder holder = new ConfigHolder();
        holder.set(ConfigSnapshot.empty());
        AdminConfigResource res = resourceWithMocks(repo, holder);
        var resp404 = res.getProviderIdents("nope").await().indefinitely();
        assertEquals(404, resp404.getStatus());
        ProfileConfig p = new ProfileConfig("p1", true,false,false,0,0,null,null,5000L,null,false,0,null,null,null,null,1L);
        holder.set(new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis()));
        var resp200 = res.getProviderIdents("p1").await().indefinitely();
        assertEquals(200, resp200.getStatus());
    }

    @Test
    void secretNeverResolvedInSnapshot() {
        ConfigHolder holder = new ConfigHolder();
        var snap = ConfigSnapshot.empty();
        holder.set(snap);
        assertTrue(snap.extendedNewcamd().desKeyRef() == null || snap.extendedNewcamd().desKeyRef().startsWith("env:") || snap.extendedNewcamd().desKeyRef().isBlank());
        ConfigRepository repo = new StubRepo();
        AdminConfigResource res = resourceWithMocks(repo, holder);
        var resp = res.snapshot().await().indefinitely();
        assertEquals(200, resp.getStatus());
        String body = resp.getEntity().toString();
        assertFalse(body.contains("plainSecret"));
    }
}
