package com.bowman.cspng.cache;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import org.junit.jupiter.api.Test;

class DcwValidatorTest {

  private static final EcmKey KEY = new EcmKey(0x80, 0x1234, 0x0001, 0x0B00, 0x12345678);
  private static final byte[] CW1 = {
      0x01, 0x02, 0x03, 0x06, 0x04, 0x05, 0x06, 0x0F,
      0x07, 0x08, 0x09, 0x18, 0x0A, 0x0B, 0x0C, 0x21
  };
  private static final byte[] CW2 = {
      0x01, 0x02, 0x03, 0x06, 0x04, 0x05, 0x06, 0x0F,
      (byte) 0xFF, 0x00, 0x01, 0x00, 0x0A, 0x0B, 0x0C, 0x21
  };
  private static final byte[] CW_ZERO_SECOND_HALF = {
      0x01, 0x02, 0x03, 0x06, 0x04, 0x05, 0x06, 0x0F,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
  };
  private static final byte[] CW_ALL_ZERO = new byte[16];
  private static final byte[] CW_FIVE_ZEROES = {
      0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03,
      (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, (byte) 0xFF, 0x00, 0x01
  };

  // ---- Test 12: equalsSingleDcw — une moitié correspond, l'autre non

  @Test
  void equalsSingleDcw_firstHalfMatches() {
    // CW1 and CW2 share first 8 bytes, differ in second half
    assertTrue(DcwValidator.equalsSingleDcw(CW1, CW2));
  }

  @Test
  void equalsSingleDcw_noMatchAtAll() {
    byte[] cwA = new byte[16];
    byte[] cwB = new byte[16];
    for (int i = 0; i < 16; i++) {
      cwA[i] = (byte) i;
      cwB[i] = (byte) (i + 1);
    }
    assertFalse(DcwValidator.equalsSingleDcw(cwA, cwB));
  }

  // ---- Test 13: equalsSingleDcw avec moitié entièrement à zéro

  @Test
  void equalsSingleDcw_zeroHalfNotCountedAsMatch() {
    // CW_ZERO_SECOND_HALF has first half matching CW1, second half all zeros
    // The zero half should NOT count as a match per legacy behavior
    byte[] cwOther = {
        0x01, 0x02, 0x03, 0x06, 0x04, 0x05, 0x06, 0x0F,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };
    // Against CW1: first half matches, second half is zeros on one side
    assertTrue(DcwValidator.equalsSingleDcw(CW1, cwOther),
        "First half match should still count even if other half is zero");
  }

  @Test
  void equalsSingleDcw_bothZeroHalves() {
    byte[] cwZeroA = {0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 6, 4, 5, 6, 15};
    byte[] cwZeroB = {0, 0, 0, 0, 0, 0, 0, 0, 7, 8, 9, 24, 10, 11, 12, 33};
    // Both have first half zero — this should NOT count as match (legacy: cz==0 → reset to false)
    // Second half differs → no match
    assertFalse(DcwValidator.equalsSingleDcw(cwZeroA, cwZeroB));
  }

  // ---- Test 14: hasZeroDcw

  @Test
  void hasZeroDcw_firstHalfZero() {
    byte[] cw = new byte[16];
    System.arraycopy(CW1, 8, cw, 8, 8);
    assertTrue(DcwValidator.hasZeroDcw(cw));
  }

  @Test
  void hasZeroDcw_secondHalfZero() {
    byte[] cw = new byte[16];
    System.arraycopy(CW1, 0, cw, 0, 8);
    assertTrue(DcwValidator.hasZeroDcw(cw));
  }

  @Test
  void hasZeroDcw_noneZero() {
    assertFalse(DcwValidator.hasZeroDcw(CW1));
  }

  @Test
  void hasZeroDcw_allZero() {
    byte[] cw = new byte[16];
    assertTrue(DcwValidator.hasZeroDcw(cw));
  }

  // ---- checksumDcw

  @Test
  void checksumDcw_valid() {
    assertTrue(DcwValidator.checksumDcw(CW1));
  }

  @Test
  void checksumDcw_invalid() {
    byte[] cw = CW1.clone();
    cw[3] = 0x42; // corrupt checksum
    assertFalse(DcwValidator.checksumDcw(cw));
  }

  @Test
  void checksumDcw_nullOrWrongLength() {
    assertFalse(DcwValidator.checksumDcw(null));
    assertFalse(DcwValidator.checksumDcw(new byte[8]));
    assertFalse(DcwValidator.checksumDcw(new byte[0]));
  }

  // ---- hasFiveZeroes

  @Test
  void hasFiveZeroes_exactlyFiveInFirstHalf() {
    assertTrue(DcwValidator.hasFiveZeroes(CW_FIVE_ZEROES));
  }

  @Test
  void hasFiveZeroes_allEightZerosTolerated() {
    byte[] cw = new byte[16];
    assertFalse(DcwValidator.hasFiveZeroes(cw));
  }

  @Test
  void hasFiveZeroes_noZeroes() {
    assertFalse(DcwValidator.hasFiveZeroes(CW1));
  }

  // ---- CwReply convenience overloads

  @Test
  void equalsSingleDcw_replyOverload() {
    CwReply a = new CwReply(KEY, 0x80, CW1, null, null, 0);
    CwReply b = new CwReply(KEY, 0x80, CW2, null, null, 0);
    assertTrue(DcwValidator.equalsSingleDcw(a, b));
  }

  @Test
  void equalsSingleDcw_replyOverloadNull() {
    assertFalse(DcwValidator.equalsSingleDcw((CwReply) null, (CwReply) null));
    CwReply a = new CwReply(KEY, 0x80, CW1, null, null, 0);
    assertFalse(DcwValidator.equalsSingleDcw(a, null));
  }

  @Test
  void hasZeroDcw_replyOverload() {
    // CW_ZERO_SECOND_HALF has first half non-zero, second half all zero.
    // hasZeroDcw returns true if EITHER half is all zero.
    CwReply r = new CwReply(KEY, 0x80, CW_ZERO_SECOND_HALF, null, null, 0);
    assertTrue(DcwValidator.hasZeroDcw(r), "Second half of CW_ZERO_SECOND_HALF is all zeros");
  }
}
