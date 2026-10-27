package com.bowman.cspng.web.admin;

import com.bowman.cspng.users.UserRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AdminSessionManager — Tests unitaires gestion de session")
class AdminSessionManagerTest {

    private AdminSessionManager manager;
    private UserRecord adminUser;
    private UserRecord secondUser;

    @BeforeEach
    void setUp() {
        manager = new AdminSessionManager(null, Duration.ofHours(8), Duration.ofHours(24), 5, 1000);
        adminUser = new UserRecord("admin", "Administrator", "$2a$12$dummyHash", null, true, true, false, false, 5, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null);
        secondUser = new UserRecord("operator", "Operator", "$2a$12$dummyHash", null, true, true, false, false, 5, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null);
    }

    @Test
    @DisplayName("Génération de token CSPRNG 256 bits base64url sans padding")
    void tokenCsprngProperties() {
        AdminSession session = manager.createSession(adminUser);
        assertNotNull(session);
        String token = session.token();
        assertNotNull(token);
        assertFalse(token.contains("+"), "no + in base64url");
        assertFalse(token.contains("/"), "no / in base64url");
        assertFalse(token.contains("="), "no padding in base64url");

        byte[] decoded = Base64.getUrlDecoder().decode(token);
        assertEquals(32, decoded.length, "32 bytes (256 bits)");
    }

    @Test
    @DisplayName("Création de session et consultation valide")
    void createAndGetSession() {
        AdminSession session = manager.createSession(adminUser);
        Optional<AdminSession> retrieved = manager.getSession(session.token());
        assertTrue(retrieved.isPresent());
        assertEquals("admin", retrieved.get().username());
        assertEquals(1L, retrieved.get().userVersion());
        assertFalse(retrieved.get().isExpired(Instant.now()));
    }

    @Test
    @DisplayName("Éviction max-per-user : la 6ème session évince la plus ancienne")
    void maxPerUserEviction() throws Exception {
        List<AdminSession> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(manager.createSession(adminUser));
            Thread.sleep(5); // Décaler l'instant created
        }
        assertEquals(5, manager.activeSessionsCount());

        // Créer la 6ème session pour admin
        AdminSession sixth = manager.createSession(adminUser);
        assertEquals(5, manager.activeSessionsCount());

        // La 1ère session doit avoir été évincée
        assertTrue(manager.getSession(created.get(0).token()).isEmpty());
        // Les sessions 2 à 6 doivent être toujours présentes
        for (int i = 1; i < 5; i++) {
            assertTrue(manager.getSession(created.get(i).token()).isPresent());
        }
        assertTrue(manager.getSession(sixth.token()).isPresent());
    }

    @Test
    @DisplayName("Révocation ciblée par token")
    void revokeToken() {
        AdminSession session = manager.createSession(adminUser);
        assertTrue(manager.getSession(session.token()).isPresent());

        manager.revoke(session.token());
        assertTrue(manager.getSession(session.token()).isEmpty());
    }

    @Test
    @DisplayName("Révocation de toutes les sessions d'un utilisateur")
    void revokeAllForUser() {
        AdminSession s1 = manager.createSession(adminUser);
        AdminSession s2 = manager.createSession(adminUser);
        AdminSession sOther = manager.createSession(secondUser);

        manager.revokeAllForUser("admin");

        assertTrue(manager.getSession(s1.token()).isEmpty());
        assertTrue(manager.getSession(s2.token()).isEmpty());
        assertTrue(manager.getSession(sOther.token()).isPresent());
    }

    @Test
    @DisplayName("InvalidateAll supprime toutes les sessions")
    void invalidateAll() {
        manager.createSession(adminUser);
        manager.createSession(secondUser);
        assertEquals(2, manager.activeSessionsCount());

        manager.invalidateAll();
        assertEquals(0, manager.activeSessionsCount());
    }

    @Test
    @DisplayName("Expiration idle TTL court")
    void idleExpiration() throws Exception {
        AdminSessionManager shortManager = new AdminSessionManager(null, Duration.ofMillis(50), Duration.ofHours(1), 5, 1000);
        AdminSession session = shortManager.createSession(adminUser);

        assertTrue(shortManager.getSession(session.token()).isPresent());

        Thread.sleep(70);
        assertTrue(shortManager.getSession(session.token()).isEmpty());
    }

    @Test
    @DisplayName("Expiration absolute TTL court")
    void absoluteExpiration() throws Exception {
        AdminSessionManager shortManager = new AdminSessionManager(null, Duration.ofHours(1), Duration.ofMillis(50), 5, 1000);
        AdminSession session = shortManager.createSession(adminUser);

        assertTrue(shortManager.getSession(session.token()).isPresent());

        Thread.sleep(70);
        assertTrue(shortManager.getSession(session.token()).isEmpty());
    }

    @Test
    @DisplayName("Éviction max-total")
    void maxTotalEviction() throws Exception {
        AdminSessionManager tightManager = new AdminSessionManager(null, Duration.ofHours(1), Duration.ofHours(1), 10, 3);
        AdminSession s1 = tightManager.createSession(new UserRecord("u1", "U1", "hash", null, true, true, false, false, 1, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));
        Thread.sleep(5);
        AdminSession s2 = tightManager.createSession(new UserRecord("u2", "U2", "hash", null, true, true, false, false, 1, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));
        Thread.sleep(5);
        AdminSession s3 = tightManager.createSession(new UserRecord("u3", "U3", "hash", null, true, true, false, false, 1, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));
        Thread.sleep(5);
        AdminSession s4 = tightManager.createSession(new UserRecord("u4", "U4", "hash", null, true, true, false, false, 1, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));

        assertEquals(3, tightManager.activeSessionsCount());
        assertTrue(tightManager.getSession(s1.token()).isEmpty(), "Oldest s1 evicted");
        assertTrue(tightManager.getSession(s4.token()).isPresent());
    }
}
