package com.bowman.cspng.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProxyConfigTest {

  @Test
  void recordImmutability() {
    ProxyConfig cfg = new ProxyConfig("test", List.of("default"));
    assertEquals("test", cfg.name());
    assertEquals(1, cfg.profiles().size());
  }

  @Test
  void fromSnapshot() {
    ConfigSnapshot snapshot = ConfigSnapshot.empty();
    ProxyConfig cfg = ProxyConfig.fromSnapshot(snapshot);
    assertEquals("CSP-NG", cfg.name());
    assertEquals(0, cfg.profiles().size());
  }

  @Test
  void fromSnapshotWithProfiles() {
    ProfileConfig profile = new ProfileConfig(
        "default", true, false, false, 0, 0, null, null, 5000L, null,
        false, 0, null, null, null, null, 1L);
    ConfigSnapshot snapshot = new ConfigSnapshot(
        RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
        List.of(profile), List.of(), List.of(),
        ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
        CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT,
        List.of(), List.of(), System.currentTimeMillis());
    ProxyConfig cfg = ProxyConfig.fromSnapshot(snapshot);
    assertEquals("CSP-NG", cfg.name());
    assertEquals(1, cfg.profiles().size());
    assertEquals("default", cfg.profiles().get(0));
  }
}
