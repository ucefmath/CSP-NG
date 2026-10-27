package com.bowman.cspng.app;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.config.*;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.*;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Preuve du fail-fast synchrone : l'observer StartupEvent doit lever
 * synchronement quand PG est actif et le snapshot echoue, et doit avoir
 * deja publie le snapshot dans le holder avant retour quand succes.
 */
class CspBootstrapFailFastTest {

  Vertx vertxCreated;

  @AfterEach
  void closeVertx() {
    if (vertxCreated != null) {
      vertxCreated.close();
      vertxCreated = null;
    }
  }

  // -- Helpers --

  static class DummyPool implements Pool {
    @Override public Future<SqlConnection> getConnection() { return Future.failedFuture("dummy"); }
    @Override public void getConnection(Handler<io.vertx.core.AsyncResult<SqlConnection>> handler) { handler.handle(Future.failedFuture("dummy")); }
    @Override public Query<RowSet<Row>> query(String sql) { throw new UnsupportedOperationException(); }
    @Override public PreparedQuery<RowSet<Row>> preparedQuery(String sql) { throw new UnsupportedOperationException(); }
    @Override public PreparedQuery<RowSet<Row>> preparedQuery(String sql, PrepareOptions opts) { throw new UnsupportedOperationException(); }
    @Override public void close(Handler<io.vertx.core.AsyncResult<Void>> handler) { handler.handle(Future.succeededFuture()); }
    @Override public Future<Void> close() { return Future.succeededFuture(); }
    @Override public Pool connectHandler(Handler<SqlConnection> handler) { return this; }
    @Override public Pool connectionProvider(java.util.function.Function<io.vertx.core.Context, Future<SqlConnection>> provider) { return this; }
    @Override public int size() { return 1; }
  }

  static class FakeInstance implements Instance<Pool> {
    private final Pool pool;
    FakeInstance(Pool p) { this.pool = p; }
    @Override public boolean isUnsatisfied() { return pool == null; }
    @Override public boolean isAmbiguous() { return false; }
    @Override public Pool get() { return pool; }
    @Override public Iterator<Pool> iterator() { return pool == null ? Collections.emptyIterator() : List.of(pool).iterator(); }
    @Override public Instance<Pool> select(Annotation... qualifiers) { return this; }
    @Override public <U extends Pool> Instance<U> select(Class<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
    @Override public <U extends Pool> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
    @Override public void destroy(Pool instance) {}
    @Override public Handle<Pool> getHandle() { throw new UnsupportedOperationException(); }
    @Override public Iterable<? extends Handle<Pool>> handles() { return Collections.emptyList(); }
  }

  private Vertx newVertx() {
    vertxCreated = Vertx.vertx();
    return vertxCreated;
  }

  // -- Tests --

  @Test
  void pgDisabled_startupPasses_holderNotInitialized() {
    ConfigHolder holder = new ConfigHolder();
    Vertx vertx = newVertx();
    CspBootstrap bootstrap = new CspBootstrap(vertx, holder);
    bootstrap.pgEnabled = false;
    bootstrap.quarkusProfile = "prod";
    bootstrap.pgPoolInstance = new FakeInstance(new DummyPool());

    assertDoesNotThrow(() -> bootstrap.onStart(null));
    assertFalse(holder.isInitialized(), "holder must remain uninitialized when pg disabled");
  }

  @Test
  void pgEnabled_loadFailure_throwsSynchronously_prodProfile() {
    ConfigHolder holder = new ConfigHolder();
    Vertx vertx = newVertx();

    CspBootstrap bootstrap = new CspBootstrap(vertx, holder) {
      @Override protected ConfigRepository createRepository(Pool pool) {
        return new ConfigRepository(pool) {
          @Override public Future<ConfigSnapshot> loadAll() {
            return Future.failedFuture(new RuntimeException("simulated PG failure"));
          }
        };
      }
      @Override protected ConfigWatcher createWatcher(ConfigRepository repo, ConfigHolder h, Pool pool, Vertx v) {
        // not reached when load fails, but stub anyway
        return new ConfigWatcher(repo, h, pool, v, 300_000L) {
          @Override public Future<Void> start() { return Future.succeededFuture(); }
        };
      }
    };
    bootstrap.pgEnabled = true;
    bootstrap.quarkusProfile = "prod";
    bootstrap.pgPoolInstance = new FakeInstance(new DummyPool());

    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> bootstrap.onStart(null));
    assertTrue(ex.getMessage().contains("FAIL-FAST"), "must be fail-fast: " + ex.getMessage());
    assertFalse(holder.isInitialized());
  }

  @Test
  void pgEnabled_loadFailure_throwsEvenInTestProfile() {
    ConfigHolder holder = new ConfigHolder();
    Vertx vertx = newVertx();

    CspBootstrap bootstrap = new CspBootstrap(vertx, holder) {
      @Override protected ConfigRepository createRepository(Pool pool) {
        return new ConfigRepository(pool) {
          @Override public Future<ConfigSnapshot> loadAll() {
            return Future.failedFuture(new RuntimeException("simulated PG failure (test)"));
          }
        };
      }
    };
    bootstrap.pgEnabled = true;
    bootstrap.quarkusProfile = "test";
    bootstrap.pgPoolInstance = new FakeInstance(new DummyPool());

    assertThrows(IllegalStateException.class, () -> bootstrap.onStart(null));
    assertFalse(holder.isInitialized());
  }

  @Test
  void pgEnabled_loadSuccess_holderContainsSnapshotBeforeReturn() {
    ConfigSnapshot expected = ConfigSnapshot.empty();
    ConfigHolder holder = new ConfigHolder();
    Vertx vertx = newVertx();

    // watcher mock that succeeds instantly
    CspBootstrap bootstrap = new CspBootstrap(vertx, holder) {
      @Override protected ConfigRepository createRepository(Pool pool) {
        return new ConfigRepository(pool) {
          @Override public Future<ConfigSnapshot> loadAll() {
            return Future.succeededFuture(expected);
          }
        };
      }
      @Override protected ConfigWatcher createWatcher(ConfigRepository repo, ConfigHolder h, Pool pool, Vertx v) {
        // verify that holder is already set when watcher is created is not possible here,
        // but we can return a mock watcher that succeeds
        return new ConfigWatcher(repo, h, pool, v, 300_000L) {
          @Override public Future<Void> start() { return Future.succeededFuture(); }
        };
      }
    };
    bootstrap.pgEnabled = true;
    bootstrap.quarkusProfile = "prod";
    bootstrap.pgPoolInstance = new FakeInstance(new DummyPool());

    assertDoesNotThrow(() -> bootstrap.onStart(null));
    assertTrue(holder.isInitialized(), "holder must be initialized after successful load");
    ConfigSnapshot actual = holder.get();
    assertNotNull(actual);
    assertEquals(expected.loadedAtMs(), actual.loadedAtMs());
    assertEquals(expected.root().proxyName(), actual.root().proxyName());
  }

  @Test
  void pgEnabled_validationFailure_throwsSynchronously() {
    // Snapshot invalide : connecteur reference un profile inexistant
    ConfigSnapshot invalid = new ConfigSnapshot(
        RootConfig.DEFAULT,
        LoggingConfig.DEFAULT,
        CaProfilesGlobalConfig.DEFAULT,
        List.of(), // no profiles
        List.of(), List.of(),
        ConnectionManagerConfig.DEFAULT,
        List.of(),
        List.of(new ConnectorConfig("conn1", "missing-profile", "newcamd", true, false, "host", 1234,
            "user", null, null, 5000, 10000, 60, 1000, 100, 1024, null, 5000, false, null, null, 0, null, null, null, null, 5, 10, 1L)),
        List.of(),
        CacheConfig.DEFAULT,
        ClusterUdpConfig.DEFAULT,
        WebUiConfig.DEFAULT,
        List.of(), List.of(),
        System.currentTimeMillis()
    );
    ConfigHolder holder = new ConfigHolder();
    Vertx vertx = newVertx();
    CspBootstrap bootstrap = new CspBootstrap(vertx, holder) {
      @Override protected ConfigRepository createRepository(Pool pool) {
        return new ConfigRepository(pool) {
          @Override public Future<ConfigSnapshot> loadAll() { return Future.succeededFuture(invalid); }
        };
      }
    };
    bootstrap.pgEnabled = true;
    bootstrap.quarkusProfile = "prod";
    bootstrap.pgPoolInstance = new FakeInstance(new DummyPool());

    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> bootstrap.onStart(null));
    assertTrue(ex.getMessage().contains("FAIL-FAST") || ex.getMessage().contains("validation"));
  }

  @Test
  void pgEnabled_noPoolAvailable_testProfile_skipsGracefully() {
    ConfigHolder holder = new ConfigHolder();
    Vertx vertx = newVertx();
    CspBootstrap bootstrap = new CspBootstrap(vertx, holder);
    bootstrap.pgEnabled = true;
    bootstrap.quarkusProfile = "test";
    bootstrap.pgPoolInstance = new FakeInstance(null); // unsatisfied

    assertDoesNotThrow(() -> bootstrap.onStart(null));
    assertFalse(holder.isInitialized());
  }
}
