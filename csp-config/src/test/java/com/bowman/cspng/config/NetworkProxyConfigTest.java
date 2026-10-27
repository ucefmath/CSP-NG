package com.bowman.cspng.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires de {@link NetworkProxyConfig} — validation stricte du record
 * (Plan P2) : nom immutable PK, type socks5 seul, host sans URI/userinfo/ctrl,
 * port 1..65535, auth appariée env:/file:, timeout borné.
 */
class NetworkProxyConfigTest {

  private static NetworkProxyConfig mk(String name, boolean enabled, String host, int port,
                                       String username, String passwordRef, long version) {
    return new NetworkProxyConfig(name, enabled, "socks5", host, port, username, passwordRef,
        null, version);
  }

  @Test
  void validMinimalProxy() {
    NetworkProxyConfig p = mk("proxy-a", true, "172.20.0.1", 1080, null, null, 1L);
    assertEquals("proxy-a", p.name());
    assertTrue(p.enabled());
    assertFalse(p.hasAuth());
    assertEquals(1L, p.version());
  }

  @Test
  void validWithAuthEnvRef() {
    NetworkProxyConfig p = mk("p2", false, "proxy.example.com", 1080, "user", "env:MY_SECRET", 3L);
    assertTrue(p.hasAuth());
    assertEquals("user", p.username());
    assertEquals("env:MY_SECRET", p.passwordRef());
    assertFalse(p.enabled());
  }

  @Test
  void validWithAuthFileRef() {
    NetworkProxyConfig p = mk("p3", true, "2001:db8::1", 1080, "u", "file:/run/secrets/pw", 1L);
    assertTrue(p.hasAuth());
    assertEquals("2001:db8::1", p.host()); // IPv6 brut documenté, sans crochets
  }

  @Test
  void validHostnameDns() {
    NetworkProxyConfig p = mk("p-dns", true, "proxy.internal.lan", 1080, null, null, 1L);
    assertEquals("proxy.internal.lan", p.host());
  }

  // --- Name validation ---

  @Test
  void nameRegexEnforced() {
    // valid charset
    assertDoesNotThrow(() -> mk("A-z0_9.-x", true, "h", 1, null, null, 1L));
    // invalid chars
    assertThrows(IllegalArgumentException.class, () -> mk("bad name", true, "h", 1, null, null, 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("bad:name", true, "h", 1, null, null, 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("bad/name", true, "h", 1, null, null, 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("a@b", true, "h", 1, null, null, 1L));
    // too long (65 chars)
    assertThrows(IllegalArgumentException.class, () -> mk("a".repeat(65), true, "h", 1, null, null, 1L));
    // empty / null
    assertThrows(IllegalArgumentException.class, () -> mk("", true, "h", 1, null, null, 1L));
    assertThrows(IllegalArgumentException.class, () -> mk(null, true, "h", 1, null, null, 1L));
  }

  // --- Type validation ---

  @Test
  void typeMustBeSocks5() {
    assertThrows(IllegalArgumentException.class, () ->
        new NetworkProxyConfig("p", true, "http", "h", 1, null, null, null, 1L));
    assertThrows(IllegalArgumentException.class, () ->
        new NetworkProxyConfig("p", true, "socks4", "h", 1, null, null, null, 1L));
    assertThrows(IllegalArgumentException.class, () ->
        new NetworkProxyConfig("p", true, null, "h", 1, null, null, null, 1L));
  }

  // --- Host validation ---

  @Test
  void hostRejectsUriAndUserinfo() {
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "socks5://h", 1, null, null, 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "user@host", 1, null, null, 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "ho st", 1, null, null, 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "host\tname", 1, null, null, 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "", 1, null, null, 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, null, 1, null, null, 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "host\u0007name", 1, null, null, 1L));
  }

  // --- Port validation ---

  @Test
  void portBounds() {
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", 0, null, null, 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", -1, null, null, 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", 65536, null, null, 1L));
    assertDoesNotThrow(() -> mk("p", true, "h", 1, null, null, 1L));
    assertDoesNotThrow(() -> mk("p", true, "h", 65535, null, null, 1L));
  }

  // --- Auth pairing ---

  @Test
  void authPairingEnforced() {
    // username sans passwordRef
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", 1, "user", null, 1L));
    // passwordRef sans username
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", 1, null, "env:X", 1L));
    // passwordRef invalide
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", 1, "u", "vault:X", 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", 1, "u", "plainvalue", 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", 1, "u", "", 1L));
    // appariés OK
    assertDoesNotThrow(() -> mk("p", true, "h", 1, "u", "env:X", 1L));
    assertDoesNotThrow(() -> mk("p", true, "h", 1, "u", "file:/run/secrets/x", 1L));
    // Durcissement isValidSecretRef (Plan P2 / revue) :
    // file: RELATIF rejeté, file: avec traversée ../ rejeté
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", 1, "u", "file:secrets/x", 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", 1, "u", "file:/run/../etc/shadow", 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", 1, "u", "file:../secret", 1L));
    // vault: et ${...} refusés pour le PROXY (env:/file: only — contrairement aux
    // secrets connecteur qui tolèrent vault:)
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", 1, "u", "vault:kv/secret", 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", 1, "u", "${file:/x}", 1L));
    // ctrl chars dans la ref rejetés
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", 1, "u", "env:X\u0007Y", 1L));
    // whitespace entourant refusé (pas de trim silencieux)
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", 1, "u", " env:X", 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", 1, "u", "env:X\n", 1L));
  }

  // --- PAS de timeout proxy (Plan P2) — retiré ---

  // --- Description / version ---

  @Test
  void descriptionAndVersionBounds() {
    assertThrows(IllegalArgumentException.class, () ->
        new NetworkProxyConfig("p", true, "socks5", "h", 1, null, null, "x".repeat(501), 1L));
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", 1, null, null, 0L));
    assertThrows(IllegalArgumentException.class, () -> mk("p", true, "h", 1, null, null, -1L));
    assertDoesNotThrow(() ->
        new NetworkProxyConfig("p", true, "socks5", "h", 1, null, null, "x".repeat(500), 1L));
  }

  // --- Structure : pas de timeout sur le proxy (Plan P2) ---

  @Test
  void recordHasNoTimeoutField() {
    // Plan P2 : le proxy ne porte PAS de timeout — le budget T est porté par
    // le connecteur (csp_connector.connect_timeout_ms) aux deux phases.
    java.util.Set<String> names = java.util.Arrays.stream(NetworkProxyConfig.class.getRecordComponents())
        .map(java.lang.reflect.RecordComponent::getName)
        .collect(java.util.stream.Collectors.toSet());
    assertFalse(names.contains("connectTimeoutMs"), "proxy must NOT carry a timeout field (Plan P2)");
    assertFalse(names.contains("withEnabled"));
  }

  // --- toString must never leak secrets (passwordRef est une ref, pas un secret, mais
  //     le record n'a pas de password en clair — vérif du contrat) ---

  @Test
  void recordHasNoPasswordField() {
    // Le record ne porte QUE des refs — aucun champ mot de passe en clair ne doit exister.
    // Vérification structurelle : les composants du record.
    assertEquals(9, NetworkProxyConfig.class.getRecordComponents().length);
    java.util.Set<String> names = java.util.Arrays.stream(NetworkProxyConfig.class.getRecordComponents())
        .map(java.lang.reflect.RecordComponent::getName)
        .collect(java.util.stream.Collectors.toSet());
    assertFalse(names.contains("password"));
    assertTrue(names.contains("passwordRef"));
  }
}
