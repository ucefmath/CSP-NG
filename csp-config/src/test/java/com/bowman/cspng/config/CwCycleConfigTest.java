package com.bowman.cspng.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires CwCycleConfig (V15) — validation fail-closed, normalisation CAIDs,
 * CSV aller/retour, sémantique "vide = toutes".
 */
class CwCycleConfigTest {

    @Test
    void defaultsAreConservative() {
        CwCycleConfig d = CwCycleConfig.DEFAULT;
        assertFalse(d.enabled());
        assertTrue(d.caids().isEmpty());
        assertEquals(500, d.maxList());
        assertEquals(15, d.keepTimeSec());
        assertEquals("drop", d.onBad());
        assertTrue(d.dropOld());
        assertFalse(d.usePeerCycleMetadata());
        assertEquals(1L, d.version());
    }

    @Test
    void caidsAreSortedAndDeduplicatedByConstruction() {
        CwCycleConfig cfg = new CwCycleConfig(true, List.of(1280, 256, 9999), 500, 15, "log", false, false, 1L);
        assertEquals(List.of(256, 1280, 9999), cfg.caids());
        assertThrows(IllegalArgumentException.class,
            () -> new CwCycleConfig(true, List.of(256, 256), 500, 15, "drop", true, false, 1L));
    }

    @Test
    void boundsAreFailClosed() {
        assertThrows(IllegalArgumentException.class,
            () -> new CwCycleConfig(true, null, 15, 15, "drop", true, false, 1L));
        assertThrows(IllegalArgumentException.class,
            () -> new CwCycleConfig(true, null, 4001, 15, "drop", true, false, 1L));
        assertThrows(IllegalArgumentException.class,
            () -> new CwCycleConfig(true, null, 500, 4, "drop", true, false, 1L));
        assertThrows(IllegalArgumentException.class,
            () -> new CwCycleConfig(true, null, 500, 241, "drop", true, false, 1L));
        assertThrows(IllegalArgumentException.class,
            () -> new CwCycleConfig(true, null, 500, 15, "warn", true, false, 1L));
        assertThrows(IllegalArgumentException.class,
            () -> new CwCycleConfig(true, List.of(0), 500, 15, "drop", true, false, 1L));
        assertThrows(IllegalArgumentException.class,
            () -> new CwCycleConfig(true, List.of(65536), 500, 15, "drop", true, false, 1L));
    }

    @Test
    void maxCaidsLimit() {
        List<Integer> tooMany = new java.util.ArrayList<>();
        for (int i = 1; i <= 33; i++) tooMany.add(i);
        assertThrows(IllegalArgumentException.class,
            () -> new CwCycleConfig(true, tooMany, 500, 15, "drop", true, false, 1L));
    }

    @Test
    void csvRoundTrip() {
        List<Integer> caids = CwCycleConfig.parseCaids(" 1280 , 256 ,256 ");
        // parseCaids ne déduplique PAS (c'est le rôle du compact constructor)
        assertEquals(List.of(1280, 256, 256), caids);
        // caidsToCsv trie (stable, sans dédup — la dédup est au constructeur)
        assertEquals("256,256,1280", CwCycleConfig.caidsToCsv(caids));
        // Aller/retour via constructeur (tri + dédup garantis, doublon rejeté fail-closed)
        assertThrows(IllegalArgumentException.class,
            () -> new CwCycleConfig(true, caids, 500, 15, "drop", true, false, 1L));
        CwCycleConfig cfg = new CwCycleConfig(true, List.copyOf(new java.util.TreeSet<>(caids)),
            500, 15, "drop", true, false, 1L);
        assertEquals("256,1280", CwCycleConfig.caidsToCsv(cfg.caids()));
        assertTrue(CwCycleConfig.parseCaids(null).isEmpty());
        assertTrue(CwCycleConfig.parseCaids("").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> CwCycleConfig.parseCaids("abc"));
    }

    @Test
    void appliesToCaidEmptyMeansAll() {
        CwCycleConfig all = new CwCycleConfig(true, List.of(), 500, 15, "drop", true, false, 1L);
        assertTrue(all.appliesToCaid(0x0100));
        assertTrue(all.appliesToCaid(0x0500));

        CwCycleConfig gated = new CwCycleConfig(true, List.of(0x0100), 500, 15, "drop", true, false, 1L);
        assertTrue(gated.appliesToCaid(0x0100));
        assertFalse(gated.appliesToCaid(0x0500));
    }

    @Test
    void equalityIgnoresVersionAndAuditFields() {
        CwCycleConfig a = new CwCycleConfig(true, List.of(256), 500, 15, "drop", true, false, 1L, null, null);
        CwCycleConfig b = new CwCycleConfig(true, List.of(256), 500, 15, "drop", true, false, 99L, 123L, "x");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
