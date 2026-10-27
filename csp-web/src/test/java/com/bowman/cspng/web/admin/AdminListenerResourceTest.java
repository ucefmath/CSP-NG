package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.*;
import io.vertx.core.Future;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AdminConfigResource — Listeners CRUD REST API (P9)")
class AdminListenerResourceTest {

    private SecurityContext mockCtx(String actor) {
        return new SecurityContext() {
            @Override public Principal getUserPrincipal() { return () -> actor; }
            @Override public boolean isUserInRole(String role) { return true; }
            @Override public boolean isSecure() { return false; }
            @Override public String getAuthenticationScheme() { return "BASIC"; }
        };
    }

    @Test
    @DisplayName("GET listeners — retourne liste des listeners du snapshot")
    void listListenersFromSnapshot() {
        AdminConfigResource res = new AdminConfigResource();
        ConfigHolder holder = new ConfigHolder();
        ProfileListenerConfig l1 = new ProfileListenerConfig(
            1L, "default", "cccam", 12000, "0.0.0.0", false, null, null, null, 1L,
            "manual", true, 0, 512, 50, 5, null, 60
        );
        ConfigSnapshot snap = new ConfigSnapshot(
            new RootConfig("proxy", 1, 1L),
            LoggingConfig.DEFAULT,
            CaProfilesGlobalConfig.DEFAULT,
            List.of(),
            List.of(l1),
            List.of(),
            ConnectionManagerConfig.DEFAULT,
            List.of(),
            List.of(),
            List.of(),
            CacheConfig.DEFAULT,
            ClusterUdpConfig.DEFAULT,
            WebUiConfig.DEFAULT,
            List.of(),
            List.of(),
            ExtendedNewcamdConfig.DEFAULT,
            List.of(),
            List.of(),
            List.of(),
            System.currentTimeMillis()
        );
        holder.set(snap);

        res.configHolder = holder;
        res.securityContext = mockCtx("admin");

        Response r = res.listListeners().await().indefinitely();
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        List<ProfileListenerConfig> list = (List<ProfileListenerConfig>) r.getEntity();
        assertEquals(1, list.size());
        assertEquals("cccam", list.get(0).protocol());
        assertEquals(12000, list.get(0).port());
        assertFalse(list.get(0).enabled());
    }

    @Test
    @DisplayName("PUT listener — validation d'entrée (rejet si port ou version invalide)")
    void putListenerInvalidInput() {
        AdminConfigResource res = new AdminConfigResource();
        res.securityContext = mockCtx("admin");

        AdminConfigResource.ListenerDto badPort = new AdminConfigResource.ListenerDto(
            "default", "cccam", 99999, "0.0.0.0", false, null, null, null, "manual", true, 0, 512, 50, 5, null, 60, 1L
        );
        Response r1 = res.upsertListener(0L, badPort).await().indefinitely();
        assertEquals(400, r1.getStatus());

        AdminConfigResource.ListenerDto badVersion = new AdminConfigResource.ListenerDto(
            "default", "cccam", 12000, "0.0.0.0", false, null, null, null, "manual", true, 0, 512, 50, 5, null, 60, 0L
        );
        Response r2 = res.upsertListener(1L, badVersion).await().indefinitely();
        assertEquals(400, r2.getStatus());
    }

    @Test
    @DisplayName("PUT listener — création et mise à jour réussies")
    void putListenerSuccess() {
        ConfigRepository repo = new ConfigRepository(null) {
            @Override
            public Future<Long> upsertListener(ProfileListenerConfig l, String actor) {
                assertEquals("admin", actor);
                return Future.succeededFuture(l.version() + 1);
            }
        };

        AdminConfigResource res = new AdminConfigResource();
        res.configRepository = repo;
        res.securityContext = mockCtx("admin");

        AdminConfigResource.ListenerDto dto = new AdminConfigResource.ListenerDto(
            "default", "cs378x", 13000, "0.0.0.0", false, null, null, null, "wildcard", true, 2, 512, 50, 5, null, 60, 1L
        );
        Response r = res.upsertListener(0L, dto).await().indefinitely();
        assertEquals(200, r.getStatus());
        AdminConfigResource.VersionResponse resp = (AdminConfigResource.VersionResponse) r.getEntity();
        assertEquals(2L, resp.version());
    }
}
