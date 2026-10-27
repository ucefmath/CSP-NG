package com.bowman.cspng.app.runtime;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.cluster.tcp.CspV2Config;
import com.bowman.cspng.cluster.tcp.CspV2Manager;
import com.bowman.cspng.cluster.tcp.CspV2Settings;
import com.bowman.cspng.cluster.tcp.CspV2TlsMaterial;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.connectors.catalog.CardDiscoveryService;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bootstrap CSPv2 TCP — démarre le manager réseau, câble le handler ECM entrant
 * et le transport cache sortant.
 *
 * <p>Ordre : Startup @Priority(260) après RuntimeCore(200), avant ListenerManager(300).
 * Shutdown @Priority(20) avant RuntimeCore(200).
 */
@ApplicationScoped
public class CspV2Bootstrap {

    private static final Logger LOG = Logger.getLogger(CspV2Bootstrap.class.getName());

    static final long RESOLVE_TIMEOUT_SECONDS = 15;
    static final long START_TIMEOUT_SECONDS = 30;

    private final Vertx vertx;
    private final CspV2Config config;
    private final RuntimeSecretResolver secretResolver;
    private final RuntimeCore runtimeCore;
    private final CacheVerticle cacheVerticle;
    private final CwsConnectorManager connectorManager;

    private final CardDiscoveryService cardDiscoveryService;
    private final com.bowman.cspng.config.ConfigHolder configHolder;
    private CspV2Manager manager;
    private CspV2CacheTransport transport;
    private CspV2Settings settings;
    private boolean started;

    @Inject
    public CspV2Bootstrap(Vertx vertx, CspV2Config config, RuntimeSecretResolver secretResolver,
                          RuntimeCore runtimeCore, CacheVerticle cacheVerticle,
                          CwsConnectorManager connectorManager,
                          CardDiscoveryService cardDiscoveryService,
                          com.bowman.cspng.config.ConfigHolder configHolder) {
        this.vertx = vertx;
        this.config = config;
        this.secretResolver = secretResolver;
        this.runtimeCore = runtimeCore;
        this.cacheVerticle = cacheVerticle;
        this.connectorManager = connectorManager;
        this.cardDiscoveryService = cardDiscoveryService;
        this.configHolder = configHolder;
    }

    public CspV2Bootstrap(Vertx vertx, CspV2Config config, RuntimeSecretResolver secretResolver,
                          RuntimeCore runtimeCore, CacheVerticle cacheVerticle,
                          CwsConnectorManager connectorManager) {
        this(vertx, config, secretResolver, runtimeCore, cacheVerticle, connectorManager, null, null);
    }

    public synchronized void onStart(@Observes @Priority(260) StartupEvent event) {
        if (!config.enabled()) {
            LOG.info("CSPv2 disabled — skipping startup");
            return;
        }
        if (started) {
            LOG.info("CSPv2 already started — idempotent skip");
            return;
        }
        if (!runtimeCore.isEnabled() || !runtimeCore.isStarted()) {
            throw new IllegalStateException("FAIL-FAST: CSPv2 requires RuntimeCore enabled and started");
        }

        CspV2Settings st;
        try {
            st = CspV2Settings.from(config);
        } catch (Exception e) {
            throw new IllegalStateException("FAIL-FAST: CSPv2 settings invalid", e);
        }

        if (Context.isOnEventLoopThread()) {
            throw new IllegalStateException("FAIL-FAST: CSPv2 startup must not run on event-loop");
        }

        byte[] certBytes = null;
        byte[] keyBytes = null;
        byte[] trustBytes = null;
        CspV2TlsMaterial material = null;
        CspV2Manager mgr = null;
        CspV2CacheTransport t = null;
        boolean transportAdded = false;
        try {
            Future<String> certF = secretResolver.resolveAsync(st.certRef(), "CSPv2 cert");
            Future<String> keyF = secretResolver.resolveAsync(st.keyRef(), "CSPv2 key");
            Future<String> trustF = secretResolver.resolveAsync(st.trustRef(), "CSPv2 trust");

            waitForStart(Future.all(certF, keyF, trustF),
                    RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS,
                    v -> { /* no-op: Future<Void>, rien à stopper */ });

            certBytes = certF.result().getBytes(StandardCharsets.UTF_8);
            keyBytes = keyF.result().getBytes(StandardCharsets.UTF_8);
            trustBytes = trustF.result().getBytes(StandardCharsets.UTF_8);

            material = new CspV2TlsMaterial(certBytes, keyBytes, trustBytes);

            CspV2DomainMapper mapper = new CspV2DomainMapper();
            CspV2EcmHandler handler = new CspV2EcmHandler(cacheVerticle, connectorManager,
                    cardDiscoveryService, mapper, st.exposeConnectorName());
            var dcwProvider = runtimeCore.dcwPolicyProvider(configHolder);
            if (dcwProvider != null) handler.setDcwPolicySupplier(dcwProvider);

            Future<CspV2Manager> startFuture = CspV2Manager.start(vertx, st, material, handler);
            mgr = waitForStart(startFuture, START_TIMEOUT_SECONDS, TimeUnit.SECONDS,
                    lateManager -> {
                        try {
                            lateManager.stop();
                        } catch (Exception ignored) { }
                    });

            t = new CspV2CacheTransport(mgr, cacheVerticle, mapper);
            cacheVerticle.addTransport(t);
            transportAdded = true;

            this.settings = st;
            this.manager = mgr;
            this.transport = t;
            this.started = true;
            LOG.info("CSPv2 manager started on port " + mgr.snapshot().actualPort());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cleanupOnFailure(mgr, t, transportAdded);
            throw new IllegalStateException("FAIL-FAST: CSPv2 startup interrupted", e);
        } catch (TimeoutException e) {
            cleanupOnFailure(mgr, t, transportAdded);
            throw new IllegalStateException("FAIL-FAST: CSPv2 startup timeout", e);
        } catch (Exception e) {
            cleanupOnFailure(mgr, t, transportAdded);
            throw new IllegalStateException("FAIL-FAST: CSPv2 startup failed", e);
        } finally {
            if (certBytes != null) Arrays.fill(certBytes, (byte) 0);
            if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
            if (trustBytes != null) Arrays.fill(trustBytes, (byte) 0);
            if (material != null) {
                try { material.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Attend la resolution d'un Future Vert.x hors event-loop.
     * En cas de timeout ou interruption, attache un callback de nettoyage
     * sur le résultat tardif avant de relancer l'exception.
     *
     * @param future   le Future Vert.x à attendre
     * @param timeout  durée maximale d'attente
     * @param unit     unité du timeout
     * @param cleanup  callback appelé sur la valeur si resolution tardive
     * @param <T>      type de la valeur
     * @return la valeur resolue
     * @throws InterruptedException si le thread est interrompu
     * @throws TimeoutException     si le timeout expire
     */
    static <T> T waitForStart(Future<T> future, long timeout, TimeUnit unit,
                              Consumer<T> cleanup) throws InterruptedException, TimeoutException {
        CompletableFuture<T> cf = future.toCompletionStage().toCompletableFuture();
        try {
            return cf.get(timeout, unit);
        } catch (ExecutionException e) {
            // Future failed — pas de cleanup, wrapper RuntimeException
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        } catch (TimeoutException | InterruptedException e) {
            cf.thenAccept(v -> {
                try { cleanup.accept(v); } catch (Exception ignored) { }
            });
            throw e;
        }
    }

    private void cleanupOnFailure(CspV2Manager mgr, CspV2CacheTransport t, boolean transportAdded) {
        if (t != null && transportAdded) {
            try { cacheVerticle.removeTransport(t); } catch (Exception ignored) {}
        }
        if (mgr != null) {
            try {
                Future<Void> stopFuture = mgr.stop();
                if (stopFuture != null) {
                    stopFuture.toCompletionStage().toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
                }
            } catch (Exception ignored) {}
        }
    }

    public synchronized void onStop(@Observes @Priority(20) ShutdownEvent event) {
        if (!started) {
            LOG.fine("CSPv2 not started — nothing to stop");
            return;
        }
        CspV2CacheTransport t = transport;
        CspV2Manager mgr = manager;
        CspV2Settings st = this.settings;
        transport = null;
        manager = null;
        settings = null;
        started = false;

        if (t != null && cacheVerticle != null) {
            try { cacheVerticle.removeTransport(t); }
            catch (Exception e) { LOG.fine("removeTransport failed"); }
        }

        if (mgr != null) {
            if (!Context.isOnEventLoopThread()) {
                try {
                    long drainMs = st != null ? Math.max(st.shutdownDrainMs(), 1000L) : 10000L;
                    long extraMs = st != null ? Math.max(st.drainMs(), 1000L) : 5000L;
                    long timeout = Math.min(drainMs + extraMs + 2000L, 120_000L);
                    mgr.stop()
                            .toCompletionStage().toCompletableFuture()
                            .get(timeout, TimeUnit.MILLISECONDS);
                    LOG.info("CSPv2 manager stopped");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.log(Level.WARNING, "CSPv2 stop interrupted");
                } catch (TimeoutException e) {
                    LOG.log(Level.WARNING, "CSPv2 stop timeout");
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "CSPv2 stop failed");
                }
            } else {
                mgr.stop().onComplete(ar -> {
                    if (ar.succeeded()) LOG.info("CSPv2 manager stopped async");
                    else LOG.log(Level.WARNING, "CSPv2 manager stop async failed");
                });
            }
        }
    }

    public boolean isStarted() { return started; }
}
