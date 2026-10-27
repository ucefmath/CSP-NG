package com.bowman.cspng.web.admin;

import com.bowman.cspng.users.UserDirectory;
import com.bowman.cspng.users.UserRecord;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.Future;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.CookieImpl;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AdminCookieAuthFailureHandlingTest — Gestion des pannes DB et révocations")
class AdminCookieAuthFailureHandlingTest {

    private AdminSessionManager sessionManager;
    private UserRecord adminUser;

    @BeforeEach
    void setUp() {
        sessionManager = new AdminSessionManager(null, Duration.ofHours(8), Duration.ofHours(24), 5, 1000);
        adminUser = new UserRecord("admin", "Administrator", "$2a$12$hash", null, true, true, false, false, 5, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null);
    }

    static class FakeRoutingContext {
        static RoutingContext create(String token) {
            return (RoutingContext) java.lang.reflect.Proxy.newProxyInstance(
                RoutingContext.class.getClassLoader(),
                new Class<?>[]{RoutingContext.class},
                (proxy, method, args) -> {
                    if ("request".equals(method.getName())) {
                        return createRequest(token);
                    }
                    return null;
                }
            );
        }

        private static HttpServerRequest createRequest(String token) {
            Cookie cookie = new CookieImpl("CSPADMINSESSION", token);
            return (HttpServerRequest) java.lang.reflect.Proxy.newProxyInstance(
                HttpServerRequest.class.getClassLoader(),
                new Class<?>[]{HttpServerRequest.class},
                (proxy, method, args) -> {
                    if ("getHeader".equals(method.getName())) {
                        String name = (String) args[0];
                        if ("Authorization".equalsIgnoreCase(name)) return null;
                        if ("Cookie".equalsIgnoreCase(name)) return "CSPADMINSESSION=" + token;
                        return null;
                    }
                    if ("getCookie".equals(method.getName())) {
                        String name = (String) args[0];
                        if ("CSPADMINSESSION".equals(name)) return cookie;
                        return null;
                    }
                    return null;
                }
            );
        }
    }

    @Test
    @DisplayName("Erreur DB : fail-closed (null) SANS révocation de la session, puis recovery")
    void dbFailureDoesNotRevokeSessionAndRecovers() {
        AdminSession session = sessionManager.createSession(adminUser);
        String token = session.token();
        assertTrue(sessionManager.getSession(token).isPresent());

        AtomicBoolean dbDown = new AtomicBoolean(true);
        UserDirectory fakeDir = new UserDirectory() {
            @Override
            public Future<Optional<UserRecord>> lookup(String name) {
                return lookupFresh(name);
            }

            @Override
            public Future<Optional<UserRecord>> lookupFresh(String name) {
                if (dbDown.get()) {
                    return Future.failedFuture(new RuntimeException("PostgreSQL connection failure simulation"));
                }
                return Future.succeededFuture(Optional.of(adminUser));
            }

            @Override
            public UserRecord lookupCached(String name) {
                return null;
            }

            @Override
            public Future<Boolean> authenticate(String name, String pass) {
                return Future.succeededFuture(true);
            }

            @Override
            public Future<Void> reload() {
                return Future.succeededFuture();
            }
        };

        AdminCookieAuthenticationMechanism mechanism = new AdminCookieAuthenticationMechanism(sessionManager, fakeDir);
        RoutingContext rc = FakeRoutingContext.create(token);

        // 1. Quand la DB est down : retourne nullItem() (401 transitoire)
        SecurityIdentity identity = mechanism.authenticate(rc, null).await().indefinitely();
        assertNull(identity, "Should be null during DB failure (fail-closed)");

        // 2. La session NE DOIT PAS avoir été révoquée
        assertTrue(sessionManager.getSession(token).isPresent(), "Session must NOT be revoked during transient DB failure");

        // 3. Quand la DB revient : recovery, authentification réussie avec la même session
        dbDown.set(false);
        SecurityIdentity recoveredIdentity = mechanism.authenticate(rc, null).await().indefinitely();
        assertNotNull(recoveredIdentity, "Should succeed once DB recovers");
        assertEquals("admin", recoveredIdentity.getPrincipal().getName());
        assertTrue(recoveredIdentity.hasRole("admin"));
    }

    @Test
    @DisplayName("Utilisateur absent ou désactivé : révocation immédiate de la session")
    void userDisabledImmediatelyRevokesSession() {
        AdminSession session = sessionManager.createSession(adminUser);
        String token = session.token();
        assertTrue(sessionManager.getSession(token).isPresent());

        UserRecord disabledUser = new UserRecord("admin", "Administrator", "$2a$12$hash", null, false, true, false, false, 5, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), 2L, null, null);
        UserDirectory fakeDir = new UserDirectory() {
            @Override public Future<Optional<UserRecord>> lookup(String name) { return lookupFresh(name); }
            @Override public Future<Optional<UserRecord>> lookupFresh(String name) { return Future.succeededFuture(Optional.of(disabledUser)); }
            @Override public UserRecord lookupCached(String name) { return null; }
            @Override public Future<Boolean> authenticate(String name, String pass) { return Future.succeededFuture(false); }
            @Override public Future<Void> reload() { return Future.succeededFuture(); }
        };

        AdminCookieAuthenticationMechanism mechanism = new AdminCookieAuthenticationMechanism(sessionManager, fakeDir);
        RoutingContext rc = FakeRoutingContext.create(token);

        SecurityIdentity identity = mechanism.authenticate(rc, null).await().indefinitely();
        assertNull(identity);

        // La session DOIT avoir été révoquée immédiatement
        assertTrue(sessionManager.getSession(token).isEmpty(), "Session MUST be revoked when user is disabled");
    }

    @Test
    @DisplayName("Changement de version ou perte du rôle admin : révocation immédiate")
    void versionChangeRevokesSession() {
        AdminSession session = sessionManager.createSession(adminUser);
        String token = session.token();

        UserRecord nonAdminUser = new UserRecord("admin", "Administrator", "$2a$12$hash", null, true, false, false, false, 5, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null);
        UserDirectory fakeDir = new UserDirectory() {
            @Override public Future<Optional<UserRecord>> lookup(String name) { return lookupFresh(name); }
            @Override public Future<Optional<UserRecord>> lookupFresh(String name) { return Future.succeededFuture(Optional.of(nonAdminUser)); }
            @Override public UserRecord lookupCached(String name) { return null; }
            @Override public Future<Boolean> authenticate(String name, String pass) { return Future.succeededFuture(false); }
            @Override public Future<Void> reload() { return Future.succeededFuture(); }
        };

        AdminCookieAuthenticationMechanism mechanism = new AdminCookieAuthenticationMechanism(sessionManager, fakeDir);
        RoutingContext rc = FakeRoutingContext.create(token);

        SecurityIdentity identity = mechanism.authenticate(rc, null).await().indefinitely();
        assertNull(identity);
        assertTrue(sessionManager.getSession(token).isEmpty(), "Session MUST be revoked when admin flag is removed");
    }
}
