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
    void perSidCountersPeerIdentityAndAntiFloodOnDrops() {
        CacheExStatsRegistry reg = new CacheExStatsRegistry();
        reg.registerInbound("L3:@STB@", "cs378x", 3, "default", 10, 2000);
        reg.updatePeerIdentity("L3:@STB@", "77.90.60.222", "1A2B3C4D5E6F7081");

        reg.countReceived("L3:@STB@", 0x1884, 0x000000, 0x32DC);
        reg.countReceived("L3:@STB@", 0x1884, 0x000000, 0x32DC);
        reg.countPushed("L3:@STB@", 0x1818, 0x00006C, 0x52ED);
        reg.countHit("L3:@STB@", 0x1884, 0x000000, 0x32DC);
        // SID déjà connu -> drop compté sur la paire ET sur ce SID.
        reg.countDropped("L3:@STB@", CacheExStatsRegistry.REASON_CHECKSUM, 0x1884, 0x000000, 0x32DC);
        // SID inconnu -> drop compté sur la paire, mais AUCUNE entrée SID créée (anti-flood).
        reg.countDropped("L3:@STB@", CacheExStatsRegistry.REASON_CHECKSUM, 0x098D, 0x000000, 0x9999);

        assertEquals(1, reg.snapshot().size());
        CacheExStatsRegistry.PairStats p = reg.snapshot().get(0);
        assertEquals("77.90.60.222", p.remoteIp());
        assertEquals("1A2B3C4D5E6F7081", p.remoteNodeId());
        assertEquals(1L, p.hits());
        assertEquals(2L, p.droppedCw());

        assertEquals(2, p.sids().size(), "seuls les SID ayant du trafic réel ouvrent une entrée");
        var sid1884 = p.sids().get(0);
        assertEquals("1884@000000:32DC", sid1884.serviceKey());
        assertEquals(2L, sid1884.receivedCw());
        assertEquals(1L, sid1884.hits());
        assertEquals(1L, sid1884.droppedCw());

        var sid1818 = p.sids().get(1);
        assertEquals("1818@00006C:52ED", sid1818.serviceKey());
        assertEquals(1L, sid1818.pushedCw());

        assertTrue(p.sids().stream().noneMatch(s -> s.serviceKey().contains("9999")),
            "un drop sur un SID jamais vu ne crée pas d'entrée (anti-flood)");

        reg.reset();
        CacheExStatsRegistry.PairStats afterReset = reg.snapshot().get(0);
        assertEquals(0L, afterReset.hits());
        assertTrue(afterReset.sids().isEmpty(), "reset purge les compteurs par SID");
    }

    @Test
    void providZeroReusesExistingSidEntryWithNonZeroProvider() {
        CacheExStatsRegistry reg = new CacheExStatsRegistry();
        reg.registerInbound("conn-p", "cs378x", 3, "default", 10, 2000);

        // Reçu initialement avec provider non nul (ex. 0x000068)
        reg.countReceived("conn-p", 0x1813, 0x000068, 0x32DC);

        // Push / Hit / Drop arrivant avec provid = 0 -> doivent s'agréger sur l'entrée existante
        reg.countHit("conn-p", 0x1813, 0, 0x32DC);
        reg.countPushed("conn-p", 0x1813, 0, 0x32DC);
        reg.countDropped("conn-p", CacheExStatsRegistry.REASON_CHECKSUM, 0x1813, 0, 0x32DC);

        var p = reg.snapshot().get(0);
        assertEquals(1, p.sids().size(), "une seule entrée SID doit exister");
        var sid = p.sids().get(0);
        assertEquals("1813@000068:32DC", sid.serviceKey());
        assertEquals(1L, sid.receivedCw());
        assertEquals(1L, sid.hits());
        assertEquals(1L, sid.pushedCw());
        assertEquals(1L, sid.droppedCw());
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
