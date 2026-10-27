package com.bowman.cspng.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bowman.cspng.users.crypto.MD5Crypt;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryUserDirectoryTest {

  @Test
  void lookupReturnsAddedUser() throws Exception {
    InMemoryUserDirectory dir = new InMemoryUserDirectory();
    UserRecord u = new UserRecord("alice", "Alice", "hash", true, false, Set.of("default"));
    dir.add(u);
    var opt = dir.lookup("alice").toCompletionStage().toCompletableFuture().get();
    assertTrue(opt.isPresent());
    assertEquals("Alice", opt.get().displayName());
    // case-insensitive
    var opt2 = dir.lookup("ALICE").toCompletionStage().toCompletableFuture().get();
    assertTrue(opt2.isPresent());
    assertEquals("alice", opt2.get().name()); // original case preserved
  }

  @Test
  void lookupCachedNoIO() {
    InMemoryUserDirectory dir = new InMemoryUserDirectory();
    UserRecord u = new UserRecord("Bob", "Bob", MD5Crypt.crypt("secret", "salty123"), true, false, Set.of("default"), 2);
    dir.add(u);
    assertNotNull(dir.lookupCached("bob"));
    assertNotNull(dir.lookupCached("BOB"));
    assertEquals("Bob", dir.lookupCached("bob").name());
  }

  @Test
  void authenticateOkAndFail() throws Exception {
    InMemoryUserDirectory dir = new InMemoryUserDirectory();
    String hash = MD5Crypt.crypt("pass123", "ab12cd34");
    UserRecord u = new UserRecord("alice", "Alice", hash, true, false, Set.of("default"));
    dir.add(u);
    assertTrue(dir.authenticate("alice", "pass123").toCompletionStage().toCompletableFuture().get());
    assertFalse(dir.authenticate("alice", "wrong").toCompletionStage().toCompletableFuture().get());
    assertFalse(dir.authenticate("ALICE", "wrong").toCompletionStage().toCompletableFuture().get());
  }

  @Test
  void disabledOrExpiredRefuses() throws Exception {
    InMemoryUserDirectory dir = new InMemoryUserDirectory();
    String hash = MD5Crypt.crypt("pass123", "salt1234");
    UserRecord disabled = new UserRecord("dis", "Dis", hash, false, false, Set.of("default"));
    dir.add(disabled);
    assertFalse(dir.authenticate("dis", "pass123").toCompletionStage().toCompletableFuture().get());

    UserRecord future = new UserRecord("future", "Future", hash, null, true, false, false, false, 1, null, -1,
        Set.of("default"), Map.of(), Map.of(), Set.of(), 1L, Instant.now().plusSeconds(3600), null);
    dir.add(future);
    assertFalse(dir.authenticate("future", "pass123").toCompletionStage().toCompletableFuture().get());

    UserRecord expired = new UserRecord("expired", "Expired", hash, null, true, false, false, false, 1, null, -1,
        Set.of("default"), Map.of(), Map.of(), Set.of(), 1L, null, Instant.now().minusSeconds(3600));
    dir.add(expired);
    assertFalse(dir.authenticate("expired", "pass123").toCompletionStage().toCompletableFuture().get());
  }

  @Test
  void unsupportedHashRejected() throws Exception {
    InMemoryUserDirectory dir = new InMemoryUserDirectory();
    UserRecord u = new UserRecord("bob", "Bob", "$2a$10$abcdefghijklmnopqrstuv", true, false, Set.of("default"));
    dir.add(u);
    assertFalse(dir.authenticate("bob", "anything").toCompletionStage().toCompletableFuture().get());
  }
}
