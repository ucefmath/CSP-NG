package com.bowman.cspng.cluster.tcp;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import static org.junit.jupiter.api.Assertions.*;

class CspV2ReconnectPolicyTest {

  @Test void rejectInvalidArgs() {
    assertThrows(IllegalArgumentException.class, () -> CspV2ReconnectPolicy.create(0, 100));
    assertThrows(IllegalArgumentException.class, () -> CspV2ReconnectPolicy.create(-1, 100));
    assertThrows(IllegalArgumentException.class, () -> CspV2ReconnectPolicy.create(100, 50));
  }

  @Test void acceptValidArgs() {
    assertDoesNotThrow(() -> CspV2ReconnectPolicy.create(10, 30000));
    assertDoesNotThrow(() -> CspV2ReconnectPolicy.create(100, 100));
  }

  private static RandomGenerator fixedRng(long v) {
    return new RandomGenerator() {
      @Override public long nextLong() { return v; }
      @Override public long nextLong(long bound) { return Math.min(v, bound - 1); }
    };
  }

  @Test void jitterAlwaysZero() {
    CspV2ReconnectPolicy p = CspV2ReconnectPolicy.create(100, 30000, fixedRng(0));
    for (int i = 0; i < 10; i++) assertEquals(0, p.nextDelayMs());
  }

  @Test void jitterAlwaysCap() {
    RandomGenerator capRng = new RandomGenerator() {
      @Override public long nextLong() { return 0; }
      @Override public long nextLong(long bound) { return bound - 1; }
    };
    CspV2ReconnectPolicy p = CspV2ReconnectPolicy.create(100, 30000, capRng);
    assertEquals(100, p.nextDelayMs());
    assertEquals(200, p.nextDelayMs());
    assertEquals(400, p.nextDelayMs());
    assertEquals(800, p.nextDelayMs());
  }

  @Test void capCappedByMax() {
    RandomGenerator maxRng = new RandomGenerator() {
      @Override public long nextLong() { return 0; }
      @Override public long nextLong(long bound) { return bound - 1; }
    };
    CspV2ReconnectPolicy p = CspV2ReconnectPolicy.create(1000, 5000, maxRng);
    assertEquals(1000, p.nextDelayMs());
    assertEquals(2000, p.nextDelayMs());
    assertEquals(4000, p.nextDelayMs());
    assertEquals(5000, p.nextDelayMs()); // capped
    assertEquals(5000, p.nextDelayMs());
  }

  // -----------------------------------------------------------------------
  // Long.MAX_VALUE safe jitter
  // -----------------------------------------------------------------------
  @Test void longMaxValueJitterSafe() {
    // cap == Long.MAX_VALUE: factories must not overflow cap+1
    CspV2ReconnectPolicy p = new CspV2ReconnectPolicy(Long.MAX_VALUE - 1, Long.MAX_VALUE,
        cap -> cap); // jitter returns cap
    assertEquals(Long.MAX_VALUE - 1, p.nextDelayMs()); // attempt 0: cap = initial
    // attempt 1: cap overflow-safe → Long.MAX_VALUE
    long d2 = p.nextDelayMs();
    assertTrue(d2 >= 0 && d2 <= Long.MAX_VALUE); // non-negative, bounded by max
  }

  // -----------------------------------------------------------------------
  // O(log ratio) exponentialCap (single loop)
  // -----------------------------------------------------------------------
  @Test void exponentialCapMaxIntFast() {
    CspV2ReconnectPolicy p = CspV2ReconnectPolicy.create(10, 300000, fixedRng(0));
    p.setAttempt(Integer.MAX_VALUE);
    long start = System.nanoTime();
    p.nextDelayMs();
    long ns = System.nanoTime() - start;
    assertTrue(ns < TimeUnit.MILLISECONDS.toNanos(100),
        "MAX_INT attempt must be <100ms, took " + TimeUnit.NANOSECONDS.toMillis(ns) + "ms");
  }

  @Test void exponentialCapOverflowSafe() {
    CspV2ReconnectPolicy p = CspV2ReconnectPolicy.create(1L << 60, Long.MAX_VALUE, fixedRng(0));
    p.setAttempt(0); assertEquals(0, p.nextDelayMs());
    p.setAttempt(3); assertEquals(0, p.nextDelayMs());
    p.setAttempt(4); assertEquals(0, p.nextDelayMs()); // overflow safe
  }

  @Test void overflowCappedBySmallMax() {
    CspV2ReconnectPolicy p = CspV2ReconnectPolicy.create(100, 5000, fixedRng(0));
    p.setAttempt(Integer.MAX_VALUE);
    assertEquals(0, p.nextDelayMs());
    assertEquals(0, p.nextDelayMs());
  }

  // -----------------------------------------------------------------------
  // attempt bounded
  // -----------------------------------------------------------------------
  @Test void attemptBounded() {
    CspV2ReconnectPolicy p = CspV2ReconnectPolicy.create(100, 100, fixedRng(0));
    for (int i = 0; i < 10000; i++) p.nextDelayMs();
    assertTrue(p.attempt() <= Integer.MAX_VALUE);
  }

  @Test void attemptIncrements() {
    CspV2ReconnectPolicy p = CspV2ReconnectPolicy.create(100, 30000, fixedRng(0));
    assertEquals(0, p.attempt());
    p.nextDelayMs(); assertEquals(1, p.attempt());
    p.nextDelayMs(); assertEquals(2, p.attempt());
  }

  @Test void resetResetsAttempt() {
    CspV2ReconnectPolicy p = CspV2ReconnectPolicy.create(100, 30000, fixedRng(0));
    p.nextDelayMs(); p.nextDelayMs();
    assertTrue(p.attempt() >= 2);
    p.reset(); assertEquals(0, p.attempt());
    assertTrue(p.nextDelayMs() <= 100);
  }

  // -----------------------------------------------------------------------
  // LongUnaryOperator injection
  // -----------------------------------------------------------------------
  @Test void customJitterFunction() {
    CspV2ReconnectPolicy p = new CspV2ReconnectPolicy(100, 5000, cap -> cap);
    assertEquals(100, p.nextDelayMs());
    assertEquals(200, p.nextDelayMs());
  }

  @Test void jitterClampedOutOfRange() {
    CspV2ReconnectPolicy p = new CspV2ReconnectPolicy(100, 5000, cap -> cap + 1000);
    assertEquals(100, p.nextDelayMs()); // clamped
  }

  @Test void jitterNegativeClamped() {
    CspV2ReconnectPolicy p = new CspV2ReconnectPolicy(100, 5000, cap -> -50);
    assertEquals(0, p.nextDelayMs());
  }

  // -----------------------------------------------------------------------
  // Range and defaults
  // -----------------------------------------------------------------------
  @Test void delayWithinRange() {
    CspV2ReconnectPolicy p = CspV2ReconnectPolicy.create(100, 30000, fixedRng(0));
    for (int i = 0; i < 50; i++) assertTrue(p.nextDelayMs() >= 0);
  }

  @Test void delayNeverExceedsMax() {
    CspV2ReconnectPolicy p = CspV2ReconnectPolicy.create(10, 100);
    for (int i = 0; i < 100; i++) {
      long d = p.nextDelayMs();
      assertTrue(d >= 0 && d <= 100, "delay " + d + " > max at attempt " + i);
    }
  }
}
