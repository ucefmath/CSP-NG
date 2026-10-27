package com.bowman.cspng.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de validation proxy du {@link ConfigValidator} (Plan P2) :
 * doublons de noms, host URI-like, port hors bornes, auth non appariée,
 * référence connecteur → proxy inconnu rejetée, proxy désactivé référencé ACCEPTÉ
 * (kill switch).
 */
class ConfigValidatorProxyTest {

  /** Construit un snapshot minimal avec proxies donnés (le reste vide/défaut). */
  private ConfigSnapshot snapWithProxies(List<NetworkProxyConfig> proxies) {
    ConfigSnapshot base = ConfigSnapshot.empty();
    return new ConfigSnapshot(
        base.root(), base.logging(), base.caProfilesGlobal(),
        base.profiles(), base.profileListeners(), base.profileCards(),
        base.connectionManager(), base.serviceMap(), base.connectors(), base.connectorServices(),
        base.cache(), base.clusterUdp(), base.webui(),
        base.plugins(), base.sidCacheLinks(), base.extendedNewcamd(),
        base.connectorCaidProfiles(), base.profileProviderIdents(),
        proxies, System.currentTimeMillis());
  }

  /** Construit un snapshot avec proxies et un connecteur. */
  private ConfigSnapshot snapWithConnectorAndProxies(ConnectorConfig connector, List<NetworkProxyConfig> proxies) {
    ConfigSnapshot base = ConfigSnapshot.empty();
    return new ConfigSnapshot(
        base.root(), base.logging(), base.caProfilesGlobal(),
        base.profiles(), base.profileListeners(), base.profileCards(),
        base.connectionManager(), base.serviceMap(), List.of(connector), base.connectorServices(),
        base.cache(), base.clusterUdp(), base.webui(),
        base.plugins(), base.sidCacheLinks(), base.extendedNewcamd(),
        base.connectorCaidProfiles(), base.profileProviderIdents(),
        proxies, System.currentTimeMillis());
  }

  private static NetworkProxyConfig proxy(String name, boolean enabled, String host, int port,
                                          String username, String passwordRef) {
    return new NetworkProxyConfig(name, enabled, "socks5", host, port, username, passwordRef,
        null, 1L);
  }

  private static ConnectorConfig radegastConnector(String name, String proxyName) {
    return new ConnectorConfig(name, "prof", "radegast", true, false,
        "up.example.com", 15000, null, null, null,
        5000, 5000, null, null, null, null, null, null,
        false, null, null, null, null, null, null, null, 50, 0, 1L, proxyName);
  }

  @Test
  void validProxiesPass() {
    ConfigSnapshot s = snapWithProxies(List.of(
        proxy("px1", true, "172.20.0.1", 1080, null, null),
        proxy("px2", true, "proxy.lan", 1080, "u", "env:PW")));
    List<String> errors = ConfigValidator.validate(s);
    Set<String> proxyErrors = errors.stream().filter(e -> e.toLowerCase().contains("proxy")).collect(Collectors.toSet());
    assertTrue(proxyErrors.isEmpty(), "no proxy errors expected, got: " + proxyErrors);
  }

  @Test
  void duplicateProxyNameRejected() {
    ConfigSnapshot s = snapWithProxies(List.of(
        proxy("dup", true, "h1", 1080, null, null),
        proxy("dup", true, "h2", 1081, null, null)));
    List<String> errors = ConfigValidator.validate(s);
    assertTrue(errors.stream().anyMatch(e -> e.contains("Duplicate proxy name: dup")),
        "expected duplicate proxy error, got: " + errors);
  }

  @Test
  void uriLikeHostRejected() {
    // Le constructeur NetworkProxyConfig REJETTE host avec '://' (première couche) :
    // impossible de construire ce record — le validateur est la seconde couche défensive
    // pour des rows DB corrompues (couvert par les tests PG).
    assertThrows(IllegalArgumentException.class,
        () -> proxy("p1", true, "socks5://evil", 1080, null, null));
    assertThrows(IllegalArgumentException.class,
        () -> proxy("p1", true, "user@host", 1080, null, null));
    assertThrows(IllegalArgumentException.class,
        () -> proxy("p1", true, "ho st", 1080, null, null));
    // Snapshot avec proxies valides : aucune erreur validateur
    ConfigSnapshot s = snapWithProxies(List.of(proxy("p1", true, "172.20.0.1", 1080, null, null)));
    List<String> errors = ConfigValidator.validate(s);
    assertTrue(errors.stream().noneMatch(e -> e.contains("p1")),
        "valid proxy must produce no errors, got: " + errors);
  }

  @Test
  void controlCharHostRejectedByRecord() {
    assertThrows(IllegalArgumentException.class,
        () -> proxy("p1", true, "h\u0007ost", 1080, null, null));
  }

  @Test
  void unknownProxyReferenceRejected() {
    ConnectorConfig c = radegastConnector("c1", "ghost-proxy");
    ConfigSnapshot s = snapWithConnectorAndProxies(c, List.of(proxy("real-proxy", true, "h", 1080, null, null)));
    List<String> errors = ConfigValidator.validate(s);
    assertTrue(errors.stream().anyMatch(e ->
            e.contains("Connector 'c1'") && e.contains("unknown proxy: ghost-proxy")),
        "expected unknown proxy reference error, got: " + errors);
  }

  @Test
  void disabledProxyReferenceAccepted() {
    // Kill switch : référencer un proxy désactivé est INTENTIONNEL et accepté.
    // Le runtime fail-closed coupe le connecteur ; la config reste valide.
    ConnectorConfig c = radegastConnector("c1", "off-proxy");
    ConfigSnapshot s = snapWithConnectorAndProxies(c, List.of(proxy("off-proxy", false, "h", 1080, null, null)));
    List<String> errors = ConfigValidator.validate(s);
    assertFalse(errors.stream().anyMatch(e -> e.contains("off-proxy")),
        "disabled proxy reference must be ACCEPTED, got errors: " + errors);
  }

  @Test
  void nullProxyNameConnectorAccepted() {
    ConnectorConfig c = radegastConnector("c1", null); // connexion directe
    ConfigSnapshot s = snapWithConnectorAndProxies(c, List.of(proxy("px", true, "h", 1080, null, null)));
    List<String> errors = ConfigValidator.validate(s);
    assertFalse(errors.stream().anyMatch(e -> e.contains("c1") && e.contains("proxy")),
        "null proxyName must be accepted, got: " + errors);
  }

  @Test
  void emptyProxyListAccepted() {
    ConfigSnapshot s = snapWithProxies(List.of());
    List<String> errors = ConfigValidator.validate(s);
    assertFalse(errors.stream().anyMatch(e -> e.toLowerCase().contains("proxy")));
  }

  @Test
  void proxyByNameMap() {
    ConfigSnapshot s = snapWithProxies(List.of(
        proxy("a", true, "h", 1, null, null),
        proxy("b", false, "h", 2, null, null)));
    assertEquals(2, s.proxyByName().size());
    assertTrue(s.proxyByName().get("a").enabled());
    assertFalse(s.proxyByName().get("b").enabled());
    assertNull(s.proxyByName().get("missing"));
  }
}
