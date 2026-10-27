package com.bowman.cspng.cluster.tcp;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;
import java.util.random.RandomGenerator;

/**
 * Politique de reconnexion full jitter conforme ADR §10.
 *
 * <p>Cap exponentiel {@code min(max, initial * 2^attempt)} en une seule boucle
 * max 63 itérations (overflow-safe), full jitter uniforme {@code [0, cap]}
 * inclusif. Quand {@code cap == Long.MAX_VALUE}, utilise
 * {@code nextLong() & Long.MAX_VALUE} pour éviter overflow de {@code cap+1}.
 */
public final class CspV2ReconnectPolicy {

  private final long initialMs;
  private final long maxMs;
  private final LongUnaryOperator jitterFn;
  private int attempt;

  CspV2ReconnectPolicy(long initialMs, long maxMs, LongUnaryOperator jitterFn) {
    if (initialMs <= 0) throw new IllegalArgumentException("initialMs must be > 0");
    if (maxMs < initialMs) throw new IllegalArgumentException("maxMs must be >= initialMs");
    Objects.requireNonNull(jitterFn, "jitterFn");
    this.initialMs = initialMs;
    this.maxMs = maxMs;
    this.jitterFn = jitterFn;
    this.attempt = 0;
  }

  private static long safeJitter(RandomGenerator rng, long cap) {
    if (cap <= 0) return 0L;
    if (cap == Long.MAX_VALUE) return rng.nextLong() & Long.MAX_VALUE;
    return rng.nextLong(cap + 1);
  }

  public static CspV2ReconnectPolicy create(long initialMs, long maxMs) {
    return new CspV2ReconnectPolicy(initialMs, maxMs, cap ->
        safeJitter(ThreadLocalRandom.current(), cap));
  }

  public static CspV2ReconnectPolicy create(long initialMs, long maxMs, RandomGenerator rng) {
    Objects.requireNonNull(rng, "rng");
    return new CspV2ReconnectPolicy(initialMs, maxMs, cap -> safeJitter(rng, cap));
  }

  public long nextDelayMs() {
    long cap = exponentialCap();
    long delay = jitterFn.applyAsLong(cap);
    if (delay < 0) delay = 0; else if (delay > cap) delay = cap;
    if (attempt < Integer.MAX_VALUE) attempt++;
    return delay;
  }

  /**
   * Single loop O(log ratio), max 63 itérations. À chaque itération :
   * double exp (overflow-safe), si exp >= maxMs → cap = maxMs ; sinon continue.
   * Si attempt épuise les doublements, le cap reste exp.
   */
  long exponentialCap() {
    long exp = initialMs;
    int left = attempt;
    while (left > 0 && exp < maxMs) {
      long next;
      try { next = Math.multiplyExact(exp, 2); } catch (ArithmeticException e) { next = Long.MAX_VALUE; }
      if (next >= maxMs) { exp = maxMs; break; }
      exp = next;
      left--;
    }
    return Math.min(exp, maxMs);
  }

  public void reset() { this.attempt = 0; }

  public int attempt() { return attempt; }

  void setAttempt(int a) { this.attempt = Math.max(0, a); }

  public long initialMs() { return initialMs; }
  public long maxMs() { return maxMs; }
}
