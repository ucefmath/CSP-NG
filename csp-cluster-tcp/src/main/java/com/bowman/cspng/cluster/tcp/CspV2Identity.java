package com.bowman.cspng.cluster.tcp;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Vérification d'identité mTLS via SAN URI urn:csp:node:<id>.
 * Ne jamais logger DN/SAN brut, messages génériques uniquement.
 * Subject DN/CN jamais fallback.
 */
public final class CspV2Identity {

  private CspV2Identity() {}

  private static final String URN_PREFIX = "urn:csp:node:";
  private static final Pattern NODE_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,62}");

  /**
   * Extrait nodeId depuis la feuille (index 0) d'une chaîne cert.
   * Exige exactement 1 SAN URI(6) avec préfixe urn:csp:node: + regex node.
   * @throws IllegalArgumentException générique sans DN/SAN brut
   */
  public static String extractNodeId(List<Certificate> certs) {
    if (certs == null || certs.isEmpty()) {
      throw new IllegalArgumentException("peer identity verification failed");
    }
    Certificate leaf = certs.get(0);
    if (!(leaf instanceof X509Certificate x509)) {
      throw new IllegalArgumentException("peer identity verification failed");
    }
    return extractNodeIdFromX509(x509);
  }

  private static String extractNodeIdFromX509(X509Certificate cert) {
    if (cert == null) throw new IllegalArgumentException("peer identity verification failed");
    Collection<List<?>> sans;
    try {
      sans = cert.getSubjectAlternativeNames();
    } catch (Exception e) {
      throw new IllegalArgumentException("peer identity verification failed", e);
    }
    if (sans == null || sans.isEmpty()) {
      throw new IllegalArgumentException("peer identity verification failed");
    }
    String found = null;
    int uriCount = 0;
    for (List<?> entry : sans) {
      if (entry == null || entry.size() < 2) continue;
      Object typeObj = entry.get(0);
      Object valueObj = entry.get(1);
      if (!(typeObj instanceof Integer type)) continue;
      if (type != 6) continue; // URI only
      uriCount++;
      if (!(valueObj instanceof String uri)) {
        throw new IllegalArgumentException("peer identity verification failed");
      }
      if (!uri.startsWith(URN_PREFIX)) {
        throw new IllegalArgumentException("peer identity verification failed");
      }
      String suffix = uri.substring(URN_PREFIX.length());
      if (suffix.isEmpty() || !NODE_ID_PATTERN.matcher(suffix).matches()) {
        throw new IllegalArgumentException("peer identity verification failed");
      }
      if (found != null) {
        // more than one valid URI => fail (also >1 uriCount)
        throw new IllegalArgumentException("peer identity verification failed");
      }
      found = suffix;
    }
    if (found == null) {
      throw new IllegalArgumentException("peer identity verification failed");
    }
    if (uriCount != 1) {
      throw new IllegalArgumentException("peer identity verification failed");
    }
    return found;
  }

  /**
   * Vérifie peer : certs doit contenir exactement le expected nodeId via SAN URI.
   * Refuse non-X509/null/nonSSL. Egalité via equals (nodeId non secret).
   */
  public static void verifyPeer(List<Certificate> certs, String expectedNodeId) {
    if (expectedNodeId == null || expectedNodeId.isBlank()) {
      throw new IllegalArgumentException("peer identity verification failed");
    }
    if (!NODE_ID_PATTERN.matcher(expectedNodeId.trim()).matches()) {
      throw new IllegalArgumentException("peer identity verification failed");
    }
    String extracted = extractNodeId(certs);
    if (!extracted.equals(expectedNodeId.trim())) {
      throw new IllegalArgumentException("peer identity verification failed");
    }
  }

  /**
   * Vérifie certificat local correspond au nodeId configuré.
   */
  public static void verifyLocal(X509Certificate cert, String expectedNodeId) {
    if (cert == null) throw new IllegalArgumentException("peer identity verification failed");
    if (expectedNodeId == null || expectedNodeId.isBlank()) {
      throw new IllegalArgumentException("peer identity verification failed");
    }
    if (!NODE_ID_PATTERN.matcher(expectedNodeId.trim()).matches()) {
      throw new IllegalArgumentException("peer identity verification failed");
    }
    // Reuse extraction logic but for single cert
    String extracted = extractNodeIdFromX509(cert);
    if (!extracted.equals(expectedNodeId.trim())) {
      throw new IllegalArgumentException("peer identity verification failed");
    }
  }
}
