package com.bowman.cspng.filters;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests du moteur CW-cycle (P4) — détections frozen/swap/old, bornes,
 * métadonnées pair (wire cacheex [18]), reconfiguration à chaud, compteurs réels.
 */
class CwCycleCheckerTest {

    private static byte[] cw(int seed) {
        byte[] b = new byte[16];
        for (int i = 0; i < 16; i++) b[i] = (byte) (seed + i);
        return b;
    }

    private static final int ODD = 0x80;
    private static final int EVEN = 0x81;

    @Test
    void boundsAndOnBadValidation() {
        assertThrows(IllegalArgumentException.class, () -> new CwCycleChecker(15, 15, "drop", true, false));
        assertThrows(IllegalArgumentException.class, () -> new CwCycleChecker(4001, 15, "drop", true, false));
        assertThrows(IllegalArgumentException.class, () -> new CwCycleChecker(500, 4, "drop", true, false));
        assertThrows(IllegalArgumentException.class, () -> new CwCycleChecker(500, 241, "drop", true, false));
        assertThrows(IllegalArgumentException.class, () -> new CwCycleChecker(500, 15, "warn", true, false));
    }

    @Test
    void disabledCheckerIsNoOp() {
        CwCycleChecker c = new CwCycleChecker(500, 15, "drop", true, false); // enabled=false
        byte[] same = cw(1);
        // Même CW, même parité, un "cycle" plus tard : pas de rejet car DISABLED
        long t0 = 100_000L;
        assertEquals(CwCycleChecker.Verdict.OK, c.check("p", 0x100, 0, 1, ODD, same, 0, false, t0));
        assertEquals(CwCycleChecker.Verdict.OK,
            c.check("p", 0x100, 0, 1, ODD, same, 0, false, t0 + 60_000L));
        assertEquals(0, c.checks(), "disabled gate must not count checks");
    }

    @Test
    void frozenCwDetectedAfterCycle() {
        CwCycleChecker c = new CwCycleChecker(true, null, 500, 15, "drop", true, false);
        byte[] frozen = cw(42);
        long t0 = 1_000_000L;
        assertEquals(CwCycleChecker.Verdict.OK, c.check("p", 0x100, 0, 1, ODD, frozen, 0, false, t0));
        // Même parité, MÊME CW, un cycle (>= learned/keepTime) plus tard => FROZEN
        CwCycleChecker.Verdict v = c.check("p", 0x100, 0, 1, ODD, frozen, 0, false, t0 + 60_000L);
        assertEquals(CwCycleChecker.Verdict.FROZEN, v);
        assertTrue(c.shouldReject(v));
        assertEquals(1, c.badCycles());
        assertEquals(1, c.droppedCount());
    }

    @Test
    void repeatedSameParityWithinShortWindowIsNotFrozen() {
        CwCycleChecker c = new CwCycleChecker(true, null, 500, 15, "drop", true, false);
        byte[] same = cw(7);
        long t0 = 1_000_000L;
        assertEquals(CwCycleChecker.Verdict.OK, c.check("p", 0x100, 0, 1, ODD, same, 0, false, t0));
        // Retransmission rapide (< 1 cycle) : pas de FROZEN (retard/dup toléré)
        assertEquals(CwCycleChecker.Verdict.OK,
            c.check("p", 0x100, 0, 1, ODD, same, 0, false, t0 + 1_000L));
    }

    @Test
    void swapDetectedWhenCwEqualsOppositeParity() {
        CwCycleChecker c = new CwCycleChecker(true, null, 500, 15, "drop", true, false);
        byte[] cwA = cw(3);
        long t0 = 1_000_000L;
        assertEquals(CwCycleChecker.Verdict.OK, c.check("p", 0x100, 0, 1, ODD, cwA, 0, false, t0));
        // La CW suivante (parité opposée) IDENTIQUE => swap suspect
        CwCycleChecker.Verdict v = c.check("p", 0x100, 0, 1, EVEN, cwA, 0, false, t0 + 2_000L);
        assertEquals(CwCycleChecker.Verdict.SWAP, v);
    }

    @Test
    void dropOldRejectsStaleTimestamp() {
        CwCycleChecker c = new CwCycleChecker(true, null, 500, 15, "drop", true, true);
        long t0 = 5_000_000L;
        assertEquals(CwCycleChecker.Verdict.OK, c.check("p", 0x100, 0, 1, ODD, cw(9), 0, false, t0));
        assertEquals(CwCycleChecker.Verdict.OK, c.check("p", 0x100, 0, 1, EVEN, cw(10), 0, false, t0 + 100));
        // CW antidatée => OLD
        CwCycleChecker.Verdict v = c.check("p", 0x100, 0, 1, ODD, cw(11), 0, false, t0 - 999_999L);
        assertEquals(CwCycleChecker.Verdict.OLD, v);
        assertTrue(c.shouldReject(v));
    }

    @Test
    void oldWithDropOldFalseCountsLoggedNotDropped() {
        // Sémantique : OLD n'existe que si dropOld=true. Avec dropOld=false, une CW
        // antidatée n'est jamais OLD (OK ici) et surtout dropped n'est JAMAIS
        // incrémenté à tort — le compteur suit shouldReject(), pas le verdict brut.
        CwCycleChecker c = new CwCycleChecker(true, null, 500, 15, "drop", false, true);
        long t0 = 5_000_000L;
        assertEquals(CwCycleChecker.Verdict.OK, c.check("p", 0x100, 0, 1, ODD, cw(9), 0, false, t0));
        assertEquals(CwCycleChecker.Verdict.OK, c.check("p", 0x100, 0, 1, ODD, cw(11), 0, false, t0 - 999_999L));
        assertEquals(0, c.badCycles());
        assertEquals(0, c.droppedCount(), "aucun dropped sans rejet effectif");
        assertEquals(0, c.loggedCount());
        // Et un vrai FROZEN sous dropOld=false se compte normalement (drop, pas OLD).
        assertEquals(CwCycleChecker.Verdict.OK, c.check("p", 0x100, 0, 1, EVEN, cw(12), 0, false, t0 + 100));
        CwCycleChecker.Verdict v = c.check("p", 0x100, 0, 1, ODD, cw(11), 0, false, t0 + 60_000L);
        assertEquals(CwCycleChecker.Verdict.FROZEN, v);
        assertTrue(c.shouldReject(v));
        assertEquals(1, c.droppedCount());
    }

    @Test
    void evictExpiredScansAllEntries() {
        // Bug avéré : break au premier non-expiré sautait des expirés plus loin
        // (ordre d'insertion != ordre d'expiration car lastUsedMs suit l'activité
        // RÉELLE — System.currentTimeMillis, pas les timestamps injectés).
        // Scénario déterministe : B inséré puis abandonné > keepTime (5 s min),
        // A retouché juste avant le rejet déclencheur => B évincé malgré sa position.
        CwCycleChecker c = new CwCycleChecker(true, null, 500, 5, "drop", true, false);
        long t0 = System.currentTimeMillis();
        c.check("pB", 0x100, 0, 2, ODD, cw(32), 0, false, t0);
        c.check("pA", 0x100, 0, 1, ODD, cw(31), 0, false, t0);
        try {
            Thread.sleep(6500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        // Retouche A (même parité, CW différente => OK, pas de rejet, pas de purge)
        c.check("pA", 0x100, 0, 1, ODD, cw(33), 0, false, t0);
        assertEquals(2, c.trackedEntries());
        // SWAP sur A (even == odd) => rejet => purge : B inactif depuis > 5 s, évincé ;
        // A frais (retouché à l'instant) => gardé, bien que B soit APRÈS A en insertion.
        // NOTE : l'ordre d'insertion est A(pA inséré 2e)... on force l'ordre inverse :
        // on réinsère via un scope frais pour placer l'expiré après le frais.
        CwCycleChecker c2 = new CwCycleChecker(true, null, 500, 5, "drop", true, false);
        long t1 = System.currentTimeMillis();
        c2.check("pFresh", 0x100, 0, 1, ODD, cw(41), 0, false, t1);
        c2.check("pStale", 0x100, 0, 2, ODD, cw(42), 0, false, t1);
        try {
            Thread.sleep(6500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        // Retouche pFresh uniquement
        c2.check("pFresh", 0x100, 0, 1, EVEN, cw(43), 0, false, t1);
        // SWAP => rejet => purge complète : pStale (expiré, 2e en insertion) doit partir
        // alors que pFresh (1er en insertion, frais) reste — l'ancien `break` ratait ce cas.
        CwCycleChecker.Verdict v = c2.check("pFresh", 0x100, 0, 1, ODD, cw(43), 0, false, t1);
        assertEquals(CwCycleChecker.Verdict.SWAP, v);
        assertEquals(1, c2.trackedEntries(), "B expiré évincé malgré sa position après le frais");
    }

    @Test
    void caidGateApplies() {
        CwCycleChecker c = new CwCycleChecker(true, List.of(0x0500), 500, 15, "drop", true, false);
        byte[] frozen = cw(31);
        long t0 = 1_000_000L;
        // 0x0100 hors gate => aucun compteur, aucun rejet
        assertEquals(CwCycleChecker.Verdict.OK, c.check("p", 0x0100, 0, 1, ODD, frozen, 0, false, t0));
        assertEquals(CwCycleChecker.Verdict.OK,
            c.check("p", 0x0100, 0, 1, ODD, frozen, 0, false, t0 + 60_000L));
        assertEquals(0, c.checks());
        // 0x0500 dans la gate => détection
        assertEquals(CwCycleChecker.Verdict.OK, c.check("p", 0x0500, 0, 1, ODD, frozen, 0, false, t0));
        assertEquals(CwCycleChecker.Verdict.FROZEN,
            c.check("p", 0x0500, 0, 1, ODD, frozen, 0, false, t0 + 60_000L));
    }

    @Test
    void peerCycleMetadataLearnedAndEmitted() {
        CwCycleChecker c = new CwCycleChecker(true, null, 500, 15, "drop", true, true);
        byte[] cwA = cw(55);
        long t0 = 1_000_000L;
        // Push pair transportant cycletime 10s + flag next-cycle (wire [18])
        assertEquals(CwCycleChecker.Verdict.OK,
            c.check("p", 0x100, 0x6A, 0x1234, ODD, cwA, 10, true, t0));
        int[] meta = c.cycleMetadataFor("p", 0x100, 0x6A, 0x1234);
        assertNotNull(meta);
        assertEquals(10, meta[0]);
        assertEquals(1, meta[1]);
        // Lookup large (caid, sid) pour l'émission (provider non reconstructible)
        int[] loose = c.cycleMetadataFor(0x100, 0x1234);
        assertNotNull(loose);
        assertEquals(10, loose[0]);
        assertNull(c.cycleMetadataFor(0x09AF, 0x1234));
    }

    @Test
    void peerMetadataIgnoredWhenDisabled() {
        CwCycleChecker c = new CwCycleChecker(true, null, 500, 15, "drop", true, false);
        assertEquals(CwCycleChecker.Verdict.OK,
            c.check("p", 0x100, 0x6A, 0x1234, ODD, cw(5), 10, true, 1_000_000L));
        assertNull(c.cycleMetadataFor("p", 0x100, 0x6A, 0x1234),
            "usePeerCycleMetadata=false => pas d'apprentissage de la métadonnée pair");
    }

    @Test
    void learnedCycleTimeShortensFrozenWindow() {
        CwCycleChecker c = new CwCycleChecker(true, null, 500, 15, "drop", true, true);
        byte[] frozen = cw(77);
        long t0 = 1_000_000L;
        // Pair annonce un cycletime de 6 s
        assertEquals(CwCycleChecker.Verdict.OK, c.check("p", 0x100, 0, 1, ODD, frozen, 6, false, t0));
        // 8 s plus tard (>= cycle appris de 6 s, < keepTime de 15 s) => FROZEN
        assertEquals(CwCycleChecker.Verdict.FROZEN,
            c.check("p", 0x100, 0, 1, ODD, frozen, 6, false, t0 + 8_000L));
    }

    @Test
    void reconfigureResetsHistoryAndUpdatesParams() {
        CwCycleChecker c = new CwCycleChecker(true, null, 500, 15, "drop", true, false);
        byte[] frozen = cw(88);
        long t0 = 1_000_000L;
        c.check("p", 0x100, 0, 1, ODD, frozen, 0, false, t0);
        assertEquals(CwCycleChecker.Verdict.FROZEN,
            c.check("p", 0x100, 0, 1, ODD, frozen, 0, false, t0 + 60_000L));
        assertEquals(1, c.badCycles());

        // Reconfiguration (reload) : historique réinitialisé, paramètres changés
        c.reconfigure(false, List.of(), 100, 30, "log", false, false);
        assertFalse(c.enabled());
        assertEquals(100, c.maxList());
        assertEquals("log", c.onBad());
        assertEquals(0, c.trackedEntries(), "reconfigure must reset history");
        // Disabled => no-op
        assertEquals(CwCycleChecker.Verdict.OK,
            c.check("p", 0x100, 0, 1, ODD, frozen, 0, false, t0 + 120_000L));
    }

    @Test
    void concurrentChecksAreSafeAndCountersExact() throws Exception {
        // C5 : le check est appelé depuis plusieurs event-loops (sessions/connecteurs/
        // routeur) — synchronized => compteurs exacts, historique borné, pas de corruption.
        CwCycleChecker c = new CwCycleChecker(true, null, 500, 15, "drop", true, false);
        int threads = 8;
        int perThread = 250;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicReference<Throwable> err = new java.util.concurrent.atomic.AtomicReference<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // Scopes distincts par thread => aucun rejet, que de l'apprentissage
                        c.check("p" + tid, 0x100 + (tid % 4), tid, 100 + i, ODD, cw(i & 0xFF),
                            0, false, 1_000_000L + i);
                    }
                } catch (Throwable e) {
                    err.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(15, java.util.concurrent.TimeUnit.SECONDS));
        assertNull(err.get(), "aucune corruption concurrente");
        assertEquals((long) threads * perThread, c.checks(), "compteurs exacts");
        assertEquals(0, c.badCycles());
        assertTrue(c.trackedEntries() <= 500, "historique borné sous concurrence");
    }

    @Test
    void scopesAreIsolated() {
        CwCycleChecker c = new CwCycleChecker(true, null, 500, 15, "drop", true, false);
        byte[] same = cw(99);
        long t0 = 1_000_000L;
        assertEquals(CwCycleChecker.Verdict.OK, c.check("p1", 0x100, 0, 1, ODD, same, 0, false, t0));
        // Même CW, même parité, autre service => OK (scopes distincts)
        assertEquals(CwCycleChecker.Verdict.OK,
            c.check("p2", 0x100, 0, 2, ODD, same, 0, false, t0 + 60_000L));
    }

    @Test
    @DisplayName("Parité inconnue (0) : bypass IGNORED compté, aucune détection, aucun apprentissage")
    void unknownParityBypassedAndCounted() {
        CwCycleChecker c = new CwCycleChecker(true, null, 500, 15, "drop", true, true);
        byte[] cwA = cw(3);
        long t0 = 1_000_000L;
        // Deux push inconnus identiques à 60 s d'intervalle : JAMAIS frozen
        assertEquals(CwCycleChecker.Verdict.IGNORED, c.check("p", 0x100, 0, 1, 0x00, cwA, 10, true, t0));
        assertEquals(CwCycleChecker.Verdict.IGNORED, c.check("p", 0x100, 0, 1, 0x00, cwA, 10, true, t0 + 60_000L));
        assertEquals(0, c.trackedEntries(), "l'inconnue n'apprend rien");
        assertEquals(0, c.badCycles(), "aucune fausse détection sur inconnue");
        assertEquals(0, c.droppedCount());
        assertEquals(0, c.loggedCount());
        assertEquals(2, c.ignoredCount(), "bypass comptabilisé");
        assertFalse(c.shouldReject(CwCycleChecker.Verdict.IGNORED), "IGNORED jamais rejeté");
        // Ni swap (même CW en 0x80 après inconnue) ni apprentissage
        assertEquals(CwCycleChecker.Verdict.OK, c.check("p", 0x100, 0, 1, 0x80, cwA, 0, false, t0 + 61_000L));
        // (le 0x81 suivant avec une CW différente reste OK : l'inconnue n'a rien appris)
        assertEquals(CwCycleChecker.Verdict.OK, c.check("p", 0x100, 0, 1, 0x81, cw(4), 0, false, t0 + 62_000L));
    }

    @Test
    @DisplayName("Parité inconnue : checker désactivé => OK silencieux (pas de bypass compté)")
    void unknownParityDisabledIsSilentOk() {
        CwCycleChecker c = new CwCycleChecker(false, null, 500, 15, "drop", true, true);
        assertEquals(CwCycleChecker.Verdict.OK, c.check("p", 0x100, 0, 1, 0x00, cw(3), 0, false, 1_000_000L));
        assertEquals(0, c.ignoredCount(), "désactivé : rien à contourner");
    }
}
