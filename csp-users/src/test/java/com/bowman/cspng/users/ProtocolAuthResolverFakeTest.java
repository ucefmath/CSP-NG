package com.bowman.cspng.users;

import io.vertx.core.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contrats d'auth snapshot P3 sur le FAKE (lease/poll simulés par bumps) :
 * révocation ⇒ rejet immédiat, expiration, rotation ⇒ génération stale,
 * fail-closed sur store indisponible. Aucune valeur de secret ne transite
 * (seule la référence remonte). Les sessions P4/P5 consommeront la même
 * interface (impl PG : lease ≤ 2s / poll ≤ 1s — PgProtocolAuthResolver).
 */
@DisplayName("ProtocolAuthResolver — gates P3 (fake déterministe)")
class ProtocolAuthResolverFakeTest {

  private static final String REF = "file:/run/secrets/alice_cccam";

  private static <T> T await(Future<T> f) {
    AtomicReference<T> ok = new AtomicReference<>();
    AtomicReference<Throwable> ko = new AtomicReference<>();
    f.onComplete(ar -> {
      if (ar.succeeded()) ok.set(ar.result());
      else ko.set(ar.cause());
    });
    try {
      for (int i = 0; i < 100 && ok.get() == null && ko.get() == null; i++) {
        Thread.sleep(10);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (ko.get() != null) {
      throw new AssertionError("future failed: " + ko.get().getMessage(), ko.get());
    }
    assertNotNull(ok.get(), "future should complete");
    return ok.get();
  }

  private static <T> Throwable awaitFail(Future<T> f) {
    AtomicReference<Throwable> ko = new AtomicReference<>();
    f.onComplete(ar -> {
      if (ar.succeeded()) ko.set(new AssertionError("future should fail"));
      else ko.set(ar.cause());
    });
    try {
      for (int i = 0; i < 100 && ko.get() == null; i++) Thread.sleep(10);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return ko.get();
  }

  @Test
  @DisplayName("Auth OK : référence (jamais la valeur) + génération")
  void authenticateEligible() {
    ProtocolAuthResolver.Fake fake = new ProtocolAuthResolver.Fake();
    fake.add("cccam", "alice01", new ProtocolAuthResolver.Fake.Entry(
        true, Instant.now().plusSeconds(3600), REF, 1));

    ProtocolAuthResolver.AuthResult r = await(fake.authenticate("cccam", "alice01"));
    assertEquals("alice01", r.userName());
    assertEquals(REF, r.secretRef(), "la RÉFÉRENCE remonte (résolution côté session)");
    assertEquals(1, r.secretVersion());
    assertTrue(r.userValidUntil().isAfter(Instant.now()));
  }

  @Test
  @DisplayName("Case-exact : le login est comparé exactement")
  void loginIsCaseExact() {
    ProtocolAuthResolver.Fake fake = new ProtocolAuthResolver.Fake();
    fake.add("cccam", "Alice01", new ProtocolAuthResolver.Fake.Entry(
        true, null, REF, 1));

    assertThrows(AssertionError.class,
        () -> await(fake.authenticate("cccam", "alice01")),
        "alice01 ≠ Alice01 (case-exact)");
    ProtocolAuthResolver.AuthResult r = await(fake.authenticate("cccam", "Alice01"));
    assertEquals("Alice01", r.userName());
  }

  @Test
  @DisplayName("Révocation ⇒ génération bump + rejet immédiat (ACL via snapshot)")
  void revocationRejectsImmediately() {
    ProtocolAuthResolver.Fake fake = new ProtocolAuthResolver.Fake();
    fake.add("cs378x", "bob", new ProtocolAuthResolver.Fake.Entry(
        true, null, REF, 1));
    long genBefore = await(fake.currentGeneration());

    fake.revoke("cs378x", "bob");
    long genAfter = await(fake.currentGeneration());
    assertTrue(genAfter > genBefore, "la révocation bump la génération (NOTIFY/poll)");

    Throwable t = awaitFail(fake.authenticate("cs378x", "bob"));
    assertTrue(t.getMessage().contains("revoked"), "rejet : " + t.getMessage());
  }

  @Test
  @DisplayName("Expiration valid_until ⇒ rejet")
  void expiredRejects() {
    ProtocolAuthResolver.Fake fake = new ProtocolAuthResolver.Fake();
    fake.add("cccam", "carol", new ProtocolAuthResolver.Fake.Entry(
        true, Instant.now().plusSeconds(3600), REF, 1));
    await(fake.authenticate("cccam", "carol")); // éligible

    fake.expire("cccam", "carol");
    Throwable t = awaitFail(fake.authenticate("cccam", "carol"));
    assertTrue(t.getMessage().contains("expired"), "rejet : " + t.getMessage());
  }

  @Test
  @DisplayName("Rotation de secret ⇒ génération stale rejetée côté sessions")
  void rotationBumpsGeneration() {
    ProtocolAuthResolver.Fake fake = new ProtocolAuthResolver.Fake();
    fake.add("cccam", "dave", new ProtocolAuthResolver.Fake.Entry(
        true, null, "file:/run/secrets/dave_v1", 1));
    ProtocolAuthResolver.AuthResult before = await(fake.authenticate("cccam", "dave"));
    assertEquals(1, before.secretVersion());

    fake.rotate("cccam", "dave", "file:/run/secrets/dave_v2");
    ProtocolAuthResolver.AuthResult after = await(fake.authenticate("cccam", "dave"));
    assertEquals(2, after.secretVersion(), "nouvelle version de secret après rotation");
    assertEquals("file:/run/secrets/dave_v2", after.secretRef());
    assertTrue(after.epoch() > before.epoch(),
        "l'epoch change — les sessions avec génération stale re-valident ou rejettent");
  }

  @Test
  @DisplayName("Fail-closed : store indisponible ⇒ rejet, jamais fail-open")
  void storeFailureIsFailClosed() {
    ProtocolAuthResolver.Fake fake = new ProtocolAuthResolver.Fake();
    fake.add("cccam", "erin", new ProtocolAuthResolver.Fake.Entry(
        true, null, REF, 1));
    await(fake.authenticate("cccam", "erin"));

    fake.failClosed(true);
    Throwable t1 = awaitFail(fake.authenticate("cccam", "erin"));
    Throwable t2 = awaitFail(fake.currentGeneration());
    assertTrue(t1.getMessage().contains("fail-closed"));
    assertTrue(t2.getMessage().contains("fail-closed"));
  }

  @Test
  @DisplayName("ucrc du repo = CRC32(MD5(name)) — golden python3 (0xe807815d pour \"test\")")
  void ucrcGoldenIndependent() {
    assertEquals(0xe807815dL, UserProtocolSecretRepository.ucrcOf("test"));
    assertEquals(0x1863bc60L, UserProtocolSecretRepository.ucrcOf("csuser"));
  }
}
