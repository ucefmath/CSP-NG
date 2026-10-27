package com.bowman.cspng.users;

import com.bowman.cspng.users.crypto.PasswordVerifier;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Annuaire PostgreSQL réactif avec cache Caffeine et single-flight.
 */
public class PostgresUserDirectory implements UserDirectory {

  private static final Logger LOG = Logger.getLogger(PostgresUserDirectory.class.getName());

  private final Pool pool;
  private final Vertx vertx;
  private final Cache<String, Optional<UserRecord>> cache;
  private final Map<String, Future<Optional<UserRecord>>> inFlight = new ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicLong cacheGeneration = new java.util.concurrent.atomic.AtomicLong(0);

  // LISTEN state
  private SqlConnection listenConn;
  private long reconnectTimerId = -1;
  private long reconnectDelay;
  private final long initialReconnectDelayMs;
  private final long maxReconnectDelayMs;
  private boolean stopped = false;
  private boolean connectInProgress = false;
  private final Object listenLock = new Object();

  public PostgresUserDirectory(Pool pool, Vertx vertx) {
    this(pool, vertx, 1000, 1000, 60_000);
  }

  public PostgresUserDirectory(Pool pool, Vertx vertx, int maxSize) {
    this(pool, vertx, maxSize, 1000, 60_000);
  }

  public PostgresUserDirectory(Pool pool, Vertx vertx, int maxSize, long initialReconnectDelayMs, long maxReconnectDelayMs) {
    this.pool = pool;
    this.vertx = vertx;
    this.initialReconnectDelayMs = initialReconnectDelayMs;
    this.maxReconnectDelayMs = maxReconnectDelayMs;
    this.reconnectDelay = initialReconnectDelayMs;
    this.cache = Caffeine.newBuilder()
        .expireAfterWrite(60, TimeUnit.SECONDS)
        .maximumSize(maxSize)
        .build();
  }

  private static String key(String name) {
    return name == null ? null : name.toLowerCase(Locale.ROOT);
  }

  @Override
  public Future<Optional<UserRecord>> lookup(String name) {
    if (name == null) return Future.succeededFuture(Optional.empty());
    String k = key(name);
    Optional<UserRecord> cached = cache.getIfPresent(k);
    if (cached != null) {
      return Future.succeededFuture(cached);
    }
    // single-flight
    Future<Optional<UserRecord>> existing = inFlight.get(k);
    if (existing != null) return existing;

    long genAtStart = cacheGeneration.get();
    Promise<Optional<UserRecord>> promise = Promise.promise();
    Future<Optional<UserRecord>> future = promise.future();
    // putIfAbsent race handling
    Future<Optional<UserRecord>> prev = inFlight.putIfAbsent(k, future);
    if (prev != null) return prev;

    loadFromDb(name)
        .onSuccess(opt -> {
          // Ne pas mettre en cache si génération a changé entre début et fin (stale)
          if (cacheGeneration.get() != genAtStart) {
            LOG.fine("Stale load ignored for user cache (generation changed)");
            inFlight.remove(k, future);
            promise.complete(opt);
            return;
          }
          cache.put(k, opt);
          inFlight.remove(k, future);
          promise.complete(opt);
        })
        .onFailure(err -> {
          LOG.log(Level.WARNING, "Failed to load user", err);
          inFlight.remove(k, future);
          promise.fail(err);
        });
    return future;
  }

  @Override
  public Future<Optional<UserRecord>> lookupFresh(String name) {
    if (name == null) return Future.succeededFuture(Optional.empty());
    String k = key(name);
    return loadFromDb(name).onSuccess(opt -> {
      cache.put(k, opt);
    });
  }

  @Override
  public UserRecord lookupCached(String name) {
    if (name == null) return null;
    Optional<UserRecord> opt = cache.getIfPresent(key(name));
    if (opt == null) return null;
    return opt.orElse(null);
  }

  @Override
  public Future<Boolean> authenticate(String name, String pass) {
    if (name == null || pass == null) return Future.succeededFuture(false);
    return lookup(name).compose(opt -> {
      if (opt.isEmpty()) return Future.succeededFuture(false);
      UserRecord rec = opt.get();
      if (!rec.enabled()) return Future.succeededFuture(false);
      if (!rec.isEnabledEffective(Instant.now())) return Future.succeededFuture(false);
      String hash = rec.passwordHash();
      boolean isBcrypt = hash != null && (hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$"));
      if (isBcrypt) {
        // Offload CPU-intensive bcrypt hors event-loop
        return vertx.executeBlocking(() -> PasswordVerifier.verify(pass, hash), false);
      }
      // MD5 ou autre : rapide, synchrone
      boolean ok = PasswordVerifier.verify(pass, hash);
      return Future.succeededFuture(ok);
    });
  }

  @Override
  public Future<Void> reload() {
    invalidateAll();
    return Future.succeededFuture();
  }

  public void invalidateAll() {
    cacheGeneration.incrementAndGet();
    cache.invalidateAll();
    // Ne pas bloquer : retirer les inFlight stale pour que les prochains lookup ne réutilisent pas un Future obsolète
    inFlight.clear();
    LOG.fine("User cache invalidated (LISTEN notify)");
  }

  @Override
  public Future<Void> start() {
    synchronized (listenLock) {
      stopped = false;
      reconnectDelay = initialReconnectDelayMs;
    }
    Promise<Void> p = Promise.promise();
    startListening()
        .onSuccess(v -> {
          LOG.info("PostgresUserDirectory listening on csp_user_changed");
          p.complete();
        })
        .onFailure(err -> {
          LOG.log(Level.WARNING, "Failed to start LISTEN, will retry", err);
          p.complete(); // still complete, reconnect will retry
          scheduleReconnect();
        });
    return p.future();
  }

  @Override
  public Future<Void> stop() {
    Promise<Void> p = Promise.promise();
    SqlConnection toClose;
    synchronized (listenLock) {
      stopped = true;
      connectInProgress = false;
      toClose = listenConn;
      listenConn = null;
    }
    cancelReconnectTimer();
    if (toClose != null) {
      toClose.close().onComplete(ar -> p.complete());
    } else {
      p.complete();
    }
    return p.future();
  }

  // Visible for tests
  public long cacheSize() {
    return cache.estimatedSize();
  }

  public boolean isListening() {
    synchronized (listenLock) {
      return listenConn != null;
    }
  }

  protected Future<Optional<UserRecord>> loadFromDb(String name) {
    return pool.withTransaction(client ->
        client.preparedQuery(
            "SELECT name, display_name, password_hash, email, enabled, admin, debug, map_excluded, max_connections, ip_mask, allowed_ecm_rate, version, updated_at, valid_from, valid_until FROM csp_user WHERE lower(name)=lower($1)")
            .execute(Tuple.of(name))
            .compose(rows -> {
              Row row = firstOrNull(rows);
              if (row == null) return Future.succeededFuture(Optional.<UserRecord>empty());
              String canonicalName = row.getString("name");
              return client.preparedQuery("SELECT profile FROM csp_user_profile WHERE lower(user_name)=lower($1)")
                  .execute(Tuple.of(canonicalName))
                  .compose(profileRows -> {
                    Set<String> profiles = new HashSet<>();
                    for (Row r : profileRows) profiles.add(r.getString("profile"));
                    return client.preparedQuery("SELECT profile, sid, mode FROM csp_user_service WHERE lower(user_name)=lower($1)")
                        .execute(Tuple.of(canonicalName))
                        .compose(svcRows -> {
                          Map<String, Set<Integer>> allowed = new HashMap<>();
                          Map<String, Set<Integer>> blocked = new HashMap<>();
                          for (Row r : svcRows) {
                            String prof = r.getString("profile");
                            int sid = r.getInteger("sid");
                            int mode = r.getInteger("mode");
                            if (mode == 0) {
                              allowed.computeIfAbsent(prof, k -> new HashSet<>()).add(sid);
                            } else {
                              blocked.computeIfAbsent(prof, k -> new HashSet<>()).add(sid);
                            }
                          }
                          return client.preparedQuery("SELECT connector FROM csp_user_connector WHERE lower(user_name)=lower($1)")
                              .execute(Tuple.of(canonicalName))
                              .map(connRows -> {
                                Set<String> connectors = new HashSet<>();
                                for (Row r : connRows) connectors.add(r.getString("connector"));
                                UserRecord rec = mapUser(row, profiles, allowed, blocked, connectors);
                                return Optional.of(rec);
                              });
                        });
                  });
            })
    );
  }

  private UserRecord mapUser(Row r, Set<String> profiles, Map<String, Set<Integer>> allowed,
                             Map<String, Set<Integer>> blocked, Set<String> connectors) {
    String name = r.getString("name");
    String displayName = r.getString("display_name");
    if (displayName == null) displayName = name;
    String passwordHash = r.getString("password_hash");
    String email = r.getString("email");
    boolean enabled = Boolean.TRUE.equals(r.getBoolean("enabled"));
    boolean admin = Boolean.TRUE.equals(r.getBoolean("admin"));
    boolean debug = Boolean.TRUE.equals(r.getBoolean("debug"));
    boolean mapExcluded = Boolean.TRUE.equals(r.getBoolean("map_excluded"));
    Integer maxConn = r.getInteger("max_connections");
    int maxConnections = maxConn != null ? maxConn : 1;
    String ipMask = r.getString("ip_mask");
    Integer ecmRate = r.getInteger("allowed_ecm_rate");
    int allowedEcmRate = ecmRate != null ? ecmRate : -1;
    Long version = r.getLong("version");
    long ver = version != null ? version : 1L;
    Instant validFrom = r.getOffsetDateTime("valid_from") != null ? r.getOffsetDateTime("valid_from").toInstant() : null;
    Instant validUntil = r.getOffsetDateTime("valid_until") != null ? r.getOffsetDateTime("valid_until").toInstant() : null;
    return new UserRecord(name, displayName, passwordHash, email, enabled, admin, debug, mapExcluded,
        maxConnections, ipMask, allowedEcmRate, profiles, allowed, blocked, connectors, ver, validFrom, validUntil);
  }

  private static Row firstOrNull(io.vertx.sqlclient.RowSet<Row> rows) {
    if (rows == null) return null;
    var it = rows.iterator();
    return it.hasNext() ? it.next() : null;
  }

  // -- LISTEN --
  private Future<Void> startListening() {
    synchronized (listenLock) {
      if (stopped) return Future.failedFuture("stopped");
      if (connectInProgress) return Future.failedFuture("already connecting");
      if (listenConn != null) return Future.succeededFuture();
      connectInProgress = true;
    }
    Promise<Void> promise = Promise.promise();
    pool.getConnection()
        .onSuccess(conn -> {
          synchronized (listenLock) {
            if (stopped) {
              connectInProgress = false;
              conn.close();
              promise.fail("stopped before LISTEN");
              return;
            }
            if (listenConn != null) {
              SqlConnection old = listenConn;
              listenConn = conn;
              old.close();
            } else {
              listenConn = conn;
            }
          }
          if (conn instanceof PgConnection pgConn) {
            pgConn.notificationHandler(n -> {
              synchronized (listenLock) { if (stopped) return; }
              LOG.fine(() -> "NOTIFY csp_user_changed received");
              invalidateAll();
            });
          }
          conn.query("LISTEN csp_user_changed").execute()
              .onSuccess(v -> {
                synchronized (listenLock) { connectInProgress = false; }
                promise.complete();
              })
              .onFailure(err -> {
                synchronized (listenLock) {
                  connectInProgress = false;
                  if (listenConn == conn) listenConn = null;
                }
                conn.close();
                promise.fail(err);
              });
          conn.closeHandler(v -> {
            boolean shouldReconnect;
            synchronized (listenLock) {
              if (listenConn == conn) listenConn = null;
              shouldReconnect = !stopped;
            }
            if (shouldReconnect) {
              LOG.warning("PG listen (user) closed — scheduling reconnect");
              scheduleReconnect();
            }
          });
          conn.exceptionHandler(err -> LOG.log(Level.WARNING, "PG listen (user) error", err));
        })
        .onFailure(err -> {
          synchronized (listenLock) { connectInProgress = false; }
          promise.fail(err);
        });
    return promise.future();
  }

  private void scheduleReconnect() {
    synchronized (listenLock) { if (stopped) return; }
    cancelReconnectTimer();
    long jittered = (long) (reconnectDelay * (0.8 + java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 0.4));
    long tid = vertx.setTimer(jittered, id -> {
      synchronized (listenLock) { if (stopped) return; }
      startListening()
          .onSuccess(v -> {
            synchronized (listenLock) { reconnectDelay = initialReconnectDelayMs; }
          })
          .onFailure(err -> {
            synchronized (listenLock) { reconnectDelay = Math.min(reconnectDelay * 2, maxReconnectDelayMs); }
            scheduleReconnect();
          });
    });
    synchronized (listenLock) { reconnectTimerId = tid; }
  }

  private void cancelReconnectTimer() {
    long id;
    synchronized (listenLock) { id = reconnectTimerId; reconnectTimerId = -1; }
    if (id >= 0) vertx.cancelTimer(id);
  }
}
