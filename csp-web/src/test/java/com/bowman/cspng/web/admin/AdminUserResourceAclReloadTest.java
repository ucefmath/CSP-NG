package com.bowman.cspng.web.admin;

import com.bowman.cspng.users.UserRecord;
import com.bowman.cspng.users.UserRepository;
import com.bowman.cspng.web.admin.dto.UpdateUserRequest;
import com.bowman.cspng.web.admin.dto.UserResponse;
import io.vertx.core.Future;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vérifie que AdminUserResource.update recharge le user complet après update
 * pour ne pas renvoyer {} sur ACL non fournies (fix review 5).
 */
class AdminUserResourceAclReloadTest {

    private SecurityContext mockCtx(String actor) {
        return new SecurityContext() {
            @Override public Principal getUserPrincipal() { return () -> actor; }
            @Override public boolean isUserInRole(String role) { return true; }
            @Override public boolean isSecure() { return false; }
            @Override public String getAuthenticationScheme() { return "BASIC"; }
        };
    }

    @Test
    void updateReloadsFullAclAfterPartialAllowed() {
        // partial record returned by repository.update (simule ancienne reconstruction incomplète)
        UserRecord partial = new UserRecord("u1", "U1", "", "a@b.c", true, false, false, false, 1, null, -1,
            Set.of(), Map.of("p1", Set.of(999)), Map.of(), Set.of(), 2L, null, null);
        // full record returned by get (après reload DB)
        UserRecord full = new UserRecord("u1", "U1", "", "a@b.c", true, false, false, false, 1, null, -1,
            Set.of("p1"), Map.of("p1", Set.of(999)), Map.of("p1", Set.of(200)), Set.of("c1"), 2L, null, null);

        UserRepository fake = new UserRepository(null, null, null) {
            @Override public Future<UserRecord> update(String name, UpdateUserRequest req, long expectedVersion, String actor) {
                return Future.succeededFuture(partial);
            }
            @Override public Future<Optional<UserRecord>> get(String name) {
                assertEquals("u1", name);
                return Future.succeededFuture(Optional.of(full));
            }
        };

        AdminUserResource res = new AdminUserResource();
        res.userRepository = fake;
        res.securityContext = mockCtx("admin");

        UpdateUserRequest dto = new UpdateUserRequest(null, null, null, null, null, null, null, null, null, null,
            null, Map.of("p1", Set.of(999)), null, null);
        Response r = res.update("u1", dto, 1L).await().atMost(java.time.Duration.ofSeconds(2));
        assertEquals(200, r.getStatus());
        UserResponse body = (UserResponse) r.getEntity();
        assertNotNull(body);
        // Doit contenir blockedServices du reload complet, pas {} du partial
        assertEquals(Set.of("p1"), body.profiles());
        assertEquals(Set.of(999), body.allowedServices().get("p1"));
        assertEquals(Set.of(200), body.blockedServices().get("p1"), "blocked should be kept via reload");
        assertEquals(Set.of("c1"), body.allowedConnectors());
    }

    @Test
    void updateFallbackToPartialIfGetEmpty() {
        UserRecord partial = new UserRecord("u1", "U1", "", "a@b.c", true, false, false, false, 1, null, -1,
            Set.of(), Map.of("p1", Set.of(111)), Map.of(), Set.of(), 2L, null, null);
        UserRepository fake = new UserRepository(null, null, null) {
            @Override public Future<UserRecord> update(String name, UpdateUserRequest req, long expectedVersion, String actor) {
                return Future.succeededFuture(partial);
            }
            @Override public Future<Optional<UserRecord>> get(String name) {
                return Future.succeededFuture(Optional.empty());
            }
        };
        AdminUserResource res = new AdminUserResource();
        res.userRepository = fake;
        res.securityContext = mockCtx("admin");
        UpdateUserRequest dto = new UpdateUserRequest(null, null, null, null, null, null, null, null, null, null,
            null, Map.of("p1", Set.of(111)), null, null);
        Response r = res.update("u1", dto, 1L).await().atMost(java.time.Duration.ofSeconds(2));
        assertEquals(200, r.getStatus());
        UserResponse body = (UserResponse) r.getEntity();
        assertEquals(Map.of("p1", Set.of(111)), body.allowedServices());
        assertEquals(Map.of(), body.blockedServices(), "fallback keeps partial blocked {}");
    }
}
