package com.bowman.cspng.config;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watcher de configuration : ecoute les changements PostgreSQL via LISTEN/NOTIFY
 * et recharge automatiquement le snapshot.
 *
 * <p>Fonctionnement :
 * <ol>
 *   <li>Se connecte a PostgreSQL et emet {@code LISTEN csp_config_changed}</li>
 *   <li>A chaque NOTIFY (payload vide ignore), recharge tout le snapshot, valide, swap</li>
 *   <li>En cas d'echec du reload, conserve l'ancien snapshot et logge l'erreur</li>
 *   <li>Reconnexion exponentielle en cas de perte de connexion PG</li>
 *   <li>Timer fallback de reload periodique (5 min par defaut)</li>
 * </ol>
 *
 * <p>Le bus d'evenements recoit le message {@code config-reloaded} apres chaque swap reussi.
 *
 * <p><b>Robustesse :</b> coalescing des NOTIFY en rafale (un seul loadAll concurrent,
 * dirty flag pour rejouer un reload apres le courant) et guard sur la connexion
 * LISTEN (pas de double startListening, fermeture propre des connexions obsoletes).
 */
public class ConfigWatcher {

    private static final Logger LOG = Logger.getLogger(ConfigWatcher.class.getName());

    /** Adresse EventBus pour les evenements de reload. */
    public static final String CONFIG_RELOADED_ADDRESS = "config-reloaded";

    private final ConfigRepository repository;
    private final ConfigHolder holder;
    private final long fallbackReloadIntervalMs;
    private final long initialReconnectDelayMs;
    private final long maxReconnectDelayMs;
    private final Pool pool;
    private final Vertx vertx;

    private SqlConnection listenConn;
    private long reconnectTimerId = -1;
    private long fallbackTimerId = -1;
    private long reconnectDelay;
    private boolean stopped;

    // -- coalescing + guard (thread-safe via lock) --
    private final Object lock = new Object();
    private boolean reloadInFlight = false;
    private boolean reloadDirty = false;
    private boolean connectInProgress = false;

    /**
     * @param repository              acces DB reactif
     * @param holder                  holder AtomicReference a mettre a jour
     * @param pool                    pool PG dedie pour l'ecoute NOTIFY (meme datasource)
     * @param vertx                   instance Vert.x pour les timers et l'EventBus
     * @param fallbackReloadIntervalMs intervalle de reload timer fallback (ex: 300_000 = 5 min)
     */
    public ConfigWatcher(ConfigRepository repository, ConfigHolder holder, Pool pool, Vertx vertx,
                          long fallbackReloadIntervalMs) {
        this(repository, holder, pool, vertx, fallbackReloadIntervalMs, 1000, 60_000);
    }

    public ConfigWatcher(ConfigRepository repository, ConfigHolder holder, Pool pool, Vertx vertx,
                          long fallbackReloadIntervalMs, long initialReconnectDelayMs, long maxReconnectDelayMs) {
        this.repository = repository;
        this.holder = holder;
        this.pool = pool;
        this.vertx = vertx;
        this.fallbackReloadIntervalMs = fallbackReloadIntervalMs;
        this.initialReconnectDelayMs = initialReconnectDelayMs;
        this.maxReconnectDelayMs = maxReconnectDelayMs;
    }

    /**
     * Demarre l'ecoute NOTIFY et le timer fallback.
     */
    public Future<Void> start() {
        Promise<Void> startPromise = Promise.promise();
        synchronized (lock) {
            this.stopped = false;
            this.reconnectDelay = initialReconnectDelayMs;
        }

        startListening()
            .onSuccess(v -> {
                LOG.info("ConfigWatcher started — listening on csp_config_changed");
                startFallbackTimer();
                startPromise.complete();
            })
            .onFailure(err -> {
                LOG.log(Level.SEVERE, "ConfigWatcher failed to start listening, will retry", err);
                startPromise.complete(); // demarre quand meme, le fallback timer sauvera
                scheduleReconnect();
                startFallbackTimer();
            });
        return startPromise.future();
    }

    /**
     * Arrete l'ecoute NOTIFY et les timers.
     */
    public Future<Void> stop() {
        Promise<Void> stopPromise = Promise.promise();
        SqlConnection toClose;
        synchronized (lock) {
            this.stopped = true;
            this.reloadInFlight = false;
            this.reloadDirty = false;
            this.connectInProgress = false;
            toClose = this.listenConn;
            this.listenConn = null;
        }
        cancelTimers();
        if (toClose != null) {
            toClose.close().onComplete(ar -> stopPromise.complete());
        } else {
            stopPromise.complete();
        }
        return stopPromise.future();
    }

    // -- NOTIFY listener --

    private Future<Void> startListening() {
        synchronized (lock) {
            if (stopped) {
                return Future.failedFuture("stopped");
            }
            if (connectInProgress) {
                LOG.fine("startListening already in progress — skipping duplicate");
                return Future.failedFuture("already connecting");
            }
            if (listenConn != null) {
                LOG.fine("already listening — skipping duplicate startListening");
                return Future.succeededFuture();
            }
            connectInProgress = true;
        }

        Promise<Void> promise = Promise.promise();
        pool.getConnection()
            .onSuccess(conn -> {
                synchronized (lock) {
                    if (stopped) {
                        connectInProgress = false;
                        conn.close();
                        promise.fail("stopped before LISTEN");
                        return;
                    }
                    // guard double connection : si une autre connexion a ete etablie entre temps, fermer l'ancienne
                    if (listenConn != null) {
                        LOG.warning("startListening: stale connection detected, closing old");
                        SqlConnection old = listenConn;
                        listenConn = conn;
                        old.close();
                    } else {
                        listenConn = conn;
                    }
                }
                // LISTEN requires PgConnection for notificationHandler
                if (conn instanceof PgConnection pgConn) {
                    pgConn.notificationHandler(notification -> {
                        synchronized (lock) {
                            if (stopped) return;
                        }
                        LOG.fine(() -> "Received NOTIFY: " + notification.getChannel());
                        handleConfigChanged();
                    });
                }
                conn.query("LISTEN csp_config_changed")
                    .execute()
                    .onSuccess(v -> {
                        synchronized (lock) {
                            connectInProgress = false;
                        }
                        LOG.info("Listening on channel: csp_config_changed");
                        promise.complete();
                    })
                    .onFailure(err -> {
                        synchronized (lock) {
                            connectInProgress = false;
                            if (listenConn == conn) listenConn = null;
                        }
                        LOG.log(Level.WARNING, "Failed to LISTEN", err);
                        conn.close();
                        promise.fail(err);
                    });

                // Gestion de la deconnexion
                conn.closeHandler(v -> {
                    boolean shouldReconnect;
                    synchronized (lock) {
                        if (listenConn == conn) listenConn = null;
                        shouldReconnect = !stopped;
                    }
                    if (shouldReconnect) {
                        LOG.warning("PG listen connection closed — scheduling reconnect");
                        scheduleReconnect();
                    }
                });
                conn.exceptionHandler(err -> {
                    LOG.log(Level.WARNING, "PG listen connection error", err);
                });
            })
            .onFailure(err -> {
                synchronized (lock) {
                    connectInProgress = false;
                }
                LOG.log(Level.WARNING, "Failed to acquire PG connection for LISTEN", err);
                promise.fail(err);
            });
        return promise.future();
    }

    // -- Reload logic avec coalescing --

    void handleConfigChanged() {
        boolean shouldStart;
        synchronized (lock) {
            if (stopped) return;
            if (reloadInFlight) {
                reloadDirty = true;
                LOG.fine("Reload in flight — marking dirty for coalescing");
                return;
            }
            reloadInFlight = true;
            reloadDirty = false;
            shouldStart = true;
        }
        if (shouldStart) doReload();
    }

    private void doReload() {
        reloadAndSwap()
            .onSuccess(snapshot -> {
                EventBus eb = vertx.eventBus();
                if (eb != null) {
                    eb.publish(CONFIG_RELOADED_ADDRESS, snapshot.loadedAtMs());
                }
            })
            .onFailure(err -> {
                LOG.log(Level.WARNING, "Config reload failed — keeping current snapshot", err);
            })
            .onComplete(ar -> {
                boolean needAgain;
                synchronized (lock) {
                    if (reloadDirty) {
                        reloadDirty = false;
                        needAgain = true;
                        LOG.fine("Coalescing: dirty flag set — triggering again");
                    } else {
                        reloadInFlight = false;
                        needAgain = false;
                    }
                }
                if (needAgain) {
                    // Rejouer sans recursion directe, via context
                    vertx.runOnContext(v -> doReload());
                }
            });
    }

    /**
     * Recharge tout depuis la DB, valide, et remplace atomiquement le snapshot.
     */
    public Future<ConfigSnapshot> reloadAndSwap() {
        return repository.loadAll()
            .compose(snapshot -> {
                List<String> errors = ConfigValidator.validate(snapshot);
                if (!errors.isEmpty()) {
                    String msg = "Config validation failed after reload: " + String.join("; ", errors);
                    LOG.severe(msg);
                    return Future.failedFuture(new IllegalStateException(msg));
                }
                holder.set(snapshot);
                LOG.info("ConfigSnapshot reloaded and validated successfully");
                return Future.succeededFuture(snapshot);
            });
    }

    // -- Reconnection --

    private void scheduleReconnect() {
        synchronized (lock) {
            if (stopped) return;
        }
        cancelReconnectTimer();

        LOG.info(() -> "Scheduling PG LISTEN reconnect in " + reconnectDelay + " ms");
        long tid = vertx.setTimer(reconnectDelay, id -> {
            synchronized (lock) {
                if (stopped) return;
            }
            startListening()
                .onSuccess(v -> {
                    synchronized (lock) {
                        reconnectDelay = initialReconnectDelayMs; // reset
                    }
            })
                .onFailure(err -> {
                    synchronized (lock) {
                        reconnectDelay = Math.min(reconnectDelay * 2, maxReconnectDelayMs);
                    }
                    scheduleReconnect();
            });
        });
        synchronized (lock) {
            reconnectTimerId = tid;
        }
    }

    // -- Fallback timer --

    private void startFallbackTimer() {
        cancelFallbackTimer();
        long tid = vertx.setPeriodic(fallbackReloadIntervalMs, id -> {
            synchronized (lock) {
                if (stopped) return;
            }
            LOG.fine("Fallback reload timer fired");
            // reuse coalescing path
            handleConfigChanged();
        });
        synchronized (lock) {
            fallbackTimerId = tid;
        }
        LOG.info(() -> "Fallback reload timer started: " + fallbackReloadIntervalMs + " ms");
    }

    private void cancelTimers() {
        cancelReconnectTimer();
        cancelFallbackTimer();
    }

    private void cancelReconnectTimer() {
        long id;
        synchronized (lock) {
            id = reconnectTimerId;
            reconnectTimerId = -1;
        }
        if (id >= 0) {
            vertx.cancelTimer(id);
        }
    }

    private void cancelFallbackTimer() {
        long id;
        synchronized (lock) {
            id = fallbackTimerId;
            fallbackTimerId = -1;
        }
        if (id >= 0) {
            vertx.cancelTimer(id);
        }
    }

    // -- Visible for testing --

    boolean isReloadInFlight() {
        synchronized (lock) { return reloadInFlight; }
    }

    boolean isListening() {
        synchronized (lock) { return listenConn != null; }
    }
}
