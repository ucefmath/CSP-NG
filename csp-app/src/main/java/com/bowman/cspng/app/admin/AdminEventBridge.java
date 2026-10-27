package com.bowman.cspng.app.admin;

import com.bowman.cspng.cache.CacheStats;
import com.bowman.cspng.config.ConfigWatcher;
import com.bowman.cspng.app.runtime.RuntimeSnapshotService;
import com.bowman.cspng.connectors.ConnectorSnapshot;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bridge runtime → SSE. Single-writer sur Context Vert.x dédié.
 * Polling borné global, coalescing sans synchronized/lock.
 */
@ApplicationScoped
public class AdminEventBridge {

    private static final Logger LOG = Logger.getLogger(AdminEventBridge.class.getName());
    static final long MIN_SCAN_MS = 250L;
    static final long MAX_SCAN_MS = 60_000L;
    public static final long MAX_CACHE_EVENT_SIZE = 10_000_000L;

    final Vertx vertx;
    final AdminEventBroker broker;
    final RuntimeSnapshotService snapshotService;

    long scanMsCfg = 1000L;

    long scanMs = 1000L;

    Context bridgeContext;
    long timerId = -1L;
    MessageConsumer<Object> configConsumer;

    volatile boolean started = false;
    volatile boolean shutdown = false;
    volatile long generation = 0L;

    // single-writer state — only mutated on bridgeContext (volatile for stop visibility)
    volatile boolean scanInFlight = false;
    volatile boolean scanDirty = false;

    // baselines aggregates only
    StatusDigest lastStatusDigest = null;
    Integer lastConnectedCount = null;
    Integer lastSessionCount = null;
    Long lastCacheSize = null;

    record StatusDigest(int total, int active, int sessions, boolean degraded) {}

    // CDI no-arg
    public AdminEventBridge() {
        this.vertx = null;
        this.broker = null;
        this.snapshotService = null;
    }

    @Inject
    public AdminEventBridge(Vertx vertx, AdminEventBroker broker, RuntimeSnapshotService snapshotService,
                            @ConfigProperty(name = "csp.sse.scan-ms", defaultValue = "1000") long scanMsCfg) {
        this.vertx = vertx;
        this.broker = broker;
        this.snapshotService = snapshotService;
        this.scanMsCfg = scanMsCfg;
        this.scanMs = scanMsCfg;
    }



    void validateScanMs(long v) {
        if (v < MIN_SCAN_MS || v > MAX_SCAN_MS) {
            throw new IllegalStateException("csp.sse.scan-ms must be in [" + MIN_SCAN_MS + ".." + MAX_SCAN_MS + "] got " + v);
        }
    }

    public void onStart(@Observes @Priority(300) StartupEvent event) {
        start();
    }

    public void onStop(@Observes @Priority(100) ShutdownEvent event) {
        stop();
    }

    void start() {
        if (started) return;
        if (broker == null || vertx == null) {
            LOG.fine("AdminEventBridge start skipped missing vertx/broker");
            return;
        }
        if (!broker.isEnabled()) {
            LOG.info("AdminEventBridge disabled (broker disabled) — no timer/consumer");
            started = true;
            return;
        }
        validateScanMs(scanMsCfg);
        this.scanMs = scanMsCfg;

        if (bridgeContext == null) {
            bridgeContext = vertx.getOrCreateContext();
        }
        shutdown = false;
        generation++;
        long genAtStart = generation;

        // consumer
        configConsumer = vertx.eventBus().consumer(ConfigWatcher.CONFIG_RELOADED_ADDRESS, msg -> {
            // never log payload/body
            if (bridgeContext != null) {
                bridgeContext.runOnContext(v -> {
                    if (shutdown || genAtStart != generation) return;
                    try {
                        if (!shutdown && broker.isEnabled()) {
                            broker.publishConfigReloaded("global");
                        }
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "publishConfigReloaded failed", e);
                    }
                    triggerScanInternal();
                });
            } else {
                try { broker.publishConfigReloaded("global"); } catch (Exception e) { LOG.log(Level.WARNING, "publishConfigReloaded failed", e); }
                triggerScanInternal();
            }
        });

        timerId = vertx.setPeriodic(scanMs, id -> {
            if (bridgeContext != null) bridgeContext.runOnContext(v -> {
                if (shutdown) return;
                triggerScanInternal();
            });
            else triggerScanInternal();
        });

        started = true;
        LOG.info("AdminEventBridge started scanMs=" + scanMs + " timerId=" + timerId);
        if (bridgeContext != null) bridgeContext.runOnContext(v -> {
            // reset baselines and flags on bridge context before baseline scan
            lastStatusDigest = null;
            lastConnectedCount = null;
            lastSessionCount = null;
            lastCacheSize = null;
            scanInFlight = false;
            scanDirty = false;
            triggerScanInternal();
        });
        else {
            lastStatusDigest = null;
            lastConnectedCount = null;
            lastSessionCount = null;
            lastCacheSize = null;
            scanInFlight = false;
            scanDirty = false;
            triggerScanInternal();
        }
    }

    void stop() {
        if (!started && shutdown) return;
        if (!started && timerId == -1 && configConsumer == null) {
            // already stopped
            return;
        }
        shutdown = true;
        generation++;
        // cancel timer
        if (timerId != -1 && vertx != null) {
            try { vertx.cancelTimer(timerId); } catch (Exception e) { LOG.log(Level.FINE, "cancelTimer failed", e); }
            timerId = -1;
        }
        if (configConsumer != null) {
            try { configConsumer.unregister(); } catch (Exception e) { LOG.log(Level.FINE, "unregister failed", e); }
            configConsumer = null;
        }
        // reset single-writer flags (no need to be on context, shutdown prevents further)
        scanInFlight = false;
        scanDirty = false;
        started = false;
        LOG.info("AdminEventBridge stopped");
    }

    // called only on bridgeContext (or directly in tests)
    void triggerScanInternal() {
        if (shutdown) return;
        if (broker == null || !broker.isEnabled()) return;
        if (scanInFlight) {
            scanDirty = true;
            return;
        }
        scanInFlight = true;
        scanDirty = false;
        long gen = generation;

        RuntimeSnapshotService.StatusPayload status = null;
        int connectedCount = 0;
        try {
            if (snapshotService != null) status = snapshotService.statusPayload();
        } catch (Exception e) {
            LOG.log(Level.FINE, "statusPayload failed", e);
        }
        try {
            if (snapshotService != null) {
                List<ConnectorSnapshot> snaps = snapshotService.connectorSnapshots();
                connectedCount = (int) snaps.stream().filter(ConnectorSnapshot::connected).count();
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "connectorSnapshots failed", e);
            connectedCount = 0;
        }

        // sync publishes aggregates
        handleSyncPublish(status, connectedCount, gen);

        Future<CacheStats> fut;
        try {
            if (snapshotService != null) fut = snapshotService.cacheStatsFuture();
            else fut = Future.failedFuture("no snapshotService");
        } catch (Exception e) {
            fut = Future.failedFuture(e);
        }

        fut.onComplete(ar -> {
            if (bridgeContext != null) bridgeContext.runOnContext(v -> handleCacheResult(ar, gen));
            else handleCacheResult(ar, gen);
        });
    }

    private void handleSyncPublish(RuntimeSnapshotService.StatusPayload status,
                                   int connectedCount,
                                   long gen) {
        if (shutdown || gen != generation) return;

        if (status != null) {
            StatusDigest cur = new StatusDigest(status.totalConnectorCount(), status.activeConnectorCount(), status.sessionCount(), status.degraded());
            if (lastStatusDigest == null || !lastStatusDigest.equals(cur)) {
                if (!shutdown && gen == generation) {
                    try {
                        broker.publishStatusChanged(cur.total(), cur.active(), cur.sessions(), cur.degraded());
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "publishStatusChanged failed", e);
                    }
                }
                lastStatusDigest = cur;
            }
            // session count derived from status (single source) to avoid double source incoherence
            int sessCount = status.sessionCount();
            if (lastSessionCount == null || !lastSessionCount.equals(sessCount)) {
                if (!shutdown && gen == generation) {
                    try {
                        broker.publishSessionChanged(sessCount, "UPDATED");
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "publishSessionChanged failed", e);
                    }
                }
                lastSessionCount = sessCount;
            }
        }

        if (lastConnectedCount == null || !lastConnectedCount.equals(connectedCount)) {
            if (!shutdown && gen == generation) {
                try {
                    broker.publishConnectorChanged("UPDATED", connectedCount);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "publishConnectorChanged failed", e);
                }
            }
            lastConnectedCount = connectedCount;
        }
    }

    private void handleCacheResult(io.vertx.core.AsyncResult<CacheStats> ar, long gen) {
        if (gen != generation) {
            return;
        }
        if (shutdown) {
            finishScanAndMaybeReschedule(gen);
            return;
        }
        if (ar != null && ar.succeeded() && ar.result() != null) {
            CacheStats cur = ar.result();
            long saturated = Math.min(cur.cachedEcms(), MAX_CACHE_EVENT_SIZE);
            if (lastCacheSize == null || !lastCacheSize.equals(saturated)) {
                if (!shutdown && gen == generation) {
                    try {
                        broker.publishCacheChanged(saturated, "OK");
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "publishCacheChanged failed", e);
                    }
                }
                lastCacheSize = saturated;
            }
        } else {
            LOG.log(Level.FINE, "cacheStatsFuture failed, no publish");
        }
        finishScanAndMaybeReschedule(gen);
    }

    private void finishScanAndMaybeReschedule(long gen) {
        if (gen != generation) {
            return;
        }
        scanInFlight = false;
        if (scanDirty && !shutdown) {
            scanDirty = false;
            triggerScanInternal();
        }
    }

    // package-private hooks for tests
    long getTimerIdForTest() { return timerId; }
    MessageConsumer<Object> getConsumerForTest() { return configConsumer; }
    boolean isScanInFlightForTest() { return scanInFlight; }
    boolean isScanDirtyForTest() { return scanDirty; }
    long getGenerationForTest() { return generation; }
    Context getBridgeContextForTest() { return bridgeContext; }
    void setBridgeContextForTest(Context ctx) { this.bridgeContext = ctx; }
    void triggerScanForTest() {
        if (bridgeContext != null) bridgeContext.runOnContext(v -> triggerScanInternal());
        else triggerScanInternal();
    }
    void triggerConfigReloadForTest() {
        if (bridgeContext != null) bridgeContext.runOnContext(v -> {
            try { broker.publishConfigReloaded("global"); } catch (Exception ignored) {}
            triggerScanInternal();
        });
        else {
            try { broker.publishConfigReloaded("global"); } catch (Exception ignored) {}
            triggerScanInternal();
        }
    }
    boolean isStartedForTest() { return started; }
    boolean isShutdownForTest() { return shutdown; }
    void setScanMsForTest(long ms) { this.scanMsCfg = ms; this.scanMs = ms; }
    void resetBaselinesForTest() {
        lastStatusDigest = null; lastConnectedCount = null; lastSessionCount = null; lastCacheSize = null;
        scanInFlight = false; scanDirty = false;
    }
}
