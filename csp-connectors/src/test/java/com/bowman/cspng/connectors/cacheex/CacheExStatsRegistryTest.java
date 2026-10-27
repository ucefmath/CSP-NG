package com.bowman.cspng.connectors.cacheex;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests du registry des stats cacheex par pair (contrat v2 §4) —
 * compteurs réels, bornes, éviction, reset sans config.
 */
class CacheExStatsRegistryTest {

    @Test
    void registerAndCounters() {
        CacheExStatsRegistry r = new CacheExStatsRegistry();
        r.registerOutbound("conn-a", "cccam", 2, "prof", 10, 2000);
        r.updateState("conn-a", true, "connected", true);

        r.countReceived("conn-a");
        r.countReceived("conn-a");
        r.countDropped("conn-a", CacheExStatsRegistry.REASON_LOOP);
        r.countDropped("conn-a", "unknown-reason"); // agrégé sous invalid

        var snap = r.snapshot();
        assertEquals(1, snap.size());
        var p = snap.get(0);
        assertEquals("conn-a", p.peerId());
        assertEquals("outbound", p.direction());
        assertEquals("cccam", p.protocol());
        assertEquals(2, p.mode());
        assertTrue(p.online());
        assertEquals("connected", p.state());
        assertEquals(2000, p.maxSendQueue());
        assertTrue(p.filterActive());
        assertEquals(2, p.receivedCw());
        assertEquals(0, p.pushedCw());
        assertEquals(2, p.droppedCw());
        assertEquals(1L, p.dropReasons().get("loop"));
        assertEquals(1L, p.dropReasons().get("invalid"), "unknown reason aggregated under invalid");
        assertNotNull(p.lastReceiveAtMs());
        assertNull(p.lastPushAtMs());
    }

    @Test
    void countersUnknownPeerAreNoOp() {
        CacheExStatsRegistry r = new CacheExStatsRegistry();
        r.countPushed("ghost");
        r.countReceived("ghost");
        r.countDropped("ghost", "loop");
        assertTrue(r.snapshot().isEmpty(), "jamais de paires fabriquées sans trafic");
    }

    @Test
    void inboundPairAggregatesIndependentlyOfSessionLifecycle() {
        CacheExStatsRegistry r = new CacheExStatsRegistry();
        r.registerInbound("L7:alice", "cs378x", 3, "prof", 5, 2000);
        r.updateState("L7:alice", true, "connected", false);
        r.countReceived("L7:alice");
        r.unregister("L7:alice");
        assertTrue(r.snapshot().isEmpty(), "unregister retire la paire (arrêt listener)");
    }

    @Test
    void resetKeepsConfigButZerosCounters() {
        CacheExStatsRegistry r = new CacheExStatsRegistry();
        r.registerOutbound("conn-b", "cccam", 3, "p", 10, 500);
        r.updateState("conn-b", true, "connected", true);
        r.countPushed("conn-b");
        r.countDropped("conn-b", CacheExStatsRegistry.REASON_QUEUE);

        r.reset();
        var p = r.snapshot().get(0);
        assertEquals(0, p.pushedCw());
        assertEquals(0, p.droppedCw());
        assertEquals(0L, p.dropReasons().get("queue"));
        assertNull(p.lastPushAtMs());
        // config/état préservés
        assertTrue(p.online());
        assertEquals("connected", p.state());
        assertEquals(500, p.maxSendQueue());
    }

    @Test
    void boundedRegistryEvictsLeastRecentlyActive() throws Exception {
        CacheExStatsRegistry small = new CacheExStatsRegistry();
        for (int i = 0; i < CacheExStatsRegistry.MAX_PAIRS; i++) {
            small.registerOutbound("conn-" + i, "cccam", 2, "p", 10, 2000);
        }
        // Rafraîchir l'activité de toutes SAUF les 10 premières, puis avancer l'horloge
        // pour rendre les horodatages strictement ordonnés (résolution ms).
        for (int i = 10; i < CacheExStatsRegistry.MAX_PAIRS; i++) {
            small.updateState("conn-" + i, false, "connecting", false);
        }
        Thread.sleep(5);
        for (int i = 0; i < 10; i++) {
            small.registerOutbound("conn-new-" + i, "cccam", 2, "p", 10, 2000);
        }
        assertTrue(small.size() <= CacheExStatsRegistry.MAX_PAIRS,
            "registry bounded to " + CacheExStatsRegistry.MAX_PAIRS);
        // Les 10 plus anciennes non rafraîchies ont été évincées
        for (int i = 0; i < 10; i++) {
            final String id = "conn-" + i;
            assertNull(small.snapshot().stream().filter(p -> p.peerId().equals(id)).findFirst().orElse(null),
                id + " should have been evicted as least recently active");
        }
        // Les rafraîchies et les nouvelles survivent
        assertNotNull(small.snapshot().stream().filter(p -> p.peerId().equals("conn-10")).findFirst().orElse(null));
        assertNotNull(small.snapshot().stream().filter(p -> p.peerId().equals("conn-new-0")).findFirst().orElse(null));
    }

    @Test
    void snapshotSortedByPeerId() {
        CacheExStatsRegistry r = new CacheExStatsRegistry();
        r.registerOutbound("z-conn", "cccam", 2, "p", 10, 2000);
        r.registerOutbound("a-conn", "cs378x", 3, "p", 10, 2000);
        var snap = r.snapshot();
        assertEquals("a-conn", snap.get(0).peerId());
        assertEquals("z-conn", snap.get(1).peerId());
    }
}
