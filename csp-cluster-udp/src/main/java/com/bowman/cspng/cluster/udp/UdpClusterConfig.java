package com.bowman.cspng.cluster.udp;

/**
 * Configuration du transport UDP cluster.
 *
 * @param bindPort         port d'écoute UDP
 * @param bindAddress      adresse d'écoute (défaut 0.0.0.0)
 * @param remoteHost       liste de pairs statiques (séparés par des virgules)
 * @param remotePort       liste de ports pour les pairs statiques (séparés par des virgules)
 * @param multicastGroup   adresse multicast (null si désactivé)
 * @param multicastPort    port multicast
 * @param multicastTtl     TTL multicast
 * @param autoAddPeers     activer l'auto-ajout des pairs
 * @param pingIntervalMs   intervalle ping (ms)
 * @param maxPacketsPerSec seuil rate-limiting par IP
 * @param blacklist        liste d'IP bloquées (séparées par des virgules)
 */
public record UdpClusterConfig(
    int bindPort,
    String bindAddress,
    String remoteHost,
    String remotePort,
    String multicastGroup,
    int multicastPort,
    int multicastTtl,
    boolean autoAddPeers,
    long pingIntervalMs,
    int maxPacketsPerSec,
    String blacklist) {

  public UdpClusterConfig {
    if (bindPort <= 0 || bindPort > 65535) throw new IllegalArgumentException("bindPort must be 1-65535");
    if (bindAddress == null || bindAddress.isEmpty()) throw new IllegalArgumentException("bindAddress required");
    if (pingIntervalMs <= 0) throw new IllegalArgumentException("pingIntervalMs must be > 0");
    if (maxPacketsPerSec <= 0) throw new IllegalArgumentException("maxPacketsPerSec must be > 0");
  }

  public static UdpClusterConfig defaults() {
    return new UdpClusterConfig(
        8099, "0.0.0.0", null, null, null, 8099, 1, true, 4000, 50, null);
  }

  public UdpClusterConfig withStaticPeer(String host, int port) {
    String newHost = this.remoteHost == null ? host : this.remoteHost + "," + host;
    String newPort = this.remotePort == null ? String.valueOf(port) : this.remotePort + "," + port;
    return new UdpClusterConfig(bindPort, bindAddress, newHost, newPort,
        multicastGroup, multicastPort, multicastTtl, autoAddPeers,
        pingIntervalMs, maxPacketsPerSec, blacklist);
  }

  public UdpClusterConfig withMulticastGroup(String group, int port, int ttl) {
    return new UdpClusterConfig(bindPort, bindAddress, remoteHost, remotePort,
        group, port, ttl, autoAddPeers, pingIntervalMs, maxPacketsPerSec, blacklist);
  }

  public UdpClusterConfig withAutoAddPeers(boolean enabled) {
    return new UdpClusterConfig(bindPort, bindAddress, remoteHost, remotePort,
        multicastGroup, multicastPort, multicastTtl, enabled,
        pingIntervalMs, maxPacketsPerSec, blacklist);
  }

  public UdpClusterConfig withMaxPacketsPerSec(int max) {
    return new UdpClusterConfig(bindPort, bindAddress, remoteHost, remotePort,
        multicastGroup, multicastPort, multicastTtl, autoAddPeers,
        pingIntervalMs, max, blacklist);
  }
}
