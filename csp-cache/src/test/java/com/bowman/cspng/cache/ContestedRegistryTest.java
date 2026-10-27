package com.bowman.cspng.cache;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContestedRegistryTest {

  private static final EcmKey KEY = new EcmKey(0x80, 0x1234, 0x0001, 0x0B00, 0x42);
  private ContestedRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new ContestedRegistry();
  }

  // ---- Test 9: CW contestée — ajout de candidat

  @Test
  void addCandidateFirstTimeReturnsTrue() {
    CwReply current = reply(new byte[]{0x01, 0x02, 0x03, 0x06, 0x04, 0x05, 0x06, 0x0F, 0x07, 0x08, 0x09, 0x18, 0x0A, 0x0B, 0x0C, 0x21});
    CwReply old = reply(new byte[]{0x11, 0x12, 0x13, 0x36, 0x14, 0x15, 0x16, 0x3F, 0x17, 0x18, 0x19, 0x48, 0x1A, 0x1B, 0x1C, 0x51});
    assertTrue(registry.addCandidate(KEY, current, old));
    assertEquals(1, registry.getCandidates(KEY).size());
    assertTrue(registry.getCandidates(KEY).contains(old));
  }

  // ---- Test 10: Plus de 3 candidats → IllegalStateException

  @Test
  void tooManyCandidatesThrows() {
    CwReply r0 = reply(new byte[16]);
    CwReply r1 = reply(new byte[]{1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    CwReply r2 = reply(new byte[]{2, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    CwReply r3 = reply(new byte[]{3, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    CwReply r4 = reply(new byte[]{4, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    CwReply r5 = reply(new byte[]{5, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});

    // Each call: remove(currentReply=r0, not in set), add(oldReply).
    // This grows the set: r1, then r1+r2, then r1+r2+r3, then r1+r2+r3+r4.
    registry.addCandidate(KEY, r0, r1); // set = {r1}
    registry.addCandidate(KEY, r0, r2); // set = {r1, r2}
    registry.addCandidate(KEY, r0, r3); // set = {r1, r2, r3}
    registry.addCandidate(KEY, r0, r4); // set = {r1, r2, r3, r4}

    // Now set.size() = 4. After remove(r0), size = 4 > 3 → throws!
    assertThrows(IllegalStateException.class, () -> registry.addCandidate(KEY, r0, r5));
  }

  @Test
  void addCandidate_nullInputsReturnFalse() {
    assertFalse(registry.addCandidate(KEY, null, reply(new byte[16])));
    assertFalse(registry.addCandidate(KEY, reply(new byte[16]), null));
  }

  @Test
  void getCandidates_emptyReturnsEmptySet() {
    assertTrue(registry.getCandidates(KEY).isEmpty());
  }

  @Test
  void clearRemovesCandidates() {
    CwReply r1 = reply(new byte[16]);
    CwReply r2 = reply(new byte[]{1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    registry.addCandidate(KEY, r1, r2);
    assertFalse(registry.getCandidates(KEY).isEmpty());
    registry.clear(KEY);
    assertTrue(registry.getCandidates(KEY).isEmpty());
  }

  private static CwReply reply(byte[] cw) {
    return new CwReply(KEY, 0x80, cw, null, null, 0);
  }
}
