package com.bowman.cspng.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EcmKeyTest {

  @Test
  void identityDependsOnlyOnDataHash() {
    EcmKey a = new EcmKey(0x80, 0x1234, 0x0001, 0x0B00, 42);
    EcmKey b = new EcmKey(0x81, 0x5678, 0x0002, 0x0B01, 42);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void differentHashMeansDifferentKey() {
    EcmKey a = new EcmKey(0x80, 0x1234, 0x0001, 0x0B00, 42);
    EcmKey b = new EcmKey(0x80, 0x1234, 0x0001, 0x0B00, 43);
    assertNotEquals(a, b);
  }
}
