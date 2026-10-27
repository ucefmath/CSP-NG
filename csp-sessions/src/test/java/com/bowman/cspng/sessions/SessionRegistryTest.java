package com.bowman.cspng.sessions;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.users.UserRecord;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

class SessionRegistryTest {

  @Test
  void emptyRegistryHasZeroSize() {
    Vertx vertx = Vertx.vertx();
    SessionRegistry reg = new SessionRegistry(vertx);
    assertEquals(0, reg.size());
    vertx.close();
  }

  @Test
  void addUserAndAuthenticate() throws Exception {
    Vertx vertx = Vertx.vertx();
    SessionRegistry reg = new SessionRegistry(vertx);

    reg.addUser("testuser", "testpass", 5);
    // Need to warm cache: lookup to populate lookupCached (InMemory does, but Postgres needs lookup first)
    // authenticate will do lookup internally
    assertTrue(reg.authenticate("testuser", "testpass").toCompletionStage().toCompletableFuture().get());
    assertFalse(reg.authenticate("testuser", "wrongpass").toCompletionStage().toCompletableFuture().get());
    assertFalse(reg.authenticate("unknown", "testpass").toCompletionStage().toCompletableFuture().get());
    // Wait for cache population for getMaxConnections (authenticate already populated via lookup)
    reg.authenticate("testuser", "testpass").toCompletionStage().toCompletableFuture().get();
    // InMemory lookupCached should be available after add
    assertEquals(5, reg.getMaxConnections("testuser"));
    assertEquals(1, reg.getMaxConnections("unknown"));

    vertx.close();
  }

  @Test
  void userCounts() {
    Vertx vertx = Vertx.vertx();
    SessionRegistry reg = new SessionRegistry(vertx);
    reg.addUser("user1", "pass1", 2);
    reg.addUser("user2", "pass2", 3);
    assertEquals(0, reg.countByUser("user1"));
    // isKnownUser via lookupCached (InMemory immediate)
    assertTrue(reg.isKnownUser("user1"));
    vertx.close();
  }

  @Test
  void noPlainStore() {
    Vertx vertx = Vertx.vertx();
    SessionRegistry reg = new SessionRegistry(vertx);
    reg.addUser("a", "secret", 1);
    var rec = reg.userDirectory().lookupCached("a");
    assertNotNull(rec);
    // passwordHash must not equal plain
    assertNotEquals("secret", rec.passwordHash());
    assertTrue(rec.passwordHash().startsWith("$1$"));
    vertx.close();
  }

  @Test
  void anonymizeIpv4() {
    assertEquals("192.168.1.0", SessionSnapshot.anonymizeAddress("192.168.1.100"));
    assertEquals("10.0.0.0", SessionSnapshot.anonymizeAddress("10.0.0.1"));
  }

  @Test
  void anonymizeIpv6() {
    String anon = SessionSnapshot.anonymizeAddress("2001:db8:abcd:0012:1234:5678:9abc:def0");
    assertTrue(anon.contains("2001"), "should keep prefix");
    assertTrue(anon.endsWith("::"), "should end with :: masked");
    assertFalse(anon.contains("5678"), "should not contain suffix");
  }

  @Test
  void anonymizeMalformedReturnsMasked() {
    assertEquals("***", SessionSnapshot.anonymizeAddress("not-an-ip"));
    assertEquals("***", SessionSnapshot.anonymizeAddress(null));
    assertEquals("***", SessionSnapshot.anonymizeAddress(""));
  }

  @Test
  void snapshotSessionsImmutabilityAndAnonymized() {
    Vertx vertx = Vertx.vertx();
    com.bowman.cspng.cache.CacheVerticle cv = new com.bowman.cspng.cache.CacheVerticle();
    SessionRegistry reg = new SessionRegistry(vertx);
    // create fake session via anonymous subclass
    AbstractSession s = new AbstractSession(vertx, cv, reg) {
      @Override public void close() {}
    };
    s.setSessionId("session-1");
    s.username = "adminUser";
    s.remoteAddress = "192.168.1.55";
    s.authenticated = true;
    s.alive = true;
    s.setProfileName("prof1");
    reg.register(s);
    var list = reg.snapshotSessions();
    assertEquals(1, list.size());
    SessionSnapshot snap = list.get(0);
    assertEquals("192.168.1.0", snap.remoteAddress());
    assertEquals("adminUser", snap.username());
    // immutability
    assertThrows(UnsupportedOperationException.class, () -> list.add(snap));
    // ensure record components do not expose sensitive names
    var comps = SessionSnapshot.class.getRecordComponents();
    for (var rc : comps) {
      String n = rc.getName().toLowerCase();
      assertFalse(n.contains("password"));
      assertFalse(n.contains("token"));
      assertFalse(n.contains("deskey"));
      assertFalse(n.contains("secret"));
    }
    String str = snap.toString().toLowerCase();
    assertFalse(str.contains("password"));
    vertx.close();
  }

  @Test
  void snapshotSessionsSortedDeterministically() {
    Vertx vertx = Vertx.vertx();
    com.bowman.cspng.cache.CacheVerticle cv = new com.bowman.cspng.cache.CacheVerticle();
    SessionRegistry reg = new SessionRegistry(vertx);
    for (int i=2;i>=0;i--) {
      AbstractSession s = new AbstractSession(vertx, cv, reg) { @Override public void close() {} };
      s.username = "u"+i;
      s.remoteAddress = "10.0.0."+i;
      reg.register(s);
    }
    var list = reg.snapshotSessions();
    assertEquals(3, list.size());
    assertTrue(list.get(0).id().compareTo(list.get(1).id()) < 0);
    assertTrue(list.get(1).id().compareTo(list.get(2).id()) < 0);
    vertx.close();
  }

  @Test
  void anonymizeWithPortAndBrackets() {
    assertEquals("192.168.1.0", SessionSnapshot.anonymizeAddress("192.168.1.100:8080"));
    assertEquals("10.0.0.0", SessionSnapshot.anonymizeAddress("10.0.0.5:12345"));
    String anon6 = SessionSnapshot.anonymizeAddress("[2001:db8:abcd:0012::1]:443");
    assertTrue(anon6.contains("2001"), "bracketed IPv6 keep prefix");
    assertTrue(anon6.endsWith("::"));
    assertFalse(anon6.contains(":" + "443"));
  }

  @Test
  void anonymizeHostnameWithoutDns() {
    // hostname should not be resolved via DNS, must return ***
    assertEquals("***", SessionSnapshot.anonymizeAddress("example.com"));
    assertEquals("***", SessionSnapshot.anonymizeAddress("example.com:8080"));
    assertEquals("***", SessionSnapshot.anonymizeAddress("my-hostname"));
    assertEquals("***", SessionSnapshot.anonymizeAddress("hostname123"));
    assertEquals("***", SessionSnapshot.anonymizeAddress("fe80::1%lo0")); // zone id rejected
    // ensure no DNS lookup delay: should be fast
    long start = System.nanoTime();
    assertEquals("***", SessionSnapshot.anonymizeAddress("nonexistent.invalid-hostname-xyz"));
    long elapsed = (System.nanoTime() - start) / 1_000_000;
    assertTrue(elapsed < 200, "should not do DNS lookup, elapsed " + elapsed + "ms");
  }

  @Test
  void snapshotCountsExact() {
    Vertx vertx = Vertx.vertx();
    com.bowman.cspng.cache.CacheVerticle cv = new com.bowman.cspng.cache.CacheVerticle();
    SessionRegistry reg = new SessionRegistry(vertx);
    AbstractSession s1 = new AbstractSession(vertx, cv, reg) { @Override public void close() {} };
    s1.username = "Alice"; s1.remoteAddress = "10.0.0.1"; reg.register(s1);
    AbstractSession s2 = new AbstractSession(vertx, cv, reg) { @Override public void close() {} };
    s2.username = "alice"; s2.remoteAddress = "10.0.0.2"; reg.register(s2);
    AbstractSession s3 = new AbstractSession(vertx, cv, reg) { @Override public void close() {} };
    s3.username = "Bob"; s3.remoteAddress = "10.0.0.3"; reg.register(s3);
    var counts = reg.snapshotCounts();
    assertEquals(3, counts.total());
    // byUser is lowercase keyed
    assertEquals(2, counts.byUserSnapshot().get("alice"));
    assertEquals(1, counts.byUserSnapshot().get("bob"));
    assertEquals(2, counts.byUserSnapshot().size());
    assertThrows(UnsupportedOperationException.class, () -> counts.byUserSnapshot().put("x", 1));
    vertx.close();
  }
}
