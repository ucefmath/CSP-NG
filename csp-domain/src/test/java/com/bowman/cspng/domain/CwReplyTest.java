package com.bowman.cspng.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CwReplyTest {

  @Test
  void cwIsDefensivelyCopiedOnConstruction() {
    byte[] original = new byte[] {0x01, 0x02, 0x03};
    EcmKey key = new EcmKey(0x80, 0x1234, 0x0001, 0x0B00, 42);
    CwReply reply = new CwReply(key, 0x80, original, null, null, 0L);
    original[0] = (byte) 0xFF;
    assertEquals(0x01, reply.cw()[0], "CwReply doit cloner le CW en entrée");
  }

  @Test
  void cwIsDefensivelyCopiedOnAccess() {
    byte[] original = new byte[] {0x01, 0x02, 0x03};
    EcmKey key = new EcmKey(0x80, 0x1234, 0x0001, 0x0B00, 42);
    CwReply reply = new CwReply(key, 0x80, original, null, null, 0L);
    byte[] returned = reply.cw();
    returned[0] = (byte) 0xFF;
    assertEquals(0x01, reply.cw()[0], "CwReply doit cloner le CW en sortie");
  }

  @Test
  void nullCwBecomesEmptyArrayAndIsEmpty() {
    EcmKey key = new EcmKey(0x80, 0x1234, 0x0001, 0x0B00, 42);
    CwReply reply = new CwReply(key, 0x80, null, null, null, 0L);
    assertEquals(0, reply.cw().length);
    assertTrue(reply.isEmpty());
  }
}
