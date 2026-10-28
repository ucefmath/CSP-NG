package com.bowman.cspng.cache;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class PendingRegistryTest {

  private Vertx vertx;
  private PendingRegistry registry;
  private static final EcmKey KEY = new EcmKey(0x80, 0x1234, 0x0001, 0x0B00, 0x12345678);

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    registry = new PendingRegistry(vertx);
  }

  @AfterEach
  void tearDown() {
    vertx.close();
  }

  @Test
  void addRequestNewKeyReturnsTrue() {
    EcmRequest req = request(KEY, "origin1");
    assertTrue(registry.addRequest(req));
    assertEquals(1, registry.size());
  }

  @Test
  void addRequestExistingKeyReturnsFalse() {
    EcmRequest req1 = request(KEY, "origin1");
    assertTrue(registry.addRequest(req1));
    EcmRequest req2 = request(KEY, "origin2");
    assertFalse(registry.addRequest(req2));
    assertEquals(1, registry.size());
  }

  @Test
  void containsExistingKey() {
    registry.addRequest(request(KEY, "o1"));
    assertTrue(registry.contains(KEY));
  }

  @Test
  void containsMissingKey() {
    assertFalse(registry.contains(KEY));
  }

  // ---- Test 16: Plusieurs attendeurs sur la même clé → tous résolus ensemble

  @Test
  void multipleWaitersOnSameKeyAllResolved() throws Exception {
    registry.addRequest(request(KEY, "origin"));

    CountDownLatch latch = new CountDownLatch(3);
    AtomicInteger replyCount = new AtomicInteger();

    EcmRequest wReq1 = request(KEY, "w1");
    EcmRequest wReq2 = request(KEY, "w2");
    EcmRequest wReq3 = request(KEY, "w3");

    PendingRegistry.Waiter w1 = registry.addWaiter(wReq1, 5000, null,
        r -> { latch.countDown(); },
        r -> { replyCount.incrementAndGet(); latch.countDown(); },
        r -> r);
    PendingRegistry.Waiter w2 = registry.addWaiter(wReq2, 5000, null,
        r -> { latch.countDown(); },
        r -> { replyCount.incrementAndGet(); latch.countDown(); },
        r -> r);
    PendingRegistry.Waiter w3 = registry.addWaiter(wReq3, 5000, null,
        r -> { latch.countDown(); },
        r -> { replyCount.incrementAndGet(); latch.countDown(); },
        r -> r);

    assertNotNull(w1);
    assertNotNull(w2);
    assertNotNull(w3);

    // Fire a reply — all waiters should receive it
    List<PendingRegistry.Waiter> waiters = registry.remove(KEY);
    assertEquals(3, waiters.size());
    for (PendingRegistry.Waiter w : waiters) {
      w.fireReply(new CwReply(KEY, 0x80, cw(), null, null, 0));
    }

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertEquals(3, replyCount.get());
    assertEquals(0, registry.size());
  }

  // ---- Test timeout on waiter

  @Test
  void waiterTimeoutFires() throws Exception {
    registry.addRequest(request(KEY, "origin"));
    CountDownLatch timeoutLatch = new CountDownLatch(1);

    registry.addWaiter(request(KEY, "w1"), 50, null,
        r -> timeoutLatch.countDown(),
        r -> {},
        r -> r);

    assertTrue(timeoutLatch.await(2, TimeUnit.SECONDS));
  }

  // ---- waiter delayAlert fires once

  @Test
  void waiterDelayAlertFiresOnce() throws Exception {
    registry.addRequest(request(KEY, "origin"));
    CountDownLatch alertLatch = new CountDownLatch(1);
    AtomicInteger alertCount = new AtomicInteger();

    Runnable onAlert = () -> alertCount.incrementAndGet();

    PendingRegistry.Waiter w = registry.addWaiter(request(KEY, "w1"), 200, onAlert,
        r -> alertLatch.countDown(),
        r -> {},
        r -> r);

    // Wait for timeout to ensure alert fired
    assertTrue(alertLatch.await(2, TimeUnit.SECONDS));
    assertEquals(1, alertCount.get(), "delayAlert should fire exactly once");
  }

  // ---- waiter addWaiter returns null when entry gone

  @Test
  void addWaiterReturnsNullWhenEntryRemoved() {
    registry.addRequest(request(KEY, "origin"));
    registry.remove(KEY); // remove entry
    PendingRegistry.Waiter w = registry.addWaiter(request(KEY, "w1"), 500, null, null, null, r -> r);
    assertNull(w);
  }

  // ---- Test défensif : maxWaitMs=0 ne doit pas lever IllegalArgumentException

  @Test
  void waiterWithZeroMaxWaitDoesNotThrow() throws Exception {
    registry.addRequest(request(KEY, "origin"));
    CountDownLatch latch = new CountDownLatch(1);

    // maxWaitMs=0 était la cause du bug "Cannot schedule a timer with delay < 1 ms"
    assertDoesNotThrow(() ->
        registry.addWaiter(request(KEY, "w1"), 0L, null,
            r -> latch.countDown(),
            r -> {},
            r -> r)
    );
    assertTrue(latch.await(2, TimeUnit.SECONDS));
  }

  private static EcmRequest request(EcmKey key, String originId) {
    return new EcmRequest(key, null, "default", "remote", originId, System.currentTimeMillis(), null, 500);
  }

  private static byte[] cw() {
    return new byte[]{0x01, 0x02, 0x03, 0x06, 0x04, 0x05, 0x06, 0x0F,
        0x07, 0x08, 0x09, 0x18, 0x0A, 0x0B, 0x0C, 0x21};
  }
}
