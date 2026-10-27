package com.bowman.cspng.cluster.tcp;

import java.util.Arrays;

/**
 * Matériel TLS PEM déjà résolu. Défensive copies, max 64KiB chaque, pas de fuite via toString/equals/hashCode.
 * AutoCloseable avec wipe sans lock (volatile destroyed + Arrays.fill).
 */
public final class CspV2TlsMaterial implements AutoCloseable {

  public static final int MAX_PEM_BYTES = 64 * 1024;

  private final byte[] certPem;
  private final byte[] keyPem;
  private final byte[] trustPem;
  private volatile boolean destroyed;

  public CspV2TlsMaterial(byte[] certPem, byte[] keyPem, byte[] trustPem) {
    if (certPem == null || certPem.length == 0 || certPem.length > MAX_PEM_BYTES) {
      throw new IllegalArgumentException("certPem must be non-empty max 64KiB");
    }
    if (keyPem == null || keyPem.length == 0 || keyPem.length > MAX_PEM_BYTES) {
      throw new IllegalArgumentException("keyPem must be non-empty max 64KiB");
    }
    if (trustPem == null || trustPem.length == 0 || trustPem.length > MAX_PEM_BYTES) {
      throw new IllegalArgumentException("trustPem must be non-empty max 64KiB");
    }
    this.certPem = Arrays.copyOf(certPem, certPem.length);
    this.keyPem = Arrays.copyOf(keyPem, keyPem.length);
    this.trustPem = Arrays.copyOf(trustPem, trustPem.length);
    this.destroyed = false;
  }

  public byte[] certPem() {
    if (destroyed) throw new IllegalStateException("material destroyed");
    return Arrays.copyOf(certPem, certPem.length);
  }

  public byte[] keyPem() {
    if (destroyed) throw new IllegalStateException("material destroyed");
    return Arrays.copyOf(keyPem, keyPem.length);
  }

  public byte[] trustPem() {
    if (destroyed) throw new IllegalStateException("material destroyed");
    return Arrays.copyOf(trustPem, trustPem.length);
  }

  public boolean isDestroyed() {
    return destroyed;
  }

  /**
   * Wipe les buffers internes. Idempotent.
   */
  public void destroy() {
    if (!destroyed) {
      Arrays.fill(certPem, (byte) 0);
      Arrays.fill(keyPem, (byte) 0);
      Arrays.fill(trustPem, (byte) 0);
      destroyed = true;
    }
  }

  @Override
  public void close() {
    destroy();
  }

  /** Alias pour compatibilité. */
  public void wipe() {
    destroy();
  }

  @Override
  public String toString() {
    return "CspV2TlsMaterial[redacted, destroyed=" + destroyed + "]";
  }

  @Override
  public boolean equals(Object o) {
    return this == o;
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }
}
