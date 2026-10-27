package com.bowman.cspng.connectors.cacheex;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Store de chemins amont (C2) — copies défensives, filtrage 8 octets,
 * borne LRU, métadonnées de cycle.
 */
class UpstreamPathStoreTest {

  private static byte[] node(int seed) {
    byte[] n = new byte[8];
    for (int i = 0; i < 8; i++) n[i] = (byte) (seed + i);
    return n;
  }

  @Test
  void noteAndGetRoundTrip() {
    UpstreamPathStore store = new UpstreamPathStore();
    byte[] x = node(0x10);
    store.note(123, List.of(x), 9, true);
    var e = store.get(123);
    assertNotNull(e);
    assertEquals(1, e.nodes().size());
    assertArrayEquals(x, e.nodes().get(0));
    assertEquals(9, e.cycleTimeSec());
    assertTrue(e.nextCwCycle());
    assertNull(store.get(124), "hash inconnu => null");
  }

  @Test
  void invalidNodesFiltered() {
    UpstreamPathStore store = new UpstreamPathStore();
    List<byte[]> mixed = new ArrayList<>();
    mixed.add(node(1));
    mixed.add(null);
    mixed.add(new byte[7]);
    mixed.add(new byte[9]);
    mixed.add(node(2));
    store.note(1, mixed);
    var e = store.get(1);
    assertNotNull(e);
    assertEquals(2, e.nodes().size(), "seuls les node id 8 octets sont gardés");
    assertEquals(0, e.cycleTimeSec(), "overload sans cycle => 0/false");
    assertFalse(e.nextCwCycle());
  }

  @Test
  void emptyOrNullNotStored() {
    UpstreamPathStore store = new UpstreamPathStore();
    store.note(1, null);
    store.note(2, List.of());
    store.note(3, List.of(new byte[7]));
    assertEquals(0, store.size());
    assertTrue(store.pathFor(1).isEmpty());
  }

  @Test
  void defensiveCopies() {
    UpstreamPathStore store = new UpstreamPathStore();
    byte[] x = node(5);
    List<byte[]> in = new ArrayList<>();
    in.add(x);
    store.note(7, in, 3, false);
    x[0] = (byte) 0xFF; // mutation externe après note
    in.clear();
    var e = store.get(7);
    assertNotNull(e);
    assertEquals((byte) 5, e.nodes().get(0)[0], "copie défensive à l'écriture");
    assertEquals(1, e.nodes().size());
  }

  @Test
  void cycleBoundsClamped() {
    UpstreamPathStore store = new UpstreamPathStore();
    store.note(1, List.of(node(1)), 200, true);
    assertEquals(127, store.get(1).cycleTimeSec(), "clamp 0..127");
    store.note(2, List.of(node(1)), -5, false);
    assertEquals(0, store.get(2).cycleTimeSec());
  }

  @Test
  void boundedLru() {
    UpstreamPathStore store = new UpstreamPathStore();
    for (int i = 0; i < UpstreamPathStore.MAX_ENTRIES + 50; i++) {
      store.note(i, List.of(node(i & 0xFF)));
    }
    assertTrue(store.size() <= UpstreamPathStore.MAX_ENTRIES);
    assertNull(store.get(0), "les plus anciens sont évincés");
    assertNotNull(store.get(UpstreamPathStore.MAX_ENTRIES + 49));
  }

  @Test
  void clearEmpties() {
    UpstreamPathStore store = new UpstreamPathStore();
    store.note(1, List.of(node(1)));
    store.clear();
    assertEquals(0, store.size());
  }
}
