package com.bowman.cspng.config;

/**
 * Configuration d'un connecteur upstream (card server).
 * Tous les secrets sont des references (env/file/vault), jamais en clair.
 *
 * <p>{@code proxyName} : nom du proxy SOCKS5 (FK vers {@code csp_proxy.name}),
 * ou {@code null} pour une connexion directe (comportement legacy).
 */
public record ConnectorConfig(
    String name,
    String profileName,
    String protocol,
    boolean enabled,
    boolean debug,
    String host,
    int port,
    String username,
    String passwordRef,
    String desKeyRef,
    int connectTimeoutMs,
    int reconnectDelayMs,
    Integer keepAliveSec,
    Integer minDelayMs,
    Integer maxQueue,
    Integer newcamdMaxMsgSize,
    String newcamdClientIdRef,
    Integer radegastResponseTimeoutMs,
    boolean cccamEnabled,
    String cccamNodeId,
    String cccamVersion,
    Integer cacheexMode,
    String cacheexFilter,
    Integer cacheexMaxSend,
    Integer cacheexMaxHop,
    String chameleonProfiles,
    Integer conaxReaderIndex,
    int priority,
    int maxCapacity,
    long version,
    String proxyName,
    boolean cardDiscovery,
    Integer reshareGrant,
    String newcamdDialect,
    Integer reshareCap,
    String newcamdPasswordEncoding
) {
    public ConnectorConfig {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        // profileName nullable : null => dynamic connector with CAID map
        if (protocol == null || protocol.isBlank()) throw new IllegalArgumentException("protocol required");
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be 1-65535");
        if (cacheexMode != null && cacheexMode != 0 && cacheexMode != 2 && cacheexMode != 3) {
            throw new IllegalArgumentException("cacheexMode must be 0, 2, or 3");
        }
        if (cacheexMaxSend != null && (cacheexMaxSend < 0 || cacheexMaxSend > 10000)) {
            throw new IllegalArgumentException("cacheexMaxSend must be 0..10000 if set");
        }
        if (cacheexMaxHop != null && (cacheexMaxHop < 0 || cacheexMaxHop > 10)) {
            throw new IllegalArgumentException("cacheexMaxHop must be 0..10 if set");
        }
        if (priority < 0 || priority > 100) throw new IllegalArgumentException("priority must be 0-100");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        if (reshareGrant != null && (reshareGrant < 0 || reshareGrant > 10)) {
            throw new IllegalArgumentException("reshareGrant must be 0-10 if set");
        }
        if (reshareCap != null && (reshareCap < 0 || reshareCap > 10)) {
            throw new IllegalArgumentException("reshareCap must be 0-10 if set");
        }
        if (newcamdDialect != null && !newcamdDialect.equalsIgnoreCase("legacy") && !newcamdDialect.equalsIgnoreCase("oscam_ncd525")) {
            throw new IllegalArgumentException("newcamdDialect must be legacy or oscam_ncd525 if set");
        }
        if (newcamdPasswordEncoding != null && !newcamdPasswordEncoding.equalsIgnoreCase("plaintext") && !newcamdPasswordEncoding.equalsIgnoreCase("md5crypt")) {
            throw new IllegalArgumentException("newcamdPasswordEncoding must be plaintext or md5crypt if set");
        }
        newcamdPasswordEncoding = newcamdPasswordEncoding != null
            ? newcamdPasswordEncoding.toLowerCase(java.util.Locale.ROOT)
            : "plaintext";
        newcamdDialect = newcamdDialect != null
            ? newcamdDialect.toLowerCase(java.util.Locale.ROOT)
            : "legacy";
        // proxyName nullable : null = direct connection
    }

    /**
     * Compat constructor without cacheexMaxHop (pre-V15 callers) — hop défaut runtime.
     */
    public ConnectorConfig(
        String name, String profileName, String protocol, boolean enabled, boolean debug,
        String host, int port, String username, String passwordRef, String desKeyRef,
        int connectTimeoutMs, int reconnectDelayMs,
        Integer keepAliveSec, Integer minDelayMs, Integer maxQueue,
        Integer newcamdMaxMsgSize, String newcamdClientIdRef,
        Integer radegastResponseTimeoutMs,
        boolean cccamEnabled, String cccamNodeId, String cccamVersion,
        Integer cacheexMode, String cacheexFilter, Integer cacheexMaxSend,
        String chameleonProfiles, Integer conaxReaderIndex,
        int priority, int maxCapacity, long version, String proxyName,
        boolean cardDiscovery, Integer reshareGrant, String newcamdDialect,
        Integer reshareCap, String newcamdPasswordEncoding
    ) {
        this(name, profileName, protocol, enabled, debug,
             host, port, username, passwordRef, desKeyRef,
             connectTimeoutMs, reconnectDelayMs, keepAliveSec, minDelayMs, maxQueue,
             newcamdMaxMsgSize, newcamdClientIdRef, radegastResponseTimeoutMs,
             cccamEnabled, cccamNodeId, cccamVersion,
             cacheexMode, cacheexFilter, cacheexMaxSend, null,
             chameleonProfiles, conaxReaderIndex,
             priority, maxCapacity, version, proxyName,
             cardDiscovery, reshareGrant, newcamdDialect, reshareCap, newcamdPasswordEncoding);
    }

    /**
     * Compat constructor with V9 fields but without newcamdPasswordEncoding.
     */
    public ConnectorConfig(
        String name, String profileName, String protocol, boolean enabled, boolean debug,
        String host, int port, String username, String passwordRef, String desKeyRef,
        int connectTimeoutMs, int reconnectDelayMs,
        Integer keepAliveSec, Integer minDelayMs, Integer maxQueue,
        Integer newcamdMaxMsgSize, String newcamdClientIdRef,
        Integer radegastResponseTimeoutMs,
        boolean cccamEnabled, String cccamNodeId, String cccamVersion,
        Integer cacheexMode, String cacheexFilter, Integer cacheexMaxSend,
        String chameleonProfiles, Integer conaxReaderIndex,
        int priority, int maxCapacity, long version, String proxyName,
        boolean cardDiscovery, Integer reshareGrant, String newcamdDialect, Integer reshareCap
    ) {
        this(name, profileName, protocol, enabled, debug,
             host, port, username, passwordRef, desKeyRef,
             connectTimeoutMs, reconnectDelayMs, keepAliveSec, minDelayMs, maxQueue,
             newcamdMaxMsgSize, newcamdClientIdRef, radegastResponseTimeoutMs,
             cccamEnabled, cccamNodeId, cccamVersion,
             cacheexMode, cacheexFilter, cacheexMaxSend, null,
             chameleonProfiles, conaxReaderIndex,
             priority, maxCapacity, version, proxyName,
             cardDiscovery, reshareGrant, newcamdDialect, reshareCap, "plaintext");
    }

    /**
     * Compat constructor with proxyName but without V9 fields.
     */
    public ConnectorConfig(
        String name, String profileName, String protocol, boolean enabled, boolean debug,
        String host, int port, String username, String passwordRef, String desKeyRef,
        int connectTimeoutMs, int reconnectDelayMs,
        Integer keepAliveSec, Integer minDelayMs, Integer maxQueue,
        Integer newcamdMaxMsgSize, String newcamdClientIdRef,
        Integer radegastResponseTimeoutMs,
        boolean cccamEnabled, String cccamNodeId, String cccamVersion,
        Integer cacheexMode, String cacheexFilter, Integer cacheexMaxSend,
        String chameleonProfiles, Integer conaxReaderIndex,
        int priority, int maxCapacity, long version, String proxyName
    ) {
        this(name, profileName, protocol, enabled, debug,
             host, port, username, passwordRef, desKeyRef,
             connectTimeoutMs, reconnectDelayMs, keepAliveSec, minDelayMs, maxQueue,
             newcamdMaxMsgSize, newcamdClientIdRef, radegastResponseTimeoutMs,
             cccamEnabled, cccamNodeId, cccamVersion,
             cacheexMode, cacheexFilter, cacheexMaxSend,
             chameleonProfiles, conaxReaderIndex,
             priority, maxCapacity, version, proxyName,
             false, null, "legacy", null, "plaintext");
    }

    /**
     * Compat constructor without proxyName (delegates with proxyName=null).
     * Preserves all existing call sites (tests, imports, upserts).
     */
    public ConnectorConfig(
        String name, String profileName, String protocol, boolean enabled, boolean debug,
        String host, int port, String username, String passwordRef, String desKeyRef,
        int connectTimeoutMs, int reconnectDelayMs,
        Integer keepAliveSec, Integer minDelayMs, Integer maxQueue,
        Integer newcamdMaxMsgSize, String newcamdClientIdRef,
        Integer radegastResponseTimeoutMs,
        boolean cccamEnabled, String cccamNodeId, String cccamVersion,
        Integer cacheexMode, String cacheexFilter, Integer cacheexMaxSend,
        String chameleonProfiles, Integer conaxReaderIndex,
        int priority, int maxCapacity, long version
    ) {
        this(name, profileName, protocol, enabled, debug,
             host, port, username, passwordRef, desKeyRef,
             connectTimeoutMs, reconnectDelayMs, keepAliveSec, minDelayMs, maxQueue,
             newcamdMaxMsgSize, newcamdClientIdRef, radegastResponseTimeoutMs,
             cccamEnabled, cccamNodeId, cccamVersion,
             cacheexMode, cacheexFilter, cacheexMaxSend,
             chameleonProfiles, conaxReaderIndex,
             priority, maxCapacity, version, null,
             false, null, "legacy", null, "plaintext");
    }

    /** True if this connector has a fixed profile (profileName non-null). */
    public boolean hasFixedProfile() {
        return profileName != null && !profileName.isBlank();
    }

    /** Hop max cacheex effectif (défaut 10 = CcConstants.CACHEEX_DEFAULT_MAXHOP). */
    public int effectiveCacheexMaxHop(int defaultMaxHop) {
        return cacheexMaxHop != null ? cacheexMaxHop : defaultMaxHop;
    }

    /**
     * Borne de file push cacheex effective (nombre de messages en attente, pas un débit).
     * null/0 = défaut 2000 (contrat v2 §6.1).
     */
    public int effectiveCacheexMaxSend() {
        return (cacheexMaxSend != null && cacheexMaxSend > 0) ? cacheexMaxSend : 2000;
    }

    /** Effective keep-alive, falling back to default. */
    public int effectiveKeepAliveSec(int defaultKeepAlive) {
        return keepAliveSec != null ? keepAliveSec : defaultKeepAlive;
    }

    /** Effective min delay, falling back to default. */
    public int effectiveMinDelayMs(int defaultMinDelay) {
        return minDelayMs != null ? minDelayMs : defaultMinDelay;
    }

    /** Effective max queue, falling back to default. */
    public int effectiveMaxQueue(int defaultMaxQueue) {
        return maxQueue != null ? maxQueue : defaultMaxQueue;
    }
}
