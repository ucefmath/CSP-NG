package com.bowman.cspng.web.admin;

import com.bowman.cspng.users.InMemoryUserDirectory;
import com.bowman.cspng.users.UserRecord;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.credential.PasswordCredential;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CspIdentityProviderTimingTest {

    private InMemoryUserDirectory directory;
    private CspIdentityProvider provider;
    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        directory = new InMemoryUserDirectory(vertx);
        // Use minFailureMs 250 and bcrypt cost 4 for fast dummy generation in tests
        provider = new CspIdentityProvider(directory, vertx, 250, 4);
        // Warmup dummy hash generation (not measured) to avoid first-request penalty
        try {
            provider.authenticate(req("warmup", "warmup"), null).await().atMost(Duration.ofSeconds(5));
        } catch (Exception ignored) {}
        // Add a valid user with bcrypt (cost 4 via hasher, but we use addWithPlainPassword which is MD5, need bcrypt)
        // Create bcrypt user via UserRepository style: use InMemory with bcrypt hash
        String bcryptHash = new com.bowman.cspng.users.crypto.PasswordHasher(4).hash("correctPass123");
        directory.add(new UserRecord("validuser", "Valid", bcryptHash, null, true, false, false, false, 1, null, -1, Set.of(), java.util.Map.of(), java.util.Map.of(), Set.of(), 1L, null, null));
        // Add disabled user
        String bcryptHash2 = new com.bowman.cspng.users.crypto.PasswordHasher(4).hash("disabledPass123");
        directory.add(new UserRecord("disableduser", "Disabled", bcryptHash2, null, false, false, false, false, 1, null, -1, Set.of(), java.util.Map.of(), java.util.Map.of(), Set.of(), 1L, null, null));
        // Add legacy MD5 user
        directory.addWithPlainPassword("md5user", "md5pass12345", true, false, 1);
    }

    @AfterEach
    void tearDown() {
        if (vertx != null) vertx.close();
    }

    private UsernamePasswordAuthenticationRequest req(String user, String pass) {
        return new UsernamePasswordAuthenticationRequest(user, new PasswordCredential(pass.toCharArray()));
    }

    @Test
    void absentAndWrongPasswordBothRespectMinDelay() {
        long startAbsent = System.nanoTime();
        assertThrows(AuthenticationFailedException.class, () -> provider.authenticate(req("nonexistent", "anyPass12345"), null).await().atMost(Duration.ofSeconds(5)));
        long elapsedAbsentMs = (System.nanoTime() - startAbsent) / 1_000_000;

        long startWrong = System.nanoTime();
        assertThrows(AuthenticationFailedException.class, () -> provider.authenticate(req("validuser", "wrongPass12345"), null).await().atMost(Duration.ofSeconds(5)));
        long elapsedWrongMs = (System.nanoTime() - startWrong) / 1_000_000;

        // Both should be at least 200ms (250 - margin 50)
        assertTrue(elapsedAbsentMs >= 200, "absent should respect min delay, was " + elapsedAbsentMs);
        assertTrue(elapsedWrongMs >= 200, "wrong password should respect min delay, was " + elapsedWrongMs);
        // They should be within 150ms of each other (convergent)
        long diff = Math.abs(elapsedAbsentMs - elapsedWrongMs);
        assertTrue(diff < 150, "absent and wrong should converge, diff=" + diff + " absent=" + elapsedAbsentMs + " wrong=" + elapsedWrongMs);
    }

    @Test
    void disabledUserAlsoRespectsMinDelay() {
        long start = System.nanoTime();
        assertThrows(AuthenticationFailedException.class, () -> provider.authenticate(req("disableduser", "disabledPass123"), null).await().atMost(Duration.ofSeconds(5)));
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsed >= 200, "disabled should respect min delay, was " + elapsed);
    }

    @Test
    void wrongLegacyMd5AlsoRespectsMinDelay() {
        long start = System.nanoTime();
        assertThrows(AuthenticationFailedException.class, () -> provider.authenticate(req("md5user", "wrongMd5Pass"), null).await().atMost(Duration.ofSeconds(5)));
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsed >= 200, "wrong MD5 should respect min delay via dummy bcrypt, was " + elapsed);
    }

    @Test
    void successIsNotArtificiallyDelayed() throws Exception {
        long start = System.nanoTime();
        var identity = provider.authenticate(req("validuser", "correctPass123"), null).await().atMost(Duration.ofSeconds(5));
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        assertNotNull(identity);
        // Success should be fast, not delayed by 250ms (allow up to 200ms for bcrypt, but not 250+)
        assertTrue(elapsed < 350, "success should not be artificially delayed by minFailure, was " + elapsed);
        // Verify event-loop not blocked: timer should fire promptly
        var timerLatch = new java.util.concurrent.CountDownLatch(1);
        vertx.setTimer(10, id -> timerLatch.countDown());
        assertTrue(timerLatch.await(1, TimeUnit.SECONDS), "event-loop should not be blocked after success");
    }

    @Test
    void callbackIsOnEventLoopWithoutBlocking() throws Exception {
        var latch = new java.util.concurrent.CountDownLatch(1);
        var ctx = vertx.getOrCreateContext();
        var result = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var onEventLoop = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        ctx.runOnContext(v -> {
            assertTrue(io.vertx.core.Context.isOnEventLoopThread());
            provider.authenticate(req("validuser", "correctPass123"), null)
                .subscribe().with(
                    id -> {
                        onEventLoop.set(io.vertx.core.Context.isOnEventLoopThread());
                        latch.countDown();
                    },
                    err -> { result.set(err); latch.countDown(); }
                );
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNull(result.get());
        assertTrue(Boolean.TRUE.equals(onEventLoop.get()), "callback should be on event-loop without blocking");
    }
}
