package com.bowman.cspng.cluster.tcp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Snapshot immuable des settings CSPv2 validés.
 * from(config) est fail-fast pur ; disabled tolère blanks sans résolution.
 */
public record CspV2Settings(
    boolean enabled,
    String nodeId,
    String listenHost,
    int listenPort,
    int maxPayloadSize,
    int maxInflight,
    int maxConnections,
    int maxConnectionsPerNode,
    int requestsPerSecondPerNode,
    int unknownStreamsPerMinute,
    long tlsHandshakeMs,
    long helloMs,
    long idleMs,
    long heartbeatMs,
    long drainMs,
    long shutdownDrainMs,
    long connectMs,
    long backpressurePauseMs,
    long reconnectInitialMs,
    long reconnectMaxMs,
    int dedupMaxEntries,
    long dedupTtlMs,
    boolean allowTls12,
    String certRef,
    String keyRef,
    String trustRef,
    boolean exposeConnectorName,
    List<CspV2PeerSettings> peers
) {

  private static final Pattern NODE_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,62}");

  public CspV2Settings {
    // defensive copy for peers — null normalisée List.of() (compat disabled)
    peers = peers == null ? List.of() : List.copyOf(peers);
    // trim copy
    if (nodeId != null) nodeId = nodeId.trim();
    if (listenHost != null) listenHost = listenHost.trim();
    if (certRef != null) certRef = certRef.trim();
    if (keyRef != null) keyRef = keyRef.trim();
    if (trustRef != null) trustRef = trustRef.trim();
    if (enabled) {
      validateEnabledFields(nodeId, listenHost, listenPort, maxPayloadSize, maxInflight, maxConnections,
          maxConnectionsPerNode, requestsPerSecondPerNode, unknownStreamsPerMinute,
          tlsHandshakeMs, helloMs, idleMs, heartbeatMs, drainMs, shutdownDrainMs, connectMs,
          backpressurePauseMs, reconnectInitialMs, reconnectMaxMs, dedupMaxEntries, dedupTtlMs,
          certRef, keyRef, trustRef, peers);
    }
  }

  public static CspV2Settings from(CspV2Config cfg) {
    if (cfg == null) throw new IllegalArgumentException("config must not be null");
    boolean enabled = cfg.enabled();
    String nodeId = cfg.nodeId().orElse("").trim();
    String listenHost = cfg.listenHost() == null ? "0.0.0.0" : cfg.listenHost().trim();
    int listenPort = cfg.listenPort();
    int maxPayloadSize = cfg.maxPayloadSize();
    int maxInflight = cfg.maxInflight();
    int maxConnections = cfg.maxConnections();
    int maxConnectionsPerNode = cfg.maxConnectionsPerNode();
    int requestsPerSecondPerNode = cfg.requestsPerSecondPerNode();
    int unknownStreamsPerMinute = cfg.unknownStreamsPerMinute();
    boolean exposeConnectorName = cfg.exposeConnectorName();

    long tlsHandshakeMs = cfg.timeouts().tlsHandshakeMs();
    long helloMs = cfg.timeouts().helloMs();
    long idleMs = cfg.timeouts().idleMs();
    long heartbeatMs = cfg.timeouts().heartbeatMs();
    long drainMs = cfg.timeouts().drainMs();
    long shutdownDrainMs = cfg.timeouts().shutdownDrainMs();
    long connectMs = cfg.timeouts().connectMs();
    long backpressurePauseMs = cfg.timeouts().backpressurePauseMs();

    long reconnectInitialMs = cfg.reconnect().initialMs();
    long reconnectMaxMs = cfg.reconnect().maxMs();

    int dedupMaxEntries = cfg.dedup().maxEntries();
    long dedupTtlMs = cfg.dedup().ttlMs();

    boolean allowTls12 = cfg.tls().allowTls12();
    String certRef = cfg.tls().certRef().orElse("").trim();
    String keyRef = cfg.tls().keyRef().orElse("").trim();
    String trustRef = cfg.tls().trustRef().orElse("").trim();

    List<CspV2PeerSettings> peers = new ArrayList<>();
    List<CspV2Config.Peer> rawPeers = cfg.peers();
    if (rawPeers != null) {
      for (CspV2Config.Peer p : rawPeers) {
        String pNodeId = p.nodeId() == null ? "" : p.nodeId().trim();
        String pHost = p.host() == null ? "" : p.host().trim();
        int pPort = p.port();
        boolean pEnabled = p.enabled();
        List<String> pProfiles = p.profiles().orElse(List.of());
        if (!enabled) {
          continue;
        }
        CspV2PeerSettings ps = new CspV2PeerSettings(pEnabled, pNodeId, pHost, pPort, pProfiles);
        peers.add(ps);
      }
    }

    if (!enabled) {
      return new CspV2Settings(false, nodeId, listenHost, listenPort,
          maxPayloadSize, maxInflight, maxConnections, maxConnectionsPerNode,
          requestsPerSecondPerNode, unknownStreamsPerMinute,
          tlsHandshakeMs, helloMs, idleMs, heartbeatMs, drainMs, shutdownDrainMs, connectMs,
          backpressurePauseMs, reconnectInitialMs, reconnectMaxMs,
          dedupMaxEntries, dedupTtlMs, allowTls12, certRef, keyRef, trustRef,
          exposeConnectorName, List.of());
    }

    // la validation complète (incluant uniqueness/self + listenHost) est déportée dans le compact constructor
    return new CspV2Settings(true, nodeId, listenHost, listenPort,
        maxPayloadSize, maxInflight, maxConnections, maxConnectionsPerNode,
        requestsPerSecondPerNode, unknownStreamsPerMinute,
        tlsHandshakeMs, helloMs, idleMs, heartbeatMs, drainMs, shutdownDrainMs, connectMs,
        backpressurePauseMs, reconnectInitialMs, reconnectMaxMs,
        dedupMaxEntries, dedupTtlMs, allowTls12, certRef, keyRef, trustRef,
        exposeConnectorName, List.copyOf(peers));
  }

  private static void validateEnabledFields(String nodeId, String listenHost, int listenPort, int maxPayloadSize,
      int maxInflight, int maxConnections, int maxConnectionsPerNode, int requestsPerSecondPerNode,
      int unknownStreamsPerMinute, long tlsHandshakeMs, long helloMs, long idleMs, long heartbeatMs,
      long drainMs, long shutdownDrainMs, long connectMs, long backpressurePauseMs,
      long reconnectInitialMs, long reconnectMaxMs, int dedupMaxEntries, long dedupTtlMs,
      String certRef, String keyRef, String trustRef, List<CspV2PeerSettings> peers) {

    if (nodeId == null || nodeId.isBlank() || !NODE_ID_PATTERN.matcher(nodeId).matches()) {
      throw new IllegalArgumentException("nodeId invalid");
    }
    validateListenHost(listenHost);
    if (listenPort < 1 || listenPort > 65535) {
      throw new IllegalArgumentException("listenPort 1..65535");
    }
    if (maxPayloadSize < 4096 || maxPayloadSize > 16777216) {
      throw new IllegalArgumentException("maxPayloadSize 4096..16777216");
    }
    if (maxInflight < 1 || maxInflight > 65535) {
      throw new IllegalArgumentException("maxInflight 1..65535");
    }
    if (maxConnections < 1 || maxConnections > 10000) {
      throw new IllegalArgumentException("maxConnections 1..10000");
    }
    if (maxConnectionsPerNode < 1 || maxConnectionsPerNode > 100) {
      throw new IllegalArgumentException("maxConnectionsPerNode 1..100");
    }
    if (requestsPerSecondPerNode < 1 || requestsPerSecondPerNode > 100000) {
      throw new IllegalArgumentException("requestsPerSecondPerNode 1..100000");
    }
    if (unknownStreamsPerMinute < 1 || unknownStreamsPerMinute > 1000) {
      throw new IllegalArgumentException("unknownStreamsPerMinute 1..1000");
    }
    if (tlsHandshakeMs < 1000 || tlsHandshakeMs > 60000) {
      throw new IllegalArgumentException("tlsHandshakeMs 1000..60000");
    }
    if (helloMs < 1000 || helloMs > 60000) {
      throw new IllegalArgumentException("helloMs 1000..60000");
    }
    if (idleMs < 5000 || idleMs > 300000) {
      throw new IllegalArgumentException("idleMs 5000..300000");
    }
    if (heartbeatMs < 1000 || heartbeatMs > 60000) {
      throw new IllegalArgumentException("heartbeatMs 1000..60000");
    }
    if (drainMs < 1000 || drainMs > 30000) {
      throw new IllegalArgumentException("drainMs 1000..30000");
    }
    if (shutdownDrainMs < 1000 || shutdownDrainMs > 60000) {
      throw new IllegalArgumentException("shutdownDrainMs 1000..60000");
    }
    if (connectMs < 1000 || connectMs > 30000) {
      throw new IllegalArgumentException("connectMs 1000..30000");
    }
    if (backpressurePauseMs < 1000 || backpressurePauseMs > 60000) {
      throw new IllegalArgumentException("backpressurePauseMs 1000..60000");
    }
    if (heartbeatMs * 2 >= idleMs) {
      throw new IllegalArgumentException("heartbeat must be < idle/2");
    }
    if (backpressurePauseMs >= idleMs) {
      throw new IllegalArgumentException("backpressurePause must be < idle");
    }
    if (reconnectInitialMs < 10 || reconnectInitialMs > 5000) {
      throw new IllegalArgumentException("reconnectInitialMs 10..5000");
    }
    if (reconnectMaxMs < 1000 || reconnectMaxMs > 300000) {
      throw new IllegalArgumentException("reconnectMaxMs 1000..300000");
    }
    if (reconnectInitialMs > reconnectMaxMs) {
      throw new IllegalArgumentException("reconnect initial must be <= max");
    }
    if (dedupMaxEntries < 100 || dedupMaxEntries > 1000000) {
      throw new IllegalArgumentException("dedupMaxEntries 100..1000000");
    }
    if (dedupTtlMs < 1000 || dedupTtlMs > 3600000) {
      throw new IllegalArgumentException("dedupTtlMs 1000..3600000");
    }
    validateTlsRef(certRef, "certRef");
    validateTlsRef(keyRef, "keyRef");
    validateTlsRef(trustRef, "trustRef");
    // uniqueness + self peer — désormais étanche via ctor
    Set<String> seen = new HashSet<>();
    for (CspV2PeerSettings ps : peers) {
      if (ps.nodeId().equals(nodeId)) {
        throw new IllegalArgumentException("peer nodeId must not equal local nodeId");
      }
      if (!seen.add(ps.nodeId())) {
        throw new IllegalArgumentException("duplicate peer nodeId");
      }
    }
  }

  static void validateListenHost(String host) {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("listenHost must not be blank");
    }
    String h = host.trim();
    // reject control/whitespace/URI/userinfo/path
    for (int i = 0; i < h.length(); i++) {
      char c = h.charAt(i);
      if (Character.isWhitespace(c) || Character.isISOControl(c)) {
        throw new IllegalArgumentException("listenHost must not contain whitespace/control");
      }
    }
    if (h.contains("://") || h.contains("@") || h.contains("/") || h.contains("\\")
        || h.contains("?") || h.contains("#")) {
      throw new IllegalArgumentException("listenHost must not contain URI parts");
    }
    if (h.equals("0.0.0.0") || h.equals("::")) {
      return;
    }
    // IPv4 literal
    if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
      String[] parts = h.split("\\.", -1);
      for (String p : parts) {
        int v = Integer.parseInt(p);
        if (v < 0 || v > 255) throw new IllegalArgumentException("listenHost invalid IPv4");
      }
      return;
    }
    // IPv6 literal — pas de résolution DNS, helper testable sans InetAddress
    if (h.contains(":")) {
      validateIPv6(h);
      return;
    }
    // DNS hostname via peer validator (reused, strict but without IP rejection since handled)
    // reuse CspV2PeerSettings validation for DNS (it rejects IP, but listenHost already handled IP above)
    CspV2PeerSettings.validateDnsHostname(h);
  }

  static void validateIPv6(String h) {
    if (h.equals("::")) {
      return;
    }
    // uniquement des ':' (ex :::) invalide hors "::"
    if (h.matches(":+")) {
      throw new IllegalArgumentException("listenHost invalid IPv6");
    }
    if (!h.matches("[0-9a-fA-F:]+")) {
      throw new IllegalArgumentException("listenHost invalid IPv6");
    }
    // groupe vide illégal hors compression unique -> ":::" interdit
    if (h.contains(":::")) {
      throw new IllegalArgumentException("listenHost invalid IPv6");
    }
    // commence par ':' sans "::" -> groupe vide illégal (ex :abc)
    if (h.startsWith(":") && !h.startsWith("::")) {
      throw new IllegalArgumentException("listenHost invalid IPv6");
    }
    // finit par ':' sans "::" -> groupe vide
    if (h.endsWith(":") && !h.endsWith("::")) {
      throw new IllegalArgumentException("listenHost invalid IPv6");
    }
    if (h.contains("::")) {
      int first = h.indexOf("::");
      int second = h.indexOf("::", first + 2);
      if (second != -1) {
        throw new IllegalArgumentException("listenHost invalid IPv6");
      }
      String left = h.substring(0, first);
      String right = h.substring(first + 2);
      // groupes vides hors compression déjà filtrés par ":::" mais vérifier left/right
      int leftGroups = 0;
      int rightGroups = 0;
      if (!left.isEmpty()) {
        String[] lp = left.split(":", -1);
        for (String p : lp) {
          if (p.isEmpty()) {
            throw new IllegalArgumentException("listenHost invalid IPv6");
          }
          if (!p.matches("[0-9a-fA-F]{1,4}")) {
            throw new IllegalArgumentException("listenHost invalid IPv6");
          }
        }
        leftGroups = lp.length;
      }
      if (!right.isEmpty()) {
        String[] rp = right.split(":", -1);
        for (String p : rp) {
          if (p.isEmpty()) {
            throw new IllegalArgumentException("listenHost invalid IPv6");
          }
          if (!p.matches("[0-9a-fA-F]{1,4}")) {
            throw new IllegalArgumentException("listenHost invalid IPv6");
          }
        }
        rightGroups = rp.length;
      }
      int total = leftGroups + rightGroups;
      if (total > 7) {
        throw new IllegalArgumentException("listenHost invalid IPv6");
      }
      // "::" doit compresser au moins 1 groupe, donc total doit être <=7 déjà vérifié, et si left+right ==8 c'est aussi invalide car pas de place pour compression
      if (total == 8) {
        throw new IllegalArgumentException("listenHost invalid IPv6");
      }
      return;
    } else {
      // sans compression -> exactement 8 groupes
      String[] parts = h.split(":", -1);
      if (parts.length != 8) {
        throw new IllegalArgumentException("listenHost invalid IPv6");
      }
      for (String p : parts) {
        if (p.isEmpty() || !p.matches("[0-9a-fA-F]{1,4}")) {
          throw new IllegalArgumentException("listenHost invalid IPv6");
        }
      }
    }
  }

  private static void validateTlsRef(String ref, String name) {
    if (ref == null || ref.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank (env: or file:)");
    }
    String r = ref.trim();
    if (r.startsWith("vault:")) {
      throw new IllegalArgumentException(name + " vault: unsupported");
    }
    String after;
    if (r.startsWith("env:")) {
      after = r.substring(4);
    } else if (r.startsWith("file:")) {
      after = r.substring(5);
    } else {
      throw new IllegalArgumentException(name + " must start with env: or file:");
    }
    if (after.isBlank()) {
      throw new IllegalArgumentException(name + " value must not be blank");
    }
  }

  /**
   * Tie-break helper : local initiates iff local nodeId < peer nodeId ASCII
   * ET dialEnabled=true (accept-only ne declenche aucun dial).
   * Refuse si settings disabled ou peer disabled (fail-fast).
   */
  public boolean shouldInitiate(CspV2PeerSettings peer) {
    if (peer == null) throw new IllegalArgumentException("peer must not be null");
    if (!enabled) throw new IllegalStateException("settings disabled");
    if (!peer.enabled()) throw new IllegalArgumentException("peer disabled");
    if (!peer.dialEnabled()) return false;
    return this.nodeId.compareTo(peer.nodeId()) < 0;
  }
}
