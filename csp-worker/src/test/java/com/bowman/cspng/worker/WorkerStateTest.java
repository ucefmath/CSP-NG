package com.bowman.cspng.worker;

import com.bowman.cspng.config.RelayWorkerContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkerState — apply monotone, expiry fail-closed, purge ciblee")
class WorkerStateTest {

  private static RelayWorkerContract.DesiredWorkerConfig desired(long version, long expiresAt) {
    return new RelayWorkerContract.DesiredWorkerConfig(
        "peer-a", version, expiresAt, 30000L,
        List.of(), List.of(
            new RelayWorkerContract.CacheView("c1", "local", true, 8000L, 10000, 512),
            new RelayWorkerContract.CacheView("c2", "local", true, 8000L, 10000, 512)),
        List.of(
            new RelayWorkerContract.LinkView(7L, "c1", true, true, false, true),
            new RelayWorkerContract.LinkView(7L, "c2", true, true, false, true)),
        List.of(), List.of());
  }

  @Test
  @DisplayName("Apply ok puis stale refuse, expire fail-closed")
  void monotoneAndExpiry() {
    WorkerState st = new WorkerState("peer-a");
    long now = System.currentTimeMillis();
    assertTrue(st.applyDesired(desired(1L, now + 30000L), now));
    assertEquals(1L, st.appliedDesired());
    assertFalse(st.expired(now));
    // stale (meme version) refuse
    assertFalse(st.applyDesired(desired(1L, now + 30000L), now));
    // expire refuse
    assertFalse(st.applyDesired(desired(2L, now - 1000L), now));
    // version superieure ok
    assertTrue(st.applyDesired(desired(2L, now + 30000L), now));
    assertFalse(st.policy().consultableCaches(7L).isEmpty());
    st.failClosed();
    assertTrue(st.policy().consultableCaches(7L).isEmpty());
  }

  @Test
  @DisplayName("RenewExpiry — renouvellement monotone du bail actif sans rechargement")
  void renewExpiryValidation() {
    WorkerState st = new WorkerState("peer-a");
    long now = 1_000_000L;
    long initialExpiry = now + 30_000L;
    assertTrue(st.applyDesired(desired(1L, initialExpiry), now));
    assertEquals(initialExpiry, st.expiresAtMs());
    assertFalse(st.expired(now));

    // 1. Renouvellement valide même version : avance l'expiration
    long renewedExpiry1 = now + 40_000L;
    assertTrue(st.renewExpiry(1L, renewedExpiry1, now + 10_000L));
    assertEquals(renewedExpiry1, st.expiresAtMs());

    // 2. Mauvaise version refusée : n'altère pas l'expiration
    assertFalse(st.renewExpiry(2L, now + 50_000L, now + 10_000L));
    assertFalse(st.renewExpiry(0L, now + 50_000L, now + 10_000L));
    assertEquals(renewedExpiry1, st.expiresAtMs());

    // 3. Nouveau bail dans le passé (< nowMs) refusé
    assertFalse(st.renewExpiry(1L, now + 5_000L, now + 10_000L));
    assertEquals(renewedExpiry1, st.expiresAtMs());

    // 4. Bail plus court que l'actuel ne régresse pas
    long shorterExpiry = now + 35_000L;
    assertTrue(st.renewExpiry(1L, shorterExpiry, now + 10_000L));
    assertEquals(renewedExpiry1, st.expiresAtMs(), "expiresAtMs ne doit pas régresser");

    // 5. État déjà expiré (nowMs > expiresAtMs) : renouvellement refusé
    long pastExpiry = renewedExpiry1 + 1000L;
    assertTrue(st.expired(pastExpiry));
    assertFalse(st.renewExpiry(1L, pastExpiry + 30_000L, pastExpiry));

    // 6. État failClosed (appliedDesired = -1) : renouvellement refusé
    st.failClosed();
    assertFalse(st.renewExpiry(1L, now + 100_000L, now));
  }
}
