package com.bowman.cspng.app.runtime;

import com.bowman.cspng.cache.CacheStats;
import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ProfileConfig;
import com.bowman.cspng.connectors.ConnectorSnapshot;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.sessions.SessionRegistry;
import com.bowman.cspng.sessions.SessionSnapshot;
import io.vertx.core.Future;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class RuntimeSnapshotService {

  public static final String SCHEMA_VERSION = "1.0";

  private final String instanceId;
  private final Instant startInstant;
  private final long startNano;

  @Inject ConfigHolder configHolder;
  @Inject RuntimeCore runtimeCore;
  @Inject CacheVerticle cacheVerticle;
  @Inject CwsConnectorManager connectorManager;
  @Inject SessionRegistry sessionRegistry;
  @Inject ConnectorLifecycleManager connectorLifecycleManager;
  @Inject ListenerManager listenerManager;

  // For CDI proxy
  public RuntimeSnapshotService() {
    this.instanceId = UUID.randomUUID().toString();
    this.startInstant = Instant.now();
    this.startNano = System.nanoTime();
  }

  // Visible for tests: factory with fixed id/start
  public RuntimeSnapshotService(String instanceId, Instant startInstant, long startNano,
                                ConfigHolder holder, RuntimeCore runtimeCore,
                                CacheVerticle cacheVerticle,
                                CwsConnectorManager connectorManager,
                                SessionRegistry sessionRegistry,
                                ConnectorLifecycleManager clm,
                                ListenerManager lm) {
    this.instanceId = instanceId != null ? instanceId : UUID.randomUUID().toString();
    this.startInstant = startInstant != null ? startInstant : Instant.now();
    this.startNano = startNano;
    this.configHolder = holder;
    this.runtimeCore = runtimeCore;
    this.cacheVerticle = cacheVerticle;
    this.connectorManager = connectorManager;
    this.sessionRegistry = sessionRegistry;
    this.connectorLifecycleManager = clm;
    this.listenerManager = lm;
  }

  public String instanceId() { return instanceId; }
  public Instant startInstant() { return startInstant; }
  public long uptimeMs() { return (System.nanoTime() - startNano) / 1_000_000; }

  public String schemaVersion() { return SCHEMA_VERSION; }

  public <T> Envelope<T> envelope(T payload) {
    return new Envelope<>(instanceId, Instant.now().toString(), SCHEMA_VERSION, payload);
  }

  public record Envelope<T>(String instanceId, String timestamp, String schemaVersion, T payload) {}

  public record PagedResult<T>(List<T> items, int page, int size, long totalElements, int totalPages) {}

  public record ProfileDto(String name, boolean enabled, int caId, String caIdHex, int networkId, boolean cacheOnly, long maxCwWaitMs) {}

  public static class NotReadyException extends RuntimeException {
    public NotReadyException(String msg) { super(msg); }
    public NotReadyException(String msg, Throwable cause) { super(msg, cause); }
  }

  public record StatusPayload(
      boolean runtimeStarted,
      boolean runtimeEnabled,
      int totalConnectorCount,
      int activeConnectorCount,
      int sessionCount,
      int listenerCount,
      boolean degraded,
      boolean cacheAvailable,
      long configLoadedAt,
      String appVersion,
      long uptimeMs,
      String startTime) {}

  public Future<CacheStats> cacheStatsFuture() {
    if (cacheVerticle == null) return Future.failedFuture(new NotReadyException("NOT_READY: cache not available"));
    if (!cacheVerticle.isStarted()) return Future.failedFuture(new NotReadyException("NOT_READY: cache not started"));
    return cacheVerticle.snapshotStats().recover(err -> {
      if (err instanceof NotReadyException) return Future.failedFuture(err);
      String m = err != null ? err.getMessage() : "";
      if (m != null && m.contains("NOT_READY")) return Future.failedFuture(new NotReadyException(m, err));
      // wrap internal snapshot failure as generic
      return Future.failedFuture(new RuntimeException("CACHE_SNAPSHOT_FAILED", err));
    });
  }

  public StatusPayload statusPayload() {
    boolean started = runtimeCore != null && runtimeCore.isStarted();
    boolean enabled = runtimeCore != null && runtimeCore.isEnabled();
    int totalC = connectorManager != null ? connectorManager.getConnectors().size() : 0;
    int activeC = connectorManager != null ? connectorManager.activeCount() : 0;
    int sCount = sessionRegistry != null ? sessionRegistry.size() : 0;
    int listeners = listenerManager != null ? listenerManager.activeCount() : 0;
    boolean degraded = connectorLifecycleManager != null && connectorLifecycleManager.isDegraded();
    if (listenerManager != null) degraded = degraded || listenerManager.isDegraded();
    boolean cacheAvail = cacheVerticle != null && cacheVerticle.isStarted();
    long loadedAt = 0;
    if (configHolder != null && configHolder.isInitialized()) {
      try { loadedAt = configHolder.get().loadedAtMs(); } catch (Exception ignored) {}
    }
    String ver = resolveAppVersion();
    return new StatusPayload(started, enabled, totalC, activeC, sCount, listeners, degraded, cacheAvail, loadedAt, ver, uptimeMs(), startInstant.toString());
  }

  private String resolveAppVersion() {
    try {
      String v = getClass().getPackage().getImplementationVersion();
      if (v != null && !v.isBlank()) return v;
    } catch (Exception ignored) {}
    // fallback: try reading from properties if available without I/O? Use constant
    return "1.0.3";
  }

  public List<ProfileDto> profileSnapshots() {
    if (configHolder == null || !configHolder.isInitialized()) {
      throw new IllegalStateException("NOT_READY: config not initialized");
    }
    ConfigSnapshot snap = configHolder.get();
    List<ProfileDto> list = snap.profiles().stream()
        .map(p -> new ProfileDto(
            p.name(),
            p.enabled(),
            p.caId(),
            String.format("0x%04X", p.caId()),
            p.networkId(),
            p.cacheOnly(),
            p.maxCwWaitMs()))
        .sorted(java.util.Comparator.comparing(ProfileDto::name))
        .toList();
    return Collections.unmodifiableList(list);
  }

  public List<ConnectorSnapshot> connectorSnapshots() {
    if (connectorManager == null) return List.of();
    return connectorManager.snapshotConnectors();
  }

  public List<SessionSnapshot> sessionSnapshots() {
    if (sessionRegistry == null) return List.of();
    return sessionRegistry.snapshotSessions();
  }

  // helper for pagination slicing — long safe, copy detached
  public static <T> PagedResult<T> paginate(List<T> sorted, int page, int size) {
    long total = sorted.size();
    int totalPages = total == 0 ? 0 : (int) ((total + size - 1L) / size);
    long fromLong = (long) page * (long) size;
    List<T> slice;
    if (fromLong >= total) {
      slice = List.of();
    } else {
      int from = (int) fromLong;
      int to = (int) Math.min(fromLong + size, total);
      slice = List.copyOf(sorted.subList(from, to));
    }
    // ensure returned list is immutable copy already
    return new PagedResult<>(slice, page, size, total, totalPages);
  }
}
