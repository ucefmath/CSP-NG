package com.bowman.cspng.config;

/**
 * Configuration du cluster UDP (protocole ClusteredCache).
 */
public record ClusterUdpConfig(
    boolean enabled,
    int bindPort,
    String bindAddress,
    String remoteHosts,
    String multicastGroup,
    int multicastPort,
    int multicastTtl,
    boolean autoAddPeers,
    String trackerUrlRef,
    long pingIntervalMs,
    long resendThrottleMs,
    int maxPacketsPerSec,
    String blacklist,
    long syncPeriodMs,
    long version
) {
    public static final ClusterUdpConfig DEFAULT = new ClusterUdpConfig(
        true, 8099, "0.0.0.0", null, null, 8099, 1,
        true, null, 4000L, 50L, 50, null, 100L, 1
    );

    public ClusterUdpConfig {
        if (bindPort < 1 || bindPort > 65535) throw new IllegalArgumentException("bindPort must be 1-65535");
        if (bindAddress == null || bindAddress.isBlank()) throw new IllegalArgumentException("bindAddress required");
        if (pingIntervalMs <= 0) throw new IllegalArgumentException("pingIntervalMs must be > 0");
        if (maxPacketsPerSec <= 0) throw new IllegalArgumentException("maxPacketsPerSec must be > 0");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
    }
}
