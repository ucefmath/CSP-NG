package com.bowman.cspng.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EcmRequestTest {

  @Test
  void bodyIsDefensivelyCopiedOnConstruction() {
    byte[] original = new byte[] {0x01, 0x02, 0x03};
    EcmKey key = new EcmKey(0x80, 0x1234, 0x0001, 0x0B00, 42);
    EcmRequest req = new EcmRequest(key, original, null, null, null, 0L, null, 0L);
    original[0] = (byte) 0xFF;
    assertEquals(0x01, req.body()[0], "EcmRequest doit cloner le body en entrée");
  }

  @Test
  void bodyIsDefensivelyCopiedOnAccess() {
    byte[] original = new byte[] {0x01, 0x02, 0x03};
    EcmKey key = new EcmKey(0x80, 0x1234, 0x0001, 0x0B00, 42);
    EcmRequest req = new EcmRequest(key, original, null, null, null, 0L, null, 0L);
    byte[] returned = req.body();
    returned[0] = (byte) 0xFF;
    assertEquals(0x01, req.body()[0], "EcmRequest doit cloner le body en sortie");
  }

  @Test
  void nullBodyBecomesEmptyArray() {
    EcmKey key = new EcmKey(0x80, 0x1234, 0x0001, 0x0B00, 42);
    EcmRequest req = new EcmRequest(key, null, null, null, null, 0L, null, 0L);
    assertEquals(0, req.body().length);
  }
}
