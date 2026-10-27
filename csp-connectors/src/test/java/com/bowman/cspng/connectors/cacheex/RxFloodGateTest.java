package com.bowman.cspng.connectors.cacheex;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Gate anti-flood RX (R3) : fenêtre 60 s, O(1), bornée. Coût négligeable
 * devant le parsing (test coût inclus).
 */
class RxFloodGateTest {

    @Test
    void allowsUpToBudgetThenTrips() {
        RxFloodGate g = new RxFloodGate();
        for (int i = 0; i < 10; i++) assertTrue(g.tryAcquire(10), "dans le budget");
        assertFalse(g.tryAcquire(10), "11e dans la même fenêtre => refusé");
        assertFalse(g.tryAcquire(10), "toujours refusé sans reset");
    }

    @Test
    void nonPositiveBudgetMeansUnlimited() {
        RxFloodGate g = new RxFloodGate();
        for (int i = 0; i < 10_000; i++) assertTrue(g.tryAcquire(0));
        for (int i = 0; i < 10_000; i++) assertTrue(g.tryAcquire(-1));
    }

    @Test
    void resetReopensWindow() {
        RxFloodGate g = new RxFloodGate();
        assertTrue(g.tryAcquire(1));
        assertFalse(g.tryAcquire(1));
        g.reset();
        assertTrue(g.tryAcquire(1));
    }

    @Test
    void costIsNegligible() {
        // Coût : 10k acquisitions < 100 ms (négligeable vs parsing/injection push).
        RxFloodGate g = new RxFloodGate();
        long t0 = System.nanoTime();
        for (int i = 0; i < 10_000; i++) g.tryAcquire(0);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(elapsedMs < 100, "coût gate = " + elapsedMs + " ms pour 10k");
    }
}
