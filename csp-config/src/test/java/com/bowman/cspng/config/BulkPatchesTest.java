package com.bowman.cspng.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires des patches bulk (contrat v2 §2.1) — validation fail-closed
 * IDENTIQUE aux PUT unitaires ; champs interdits structurellement ABSENTS.
 */
class BulkPatchesTest {

    @Test
    void bulkItemValidation() {
        assertDoesNotThrow(() -> new BulkPatches.BulkItem("conn-a", 1L));
        assertThrows(IllegalArgumentException.class, () -> new BulkPatches.BulkItem(null, 1L));
        assertThrows(IllegalArgumentException.class, () -> new BulkPatches.BulkItem(" ", 1L));
        assertThrows(IllegalArgumentException.class, () -> new BulkPatches.BulkItem("a", 0L));
    }

    @Test
    void connectorPatchBoundsMatchUnitary() {
        assertThrows(IllegalArgumentException.class,
            () -> new BulkPatches.ConnectorPatch(null, null, null, null, null, null, null, null,
                null, null, null, 101, null, null, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new BulkPatches.ConnectorPatch(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, 1, null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new BulkPatches.ConnectorPatch(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, 11));
        assertThrows(IllegalArgumentException.class,
            () -> new BulkPatches.ConnectorPatch(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, 10001, null));
        assertThrows(IllegalArgumentException.class,
            () -> new BulkPatches.ConnectorPatch(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, 11, null, null, null, null, null));
        assertDoesNotThrow(() -> new BulkPatches.ConnectorPatch(
            true, null, 5000, null, null, null, null, null, null, null, null,
            50, null, null, null, null, null, null, null, 3, "0100:00006A", 2000, 10));
    }

    @Test
    void connectorPatchHasNoSecretOrIdentityField() {
        // Contrat v2 §2.1 : secrets/identité/nodeId interdits — vérif structurelle
        for (java.lang.reflect.RecordComponent rc : BulkPatches.ConnectorPatch.class.getRecordComponents()) {
            String n = rc.getName();
            assertNotEquals("passwordRef", n);
            assertNotEquals("desKeyRef", n);
            assertNotEquals("newcamdClientIdRef", n);
            assertNotEquals("cccamNodeId", n);
            assertNotEquals("name", n);
            assertNotEquals("host", n);
            assertNotEquals("port", n);
            assertNotEquals("username", n);
            assertNotEquals("caidProfileMap", n);
        }
    }

    @Test
    void listenerPatchBounds() {
        assertThrows(IllegalArgumentException.class,
            () -> new BulkPatches.ListenerPatch(null, "bogus", null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new BulkPatches.ListenerPatch(null, null, null, 11, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new BulkPatches.ListenerPatch(null, null, null, null, 4097, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new BulkPatches.ListenerPatch(null, null, null, null, null, null, null, 4, null, null));
        assertDoesNotThrow(() -> new BulkPatches.ListenerPatch(
            true, "wildcard", true, 5, 512, 32, 5, 60, null, null));
    }

    @Test
    void listenerPatchHasNoSecretNorNodeId() {
        for (java.lang.reflect.RecordComponent rc : BulkPatches.ListenerPatch.class.getRecordComponents()) {
            String n = rc.getName();
            assertNotEquals("desKeyRef", n);
            assertNotEquals("nodeId", n);
            assertNotEquals("profileName", n);
            assertNotEquals("protocol", n);
            assertNotEquals("port", n);
            assertNotEquals("bindAddress", n);
        }
    }

    @Test
    void profilePatchBounds() {
        assertThrows(IllegalArgumentException.class,
            () -> new BulkPatches.ProfilePatch(null, null, null, null, null, -1L, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new BulkPatches.ProfilePatch(null, null, null, null, null, null, null, null, 101, null, null, null, null));
        assertDoesNotThrow(() -> new BulkPatches.ProfilePatch(
            true, false, true, null, null, 1000L, 500L, null, 3, null, null, null, null));
    }

    @Test
    void profilePatchHasNoIdentity() {
        for (java.lang.reflect.RecordComponent rc : BulkPatches.ProfilePatch.class.getRecordComponents()) {
            String n = rc.getName();
            assertNotEquals("name", n);
            assertNotEquals("caId", n);
            assertNotEquals("networkId", n);
        }
    }
}
