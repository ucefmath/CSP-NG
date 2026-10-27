package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.BulkPatches;
import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bulk edit (contrat v2 §2) — fail-closed sur les clés AVANT tout accès DB :
 * identité/secrets/nodeId/admin rejetés en 400 INVALID_PATCH_FIELD, ressource
 * inconnue en 404. Le repository n'est jamais atteint sur ces chemins.
 */
@DisplayName("AdminBulkResource — garde-fous bulk sans DB")
class AdminBulkResourceTest {

    private AdminBulkResource resource() {
        AdminBulkResource res = new AdminBulkResource();
        res.configRepository = null;
        res.userRepository = null;
        res.mapper = new ObjectMapper();
        res.securityContext = new SecurityContext() {
            @Override public Principal getUserPrincipal() { return () -> "admin"; }
            @Override public boolean isUserInRole(String role) { return true; }
            @Override public boolean isSecure() { return false; }
            @Override public String getAuthenticationScheme() { return "BASIC"; }
        };
        return res;
    }

    private AdminBulkResource.BulkRequestDto req(String id, long version, String... patchFields) {
        ObjectMapper m = new ObjectMapper();
        ObjectNode patch = m.createObjectNode();
        for (String f : patchFields) patch.put(f, true);
        return new AdminBulkResource.BulkRequestDto(
            List.of(new AdminBulkResource.BulkItemDto(id, version)), patch);
    }

    private String code(Response r) {
        return ((ErrorResponse) r.getEntity()).code();
    }

    @Test
    @DisplayName("Connecteurs : passwordRef/name/host rejetés (INVALID_PATCH_FIELD)")
    void connectorsRejectSecretsAndIdentity() {
        AdminBulkResource res = resource();
        assertEquals(400, res.bulkConnectors(req("c1", 1, "passwordRef")).await().indefinitely().getStatus());
        assertEquals("INVALID_PATCH_FIELD",
            code(res.bulkConnectors(req("c1", 1, "passwordRef")).await().indefinitely()));
        assertEquals("INVALID_PATCH_FIELD",
            code(res.bulkConnectors(req("c1", 1, "desKeyRef")).await().indefinitely()));
        assertEquals("INVALID_PATCH_FIELD",
            code(res.bulkConnectors(req("c1", 1, "cccamNodeId")).await().indefinitely()));
        assertEquals("INVALID_PATCH_FIELD",
            code(res.bulkConnectors(req("c1", 1, "name")).await().indefinitely()));
        assertEquals("INVALID_PATCH_FIELD",
            code(res.bulkConnectors(req("c1", 1, "host")).await().indefinitely()));
    }

    @Test
    @DisplayName("Users : admin/password rejetés")
    void usersRejectAdminAndPassword() {
        AdminBulkResource res = resource();
        assertEquals("INVALID_PATCH_FIELD",
            code(res.bulkUsers(req("alice", 1, "admin")).await().indefinitely()));
        assertEquals("INVALID_PATCH_FIELD",
            code(res.bulkUsers(req("alice", 1, "password")).await().indefinitely()));
        assertEquals("INVALID_PATCH_FIELD",
            code(res.bulkUsers(req("alice", 1, "name")).await().indefinitely()));
    }

    @Test
    @DisplayName("Listeners : desKeyRef/nodeId/identité rejetés ; ids non numériques rejetés")
    void listenersRejectSecretsAndNonNumericIds() {
        AdminBulkResource res = resource();
        assertEquals("INVALID_PATCH_FIELD",
            code(res.bulkListeners(req("12", 1, "desKeyRef")).await().indefinitely()));
        assertEquals("INVALID_PATCH_FIELD",
            code(res.bulkListeners(req("12", 1, "nodeId")).await().indefinitely()));
        assertEquals("INVALID_PATCH_FIELD",
            code(res.bulkListeners(req("12", 1, "port")).await().indefinitely()));
        Response r = res.bulkListeners(req("abc", 1, "enabled")).await().indefinitely();
        assertEquals(400, r.getStatus());
        assertEquals("INVALID_INPUT", code(r));
    }

    @Test
    @DisplayName("Présence threadée au repo : absent vs null explicite distingués")
    void presenceThreadedToRepository() {
        ObjectMapper m = new ObjectMapper();
        ObjectNode patch = m.createObjectNode();
        patch.put("enabled", true);
        patch.putNull("cacheexFilter"); // null EXPLICITE présent
        var req = new AdminBulkResource.BulkRequestDto(
            List.of(new AdminBulkResource.BulkItemDto("c1", 1)), patch);

        var capturedPresent = new java.util.HashSet<String>();
        var capturedPatch = new java.util.concurrent.atomic.AtomicReference<BulkPatches.ConnectorPatch>();
        ConfigRepository fakeRepo = new ConfigRepository(null) {
            @Override
            public Future<java.util.List<BulkPatches.BulkItemResult>> bulkUpdateConnectors(
                    java.util.List<BulkPatches.BulkItem> items, BulkPatches.ConnectorPatch p,
                    java.util.Set<String> present, String actor) {
                capturedPresent.addAll(present);
                capturedPatch.set(p);
                return Future.succeededFuture(List.of(new BulkPatches.BulkItemResult("c1", 2L)));
            }
        };
        AdminBulkResource res = resource();
        res.configRepository = fakeRepo;
        Response r = res.bulkConnectors(req).await().indefinitely();
        assertEquals(200, r.getStatus());
        // absent != null explicite : les deux clés sont présentes, la valeur est null
        assertEquals(Set.of("enabled", "cacheexFilter"), capturedPresent);
        assertTrue(capturedPatch.get().enabled());
        assertNull(capturedPatch.get().cacheexFilter(),
            "null explicite threadé tel quel (le repo applique reset nullable / 400 NOT NULL)");
    }

    @Test
    @DisplayName("Gardes structurelles : items vides/dupliqués/patch vide => 400 (sans DB)")
    void structuralGuards() {
        AdminBulkResource res = resource();
        ObjectMapper m = new ObjectMapper();
        var emptyItems = new AdminBulkResource.BulkRequestDto(List.of(), m.createObjectNode().put("enabled", true));
        assertEquals(400, res.bulkConnectors(emptyItems).await().indefinitely().getStatus());

        var dupItems = new AdminBulkResource.BulkRequestDto(
            List.of(new AdminBulkResource.BulkItemDto("c1", 1), new AdminBulkResource.BulkItemDto("c1", 1)),
            m.createObjectNode().put("enabled", true));
        assertEquals(400, res.bulkConnectors(dupItems).await().indefinitely().getStatus());

        var emptyPatch = new AdminBulkResource.BulkRequestDto(
            List.of(new AdminBulkResource.BulkItemDto("c1", 1)), m.createObjectNode());
        assertEquals(400, res.bulkConnectors(emptyPatch).await().indefinitely().getStatus());
    }
}
