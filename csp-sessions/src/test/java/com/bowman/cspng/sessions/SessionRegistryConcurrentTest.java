package com.bowman.cspng.sessions;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.users.InMemoryUserDirectory;
import com.bowman.cspng.users.UserRecord;
import io.vertx.core.Vertx;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SessionRegistryConcurrentTest {

  @Test
  void maxConnectionsNeverExceededConcurrently() throws Exception {
    Vertx vertx = Vertx.vertx();
    InMemoryUserDirectory dir = new InMemoryUserDirectory();
    int max = 3;
    // user with max 3
    dir.add(new UserRecord("u", "u", "$1$salt$hash", true, false, Set.of("default"), max));
    SessionRegistry reg = new SessionRegistry(vertx, dir);
    // But getMaxConnections reads from lookupCached, so need to ensure cache populated (InMemory already)
    int threads = 20;
    ExecutorService exec = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger successes = new AtomicInteger();
    for (int i = 0; i < threads; i++) {
      exec.submit(() -> {
        try {
          start.await();
          // create dummy session
          AbstractSession s = new AbstractSession(vertx, null, reg) {
            @Override public void close() {}
          };
          s.username = "u";
          s.remoteAddress = "127.0.0.1";
          if (reg.tryRegister(s, max)) successes.incrementAndGet();
        } catch (Exception e) { } finally { done.countDown(); }
      });
    }
    start.countDown();
    done.await();
    assertEquals(max, successes.get(), "only max should succeed");
    assertEquals(max, reg.countByUser("u"));
    // unregister one and try again should allow
    // pick one session to unregister
    // need to get a session instance — we don't have refs, but count should be max
    exec.shutdown();
    vertx.close();
  }

  @Test
  void tryRegisterRollbackOnQuotaExceeded() {
    Vertx vertx = Vertx.vertx();
    InMemoryUserDirectory dir = new InMemoryUserDirectory();
    dir.add(new UserRecord("a", "a", "$1$salt$hash", true, false, Set.of("default"), 1));
    SessionRegistry reg = new SessionRegistry(vertx, dir);
    AbstractSession s1 = new AbstractSession(vertx, null, reg) { @Override public void close() {} };
    s1.username = "a"; s1.remoteAddress = "1.1.1.1";
    assertTrue(reg.tryRegister(s1, 1));
    AbstractSession s2 = new AbstractSession(vertx, null, reg) { @Override public void close() {} };
    s2.username = "a"; s2.remoteAddress = "1.1.1.2";
    assertFalse(reg.tryRegister(s2, 1));
    assertEquals(1, reg.countByUser("a"));
    // unregister and retry
    reg.unregister(s1);
    assertEquals(0, reg.countByUser("a"));
    assertTrue(reg.tryRegister(s2, 1));
    vertx.close();
  }

  @Test
  void doubleUnregisterIsIdempotent() {
    Vertx vertx = Vertx.vertx();
    InMemoryUserDirectory dir = new InMemoryUserDirectory();
    dir.add(new UserRecord("b", "b", "$1$salt$hash", true, false, Set.of("default"), 5));
    SessionRegistry reg = new SessionRegistry(vertx, dir);
    AbstractSession s = new AbstractSession(vertx, null, reg) { @Override public void close() {} };
    s.username = "b"; s.remoteAddress = "2.2.2.2";
    assertTrue(reg.tryRegister(s, 5));
    assertEquals(1, reg.countByUser("b"));
    assertEquals(1, reg.countByIp("2.2.2.2"));
    reg.unregister(s);
    assertEquals(0, reg.countByUser("b"));
    assertEquals(0, reg.countByIp("2.2.2.2"));
    // second unregister must not go negative
    reg.unregister(s);
    assertEquals(0, reg.countByUser("b"));
    assertEquals(0, reg.countByIp("2.2.2.2"));
    // third with different instance but same id should not decrement
    AbstractSession fake = new AbstractSession(vertx, null, reg) { @Override public void close() {} };
    fake.username = "b"; fake.remoteAddress = "2.2.2.2";
    fake.setSessionId(s.getSessionId());
    reg.unregister(fake);
    assertEquals(0, reg.countByUser("b"));
    vertx.close();
  }

  @Test
  void caseInsensitiveQuotaShared() {
    Vertx vertx = Vertx.vertx();
    InMemoryUserDirectory dir = new InMemoryUserDirectory();
    dir.add(new UserRecord("Alice", "Alice", "$1$salt$hash", true, false, Set.of("default"), 2));
    SessionRegistry reg = new SessionRegistry(vertx, dir);
    AbstractSession s1 = new AbstractSession(vertx, null, reg) { @Override public void close() {} };
    s1.username = "alice"; s1.remoteAddress = "1.1.1.1";
    AbstractSession s2 = new AbstractSession(vertx, null, reg) { @Override public void close() {} };
    s2.username = "ALICE"; s2.remoteAddress = "1.1.1.2";
    AbstractSession s3 = new AbstractSession(vertx, null, reg) { @Override public void close() {} };
    s3.username = "AlIcE"; s3.remoteAddress = "1.1.1.3";
    assertTrue(reg.tryRegister(s1, 2));
    assertTrue(reg.tryRegister(s2, 2));
    assertFalse(reg.tryRegister(s3, 2), "ALICE/aLiCe doivent partager le même quota que Alice");
    assertEquals(2, reg.countByUser("Alice"));
    assertEquals(2, reg.countByUser("alice"));
    assertEquals(2, reg.countByUser("ALICE"));
    vertx.close();
  }

  @Test
  void concurrentConnectDisconnectNeverExceedsOrLosesQuota() throws Exception {
    Vertx vertx = Vertx.vertx();
    InMemoryUserDirectory dir = new InMemoryUserDirectory();
    int max = 5;
    dir.add(new UserRecord("c", "c", "$1$salt$hash", true, false, Set.of("default"), max));
    SessionRegistry reg = new SessionRegistry(vertx, dir);
    int threads = 30;
    int iterations = 200;
    ExecutorService exec = Executors.newFixedThreadPool(threads);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger overQuota = new AtomicInteger(0);
    AtomicInteger negativeCount = new AtomicInteger(0);
    for (int t = 0; t < threads; t++) {
      exec.submit(() -> {
        try {
          for (int i = 0; i < iterations; i++) {
            AbstractSession s = new AbstractSession(vertx, null, reg) { @Override public void close() {} };
            s.username = "c";
            s.remoteAddress = "3.3.3.3";
            boolean ok = reg.tryRegister(s, max);
            int cur = reg.countByUser("c");
            if (cur > max) overQuota.incrementAndGet();
            if (cur < 0) negativeCount.incrementAndGet();
            if (ok) {
              // simulate work then unregister
              Thread.yield();
              reg.unregister(s);
              int after = reg.countByUser("c");
              if (after < 0) negativeCount.incrementAndGet();
            }
          }
        } finally { done.countDown(); }
      });
    }
    done.await();
    assertEquals(0, overQuota.get(), "quota jamais dépassé");
    assertEquals(0, negativeCount.get(), "compteur jamais négatif");
    assertTrue(reg.countByUser("c") >= 0 && reg.countByUser("c") <= max);
    exec.shutdown();
    vertx.close();
  }
}
