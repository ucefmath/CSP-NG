package com.bowman.cspng.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConfigValidator relay (ADR 0002) — pas d'activation inconnue, refs, applied<=desired")
class RelayValidatorTest {

  private static ConfigSnapshot base(List<RelayNodeConfig> nodes, List<RelayCacheConfig> caches,
                                     List<ListenerCacheLinkConfig> links) {
    ProfileConfig p = new ProfileConfig("default", true, false, false, 0, 0, null, null,
        5000L, null, false, 0, null, null, null, null, 1L);
    ProfileListenerConfig l = new ProfileListenerConfig(7L, "default", "cccam", 12000, "0.0.0.0",
        true, null, null, null, 1L, "manual", true, 0, 512, 32, 5, null, 60);
    return baseWithPeers(nodes, caches, links, List.of());
  }

  private static ConfigSnapshot baseWithPeers(List<RelayNodeConfig> nodes, List<RelayCacheConfig> caches,
                                              List<ListenerCacheLinkConfig> links,
                                              List<RelayUdpPeerConfig> peers) {
    ProfileConfig p = new ProfileConfig("default", true, false, false, 0, 0, null, null,
        5000L, null, false, 0, null, null, null, null, 1L);
    ProfileListenerConfig l = new ProfileListenerConfig(7L, "default", "cccam", 12000, "0.0.0.0",
        true, null, null, null, 1L, "manual", true, 0, 512, 32, 5, null, 60);
    return new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
        List.of(p), List.of(l), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(),
        List.of(), CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(),
        List.of(), ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), List.of(),
        nodes, caches, links, List.of(), List.of(), peers, System.currentTimeMillis());
  }

  @Test
  @DisplayName("Vide => aucune erreur (comportement legacy preserve)")
  void emptyRelayValid() {
    assertTrue(ConfigValidator.validate(base(List.of(), List.of(), List.of())).isEmpty());
  }

  @Test
  @DisplayName("Node enabled sans authorized => erreur")
  void enabledWithoutAuthorizedRejected() {
    var n = new RelayNodeConfig("peer-a", null, true, false, false, 1L, null, 1L);
    var errs = ConfigValidator.validate(base(List.of(n), List.of(), List.of()));
    assertTrue(errs.stream().anyMatch(e -> e.contains("peer-a") && e.contains("authorized")));
  }

  @Test
  @DisplayName("Cache node vers node inconnu => erreur")
  void cacheUnknownNodeRejected() {
    var c = new RelayCacheConfig("n1", "node", "ghost", true, 8000L, 10000, 512, 1L);
    var errs = ConfigValidator.validate(base(List.of(), List.of(c), List.of()));
    assertTrue(errs.stream().anyMatch(e -> e.contains("n1") && e.contains("unknown node")));
  }

  @Test
  @DisplayName("Consultation UDP autorisee via lien explicite (transport cable)")
  void udpConsultAllowedWithLink() {
    var c = new RelayCacheConfig("u1", "udp", null, true, 8000L, 10000, 512, 1L);
    var link = new ListenerCacheLinkConfig(7L, "u1", true, false, true, true, 1L);
    var errs = ConfigValidator.validate(base(List.of(), List.of(c), List.of(link)));
    assertTrue(errs.stream().noneMatch(e -> e.contains("must not consult udp")));
  }

  @Test
  @DisplayName("Pair UDP sur cache non-udp => erreur")
  void udpPeerOnNonUdpRejected() {
    var c = new RelayCacheConfig("c1", "local", null, true, 8000L, 10000, 512, 1L);
    var snap = baseWithPeers(List.of(), List.of(c), List.of(),
        List.of(new RelayUdpPeerConfig(1L, "c1", "127.0.0.1", 9000, true, 1L)));
    var errs = ConfigValidator.validate(snap);
    assertTrue(errs.stream().anyMatch(e -> e.contains("non-udp cache")));
  }

  @Test
  @DisplayName("Pair UDP sur cache inconnu => erreur")
  void udpPeerUnknownCacheRejected() {
    var snap = baseWithPeers(List.of(), List.of(), List.of(),
        List.of(new RelayUdpPeerConfig(1L, "ghost", "127.0.0.1", 9000, true, 1L)));
    var errs = ConfigValidator.validate(snap);
    assertTrue(errs.stream().anyMatch(e -> e.contains("unknown cache")));
  }

  @Test
  @DisplayName("localNodeId obligatoire si relais actif (FIX #6)")
  void localNodeRequired() {
    var n = new RelayNodeConfig("peer-a", null, true, true, false, 1L, null, 1L);
    var snap = base(List.of(n), List.of(), List.of());
    assertFalse(ConfigValidator.validateRelayRequiresLocalNode(snap, "local1").isEmpty() == false);
    assertEquals(1, ConfigValidator.validateRelayRequiresLocalNode(snap, "").size());
    assertTrue(ConfigValidator.validateRelayRequiresLocalNode(
        base(List.of(), List.of(), List.of()), "").isEmpty());
  }

  @Test
  @DisplayName("Lien vers listener/cache inconnu => erreur")
  void linkUnknownRefRejected() {
    var c = new RelayCacheConfig("c1", "local", null, true, 8000L, 10000, 512, 1L);
    var bad = new ListenerCacheLinkConfig(999L, "c1", true, false, false, true, 1L);
    var errs = ConfigValidator.validate(base(List.of(), List.of(c), List.of(bad)));
    assertTrue(errs.stream().anyMatch(e -> e.contains("999")));
  }
}
