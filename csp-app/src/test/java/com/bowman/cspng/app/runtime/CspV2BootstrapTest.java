package com.bowman.cspng.app.runtime;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class CspV2BootstrapTest {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (vertx != null) vertx.close().onComplete(ctx.succeedingThenComplete());
    }

    // -- waitForStart: timeout → cleanup on late completion exactly1 --

    @Test void waitForStartTimeoutCleanupCalledExactlyOnce() throws Exception {
        Promise<String> promise = Promise.promise();
        AtomicInteger cleanupCount = new AtomicInteger();

        // Start a thread that calls waitForStart with a very short timeout
        Thread waiter = new Thread(() -> {
            try {
                CspV2Bootstrap.waitForStart(promise.future(), 50, TimeUnit.MILLISECONDS,
                        v -> cleanupCount.incrementAndGet());
                fail("Expected TimeoutException");
            } catch (TimeoutException e) {
                // expected
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Unexpected interrupt");
            }
        });
        waiter.start();
        waiter.join(5000);
        assertFalse(waiter.isAlive());

        // Cleanup should NOT have been called yet (no late completion)
        assertEquals(0, cleanupCount.get());

        // Now complete the promise late (simulates manager started after timeout)
        vertx.runOnContext(v -> promise.complete("late-result"));
        Thread.sleep(200); // let the callback fire

        // Cleanup should have been called exactly once
        assertEquals(1, cleanupCount.get());
    }

    // -- waitForStart: success → no cleanup --

    @Test void waitForStartSuccessNoCleanup() throws Exception {
        Promise<String> promise = Promise.promise();
        AtomicInteger cleanupCount = new AtomicInteger();

        // Complete before timeout
        vertx.runOnContext(v -> promise.complete("ok"));

        String result = CspV2Bootstrap.waitForStart(promise.future(), 5, TimeUnit.SECONDS,
                v -> cleanupCount.incrementAndGet());

        assertEquals("ok", result);
        assertEquals(0, cleanupCount.get());
    }

    // -- waitForStart: failed future → no cleanup (propagates) --

    @Test void waitForStartFailedFuturePropagates() {
        Promise<String> promise = Promise.promise();
        AtomicInteger cleanupCount = new AtomicInteger();

        vertx.runOnContext(v -> promise.fail("error"));

        try {
            CspV2Bootstrap.waitForStart(promise.future(), 5, TimeUnit.SECONDS,
                    v -> cleanupCount.incrementAndGet());
            fail("Expected exception");
        } catch (Exception e) {
            // failed future → ExecutionException wrapping the cause
            assertTrue(e.getMessage().contains("error") || e.getCause() != null);
        }
        assertEquals(0, cleanupCount.get());
    }

    // -- waitForStart: interrupted → cleanup on late completion --

    @Test void waitForStartInterruptedCleanupOnLateCompletion(VertxTestContext ctx) throws Exception {
        Promise<String> promise = Promise.promise();
        AtomicInteger cleanupCount = new AtomicInteger();

        Thread waiter = new Thread(() -> {
            try {
                CspV2Bootstrap.waitForStart(promise.future(), 5000, TimeUnit.MILLISECONDS,
                        v -> cleanupCount.incrementAndGet());
                fail("Expected InterruptedException");
            } catch (InterruptedException e) {
                // expected
            } catch (TimeoutException e) {
                fail("Unexpected TimeoutException");
            }
        });
        waiter.start();
        Thread.sleep(100);
        waiter.interrupt();
        waiter.join(5000);
        assertFalse(waiter.isAlive());

        assertEquals(0, cleanupCount.get());

        // Late completion after interrupt
        vertx.runOnContext(v -> promise.complete("late"));
        Thread.sleep(200);

        assertEquals(1, cleanupCount.get());
        ctx.completeNow();
    }
}
