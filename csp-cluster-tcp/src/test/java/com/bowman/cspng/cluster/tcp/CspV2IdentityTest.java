package com.bowman.cspng.cluster.tcp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;

public class CspV2IdentityTest {

  static X509Certificate fakeCert(Collection<List<?>> sans) {
    return new X509Certificate() {
      public Collection<List<?>> getSubjectAlternativeNames() throws CertificateParsingException { return sans; }
      public void checkValidity() throws CertificateExpiredException, CertificateNotYetValidException {}
      public void checkValidity(Date date) throws CertificateExpiredException, CertificateNotYetValidException {}
      public int getVersion() { return 3; }
      public BigInteger getSerialNumber() { return BigInteger.ONE; }
      public Principal getIssuerDN() { return () -> "CN=issuer"; }
      public Principal getSubjectDN() { return () -> "CN=subject"; }
      public Date getNotBefore() { return new Date(); }
      public Date getNotAfter() { return new Date(System.currentTimeMillis()+86400000); }
      public byte[] getTBSCertificate() throws CertificateEncodingException { return new byte[0]; }
      public byte[] getSignature() { return new byte[0]; }
      public String getSigAlgName() { return "SHA256withRSA"; }
      public String getSigAlgOID() { return "1.2.3"; }
      public byte[] getSigAlgParams() { return null; }
      public boolean[] getIssuerUniqueID() { return null; }
      public boolean[] getSubjectUniqueID() { return null; }
      public boolean[] getKeyUsage() { return null; }
      public int getBasicConstraints() { return -1; }
      public byte[] getEncoded() throws CertificateEncodingException { return new byte[0]; }
      public void verify(PublicKey key) {}
      public void verify(PublicKey key, String sigProvider) {}
      public String toString() { return "fake"; }
      public PublicKey getPublicKey() { return null; }
      public boolean hasUnsupportedCriticalExtension() { return false; }
      public Set<String> getCriticalExtensionOIDs() { return null; }
      public Set<String> getNonCriticalExtensionOIDs() { return null; }
      public byte[] getExtensionValue(String oid) { return null; }
    };
  }

  static List<?> sanUri(String uri) { return List.of(6, uri); }
  static List<?> sanDns(String dns) { return List.of(2, dns); }

  @Test
  void extractSingleUriSuccess() {
    X509Certificate cert = fakeCert(List.of(sanUri("urn:csp:node:node-a")));
    List<Certificate> chain = List.of(cert);
    assertEquals("node-a", CspV2Identity.extractNodeId(chain));
  }

  @Test
  void extractFailsZeroUri() {
    X509Certificate cert = fakeCert(List.of());
    assertThrows(IllegalArgumentException.class, () -> CspV2Identity.extractNodeId(List.of(cert)));
    X509Certificate certNull = fakeCert(null);
    assertThrows(IllegalArgumentException.class, () -> CspV2Identity.extractNodeId(List.of(certNull)));
  }

  @Test
  void extractFailsTwoUris() {
    X509Certificate cert = fakeCert(List.of(sanUri("urn:csp:node:node-a"), sanUri("urn:csp:node:node-b")));
    assertThrows(IllegalArgumentException.class, () -> CspV2Identity.extractNodeId(List.of(cert)));
  }

  @Test
  void extractFailsInvalidPrefix() {
    X509Certificate cert = fakeCert(List.of(sanUri("urn:csp:wrong:node-a")));
    assertThrows(IllegalArgumentException.class, () -> CspV2Identity.extractNodeId(List.of(cert)));
  }

  @Test
  void extractFailsInvalidNodeSuffix() {
    X509Certificate cert = fakeCert(List.of(sanUri("urn:csp:node:BAD-UPPER")));
    assertThrows(IllegalArgumentException.class, () -> CspV2Identity.extractNodeId(List.of(cert)));
    X509Certificate cert2 = fakeCert(List.of(sanUri("urn:csp:node:")));
    assertThrows(IllegalArgumentException.class, () -> CspV2Identity.extractNodeId(List.of(cert2)));
  }

  @Test
  void dnsSanIgnoredIfOneUri() {
    X509Certificate cert = fakeCert(List.of(sanUri("urn:csp:node:node-a"), sanDns("example.com")));
    // has one URI but also DNS => uriCount should be 1 still? Actually we count only URI type 6, DNS is type 2, so uriCount=1 passes.
    // Our implementation counts only URI types, so this should succeed.
    assertEquals("node-a", CspV2Identity.extractNodeId(List.of(cert)));
  }

  @Test
  void twoUrisWithDnsFails() {
    X509Certificate cert = fakeCert(List.of(sanUri("urn:csp:node:node-a"), sanUri("urn:csp:node:node-b"), sanDns("example.com")));
    assertThrows(IllegalArgumentException.class, () -> CspV2Identity.extractNodeId(List.of(cert)));
  }

  @Test
  void verifyPeerSuccessAndMismatch() {
    X509Certificate cert = fakeCert(List.of(sanUri("urn:csp:node:node-b")));
    assertDoesNotThrow(() -> CspV2Identity.verifyPeer(List.of(cert), "node-b"));
    assertThrows(IllegalArgumentException.class, () -> CspV2Identity.verifyPeer(List.of(cert), "node-c"));
    // exception message generic not containing DN
    try {
      CspV2Identity.verifyPeer(List.of(cert), "node-c");
      fail();
    } catch (IllegalArgumentException e) {
      assertFalse(e.getMessage().contains("node-b"));
      assertFalse(e.getMessage().toLowerCase().contains("subject"));
    }
  }

  @Test
  void verifyPeerRefusesNonX509AndNull() {
    Certificate nonX509 = new Certificate("X.509") {
      public byte[] getEncoded() { return new byte[0]; }
      public void verify(PublicKey key) {}
      public void verify(PublicKey key, String sigProvider) {}
      public String toString() { return "nonX509"; }
      public PublicKey getPublicKey() { return null; }
    };
    assertThrows(IllegalArgumentException.class, () -> CspV2Identity.verifyPeer(List.of(nonX509), "node-a"));
    assertThrows(IllegalArgumentException.class, () -> CspV2Identity.verifyPeer(null, "node-a"));
    assertThrows(IllegalArgumentException.class, () -> CspV2Identity.verifyPeer(List.of(), "node-a"));
  }

  @Test
  void verifyLocalSuccess() {
    X509Certificate cert = fakeCert(List.of(sanUri("urn:csp:node:node-a")));
    assertDoesNotThrow(() -> CspV2Identity.verifyLocal(cert, "node-a"));
    assertThrows(IllegalArgumentException.class, () -> CspV2Identity.verifyLocal(cert, "node-b"));
    assertThrows(IllegalArgumentException.class, () -> CspV2Identity.verifyLocal(null, "node-a"));
  }

  @Test
  void extractDoesNotExposeDn() {
    X509Certificate cert = fakeCert(List.of(sanUri("urn:csp:node:node-a")));
    // Ensure exception generic without SAN content when fails
    X509Certificate bad = fakeCert(List.of(sanUri("urn:csp:node:BAD")));
    try {
      CspV2Identity.extractNodeId(List.of(bad));
      fail();
    } catch (IllegalArgumentException e) {
      String msg = e.getMessage();
      assertFalse(msg.contains("BAD"));
      assertFalse(msg.contains("urn:csp"));
    }
  }
}
