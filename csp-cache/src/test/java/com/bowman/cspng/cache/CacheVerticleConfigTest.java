package com.bowman.cspng.cache;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CacheVerticleConfigTest {

  // ---- Test 20: getMaxCacheWait mode pourcentage

  @Test
  void getMaxCacheWaitPercentageMode() {
    CacheVerticleConfig config = new CacheVerticleConfig(30_000L, 100_000L, -1, 70);
    long result = config.getMaxCacheWait(7000L);
    // 70% of 7000 = 4900
    assertEquals(4900L, result);
  }

  @Test
  void getMaxCacheWaitPercentageModeRounding() {
    CacheVerticleConfig config = new CacheVerticleConfig(30_000L, 100_000L, -1, 70);
    long result = config.getMaxCacheWait(7001L);
    // 70% of 7001 = 4900.7 → truncated to 4900
    assertEquals(4900L, result);
  }

  // ---- Test 21: getMaxCacheWait mode absolu

  @Test
  void getMaxCacheWaitAbsoluteMode() {
    CacheVerticleConfig config = new CacheVerticleConfig(30_000L, 100_000L, 3000L, -1);
    long result = config.getMaxCacheWait(7000L);
    // min(7000, 3000) = 3000
    assertEquals(3000L, result);
  }

  @Test
  void getMaxCacheWaitAbsoluteModeCwWaitSmaller() {
    CacheVerticleConfig config = new CacheVerticleConfig(30_000L, 100_000L, 5000L, -1);
    long result = config.getMaxCacheWait(3000L);
    // min(3000, 5000) = 3000
    assertEquals(3000L, result);
  }

  @Test
  void defaults() {
    CacheVerticleConfig config = CacheVerticleConfig.defaults();
    assertEquals(30_000L, config.cwMaxAgeMs());
    assertEquals(100_000L, config.maximumSize());
    assertEquals(500L, config.maxCacheWaitMs());
    assertEquals(-1, config.maxCacheWaitPercent());
  }

  @Test
  void validationRejectsBothMinusOne() {
    assertThrows(IllegalArgumentException.class,
        () -> new CacheVerticleConfig(30_000L, 100_000L, -1, -1));
  }

  @Test
  void validationRejectsInvalidPercent() {
    assertThrows(IllegalArgumentException.class,
        () -> new CacheVerticleConfig(30_000L, 100_000L, -1, 101));
    assertThrows(IllegalArgumentException.class,
        () -> new CacheVerticleConfig(30_000L, 100_000L, -1, -2));
  }

  @Test
  void validationRejectsNegativeCwMaxAge() {
    assertThrows(IllegalArgumentException.class,
        () -> new CacheVerticleConfig(-1, 100_000L, 500L, -1));
  }
}
