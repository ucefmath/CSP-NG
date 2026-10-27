package com.bowman.cspng.worker;

import com.bowman.cspng.config.RelayWorkerContract;

/**
 * Config worker headless (env uniquement, jamais de PG, jamais de secret en log).
 * Baseline portable documentee dans {@code docs/packaging-worker.md}.
 */
public record WorkerConfig(
    String centralUrl,
    String nodeId,
    String certFile,
    String keyFile,
    String trustFile,
    long ttlMs,
    long pollIntervalMs,
    int cspv2Port,
    int udpPort
) {
  public WorkerConfig {
    if (centralUrl == null || centralUrl.isBlank()) throw new IllegalArgumentException("centralUrl required");
    if (nodeId == null || !nodeId.matches("^[a-z0-9][a-z0-9._-]{0,62}$"))
      throw new IllegalArgumentException("nodeId invalid");
    if (certFile == null || certFile.isBlank()) throw new IllegalArgumentException("certFile required");
    if (keyFile == null || keyFile.isBlank()) throw new IllegalArgumentException("keyFile required");
    if (trustFile == null || trustFile.isBlank()) throw new IllegalArgumentException("trustFile required");
    ttlMs = RelayWorkerContract.normalizeTtl(ttlMs);
    if (pollIntervalMs < 1000 || pollIntervalMs > ttlMs)
      throw new IllegalArgumentException("pollInterval must be 1000..ttl");
    if (cspv2Port < 0 || cspv2Port > 65535) throw new IllegalArgumentException("cspv2Port 0..65535 (0=disabled)");
    if (udpPort < 0 || udpPort > 65535) throw new IllegalArgumentException("udpPort 0..65535 (0=disabled)");
  }

  /** Compat sans CSPv2/UDP (desactives). */
  public WorkerConfig(String centralUrl, String nodeId, String certFile, String keyFile,
                      String trustFile, long ttlMs, long pollIntervalMs) {
    this(centralUrl, nodeId, certFile, keyFile, trustFile, ttlMs, pollIntervalMs, 0, 0);
  }

  /** Compat sans UDP (desactive). */
  public WorkerConfig(String centralUrl, String nodeId, String certFile, String keyFile,
                      String trustFile, long ttlMs, long pollIntervalMs, int cspv2Port) {
    this(centralUrl, nodeId, certFile, keyFile, trustFile, ttlMs, pollIntervalMs, cspv2Port, 0);
  }

  public static WorkerConfig fromEnv() {
    String url = System.getenv().getOrDefault("CSP_WORKER_CENTRAL_URL", "https://127.0.0.1:8444");
    String node = System.getenv().getOrDefault("CSP_WORKER_NODE_ID", "");
    String cert = System.getenv().getOrDefault("CSP_WORKER_TLS_CERT_FILE", "");
    String key = System.getenv().getOrDefault("CSP_WORKER_TLS_KEY_FILE", "");
    String trust = System.getenv().getOrDefault("CSP_WORKER_TLS_TRUST_FILE", "");
    long ttl = Long.parseLong(System.getenv().getOrDefault("CSP_WORKER_TTL_MS", "30000"));
    long poll = Long.parseLong(System.getenv().getOrDefault("CSP_WORKER_POLL_MS", "10000"));
    int cspv2 = Integer.parseInt(System.getenv().getOrDefault("CSP_WORKER_CSPV2_PORT", "0"));
    int udp = Integer.parseInt(System.getenv().getOrDefault("CSP_WORKER_UDP_PORT", "0"));
    return new WorkerConfig(url, node, cert, key, trust, ttl, poll, cspv2, udp);
  }
}
