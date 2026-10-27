package com.bowman.cspng.config;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConfigWatcherTest {

    Vertx vertx;

    @AfterEach
    void close() {
        if (vertx != null) vertx.close();
    }

    static class DummyPool implements Pool {
        @Override public Future<SqlConnection> getConnection() { return Future.failedFuture("dummy no pg"); }
        @Override public void getConnection(Handler<io.vertx.core.AsyncResult<SqlConnection>> h){ h.handle(Future.failedFuture("dummy")); }
        @Override public Query<RowSet<Row>> query(String sql){ throw new UnsupportedOperationException(); }
        @Override public PreparedQuery<RowSet<Row>> preparedQuery(String sql){ throw new UnsupportedOperationException(); }
        @Override public PreparedQuery<RowSet<Row>> preparedQuery(String sql, PrepareOptions o){ throw new UnsupportedOperationException(); }
        @Override public void close(Handler<io.vertx.core.AsyncResult<Void>> h){ h.handle(Future.succeededFuture()); }
        @Override public Future<Void> close(){ return Future.succeededFuture(); }
        @Override public Pool connectHandler(Handler<SqlConnection> h){ return this; }
        @Override public Pool connectionProvider(java.util.function.Function<io.vertx.core.Context, Future<SqlConnection>> p){ return this; }
        @Override public int size(){ return 1; }
    }

    @Test
    void burstIsCoalesced() throws Exception {
        vertx = Vertx.vertx();
        ConfigHolder holder = new ConfigHolder();
        holder.set(ConfigSnapshot.empty());

        AtomicInteger loadCalls = new AtomicInteger();
        ConfigRepository slowRepo = new ConfigRepository(new DummyPool()) {
            @Override public Future<ConfigSnapshot> loadAll() {
                loadCalls.incrementAndGet();
                // simulate slow DB 200ms
                io.vertx.core.Promise<ConfigSnapshot> p = io.vertx.core.Promise.promise();
                vertx.setTimer(200, id -> p.complete(ConfigSnapshot.empty()));
                return p.future();
            }
        };

        ConfigWatcher watcher = new ConfigWatcher(slowRepo, holder, new DummyPool(), vertx, 60_000L);
        // start will fail LISTEN but still schedule fallback; coalescing should still work for handleConfigChanged
        watcher.start().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        loadCalls.set(0);
        // burst 5 triggers rapidly
        for (int i=0;i<5;i++) watcher.handleConfigChanged();
        Thread.sleep(800); // wait for coalesced reloads (200ms each, at most 2)
        int calls = loadCalls.get();
        assertTrue(calls <= 2, "burst should be coalesced, got " + calls);
        watcher.stop().toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test
    void reloadFailedKeepsOldSnapshot() throws Exception {
        vertx = Vertx.vertx();
        ConfigHolder holder = new ConfigHolder();
        ConfigSnapshot initial = ConfigSnapshot.empty();
        holder.set(initial);

        ConfigRepository failingRepo = new ConfigRepository(new DummyPool()) {
            @Override public Future<ConfigSnapshot> loadAll() {
                return Future.failedFuture(new RuntimeException("simulated reload failure"));
            }
        };

        ConfigWatcher watcher = new ConfigWatcher(failingRepo, holder, new DummyPool(), vertx, 60_000L);
        // call reloadAndSwap directly
        try {
            watcher.reloadAndSwap().toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
            fail("should have failed");
        } catch (Exception e) {
            // expected
        }
        // holder must still contain initial
        assertSame(initial, holder.get());
        // via handleConfigChanged (coalesced) should also keep old
        watcher.handleConfigChanged();
        Thread.sleep(300);
        assertSame(initial, holder.get());
        watcher.stop();
    }

    @Test
    void stopCancelsTimersAndConnection() throws Exception {
        vertx = Vertx.vertx();
        ConfigHolder holder = new ConfigHolder();
        holder.set(ConfigSnapshot.empty());
        ConfigRepository repo = new ConfigRepository(new DummyPool()) {
            @Override public Future<ConfigSnapshot> loadAll(){ return Future.succeededFuture(ConfigSnapshot.empty()); }
        };
        ConfigWatcher watcher = new ConfigWatcher(repo, holder, new DummyPool(), vertx, 100L);
        watcher.start().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        // fallback timer should be running; stop must cancel
        watcher.stop().toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
        // after stop, handleConfigChanged should be no-op (not trigger load)
        AtomicInteger c = new AtomicInteger();
        ConfigRepository counting = new ConfigRepository(new DummyPool()){
            @Override public Future<ConfigSnapshot> loadAll(){ c.incrementAndGet(); return Future.succeededFuture(ConfigSnapshot.empty()); }
        };
        ConfigWatcher watcher2 = new ConfigWatcher(counting, holder, new DummyPool(), vertx, 100L);
        watcher2.start().toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
        watcher2.stop().toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
        watcher2.handleConfigChanged();
        Thread.sleep(300);
        assertEquals(0, c.get(), "stopped watcher should not reload");
    }
}
