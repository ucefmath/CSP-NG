package com.bowman.cspng.web.admin;

import com.bowman.cspng.users.UserNotFoundException;
import com.bowman.cspng.users.UserOptimisticLockException;
import com.bowman.cspng.users.UserProtocolSecretRepository;
import com.bowman.cspng.users.UserProtocolSecretRepository.Protocol;
import com.bowman.cspng.users.UserProtocolSecretRepository.ProtocolSecretRecord;
import com.bowman.cspng.users.UserProtocolSecretRepository.SecretCollisionException;
import com.bowman.cspng.users.UserRecord;
import com.bowman.cspng.users.UserRepository;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import com.bowman.cspng.web.admin.dto.ProtocolSecretResponse;
import com.bowman.cspng.web.admin.dto.UpsertProtocolSecretRequest;
import io.vertx.core.Future;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AdminUserSecretResource — REST Admin CRUD pour secrets protocoles (refs only)")
class AdminUserSecretResourceTest {

    private SecurityContext mockCtx(String actor) {
        return new SecurityContext() {
            @Override public Principal getUserPrincipal() { return () -> actor; }
            @Override public boolean isUserInRole(String role) { return true; }
            @Override public boolean isSecure() { return false; }
            @Override public String getAuthenticationScheme() { return "BASIC"; }
        };
    }

    private UserRecord dummyUser(String name) {
        return new UserRecord(name, "User " + name, "", "test@domain.com", true, false, false, false,
            1, null, -1, Set.of(), java.util.Map.of(), java.util.Map.of(), Set.of(), 1L, null, null);
    }

    @Test
    @DisplayName("GET secrets list — 404 si user inexistant")
    void listUserNotFound404() {
        UserRepository userRepo = new UserRepository(null, null, null) {
            @Override public Future<Optional<UserRecord>> get(String name) {
                return Future.succeededFuture(Optional.empty());
            }
        };
        UserProtocolSecretRepository secretRepo = new UserProtocolSecretRepository(null);

        AdminUserSecretResource res = new AdminUserSecretResource();
        res.userRepository = userRepo;
        res.secretRepository = secretRepo;
        res.securityContext = mockCtx("admin");

        Response resp = res.list("nonexistent").await().indefinitely();
        assertEquals(404, resp.getStatus());
    }

    @Test
    @DisplayName("GET secrets list — 200 avec liste de ProtocolSecretResponse (refs only)")
    void listReturnsSecrets() {
        UserRepository userRepo = new UserRepository(null, null, null) {
            @Override public Future<Optional<UserRecord>> get(String name) {
                return Future.succeededFuture(Optional.of(dummyUser(name)));
            }
        };
        ProtocolSecretRecord rec = new ProtocolSecretRecord("alice", Protocol.CCAM, "file:/run/secrets/alice",
            1L, null, true, null, 1L, "admin", Instant.now());
        UserProtocolSecretRepository secretRepo = new UserProtocolSecretRepository(null) {
            @Override public Future<List<ProtocolSecretRecord>> listByUser(String userName) {
                return Future.succeededFuture(List.of(rec));
            }
        };

        AdminUserSecretResource res = new AdminUserSecretResource();
        res.userRepository = userRepo;
        res.secretRepository = secretRepo;
        res.securityContext = mockCtx("admin");

        Response resp = res.list("alice").await().indefinitely();
        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        List<ProtocolSecretResponse> list = (List<ProtocolSecretResponse>) resp.getEntity();
        assertEquals(1, list.size());
        assertEquals("cccam", list.get(0).protocol());
        assertEquals("file:/run/secrets/alice", list.get(0).secretRef());
        assertNull(list.get(0).ucrc());
    }

    @Test
    @DisplayName("GET secret par protocole — 400 si protocole inconnu")
    void getInvalidProtocol400() {
        AdminUserSecretResource res = new AdminUserSecretResource();
        res.securityContext = mockCtx("admin");

        Response resp = res.get("alice", "unknown_proto").await().indefinitely();
        assertEquals(400, resp.getStatus());
    }

    @Test
    @DisplayName("GET secret par protocole — 404 si secret absent")
    void getSecretNotFound404() {
        UserRepository userRepo = new UserRepository(null, null, null) {
            @Override public Future<Optional<UserRecord>> get(String name) {
                return Future.succeededFuture(Optional.of(dummyUser(name)));
            }
        };
        UserProtocolSecretRepository secretRepo = new UserProtocolSecretRepository(null) {
            @Override public Future<Optional<ProtocolSecretRecord>> get(String userName, Protocol protocol) {
                return Future.succeededFuture(Optional.empty());
            }
        };

        AdminUserSecretResource res = new AdminUserSecretResource();
        res.userRepository = userRepo;
        res.secretRepository = secretRepo;
        res.securityContext = mockCtx("admin");

        Response resp = res.get("alice", "cccam").await().indefinitely();
        assertEquals(404, resp.getStatus());
    }

    @Test
    @DisplayName("PUT secret — 400 si nom d'utilisateur dépasse la limite protocole (CCcam <= 20)")
    void putUsernameTooLong400() {
        AdminUserSecretResource res = new AdminUserSecretResource();
        res.securityContext = mockCtx("admin");

        String longName = "a".repeat(21);
        UpsertProtocolSecretRequest dto = new UpsertProtocolSecretRequest("env:MY_SECRET", null, true);
        Response resp = res.upsert(longName, "cccam", dto, 0L).await().indefinitely();
        assertEquals(400, resp.getStatus());
    }

    @Test
    @DisplayName("PUT secret — 201 Created lors de la création (version 0)")
    void putCreateReturns201() {
        UserRepository userRepo = new UserRepository(null, null, null) {
            @Override public Future<Optional<UserRecord>> get(String name) {
                return Future.succeededFuture(Optional.of(dummyUser(name)));
            }
        };
        ProtocolSecretRecord created = new ProtocolSecretRecord("alice", Protocol.CCAM, "env:ALICE_PASS",
            1L, null, true, null, 1L, "admin", Instant.now());
        UserProtocolSecretRepository secretRepo = new UserProtocolSecretRepository(null) {
            @Override public Future<ProtocolSecretRecord> upsert(String userName, Protocol protocol,
                                                                UserProtocolSecretRepository.UpsertProtocolSecretRequest req,
                                                                long expectedVersion, String actor) {
                assertEquals(0L, expectedVersion);
                assertEquals("admin", actor);
                return Future.succeededFuture(created);
            }
        };

        AdminUserSecretResource res = new AdminUserSecretResource();
        res.userRepository = userRepo;
        res.secretRepository = secretRepo;
        res.securityContext = mockCtx("admin");

        UpsertProtocolSecretRequest dto = new UpsertProtocolSecretRequest("env:ALICE_PASS", null, true);
        Response resp = res.upsert("alice", "cccam", dto, 0L).await().indefinitely();
        assertEquals(201, resp.getStatus());
        ProtocolSecretResponse body = (ProtocolSecretResponse) resp.getEntity();
        assertEquals("alice", body.userName());
        assertEquals("cccam", body.protocol());
        assertEquals("env:ALICE_PASS", body.secretRef());
        assertEquals(1L, body.version());
    }

    @Test
    @DisplayName("PUT secret — 200 OK lors de l'update (version > 0)")
    void putUpdateReturns200() {
        UserRepository userRepo = new UserRepository(null, null, null) {
            @Override public Future<Optional<UserRecord>> get(String name) {
                return Future.succeededFuture(Optional.of(dummyUser(name)));
            }
        };
        ProtocolSecretRecord updated = new ProtocolSecretRecord("bob", Protocol.CS378X, "file:/run/secrets/bob_v2",
            2L, 0x12345678L, true, null, 2L, "admin", Instant.now());
        UserProtocolSecretRepository secretRepo = new UserProtocolSecretRepository(null) {
            @Override public Future<ProtocolSecretRecord> upsert(String userName, Protocol protocol,
                                                                UserProtocolSecretRepository.UpsertProtocolSecretRequest req,
                                                                long expectedVersion, String actor) {
                assertEquals(1L, expectedVersion);
                return Future.succeededFuture(updated);
            }
        };

        AdminUserSecretResource res = new AdminUserSecretResource();
        res.userRepository = userRepo;
        res.secretRepository = secretRepo;
        res.securityContext = mockCtx("admin");

        UpsertProtocolSecretRequest dto = new UpsertProtocolSecretRequest("file:/run/secrets/bob_v2", null, true);
        Response resp = res.upsert("bob", "cs378x", dto, 1L).await().indefinitely();
        assertEquals(200, resp.getStatus());
        ProtocolSecretResponse body = (ProtocolSecretResponse) resp.getEntity();
        assertEquals("cs378x", body.protocol());
        assertEquals("file:/run/secrets/bob_v2", body.secretRef());
        assertEquals(2L, body.secretVersion());
        assertEquals(2L, body.version());
    }

    @Test
    @DisplayName("POST revoke — 200 OK avec enabled=false")
    void revokeReturns200() {
        UserRepository userRepo = new UserRepository(null, null, null) {
            @Override public Future<Optional<UserRecord>> get(String name) {
                return Future.succeededFuture(Optional.of(dummyUser(name)));
            }
        };
        ProtocolSecretRecord revoked = new ProtocolSecretRecord("alice", Protocol.CCAM, "file:/run/secrets/alice",
            1L, null, false, null, 2L, "admin", Instant.now());
        UserProtocolSecretRepository secretRepo = new UserProtocolSecretRepository(null) {
            @Override public Future<ProtocolSecretRecord> revoke(String userName, Protocol protocol,
                                                                long expectedVersion, String actor) {
                assertEquals(1L, expectedVersion);
                return Future.succeededFuture(revoked);
            }
        };

        AdminUserSecretResource res = new AdminUserSecretResource();
        res.userRepository = userRepo;
        res.secretRepository = secretRepo;
        res.securityContext = mockCtx("admin");

        Response resp = res.revoke("alice", "cccam", 1L).await().indefinitely();
        assertEquals(200, resp.getStatus());
        ProtocolSecretResponse body = (ProtocolSecretResponse) resp.getEntity();
        assertFalse(body.enabled());
        assertEquals(2L, body.version());
    }

    @Test
    @DisplayName("DELETE — 204 No Content")
    void deleteReturns204() {
        UserRepository userRepo = new UserRepository(null, null, null) {
            @Override public Future<Optional<UserRecord>> get(String name) {
                return Future.succeededFuture(Optional.of(dummyUser(name)));
            }
        };
        UserProtocolSecretRepository secretRepo = new UserProtocolSecretRepository(null) {
            @Override public Future<Void> delete(String userName, Protocol protocol, long expectedVersion) {
                assertEquals(2L, expectedVersion);
                return Future.succeededFuture();
            }
        };

        AdminUserSecretResource res = new AdminUserSecretResource();
        res.userRepository = userRepo;
        res.secretRepository = secretRepo;
        res.securityContext = mockCtx("admin");

        Response resp = res.delete("alice", "cccam", 2L).await().indefinitely();
        assertEquals(204, resp.getStatus());
    }
}
