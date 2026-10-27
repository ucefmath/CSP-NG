package com.bowman.cspng.users.crypto;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BcryptFailFastTest {

    @Test
    void syncHashOnEventLoopThrows() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            PasswordHasher hasher = new PasswordHasher(4);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            vertx.runOnContext(v -> {
                assertTrue(Context.isOnEventLoopThread());
                try {
                    hasher.hash("TestPass123456");
                } catch (Throwable t) {
                    thrown.set(t);
                } finally {
                    latch.countDown();
                }
            });
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertNotNull(thrown.get());
            assertTrue(thrown.get() instanceof IllegalStateException);
        } finally {
            vertx.close();
        }
    }

    @Test
    void syncVerifyOnEventLoopThrowsForBcrypt() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            PasswordHasher hasher = new PasswordHasher(4);
            // create hash off event-loop
            String hash = hasher.hash("TestPass123456");
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            vertx.runOnContext(v -> {
                assertTrue(Context.isOnEventLoopThread());
                try {
                    PasswordVerifier.verify("TestPass123456", hash);
                } catch (Throwable t) {
                    thrown.set(t);
                } finally {
                    latch.countDown();
                }
            });
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertNotNull(thrown.get());
            assertTrue(thrown.get() instanceof IllegalStateException);
        } finally {
            vertx.close();
        }
    }

    @Test
    void syncVerifyOnEventLoopDoesNotThrowForMd5() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            String hash = MD5Crypt.crypt("pass", "salt1234");
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicReference<Boolean> result = new AtomicReference<>();
            vertx.runOnContext(v -> {
                assertTrue(Context.isOnEventLoopThread());
                try {
                    boolean ok = PasswordVerifier.verify("pass", hash);
                    result.set(ok);
                } catch (Throwable t) {
                    thrown.set(t);
                } finally {
                    latch.countDown();
                }
            });
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertNull(thrown.get(), "MD5 verify should not throw on event-loop");
            assertTrue(result.get());
        } finally {
            vertx.close();
        }
    }

    @Test
    void asyncHashSucceedsOnEventLoop() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            PasswordHasher hasher = new PasswordHasher(4);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> hashRef = new AtomicReference<>();
            vertx.runOnContext(v -> {
                assertTrue(Context.isOnEventLoopThread());
                hasher.hashAsync("TestPass123456", vertx).onComplete(ar -> {
                    if (ar.succeeded()) hashRef.set(ar.result());
                    latch.countDown();
                });
            });
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertNotNull(hashRef.get());
            assertTrue(hashRef.get().startsWith("$2a$") || hashRef.get().startsWith("$2b$"));
        } finally {
            vertx.close();
        }
    }
}
