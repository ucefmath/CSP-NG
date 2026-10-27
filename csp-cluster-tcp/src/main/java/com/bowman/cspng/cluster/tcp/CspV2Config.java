package com.bowman.cspng.cluster.tcp;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Optional;

/**
 * ConfigMapping CSPv2 — préfixe csp.cspv2.
 * Mapping kebab naturel, defaults conformes ADR 0001.
 * Absence peers => liste vide.
 */
@ConfigMapping(prefix = "csp.cspv2")
public interface CspV2Config {

  @WithDefault("false")
  boolean enabled();

  Optional<String> nodeId();

  @WithDefault("0.0.0.0")
  String listenHost();

  @WithDefault("9090")
  int listenPort();

  @WithDefault("1048576")
  int maxPayloadSize();

  @WithDefault("1000")
  int maxInflight();

  @WithDefault("100")
  int maxConnections();

  @WithDefault("10")
  int maxConnectionsPerNode();

  @WithDefault("5000")
  int requestsPerSecondPerNode();

  @WithDefault("100")
  int unknownStreamsPerMinute();

  @WithDefault("false")
  boolean exposeConnectorName();

  Timeouts timeouts();

  Reconnect reconnect();

  Dedup dedup();

  Tls tls();

  List<Peer> peers();

  interface Timeouts {
    @WithDefault("5000")
    long tlsHandshakeMs();

    @WithDefault("5000")
    long helloMs();

    @WithDefault("30000")
    long idleMs();

    @WithDefault("10000")
    long heartbeatMs();

    @WithDefault("5000")
    long drainMs();

    @WithDefault("10000")
    long shutdownDrainMs();

    @WithDefault("5000")
    long connectMs();

    @WithDefault("15000")
    long backpressurePauseMs();
  }

  interface Reconnect {
    @WithDefault("100")
    long initialMs();

    @WithDefault("30000")
    long maxMs();
  }

  interface Dedup {
    @WithDefault("10000")
    int maxEntries();

    @WithDefault("60000")
    long ttlMs();
  }

  interface Tls {
    @WithDefault("false")
    boolean allowTls12();

    Optional<String> certRef();

    Optional<String> keyRef();

    Optional<String> trustRef();
  }

  interface Peer {
    @WithDefault("true")
    boolean enabled();

    @WithDefault("")
    String nodeId();

    @WithDefault("")
    String host();

    @WithDefault("9090")
    int port();

    Optional<List<String>> profiles();
  }
}
