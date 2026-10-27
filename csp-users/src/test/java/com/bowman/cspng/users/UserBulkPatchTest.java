package com.bowman.cspng.users;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tests du patch bulk users (contrat v2 §2.1) — ni name, ni admin, ni password,
 * ni secrets protocoles ; sets fournis = remplacement, null = inchangé.
 */
class UserBulkPatchTest {

    @Test
    void structureExcludesPrivilegeEscalation() {
        for (java.lang.reflect.RecordComponent rc : UserBulkPatch.class.getRecordComponents()) {
            String n = rc.getName();
            assertNotEquals("admin", n, "bulk users must never patch admin");
            assertNotEquals("password", n);
            assertNotEquals("passwordHash", n);
            assertNotEquals("name", n);
            assertNotEquals("validFrom", n);
            assertNotEquals("validUntil", n);
        }
    }

    @Test
    void boundsFailClosed() {
        assertThrows(IllegalArgumentException.class,
            () -> new UserBulkPatch(null, null, null, null, null, 1001, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new UserBulkPatch(null, null, null, null, null, null, null, 10001, null, null, null, null));
        assertDoesNotThrow(() -> new UserBulkPatch(
            "display", null, true, null, null, 10, null, 30,
            Set.of("prof-a"), Set.of(), Map.of(), Map.of()));
    }

    @Test
    void nullMeansUnchangedEmptyDetectable() {
        UserBulkPatch empty = new UserBulkPatch(null, null, null, null, null, null, null, null, null, null, null, null);
        assertTrue(empty.isEmpty());
        UserBulkPatch notEmpty = new UserBulkPatch(null, null, true, null, null, null, null, null, null, null, null, null);
        assertFalse(notEmpty.isEmpty());
    }

    @Test
    void cacheExPolicySemantics() {
        // Arbitrage D2 : mode 2 = NOUS émettons (client reçoit) ; mode 3 = NOUS recevons (client émet)
        assertEquals(2, new CacheExPolicy(2, 10, null).mode());
        assertTrue(new CacheExPolicy(2, 10, null).enabled());
        assertTrue(new CacheExPolicy(3, 10, "0100:00006A").enabled());
        assertFalse(CacheExPolicy.OFF.enabled());
        assertEquals(0, CacheExPolicy.OFF.mode());
        assertThrows(IllegalArgumentException.class, () -> new CacheExPolicy(1, 10, null));
        assertThrows(IllegalArgumentException.class, () -> new CacheExPolicy(2, 11, null));
        // fromDb : null mode = off ; maxHop défaut 10
        assertEquals(CacheExPolicy.OFF, CacheExPolicy.fromDb(null, null, null));
        assertEquals(10, CacheExPolicy.fromDb(3, null, null).maxHop());
        assertEquals("0100:00006A", CacheExPolicy.fromDb(3, 5, "0100:00006A").filter());
    }
}
