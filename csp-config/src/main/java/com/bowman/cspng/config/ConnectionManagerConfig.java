package com.bowman.cspng.config;

/**
 * Configuration du connection-manager (gestion des sessions clientes).
 */
public record ConnectionManagerConfig(
    int maxThreads,
    int sessionTimeoutMs,
    int newcamdMaxMsgSize,
    int maxPending,
    int maxConnectionsIp,
    int sessionKeepaliveSec,
    String sessionKeepaliveExcluded,
    long version
) {
    public static final ConnectionManagerConfig DEFAULT = new ConnectionManagerConfig(
        64, 30000, 2048, 500, 5, 30, null, 1
    );

    public ConnectionManagerConfig {
        if (maxThreads < 1) throw new IllegalArgumentException("maxThreads must be >= 1");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
    }
}
