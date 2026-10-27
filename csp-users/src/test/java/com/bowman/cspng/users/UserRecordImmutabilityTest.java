package com.bowman.cspng.users;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UserRecordImmutabilityTest {

  @Test
  void defensiveCopyImmutable() {
    Set<String> profiles = new HashSet<>(Set.of("default"));
    Map<String, Set<Integer>> allowed = new HashMap<>();
    allowed.put("default", new HashSet<>(Set.of(100, 200)));
    Set<String> connectors = new HashSet<>(Set.of("conn1"));
    UserRecord rec = new UserRecord("Alice", "Alice", "$1$salt$hash", "a@b.c", true, false, false, false, 1, null, -1,
        profiles, allowed, Map.of(), connectors, 1L, null, null);
    // mutate originals
    profiles.add("other");
    allowed.get("default").add(999);
    connectors.add("conn2");
    assertFalse(rec.profiles().contains("other"));
    assertFalse(rec.allowedServices().get("default").contains(999));
    assertFalse(rec.allowedConnectors().contains("conn2"));
    // try mutate returned
    assertThrows(UnsupportedOperationException.class, () -> rec.profiles().add("x"));
    assertThrows(UnsupportedOperationException.class, () -> rec.allowedConnectors().add("x"));
  }

  @Test
  void originalNamePreservedButLookupCaseInsensitive() throws Exception {
    InMemoryUserDirectory dir = new InMemoryUserDirectory();
    UserRecord rec = new UserRecord("AlIcE", "AlIcE", "$1$salt$hash", true, false, Set.of("default"));
    dir.add(rec);
    var opt = dir.lookup("alice").toCompletionStage().toCompletableFuture().get();
    assertTrue(opt.isPresent());
    assertEquals("AlIcE", opt.get().name());
  }
}
