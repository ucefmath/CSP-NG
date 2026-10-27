package com.bowman.cspng.cluster.tcp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;

public class CspV2TlsMaterialTest {

  @Test
  void defensiveCopies() {
    byte[] c = "cert".getBytes();
    byte[] k = "key".getBytes();
    byte[] t = "trust".getBytes();
    CspV2TlsMaterial m = new CspV2TlsMaterial(c, k, t);
    c[0] = 0;
    assertNotEquals((byte)0, m.certPem()[0]);
    byte[] out = m.certPem();
    out[0] = 0;
    assertNotEquals((byte)0, m.certPem()[0]);
    byte[] out2 = m.keyPem();
    out2[0] = 42;
    assertNotEquals((byte)42, m.keyPem()[0]);
  }

  @Test
  void redactedToString() {
    CspV2TlsMaterial m = new CspV2TlsMaterial("c".getBytes(), "k".getBytes(), "t".getBytes());
    String s = m.toString();
    assertTrue(s.contains("redacted"));
    // ensure not leaking actual pem content
    byte[] secret = "superSecretCertContent".getBytes();
    CspV2TlsMaterial m2 = new CspV2TlsMaterial(secret, "k".getBytes(), "t".getBytes());
    assertFalse(m2.toString().contains("superSecret"));
    assertFalse(m2.toString().contains("superSecretCertContent"));
  }

  @Test
  void wipeAndDestroyed() {
    CspV2TlsMaterial m = new CspV2TlsMaterial("cert".getBytes(), "key".getBytes(), "trust".getBytes());
    assertFalse(m.isDestroyed());
    m.destroy();
    assertTrue(m.isDestroyed());
    assertThrows(IllegalStateException.class, () -> m.certPem());
    assertThrows(IllegalStateException.class, () -> m.keyPem());
    assertThrows(IllegalStateException.class, () -> m.trustPem());
    // second destroy idempotent
    assertDoesNotThrow(() -> m.destroy());
    // close alias
    CspV2TlsMaterial m2 = new CspV2TlsMaterial("c".getBytes(), "k".getBytes(), "t".getBytes());
    m2.close();
    assertTrue(m2.isDestroyed());
  }

  @Test
  void validationNonEmptyMax() {
    assertThrows(IllegalArgumentException.class, () -> new CspV2TlsMaterial(null, "k".getBytes(), "t".getBytes()));
    assertThrows(IllegalArgumentException.class, () -> new CspV2TlsMaterial(new byte[0], "k".getBytes(), "t".getBytes()));
    assertThrows(IllegalArgumentException.class, () -> new CspV2TlsMaterial("c".getBytes(), null, "t".getBytes()));
    byte[] big = new byte[65 * 1024];
    assertThrows(IllegalArgumentException.class, () -> new CspV2TlsMaterial(big, "k".getBytes(), "t".getBytes()));
    assertThrows(IllegalArgumentException.class, () -> new CspV2TlsMaterial("c".getBytes(), big, "t".getBytes()));
    byte[] ok = new byte[64 * 1024];
    Arrays.fill(ok, (byte) 'a');
    assertDoesNotThrow(() -> new CspV2TlsMaterial(ok, ok, ok));
  }

  @Test
  void equalsIdentityNotContent() {
    byte[] c = "same".getBytes();
    CspV2TlsMaterial a = new CspV2TlsMaterial(c, "k".getBytes(), "t".getBytes());
    CspV2TlsMaterial b = new CspV2TlsMaterial(c, "k".getBytes(), "t".getBytes());
    assertNotEquals(a, b);
    assertEquals(a, a);
    assertEquals(System.identityHashCode(a), a.hashCode());
    assertEquals(System.identityHashCode(b), b.hashCode());
  }
}
