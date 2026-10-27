package com.bowman.cspng.cluster.tcp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

public class CspV2SettingsTest {

  // --- helpers for fake mapping ---
  static CspV2Config.Peer peer(String nodeId, String host, int port, boolean enabled, List<String> profiles) {
    return new CspV2Config.Peer() {
      public boolean enabled() { return enabled; }
      public String nodeId() { return nodeId; }
      public String host() { return host; }
      public int port() { return port; }
      public Optional<List<String>> profiles() { return profiles == null ? Optional.empty() : Optional.of(profiles); }
    };
  }

  static CspV2Config config(boolean enabled, Optional<String> nodeId, List<CspV2Config.Peer> peers,
      Optional<String> certRef, Optional<String> keyRef, Optional<String> trustRef,
      long heartbeatMs, long idleMs, long bpPauseMs,
      long reconInitial, long reconMax,
      int maxPayload, int maxInflight, int maxConn, int maxConnPerNode,
      int rps, int unknown, int dedupMax, long dedupTtl,
      boolean allowTls12, int listenPort, String listenHost,
      long tlsHs, long hello, long drain, long shutdownDrain, long connect) {
    return new CspV2Config() {
      public boolean enabled() { return enabled; }
      public Optional<String> nodeId() { return nodeId; }
      public String listenHost() { return listenHost; }
      public int listenPort() { return listenPort; }
      public int maxPayloadSize() { return maxPayload; }
      public int maxInflight() { return maxInflight; }
      public int maxConnections() { return maxConn; }
      public int maxConnectionsPerNode() { return maxConnPerNode; }
      public int requestsPerSecondPerNode() { return rps; }
      public int unknownStreamsPerMinute() { return unknown; }
      public boolean exposeConnectorName() { return false; }
      public Timeouts timeouts() {
        return new Timeouts() {
          public long tlsHandshakeMs() { return tlsHs; }
          public long helloMs() { return hello; }
          public long idleMs() { return idleMs; }
          public long heartbeatMs() { return heartbeatMs; }
          public long drainMs() { return drain; }
          public long shutdownDrainMs() { return shutdownDrain; }
          public long connectMs() { return connect; }
          public long backpressurePauseMs() { return bpPauseMs; }
        };
      }
      public Reconnect reconnect() {
        return new Reconnect() {
          public long initialMs() { return reconInitial; }
          public long maxMs() { return reconMax; }
        };
      }
      public Dedup dedup() {
        return new Dedup() {
          public int maxEntries() { return dedupMax; }
          public long ttlMs() { return dedupTtl; }
        };
      }
      public Tls tls() {
        return new Tls() {
          public boolean allowTls12() { return allowTls12; }
          public Optional<String> certRef() { return certRef; }
          public Optional<String> keyRef() { return keyRef; }
          public Optional<String> trustRef() { return trustRef; }
        };
      }
      public List<Peer> peers() { return peers == null ? List.of() : peers; }
    };
  }

  static CspV2Config validEnabled(String localNode, List<CspV2Config.Peer> peers) {
    return config(true, Optional.of(localNode), peers,
        Optional.of("env:CSP_TLS_CERT"), Optional.of("env:CSP_TLS_KEY"), Optional.of("env:CSP_TLS_TRUST"),
        10000, 30000, 15000,
        100, 30000,
        1048576, 1000, 100, 10, 5000, 100, 10000, 60000,
        false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000);
  }

  @Test
  void disabledToleratesBlanks() {
    CspV2Config cfg = config(false, Optional.empty(), List.of(),
        Optional.empty(), Optional.empty(), Optional.empty(),
        10000, 30000, 15000, 100, 30000,
        1048576, 1000, 100, 10, 5000, 100, 10000, 60000,
        false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000);
    assertDoesNotThrow(() -> CspV2Settings.from(cfg));
    CspV2Settings s = CspV2Settings.from(cfg);
    assertFalse(s.enabled());
    assertTrue(s.peers().isEmpty());
  }

  @Test
  void enabledRequiresNodeRegex() {
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(validEnabled("", List.of())));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(config(true, Optional.empty(), List.of(), Optional.of("env:C"), Optional.of("env:K"), Optional.of("env:T"), 10000, 30000, 15000, 100, 30000, 1048576, 1000, 100, 10, 5000, 100, 10000, 60000, false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(validEnabled("INVALID-UPPER", List.of())));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(validEnabled("a".repeat(64), List.of())));
    assertDoesNotThrow(() -> CspV2Settings.from(validEnabled("node-a1", List.of())));
    assertDoesNotThrow(() -> CspV2Settings.from(validEnabled("a", List.of())));
    assertDoesNotThrow(() -> CspV2Settings.from(validEnabled("a0._-b", List.of())));
  }

  @Test
  void enabledRequiresTlsRefs() {
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.empty(), Optional.of("env:K"), Optional.of("env:T"),
            10000, 30000, 15000, 100, 30000, 1048576, 1000, 100, 10, 5000, 100, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("vault:secret"), Optional.of("env:K"), Optional.of("env:T"),
            10000, 30000, 15000, 100, 30000, 1048576, 1000, 100, 10, 5000, 100, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("file:"), Optional.of("env:K"), Optional.of("env:T"),
            10000, 30000, 15000, 100, 30000, 1048576, 1000, 100, 10, 5000, 100, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("http:/tmp/cert"), Optional.of("env:K"), Optional.of("env:T"),
            10000, 30000, 15000, 100, 30000, 1048576, 1000, 100, 10, 5000, 100, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
    assertDoesNotThrow(() -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("file:/run/secrets/cert"), Optional.of("file:/run/secrets/key"), Optional.of("env:TRUST"),
            10000, 30000, 15000, 100, 30000, 1048576, 1000, 100, 10, 5000, 100, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
  }

  @Test
  void boundsValidation() {
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("env:C"), Optional.of("env:K"), Optional.of("env:T"),
            10000, 30000, 15000, 100, 30000, 1048576, 1000, 100, 10, 5000, 100, 10000, 60000,
            false, 0, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("env:C"), Optional.of("env:K"), Optional.of("env:T"),
            10000, 30000, 15000, 100, 30000, 4095, 1000, 100, 10, 5000, 100, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("env:C"), Optional.of("env:K"), Optional.of("env:T"),
            10000, 30000, 15000, 100, 30000, 1048576, 0, 100, 10, 5000, 100, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("env:C"), Optional.of("env:K"), Optional.of("env:T"),
            10000, 30000, 15000, 100, 30000, 1048576, 1000, 0, 10, 5000, 100, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("env:C"), Optional.of("env:K"), Optional.of("env:T"),
            10000, 30000, 15000, 100, 30000, 1048576, 1000, 100, 0, 5000, 100, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("env:C"), Optional.of("env:K"), Optional.of("env:T"),
            10000, 30000, 15000, 100, 30000, 1048576, 1000, 100, 10, 0, 100, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("env:C"), Optional.of("env:K"), Optional.of("env:T"),
            10000, 30000, 15000, 100, 30000, 1048576, 1000, 100, 10, 5000, 0, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("env:C"), Optional.of("env:K"), Optional.of("env:T"),
            10000, 30000, 15000, 100, 30000, 1048576, 1000, 100, 10, 5000, 100, 99, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
  }

  @Test
  void heartbeatIdleValidation() {
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("env:C"), Optional.of("env:K"), Optional.of("env:T"),
            15000, 30000, 15000, 100, 30000, 1048576, 1000, 100, 10, 5000, 100, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("env:C"), Optional.of("env:K"), Optional.of("env:T"),
            15000, 30000, 14000, 100, 30000, 1048576, 1000, 100, 10, 5000, 100, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
    assertDoesNotThrow(() -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("env:C"), Optional.of("env:K"), Optional.of("env:T"),
            10000, 30000, 15000, 100, 30000, 1048576, 1000, 100, 10, 5000, 100, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
  }

  @Test
  void backpressurePauseValidation() {
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("env:C"), Optional.of("env:K"), Optional.of("env:T"),
            10000, 30000, 30000, 100, 30000, 1048576, 1000, 100, 10, 5000, 100, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("env:C"), Optional.of("env:K"), Optional.of("env:T"),
            10000, 30000, 35000, 100, 30000, 1048576, 1000, 100, 10, 5000, 100, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
  }

  @Test
  void reconnectInitialMaxValidation() {
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("env:C"), Optional.of("env:K"), Optional.of("env:T"),
            10000, 30000, 15000, 5000, 1000, 1048576, 1000, 100, 10, 5000, 100, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(
        config(true, Optional.of("node-a"), List.of(), Optional.of("env:C"), Optional.of("env:K"), Optional.of("env:T"),
            10000, 30000, 15000, 5, 30000, 1048576, 1000, 100, 10, 5000, 100, 10000, 60000,
            false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000)));
  }

  @Test
  void peersDuplicateAndSelf() {
    CspV2Config.Peer p1 = peer("node-b", "node-b.example.com", 9090, true, List.of("profile-a"));
    CspV2Config.Peer p2 = peer("node-b", "other.example.com", 9090, true, List.of());
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(validEnabled("node-a", List.of(p1, p2))));
    CspV2Config.Peer self = peer("node-a", "node-a.example.com", 9090, true, List.of());
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(validEnabled("node-a", List.of(self))));
    // also via direct constructor étanche
    var dup1 = new CspV2PeerSettings(true, "node-b", "node-b.example.com", 9090, List.of());
    var dup2 = new CspV2PeerSettings(true, "node-b", "other.example.com", 9090, List.of());
    assertThrows(IllegalArgumentException.class, () -> new CspV2Settings(true, "node-a", "0.0.0.0", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of(dup1, dup2)));
    var selfPs = new CspV2PeerSettings(true, "node-a", "node-a.example.com", 9090, List.of());
    assertThrows(IllegalArgumentException.class, () -> new CspV2Settings(true, "node-a", "0.0.0.0", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of(selfPs)));
  }

  @Test
  void peersDnsAndIpValidation() {
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(validEnabled("node-a",
        List.of(peer("node-b", "192.168.1.10", 9090, true, List.of())))));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(validEnabled("node-a",
        List.of(peer("node-b", "10.0.0.1", 9090, true, List.of())))));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(validEnabled("node-a",
        List.of(peer("node-b", "2001:db8::1", 9090, true, List.of())))));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(validEnabled("node-a",
        List.of(peer("node-b", "https://node-b.example.com", 9090, true, List.of())))));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(validEnabled("node-a",
        List.of(peer("node-b", "user@node-b.example.com", 9090, true, List.of())))));
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.from(validEnabled("node-a",
        List.of(peer("node-b", "node-b.example.com/path", 9090, true, List.of())))));
    assertDoesNotThrow(() -> CspV2Settings.from(validEnabled("node-a",
        List.of(peer("node-b", "node-b.cluster.internal", 9090, true, List.of("p1"))))));
    assertDoesNotThrow(() -> CspV2Settings.from(validEnabled("node-a",
        List.of(peer("node-b", "example.com", 9090, true, List.of())))));
  }

  @Test
  void listenHostValidation() {
    // wildcard allowed
    assertDoesNotThrow(() -> new CspV2Settings(true, "node-a", "0.0.0.0", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    assertDoesNotThrow(() -> new CspV2Settings(true, "node-a", "::", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    assertDoesNotThrow(() -> new CspV2Settings(true, "node-a", "127.0.0.1", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    assertDoesNotThrow(() -> new CspV2Settings(true, "node-a", "my-host.example.com", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    // illegal
    assertThrows(IllegalArgumentException.class, () -> new CspV2Settings(true, "node-a", "", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new CspV2Settings(true, "node-a", "host with space", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new CspV2Settings(true, "node-a", "https://example.com", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new CspV2Settings(true, "node-a", "user@example.com", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new CspV2Settings(true, "node-a", "host/path", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
  }

  @Test
  void listenHostIPv6Validation() {
    // valides
    assertDoesNotThrow(() -> new CspV2Settings(true, "node-a", "::", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    assertDoesNotThrow(() -> new CspV2Settings(true, "node-a", "::1", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    assertDoesNotThrow(() -> new CspV2Settings(true, "node-a", "2001:db8::1", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    assertDoesNotThrow(() -> new CspV2Settings(true, "node-a", "2001:db8:0:0:0:0:2:1", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    // invalides ciblés
    assertThrows(IllegalArgumentException.class, () -> new CspV2Settings(true, "node-a", ":abc", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new CspV2Settings(true, "node-a", "::: ", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new CspV2Settings(true, "node-a", ":::".trim(), 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new CspV2Settings(true, "node-a", "2001::db8::1", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new CspV2Settings(true, "node-a", "11111:0:0:0:0:0:0:1", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new CspV2Settings(true, "node-a", "1:2:3:4:5:6:7:8:9", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new CspV2Settings(true, "node-a", "1:2:3:4:5:6:7", 9090, 1048576, 1000, 100, 10, 5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000, 100, 30000, 10000, 60000, false, "env:C", "env:K", "env:T", false, List.of()));
    // helper direct
    assertThrows(IllegalArgumentException.class, () -> CspV2Settings.validateIPv6(":::"));
    assertDoesNotThrow(() -> CspV2Settings.validateIPv6("::1"));
  }

  @Test
  void shouldInitiateTieBreak() {
    CspV2Settings s = CspV2Settings.from(validEnabled("node-a", List.of()));
    CspV2PeerSettings peerB = new CspV2PeerSettings(true, "node-b", "node-b.example.com", 9090, List.of());
    CspV2PeerSettings peer0 = new CspV2PeerSettings(true, "node-0", "node-0.example.com", 9090, List.of());
    assertTrue(s.shouldInitiate(peerB));
    assertFalse(s.shouldInitiate(peer0));
    CspV2Settings s2 = CspV2Settings.from(validEnabled("node-m", List.of()));
    CspV2PeerSettings peerN = new CspV2PeerSettings(true, "node-n", "node-n.example.com", 9090, List.of());
    assertTrue(s2.shouldInitiate(peerN));
    assertFalse(CspV2Settings.from(validEnabled("node-z", List.of())).shouldInitiate(
        new CspV2PeerSettings(true, "node-a", "node-a.example.com", 9090, List.of())));
  }

  @Test
  void shouldInitiateDisabledChecks() {
    CspV2Settings enabled = CspV2Settings.from(validEnabled("node-a", List.of()));
    CspV2PeerSettings disabledPeer = new CspV2PeerSettings(false, "node-b", "node-b.example.com", 9090, List.of());
    assertThrows(IllegalArgumentException.class, () -> enabled.shouldInitiate(disabledPeer));
    assertThrows(IllegalArgumentException.class, () -> enabled.shouldInitiate(null));
    CspV2Settings disabled = CspV2Settings.from(config(false, Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), 10000, 30000, 15000, 100, 30000, 1048576, 1000, 100, 10, 5000, 100, 10000, 60000, false, 9090, "0.0.0.0", 5000, 5000, 5000, 10000, 5000));
    CspV2PeerSettings peer = new CspV2PeerSettings(true, "node-b", "node-b.example.com", 9090, List.of());
    assertThrows(IllegalStateException.class, () -> disabled.shouldInitiate(peer));
  }

  @Test
  void profilesValidation() {
    assertThrows(IllegalArgumentException.class, () -> new CspV2PeerSettings(true, "node-b", "node-b.example.com", 9090, List.of("")));
    assertThrows(IllegalArgumentException.class, () -> new CspV2PeerSettings(true, "node-b", "node-b.example.com", 9090, List.of("  ")));
    assertThrows(IllegalArgumentException.class, () -> new CspV2PeerSettings(true, "node-b", "node-b.example.com", 9090, List.of("a".repeat(65))));
    assertDoesNotThrow(() -> new CspV2PeerSettings(true, "node-b", "node-b.example.com", 9090, List.of()));
    List<String> mutable = new java.util.ArrayList<>(List.of("p1"));
    CspV2PeerSettings ps = new CspV2PeerSettings(true, "node-b", "node-b.example.com", 9090, mutable);
    mutable.add("p2");
    assertEquals(1, ps.profiles().size());
    assertThrows(UnsupportedOperationException.class, () -> ps.profiles().add("x"));
  }

  @Test
  void peerNodeIdValidation() {
    assertThrows(IllegalArgumentException.class, () -> new CspV2PeerSettings(true, "", "example.com", 9090, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new CspV2PeerSettings(true, "BAD", "example.com", 9090, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new CspV2PeerSettings(true, "node-b", "", 9090, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new CspV2PeerSettings(true, "node-b", "example.com", 0, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new CspV2PeerSettings(true, "node-b", "example.com", 70000, List.of()));
  }

  @Test
  void smallRyeMappingEmptyDisabledShouldSucceed() {
    Map<String,String> map = new HashMap<>();
    // no csp.cspv2 properties — defaults disabled
    SmallRyeConfig cfg = new SmallRyeConfigBuilder()
        .withMapping(CspV2Config.class)
        .withSources(new PropertiesConfigSource(map, "test", 100))
        .build();
    CspV2Config c = cfg.getConfigMapping(CspV2Config.class);
    assertFalse(c.enabled());
    assertTrue(c.nodeId().isEmpty());
    assertTrue(c.tls().certRef().isEmpty());
    assertTrue(c.tls().keyRef().isEmpty());
    assertTrue(c.tls().trustRef().isEmpty());
    assertTrue(c.peers().isEmpty());
    CspV2Settings s = CspV2Settings.from(c);
    assertFalse(s.enabled());
  }

  @Test
  void smallRyeMappingEnabledWithPeersAndProfiles() {
    Map<String,String> map = new HashMap<>();
    map.put("csp.cspv2.enabled", "true");
    map.put("csp.cspv2.node-id", "node-a");
    map.put("csp.cspv2.tls.cert-ref", "env:CERT");
    map.put("csp.cspv2.tls.key-ref", "env:KEY");
    map.put("csp.cspv2.tls.trust-ref", "env:TRUST");
    map.put("csp.cspv2.peers[0].node-id", "node-b");
    map.put("csp.cspv2.peers[0].host", "node-b.example.com");
    map.put("csp.cspv2.peers[0].port", "9091");
    map.put("csp.cspv2.peers[0].profiles", "a,b");
    map.put("csp.cspv2.peers[0].enabled", "true");
    map.put("csp.cspv2.peers[1].node-id", "node-c");
    map.put("csp.cspv2.peers[1].host", "node-c.example.com");
    // port default, enabled default, profiles absent
    SmallRyeConfig cfg = new SmallRyeConfigBuilder()
        .withMapping(CspV2Config.class)
        .withSources(new PropertiesConfigSource(map, "test", 100))
        .build();
    CspV2Config c = cfg.getConfigMapping(CspV2Config.class);
    assertTrue(c.enabled());
    assertEquals("node-a", c.nodeId().orElseThrow());
    assertEquals(2, c.peers().size());
    assertEquals("node-b", c.peers().get(0).nodeId());
    assertEquals(Optional.of(List.of("a","b")), c.peers().get(0).profiles());
    assertTrue(c.peers().get(1).profiles().isEmpty());
    assertEquals(9090, c.peers().get(1).port()); // default
    CspV2Settings s = CspV2Settings.from(c);
    assertEquals(2, s.peers().size());
    assertEquals(List.of("a","b"), s.peers().get(0).profiles());
    assertTrue(s.peers().get(1).profiles().isEmpty());
    // nested defaults still mapped
    assertEquals(1048576, c.maxPayloadSize());
    assertEquals(10000, c.timeouts().heartbeatMs());
  }

  @Test
  void smallRyeProfilesAbsentIsOptionalEmpty() {
    Map<String,String> map = new HashMap<>();
    map.put("csp.cspv2.enabled", "true");
    map.put("csp.cspv2.node-id", "node-a");
    map.put("csp.cspv2.tls.cert-ref", "env:C");
    map.put("csp.cspv2.tls.key-ref", "env:K");
    map.put("csp.cspv2.tls.trust-ref", "env:T");
    map.put("csp.cspv2.peers[0].node-id", "node-b");
    map.put("csp.cspv2.peers[0].host", "node-b.example.com");
    // no profiles entry
    SmallRyeConfig cfg = new SmallRyeConfigBuilder()
        .withMapping(CspV2Config.class)
        .withSources(new PropertiesConfigSource(map, "test", 100))
        .build();
    CspV2Config c = cfg.getConfigMapping(CspV2Config.class);
    assertTrue(c.peers().get(0).profiles().isEmpty());
    CspV2Settings s = CspV2Settings.from(c);
    assertTrue(s.peers().get(0).profiles().isEmpty());
  }

  @Test
  void smallRyeGapsPeersHandled() {
    Map<String,String> map = new HashMap<>();
    map.put("csp.cspv2.enabled", "true");
    map.put("csp.cspv2.node-id", "node-a");
    map.put("csp.cspv2.tls.cert-ref", "env:C");
    map.put("csp.cspv2.tls.key-ref", "env:K");
    map.put("csp.cspv2.tls.trust-ref", "env:T");
    map.put("csp.cspv2.peers[0].node-id", "node-b");
    map.put("csp.cspv2.peers[0].host", "node-b.example.com");
    map.put("csp.cspv2.peers[2].node-id", "node-d");
    map.put("csp.cspv2.peers[2].host", "node-d.example.com");
    SmallRyeConfig cfg = new SmallRyeConfigBuilder()
        .withMapping(CspV2Config.class)
        .withSources(new PropertiesConfigSource(map, "test", 100))
        .build();
    CspV2Config c = cfg.getConfigMapping(CspV2Config.class);
    // SmallRye compacte gaps -> taille 2 ou lève? On vérifie que mapping ne plante pas et from réussit
    assertEquals(2, c.peers().size());
    CspV2Settings s = CspV2Settings.from(c);
    assertEquals(2, s.peers().size());
  }
}
