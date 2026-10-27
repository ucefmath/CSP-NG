package com.bowman.cspng.config;

/**
 * Configuration de logging.
 */
public record LoggingConfig(
    String logLevel,
    String logFile,
    boolean logEcm,
    boolean logEmm,
    boolean logZap,
    boolean logFailures,
    boolean silent,
    boolean debug,
    boolean hideIpAddresses,
    String logDateformat,
    int logRotateCount,
    int logRotateLimit,
    String wtBadFlags,
    int wtMaxDelayMs,
    boolean wtIncludeFileEvents,
    int etMinCount,
    long version
) {
    public static final LoggingConfig DEFAULT = new LoggingConfig(
        "INFO", null, true, true, true, true,
        false, false, false, "yyMMdd HH:mm:ss.SSS",
        0, 0, "YMNTSAOQGXWDHU-", 5000, true, 1, 1
    );

    public LoggingConfig {
        if (logLevel == null || logLevel.isBlank()) throw new IllegalArgumentException("logLevel required");
        if (wtMaxDelayMs < 0) throw new IllegalArgumentException("wtMaxDelayMs must be >= 0");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
    }
}
