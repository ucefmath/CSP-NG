package com.bowman.cspng.config;

/**
 * Configuration extended-newcamd singleton (catch-all, port 2233 par defaut).
 * Sentinel catch-all interne = "**" (cache-only fallback, aucun upstream).
 */
public record ExtendedNewcamdConfig(
    boolean enabled,
    int listenPort,
    String bindAddress,
    boolean catchAll,
    boolean debug,
    String desKeyRef,
    String allowList,
    String denyList,
    int maxMsgSize,
    long version
) {
    /** Valeur sentinelle interne pour le fallback catch-all (jamais en DB). */
    public static final String CATCH_ALL_SENTINEL = "**";

    public static final ExtendedNewcamdConfig DEFAULT = new ExtendedNewcamdConfig(
        false, 2233, "0.0.0.0", false, false, null, null, null, 2048, 1
    );

    public ExtendedNewcamdConfig {
        if (listenPort < 1 || listenPort > 65535) throw new IllegalArgumentException("listenPort must be 1-65535");
        if (bindAddress == null || bindAddress.isBlank()) throw new IllegalArgumentException("bindAddress required");
        if (!ConfigValidator.isValidInet(bindAddress)) throw new IllegalArgumentException("bindAddress must be IP literal");
        if (maxMsgSize < 128 || maxMsgSize > 65536) throw new IllegalArgumentException("maxMsgSize must be 128-65536");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
    }
}
