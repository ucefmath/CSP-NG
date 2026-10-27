package com.bowman.cspng.testkit;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.testkit.VectorRepository.Vector;
import org.junit.jupiter.api.Test;

class VectorRepositoryTest {

  @Test
  void loadsAtLeastThirtyVectors() {
    VectorRepository repo = new VectorRepository();
    assertTrue(repo.all().size() >= 30, "Expected at least 30 vectors, got " + repo.all().size());
  }

  @Test
  void coversMandatoryFrameTypesAndCases() {
    VectorRepository repo = new VectorRepository();
    assertTrue(repo.countByType("REQUEST") >= 1);
    assertTrue(repo.countByType("REPLY") >= 1);
    assertTrue(repo.countByType("PINGREQ") >= 1);
    assertTrue(repo.countByType("PINGRPL") >= 1);
    assertTrue(repo.countByType("RESENDREQ") >= 1);
    assertTrue(repo.countByType("RAW") >= 1);

    assertTrue(repo.byId("req-onid0").isPresent());
    assertTrue(repo.byId("req-caid0").isPresent());
    assertTrue(repo.byId("req-arbiter-neg").isPresent());
    assertTrue(repo.byId("reply-cw-name-32").isPresent());
    assertTrue(repo.byId("reply-cw-name-utf8").isPresent());
    assertTrue(repo.byId("truncated-req").isPresent());
    assertTrue(repo.byId("oversized-req").isPresent());
    assertTrue(repo.byId("unknown-type").isPresent());
  }

  @Test
  void hexLengthMatchesDeclaredLength() {
    VectorRepository repo = new VectorRepository();
    for (Vector v : repo.all()) {
      assertEquals(v.length(), v.bytes().length,
          "Declared length does not match hex length for vector " + v.id());
    }
  }

  @Test
  void knownVectorDecodesBackToExpectedBytes() {
    VectorRepository repo = new VectorRepository();
    Vector v = repo.byId("req-basic-80").orElseThrow();
    byte[] bytes = v.bytes();
    assertEquals(12, bytes.length);
    assertEquals(0x01, bytes[0] & 0xFF); // type REQUEST
    assertEquals(0x80, bytes[1] & 0xFF); // commandTag
  }
}
