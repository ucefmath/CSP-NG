package com.bowman.cspng.filters;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.dcw.DcwDigest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DcwObservationTracker & InterProviderLinkObserver — Observation sans auto-BAN et candidats inter-provider")
class DcwObservationAndLinkTrackerTest {

  private DcwObservationTracker observationTracker;
  private InterProviderLinkObserver linkObserver;

  private static final byte[] CW_CYCLE_1 = new byte[]{
      0x01, 0x02, 0x03, 0x06, 0x04, 0x05, 0x06, 0x0F,
      0x07, 0x08, 0x09, 0x18, 0x0A, 0x0B, 0x0C, 0x21
  };

  private static final byte[] CW_CYCLE_2 = new byte[]{
      0x11, 0x12, 0x13, 0x36, 0x14, 0x15, 0x16, 0x3F,
      0x17, 0x18, 0x19, 0x48, 0x1A, 0x1B, 0x1C, 0x51
  };

  private static final byte[] HALF_ZERO_CW = new byte[]{
      0x01, 0x02, 0x03, 0x06, 0x04, 0x05, 0x06, 0x0F,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
  };

  @BeforeEach
  void setUp() {
    observationTracker = new DcwObservationTracker(100);
    linkObserver = new InterProviderLinkObserver(100);
  }

  @Test
  @DisplayName("Observation d'anomalies : enregistre sans auto-BAN, empreintes SHA-256 sans CW en clair")
  void testRecordAnomalyWithoutAutoBan() {
    observationTracker.recordAnomaly(
        "profile1", 0x1884, 0x000030, 0x2938, "conn1",
        DcwObservationTracker.AnomalyType.CHECKSUM_FAIL,
        CW_CYCLE_1,
        "Bad checksum detected"
    );

    List<DcwObservationTracker.AnomalyEvent> anomalies = observationTracker.recentAnomalies();
    assertEquals(1, anomalies.size());
    DcwObservationTracker.AnomalyEvent event = anomalies.get(0);
    assertEquals("profile1", event.profile());
    assertEquals(0x1884, event.caid());
    assertEquals(0x000030, event.provider());
    assertEquals(0x2938, event.sid());
    assertEquals("conn1", event.sourceId());
    assertEquals(DcwObservationTracker.AnomalyType.CHECKSUM_FAIL, event.type());
    assertEquals(DcwDigest.digestSha256Hex(CW_CYCLE_1), event.fingerprintSha256());
    assertEquals(1, event.count());
  }

  @Test
  @DisplayName("Détection de Frozen CW suspicion sur plusieurs cycles de contrôle distincts")
  void testFrozenCwSuspicionAcrossCycles() {
    // Cycle 1: tag 0x80, hash 0x1111
    observationTracker.trackCwCycle("profile1", 0x1884, 0, 0x2938, 0x80, 0x1111, CW_CYCLE_1);
    // Cycle 2: tag 0x81, hash 0x2222 avec la MEME CW -> suspicion 1
    observationTracker.trackCwCycle("profile1", 0x1884, 0, 0x2938, 0x81, 0x2222, CW_CYCLE_1);
    // Cycle 3: tag 0x80, hash 0x3333 avec la MEME CW -> suspicion confirmée et enregistrée
    observationTracker.trackCwCycle("profile1", 0x1884, 0, 0x2938, 0x80, 0x3333, CW_CYCLE_1);

    List<DcwObservationTracker.AnomalyEvent> anomalies = observationTracker.recentAnomalies();
    assertTrue(anomalies.stream().anyMatch(a -> a.type() == DcwObservationTracker.AnomalyType.FROZEN_CW_SUSPICION));
  }

  @Test
  @DisplayName("Liens inter-provider : 1 seul match ne confirme PAS le candidat (state reste OBSERVATION)")
  void testSingleMatchDoesNotConfirmCandidate() {
    // Match 1
    linkObserver.observeReply("profile1", 0x1884, 0x000030, 0x2938, 0x80, 0x1000, CW_CYCLE_1, System.currentTimeMillis());
    linkObserver.observeReply("profile1", 0x0500, 0x032830, 0x1234, 0x80, 0x2000, CW_CYCLE_1, System.currentTimeMillis() + 50);

    List<InterProviderLinkObserver.CandidateLink> candidates = linkObserver.candidates();
    assertEquals(1, candidates.size());
    var cand = candidates.get(0);
    assertEquals("OBSERVATION", cand.state());
    assertEquals(1, cand.distinctEvidenceCount());
    assertFalse(cand.isConfirmed());
  }

  @Test
  @DisplayName("Liens inter-provider : les duplicats/retransmissions du même ECM n'incrémentent pas les preuves")
  void testDuplicatesDoNotIncrementEvidence() {
    // Cycle 1
    linkObserver.observeReply("profile1", 0x1884, 0x000030, 0x2938, 0x80, 0x1000, CW_CYCLE_1, System.currentTimeMillis());
    linkObserver.observeReply("profile1", 0x0500, 0x032830, 0x1234, 0x80, 0x2000, CW_CYCLE_1, System.currentTimeMillis() + 50);

    // Même cycle / même dataHash répété
    linkObserver.observeReply("profile1", 0x1884, 0x000030, 0x2938, 0x80, 0x1000, CW_CYCLE_1, System.currentTimeMillis() + 100);
    linkObserver.observeReply("profile1", 0x0500, 0x032830, 0x1234, 0x80, 0x2000, CW_CYCLE_1, System.currentTimeMillis() + 120);

    List<InterProviderLinkObserver.CandidateLink> candidates = linkObserver.candidates();
    assertEquals(1, candidates.size());
    assertEquals(1, candidates.get(0).distinctEvidenceCount());
  }

  @Test
  @DisplayName("Liens inter-provider : transitions sur cycles ECM distincts ET parités différentes accumulent les preuves")
  void testDistinctCyclesAccumulateEvidence() {
    long now = System.currentTimeMillis();
    // Cycle 1 (tag 0x80, dataHash 0x1000 & 0x2000, CW 1)
    linkObserver.observeReply("profile1", 0x1884, 0x000030, 0x2938, 0x80, 0x1000, CW_CYCLE_1, now);
    linkObserver.observeReply("profile1", 0x0500, 0x032830, 0x1234, 0x80, 0x2000, CW_CYCLE_1, now + 50);

    // Cycle 2 (tag 0x81, dataHash 0x1001 & 0x2001, CW 2)
    linkObserver.observeReply("profile1", 0x1884, 0x000030, 0x2938, 0x81, 0x1001, CW_CYCLE_2, now + 10000);
    linkObserver.observeReply("profile1", 0x0500, 0x032830, 0x1234, 0x81, 0x2001, CW_CYCLE_2, now + 10050);

    List<InterProviderLinkObserver.CandidateLink> candidates = linkObserver.candidates();
    assertEquals(1, candidates.size());
    assertEquals(2, candidates.get(0).distinctEvidenceCount());
  }

  @Test
  @DisplayName("Demi-zéro n'est pas une preuve d'équivalence")
  void testHalfZeroNotCountedAsEquivalenceEvidence() {
    long now = System.currentTimeMillis();
    linkObserver.observeReply("profile1", 0x1884, 0x000030, 0x2938, 0x80, 0x1000, HALF_ZERO_CW, now);
    linkObserver.observeReply("profile1", 0x0500, 0x032830, 0x1234, 0x80, 0x2000, HALF_ZERO_CW, now + 50);

    List<InterProviderLinkObserver.CandidateLink> candidates = linkObserver.candidates();
    assertTrue(candidates.isEmpty() || candidates.get(0).distinctEvidenceCount() == 0);
  }

  @Test
  @DisplayName("DcwObservationTracker : trackCwCycle est borné (éviction au-delà de MAX_TRACKED_SERVICES)")
  void testTrackCwCycleBounded() {
    for (int i = 0; i < 2500; i++) {
      observationTracker.trackCwCycle("p", 0x1884, 0, i, 0x80, i, CW_CYCLE_1);
    }
    // Assertion stricte sur la taille bornée
    assertTrue(observationTracker.trackedServicesCount() <= DcwObservationTracker.MAX_TRACKED_SERVICES,
        "trackedServicesCount (" + observationTracker.trackedServicesCount() + ") must be <= " + DcwObservationTracker.MAX_TRACKED_SERVICES);
    assertTrue(observationTracker.trackedServicesCount() > 0);
    assertNotNull(observationTracker.recentAnomalies());
  }
}
