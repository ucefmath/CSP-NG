package com.bowman.cspng.users;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PostgresUserDirectoryStaleTest {

  static class ControlledDirectory extends PostgresUserDirectory {
    Promise<Optional<UserRecord>> pending;
    String pendingName;
    ControlledDirectory(Pool pool, Vertx vertx) { super(pool, vertx, 100); }
    @Override
    protected Future<Optional<UserRecord>> loadFromDb(String name) {
      // hijack load for test user "staleUser"
      if ("staleUser".equalsIgnoreCase(name) && pending != null) {
        return pending.future();
      }
      return super.loadFromDb(name);
    }
  }

  @Test
  void staleCacheNotRepopulatedAfterInvalidate() throws Exception {
    Vertx vertx = Vertx.vertx();
    Pool dummyPool = (Pool) java.lang.reflect.Proxy.newProxyInstance(
        Pool.class.getClassLoader(),
        new Class[]{Pool.class},
        (proxy, method, args) -> {
          if ("close".equals(method.getName())) return Future.succeededFuture();
          return Future.failedFuture("not used");
        });
    ControlledDirectory dir = new ControlledDirectory(dummyPool, vertx);
    UserRecord staleRec = new UserRecord("staleUser", "staleUser", "$1$salt$hash", true, false, Set.of("default"));
    UserRecord freshRec = new UserRecord("staleUser", "staleUser", "$1$salt$hash2", true, false, Set.of("extra"));

    Promise<Optional<UserRecord>> p = Promise.promise();
    dir.pending = p;
    // start lookup — captures generation and goes into inFlight
    Future<Optional<UserRecord>> f1 = dir.lookup("staleUser");
    assertNotNull(f1);
    // before completion, invalidate (simulates NOTIFY)
    dir.invalidateAll();
    // cache should be empty
    assertNull(dir.lookupCached("staleUser"));
    // complete stale load
    p.complete(Optional.of(staleRec));
    // wait for f1
    Optional<UserRecord> res = f1.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    assertTrue(res.isPresent());
    assertEquals("staleUser", res.get().name());
    // but cache must NOT contain stale value
    assertNull(dir.lookupCached("staleUser"), "stale load must not repopulate cache after generation bump");

    // next lookup should trigger new load and get fresh value
    Promise<Optional<UserRecord>> p2 = Promise.promise();
    dir.pending = p2;
    Future<Optional<UserRecord>> f2 = dir.lookup("staleUser");
    // f2 is new future, not same as f1
    assertNotSame(f1, f2);
    p2.complete(Optional.of(freshRec));
    Optional<UserRecord> res2 = f2.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    assertEquals("extra", res2.get().profiles().iterator().next());
    // after fresh load, cache should contain fresh
    assertNotNull(dir.lookupCached("staleUser"));
    assertTrue(dir.lookupCached("staleUser").profiles().contains("extra"));

    vertx.close();
  }
}
