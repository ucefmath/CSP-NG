package com.bowman.cspng.filters;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.cache.DcwValidator;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

class DcwFilterTest {

  // ==================== helpers ====================

  static EcmKey k(int sid) { return new EcmKey(0x80, sid, 1, 0x1810, sid * 100 + 12345); }
  static EcmRequest req(String p) { return new EcmRequest(k(1), new byte[]{1, 2, 3}, p, "10.0.0.1", "o1", 0, 0.5, 5000); }
  static EcmRequest req(String p, int sid) { return new EcmRequest(k(sid), new byte[]{1, 2, 3}, p, "10.0.0.1", "o1", 0, 0.5, 5000); }

  static byte[] vcw() { return new byte[]{4, 5, 6, 15, 7, 8, 9, 24, 10, 11, 12, 33, 13, 14, 15, 42}; }
  static byte[] h1z() { return new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 10, 11, 12, 33, 13, 14, 15, 42}; }
  static byte[] h2z() { return new byte[]{4, 5, 6, 15, 7, 8, 9, 24, 0, 0, 0, 0, 0, 0, 0, 0}; }
  static byte[] f5z() { return new byte[]{0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 15, 7, 8, 9, 24}; }
  static byte[] badCk() { return new byte[]{0, 1, 2, 99, 4, 5, 6, 15, 7, 8, 9, 24, 10, 11, 12, 33}; }
  static byte[] cw2() { return new byte[]{1, 1, 1, 3, 1, 1, 1, 3, 1, 1, 1, 3, 1, 1, 1, 3}; }
  static byte[] cw3() { return new byte[]{2, 2, 2, 6, 2, 2, 2, 6, 2, 2, 2, 6, 2, 2, 2, 6}; }
  static byte[] cw4() { return new byte[]{3, 3, 3, 9, 3, 3, 3, 9, 3, 3, 3, 9, 3, 3, 3, 9}; }

  static CwReply r(String c, byte[] cw) { return r(c, cw, 1); }
  static CwReply r(String c, byte[] cw, int sid) { return new CwReply(k(sid), 0x80, cw, c, "127.0.0.1", 0); }
  static CwReply rt(String c, byte[] cw, int sid, int tag) { return new CwReply(k(sid), tag, cw, c, "127.0.0.1", 0); }

  // ==================== FakeScheduler ====================

  static class FS implements FilterContext.FilterScheduler {
    long now;
    final List<Sched> pending = new ArrayList<>();
    record Sched(long fireAt, Runnable task, TH handle) {}
    static class TH implements ScheduledTask { boolean cancelled; @Override public void cancel() { cancelled = true; } }
    @Override public ScheduledTask schedule(long ms, Runnable task) {
      TH h = new TH(); pending.add(new Sched(now + ms, task, h)); return h;
    }
    void advance(long ms) {
      long target = now + ms;
      while (true) {
        Sched next = null; long nf = Long.MAX_VALUE;
        for (var s : pending) { if (!s.handle.cancelled && s.fireAt <= target && s.fireAt < nf) { next = s; nf = s.fireAt; } }
        if (next == null) break;
        now = nf; pending.remove(next);
        if (!next.handle.cancelled) next.task.run();
      }
      now = target;
    }
    int schedCount() { return (int) pending.stream().filter(s -> !s.handle.cancelled).count(); }
  }

  static class FSD implements ServiceDirectory {
    final Set<String> tv = new HashSet<>();
    void add(String p, int s) { tv.add(p + ":" + s); }
    @Override public Optional<ServiceDescriptor> lookup(String p, int s) {
      return tv.contains(p + ":" + s) ? Optional.of(new ServiceDescriptor(p, s, "TV" + s, true)) : Optional.empty();
    }
  }

  static FilterContext ctx(FS sched, long ts, String p, String c) {
    return new FilterContext(req(p), c, p, ts, FilterContext.ReplyRelease.UNSUPPORTED, sched);
  }
  static FilterContext ctx(FS sched, long ts, String p, String c, int sid) {
    return new FilterContext(req(p, sid), c, p, ts, FilterContext.ReplyRelease.UNSUPPORTED, sched);
  }

  static DcwFilterConfig dc() { return new DcwFilterConfig(); }
  static DcwFilterConfig cfg(boolean verify, boolean continuity, boolean shadow, boolean links) {
    return new DcwFilterConfig(true, links, verify, continuity, true, shadow,
        DcwFilterConfig.ContinuityMode.COMPATIBILITY, Set.of(), null, 1000);
  }
  static DcwFilterConfig cfgStrict(boolean continuity, boolean shadow) {
    return new DcwFilterConfig(true, false, false, continuity, true, shadow,
        DcwFilterConfig.ContinuityMode.STRICT, Set.of(), null, 1000);
  }

  // ==================== 1. detectLinks + null SD fail-fast ====================

  @Test void detectLinksNullSdFails() {
    var c = new DcwFilterConfig(true, true, false, false, true, false,
        DcwFilterConfig.ContinuityMode.COMPATIBILITY, Set.of(), null, 1000);
    assertThrows(IllegalArgumentException.class, () -> new DcwFilter(c, null));
  }

  @Test void detectLinksFalseNullSdOk() {
    new DcwFilter(dc(), null); // no detectLinks -> null ok
  }

  // ==================== 2. Basic rules ====================

  @Test void badLengthDropped() {
    var f = new DcwFilter(dc(), null); var s = new FS();
    assertInstanceOf(FilterDecision.Drop.class, f.evaluateReply(ctx(s, 0, "p", "c"), r("c", new byte[]{0, 1})));
    assertEquals(1, f.stats().badLength());
  }

  @Test void badChecksumDropped() {
    var f = new DcwFilter(dc(), null); var s = new FS();
    assertInstanceOf(FilterDecision.Drop.class, f.evaluateReply(ctx(s, 0, "p", "c"), r("c", badCk())));
  }

  @Test void allZeroFiltered() {
    var f = new DcwFilter(dc(), null); var s = new FS();
    assertInstanceOf(FilterDecision.Drop.class, f.evaluateReply(ctx(s, 0, "p", "c"), r("c", new byte[16])));
  }

  @Test void validAccepted() {
    var f = new DcwFilter(dc(), null); var s = new FS();
    assertInstanceOf(FilterDecision.Accept.class, f.evaluateReply(ctx(s, 0, "p", "c"), r("c", vcw())));
    assertEquals(1, f.stats().accepted());
  }

  // ==================== 3. Shadow mode ====================

  @Test void shadowNeverDrops() {
    var f = new DcwFilter(cfg(false, false, true, false), null); var s = new FS();
    var d = f.evaluateReply(ctx(s, 0, "p", "c"), r("c", new byte[]{0, 1}));
    assertInstanceOf(FilterDecision.Accept.class, d);
    assertEquals(1, f.stats().shadowDivergences());
  }

  @Test void shadowNeverRewrites() {
    var f = new DcwFilter(cfgStrict(true, true), null); var s = new FS();
    f.evaluateReply(ctx(s, 0, "p", "c"), rt("c", h1z(), 1, 0x80));
    var d = f.evaluateReply(ctx(s, 50, "p", "c"), rt("c", h2z(), 1, 0x81));
    assertInstanceOf(FilterDecision.Accept.class, d);
    assertEquals(1, f.stats().shadowDivergences());
  }

  @Test void shadowNeverHolds() {
    var f = new DcwFilter(cfg(true, false, true, false), new FSD()); var s = new FS();
    var d = f.evaluateReply(ctx(s, 0, "p", "c"), r("c", vcw()));
    assertInstanceOf(FilterDecision.Accept.class, d);
    assertEquals(0, f.stats().held());
    assertEquals(1, f.stats().shadowDivergences());
  }

  @Test void shadowVerifyFirstHoldsProductionButShadowAccepts() {
    var f = new DcwFilter(cfg(true, false, true, false), new FSD()); var s = new FS();
    var d = f.evaluateReply(ctx(s, 0, "p", "c1"), r("c1", vcw()));
    assertInstanceOf(FilterDecision.Accept.class, d);
    assertEquals(1, f.stats().shadowDivergences());
  }

  @Test void shadowVerifySecondReleaseProductionButShadowAccepts() {
    var f = new DcwFilter(cfg(true, false, true, false), new FSD()); var s = new FS();
    f.evaluateReply(ctx(s, 0, "p", "c1"), r("c1", vcw()));
    var d = f.evaluateReply(ctx(s, 50, "p", "c2"), r("c2", vcw()));
    assertInstanceOf(FilterDecision.Accept.class, d);
    assertEquals(2, f.stats().shadowDivergences()); // one for HOLD, one for RELEASE
  }

  @Test void shadowContinuityMergeStillAcceptsOriginal() {
    var f = new DcwFilter(cfgStrict(true, true), null); var s = new FS();
    f.evaluateReply(ctx(s, 0, "p", "c"), rt("c", h1z(), 1, 0x80));
    var d = f.evaluateReply(ctx(s, 50, "p", "c"), rt("c", h2z(), 1, 0x81));
    assertInstanceOf(FilterDecision.Accept.class, d);
    // original CW should be h2z (not merged)
    byte[] result = ((FilterDecision.Accept) d).reply().cw();
    assertArrayEquals(h2z(), result);
  }

  @Test void shadowModeIncrementsAccept() {
    var f = new DcwFilter(cfg(false, false, true, false), null); var s = new FS();
    f.evaluateReply(ctx(s, 0, "p", "c"), r("c", vcw()));
    f.evaluateReply(ctx(s, 10, "p", "c"), r("c", new byte[16]));
    assertEquals(2, f.stats().accepted());
  }

  // ==================== 4. Force continuity ====================

  @Test void continuityExpiresAt12s() {
    var f = new DcwFilter(cfgStrict(true, false), null); var s = new FS();
    f.evaluateReply(ctx(s, 0, "p", "c"), rt("c", h1z(), 1, 0x80));
    var d = f.evaluateReply(ctx(s, 11999, "p", "c"), rt("c", h2z(), 1, 0x81));
    assertInstanceOf(FilterDecision.Rewrite.class, d);
    assertEquals(1, f.stats().merged());
  }

  @Test void continuityExpiredAt12s() {
    var f = new DcwFilter(cfgStrict(true, false), null); var s = new FS();
    f.evaluateReply(ctx(s, 0, "p", "c"), rt("c", h1z(), 1, 0x80));
    s.advance(12_000); // fire cleanup
    var d = f.evaluateReply(ctx(s, 12_001, "p", "c"), rt("c", h2z(), 1, 0x81));
    assertInstanceOf(FilterDecision.Accept.class, d);
    assertEquals(0, f.stats().merged());
  }

  @Test void continuityCleanupOnlyRemovesSameInstance() {
    var f = new DcwFilter(cfgStrict(true, false), null); var s = new FS();

    // Insert entry A at t=0
    f.evaluateReply(ctx(s, 0, "p", "c"), rt("c", h1z(), 1, 0x80));
    assertEquals(1, s.schedCount());

    // Replace with entry B at t=100 (cancel old cleanup, schedule new one)
    f.evaluateReply(ctx(s, 100, "p", "c"), rt("c", h2z(), 1, 0x80));
    // old cleanup was cancelled, new one exists
    assertTrue(s.schedCount() >= 1);

    // Advance to t=12000: old task (firing at t=12000) was cancelled, does nothing
    // New task fires at t=12100
    s.advance(11_900); // now = 12000
    // Entry should still be in the map (old task cancelled, new task not yet fired)
    s.advance(200);    // now = 12200, new task fires
    // After new task fires, entry should be removed
    // Verify by trying to merge (should not find prev)
    var d = f.evaluateReply(ctx(s, s.now, "p", "c"), rt("c", h2z(), 1, 0x81));
    assertInstanceOf(FilterDecision.Accept.class, d, "cleanup should remove after 12s from insert");
  }

  // ==================== 5. Verify replies ====================

  @Test void verifyExpiresAt20s() {
    var f = new DcwFilter(cfg(true, false, false, false), new FSD()); var s = new FS();
    f.evaluateReply(ctx(s, 0, "p", "c1"), r("c1", vcw()));
    var d = f.evaluateReply(ctx(s, 19999, "p", "c2"), r("c2", vcw()));
    assertInstanceOf(FilterDecision.ReleasePrevious.class, d);
  }

  @Test void verifyExpiredAt20s() {
    var f = new DcwFilter(cfg(true, false, false, false), new FSD()); var s = new FS();
    f.evaluateReply(ctx(s, 0, "p", "c1"), r("c1", vcw()));
    s.advance(20_000); // cleanup fires
    var d = f.evaluateReply(ctx(s, 20_001, "p", "c2"), r("c2", vcw()));
    assertInstanceOf(FilterDecision.Hold.class, d, "after expiry should be new HOLD");
  }

  @Test void verifySameConnectorNoRelease() {
    var f = new DcwFilter(cfg(true, false, false, false), new FSD()); var s = new FS();
    f.evaluateReply(ctx(s, 0, "p", "c1"), r("c1", vcw()));
    var d = f.evaluateReply(ctx(s, 50, "p", "c1"), r("c1", vcw()));
    assertInstanceOf(FilterDecision.Hold.class, d);
  }

  @Test void verifyOldCleanupNotRemoveNewEntry() {
    var f = new DcwFilter(cfg(true, false, false, false), new FSD()); var s = new FS();
    f.evaluateReply(ctx(s, 0, "p", "c1"), r("c1", vcw())); // cleanup at t=20000
    f.evaluateReply(ctx(s, 1000, "p", "c2"), r("c2", vcw())); // cancels old, removes entry
    s.advance(20_000);
    // No exception, stats unchanged
    assertEquals(1, f.stats().verified());
  }

  // ==================== 6. Detect links ====================

  @Test void detectLinksSameProfileOnly() {
    var sd = fs("pa", 1, 2); sd.add("pb", 3);
    var f = new DcwFilter(cfg(false, false, false, true), sd); var s = new FS();
    f.evaluateReply(ctx(s, 0, "pa", "c"), r("c", vcw(), 1));
    f.evaluateReply(ctx(s, 50, "pa", "c"), r("c", vcw(), 2));
    assertEquals(1, f.stats().linksDetected());
    f.evaluateReply(ctx(s, 100, "pb", "c"), r("c", vcw(), 3));
    assertEquals(1, f.stats().linksDetected(), "cross-profile no link");
  }

  @Test void detectLinksTvGate() {
    var sd = new FSD(); sd.add("p", 1);
    var f = new DcwFilter(cfg(false, false, false, true), sd); var s = new FS();
    f.evaluateReply(ctx(s, 0, "p", "c"), r("c", vcw(), 1));
    f.evaluateReply(ctx(s, 50, "p", "c"), r("c", vcw(), 2));
    assertEquals(0, f.stats().linksDetected());
  }

  @Test void detectLinksInvalidationTwoStrikes() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2)); var s = new FS();
    f.evaluateReply(ctx(s, 0, "p", "c"), r("c", vcw(), 1));
    f.evaluateReply(ctx(s, 10, "p", "c"), r("c", vcw(), 2));
    assertEquals(1, f.stats().linksDetected());
    // mismatch 1
    f.evaluateReply(ctx(s, 20, "p", "c"), r("c", cw2(), 1));
    f.evaluateReply(ctx(s, 30, "p", "c"), r("c", cw3(), 2));
    assertEquals(0, f.stats().linksRemoved());
    // mismatch 2 (<20s from first)
    f.evaluateReply(ctx(s, 50, "p", "c"), r("c", cw2(), 1));
    f.evaluateReply(ctx(s, 60, "p", "c"), r("c", cw3(), 2));
    assertEquals(1, f.stats().linksRemoved());
  }

  @Test void detectLinksMismatchAfter20sReset() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2)); var s = new FS();
    f.evaluateReply(ctx(s, 0, "p", "c"), r("c", vcw(), 1));
    f.evaluateReply(ctx(s, 10, "p", "c"), r("c", vcw(), 2));
    // first mismatch
    f.evaluateReply(ctx(s, 20, "p", "c"), r("c", cw2(), 1));
    f.evaluateReply(ctx(s, 30, "p", "c"), r("c", cw3(), 2));
    // advance past 20s
    s.advance(20_100); // now ~20130
    // second mismatch after 20s -> new strike
    f.evaluateReply(ctx(s, s.now, "p", "c"), r("c", cw2(), 1));
    f.evaluateReply(ctx(s, s.now + 10, "p", "c"), r("c", cw3(), 2));
    assertEquals(0, f.stats().linksRemoved(), "strike reset after 20s");
  }

  @Test void detectLinksNoInvalidationIfDeltaOver2s() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2)); var s = new FS();
    f.evaluateReply(ctx(s, 0, "p", "c"), r("c", vcw(), 1));
    f.evaluateReply(ctx(s, 10, "p", "c"), r("c", vcw(), 2));

    // Service 2 gets cw2 at t=100, service 1 gets cw3 at t=2200 (delta>2000)
    f.evaluateReply(ctx(s, 100, "p", "c"), r("c", cw2(), 2));
    f.evaluateReply(ctx(s, 2200, "p", "c"), r("c", cw3(), 1));
    assertEquals(0, f.stats().linksRemoved(), "delta > 2s no invalidation");
  }

  @Test void detectLinksGroup2RemovalNoOrphanKeys() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2)); var s = new FS();
    byte[] cw = vcw();
    f.evaluateReply(ctx(s, 0, "p", "c"), r("c", cw, 1));
    f.evaluateReply(ctx(s, 5, "p", "c"), r("c", cw, 2));
    assertEquals(1, f.stats().linksDetected());
    assertEquals(1, f.linkGroupsSnapshot().size());

    // Invalidate: two strikes on the only pair
    f.evaluateReply(ctx(s, 20, "p", "c"), r("c", cw2(), 1));
    f.evaluateReply(ctx(s, 25, "p", "c"), r("c", cw3(), 2));
    f.evaluateReply(ctx(s, 40, "p", "c"), r("c", cw2(), 1));
    f.evaluateReply(ctx(s, 45, "p", "c"), r("c", cw3(), 2));
    assertEquals(1, f.stats().linksRemoved());

    // Both keys must be gone, no orphan
    assertTrue(f.linkGroupsSnapshot().isEmpty(), "no groups left");
  }

  @Test void detectLinksGroup3RemovalCoherent() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2, 3)); var s = new FS();
    byte[] cw = vcw();
    f.evaluateReply(ctx(s, 0, "p", "c"), r("c", cw, 1));
    f.evaluateReply(ctx(s, 5, "p", "c"), r("c", cw, 2));
    f.evaluateReply(ctx(s, 10, "p", "c"), r("c", cw, 3));
    assertEquals(2, f.stats().linksDetected());
    var snap = f.linkGroupsSnapshot();
    assertEquals(1, snap.size());
    assertEquals(3, snap.iterator().next().size());

    // Two strikes between s1 and s2: remove s2 from the 3-group
    f.evaluateReply(ctx(s, 20, "p", "c"), r("c", cw2(), 1));
    f.evaluateReply(ctx(s, 25, "p", "c"), r("c", cw3(), 2));
    f.evaluateReply(ctx(s, 40, "p", "c"), r("c", cw2(), 1));
    f.evaluateReply(ctx(s, 45, "p", "c"), r("c", cw4(), 2));
    assertTrue(f.stats().linksRemoved() >= 1);

    // Remaining members (1 and 3) share a coherent group
    var snap2 = f.linkGroupsSnapshot();
    assertFalse(snap2.isEmpty(), "group should have remaining members");
    var g = snap2.iterator().next();
    assertEquals(2, g.size(), "exactly 2 remaining");
    assertFalse(g.contains(DcwFilter.sidKey(2, "p")), "service 2 absent");
    assertTrue(g.contains(DcwFilter.sidKey(1, "p")));
    assertTrue(g.contains(DcwFilter.sidKey(3, "p")));
  }

  // ==================== 7. Continuity + detectLinks combo ====================

  @Test void continuityDetectLinksOriginalDigest() {
    var cfg2 = new DcwFilterConfig(true, true, false, true, true, false,
        DcwFilterConfig.ContinuityMode.STRICT, Set.of(), null, 1000);
    var f = new DcwFilter(cfg2, fs("p", 1, 2)); var s = new FS();

    // Service 1: cw1 (non-zero), then service 2: same cw1 = link created
    byte[] cw1orig = vcw();
    f.evaluateReply(ctx(s, 0, "p", "c"), r("c", cw1orig, 1));
    f.evaluateReply(ctx(s, 5, "p", "c"), r("c", cw1orig, 2));
    assertEquals(1, f.stats().linksDetected());

    // Service 1: zero cw (h1z) tagged 0x80, merge happens
    f.evaluateReply(ctx(s, 10, "p", "c"), rt("c", h1z(), 1, 0x80));
    // Service 1: zero cw (h2z) tagged 0x81 -> merge with h1z
    // detectLinks sees the ORIGINAL CW (h2z, not merged)
    var d = f.evaluateReply(ctx(s, 15, "p", "c"), rt("c", h2z(), 1, 0x81));
    assertInstanceOf(FilterDecision.Rewrite.class, d);
    assertEquals(1, f.stats().merged());
    // verify: merged CW differs from original h2z
    byte[] merged = ((FilterDecision.Rewrite) d).reply().cw();
    assertFalse(Arrays.equals(h2z(), merged), "merged should differ from original");
    assertEquals(1, f.stats().linksDetected(), "detectLinks should use original digest");
  }

  // ==================== 8. Stats ====================

  @Test void statsVolatileRead() {
    var f = new DcwFilter(dc(), null);
    assertNotNull(f.stats());
    assertEquals(0, f.stats().accepted());
  }

  // ==================== 9. Byte array non-mutation ====================

  @Test void byteArrayNotMutated() {
    var f = new DcwFilter(cfgStrict(true, false), null); var s = new FS();
    byte[] orig = h1z().clone();
    f.evaluateReply(ctx(s, 0, "p", "c"), rt("c", orig, 1, 0x80));
    assertArrayEquals(h1z(), orig);
  }

  // ==================== 10. Name/order/fan-out ====================

  @Test void nameOrderFanOut() {
    var f = new DcwFilter(dc(), null);
    assertEquals("dcw", f.name()); assertEquals(0, f.order());
    assertFalse(f.requiresMultipleReplies());
    assertTrue(new DcwFilter(cfg(true, false, false, false), new FSD()).requiresMultipleReplies());
  }

  // ==================== 11. Custom bad-dcw ====================

  @Test void customBadFiltered() {
    byte[] cb = vcw().clone(); Set<byte[]> bs = new HashSet<>(); bs.add(cb);
    var c = new DcwFilterConfig(true, false, false, false, true, false,
        DcwFilterConfig.ContinuityMode.COMPATIBILITY, Set.of(), bs, 1000);
    var f = new DcwFilter(c, null); var s = new FS();
    assertInstanceOf(FilterDecision.Drop.class, f.evaluateReply(ctx(s, 0, "p", "c"), r("c", cb)));
  }

  // ==================== 12. Evictions ====================

  @Test void evictionsOnOverflow() {
    var conf = new DcwFilterConfig(true, true, false, false, true, false,
        DcwFilterConfig.ContinuityMode.COMPATIBILITY, Set.of(), null, 100);
    var f = new DcwFilter(conf, fs("p", 1)); var s = new FS();
    for (int i = 0; i < 250; i++) {
      byte a = (byte) ((i + 1) % 127 + 1), b = (byte) ((i + 2) % 127 + 1), c = (byte) ((i + 3) % 127 + 1);
      byte[] cw = {a, b, c, (byte) ((a + b + c) & 0xFF), a, b, c, (byte) ((a + b + c) & 0xFF),
                   a, b, c, (byte) ((a + b + c) & 0xFF), a, b, c, (byte) ((a + b + c) & 0xFF)};
      f.evaluateReply(ctx(s, i, "p", "c"), r("c", cw, (i % 50) + 1));
    }
    assertTrue(f.stats().evictions() > 0);
  }

  // ==================== helpers ====================

  static FSD fs(String profile, int... sids) {
    var sd = new FSD(); for (int s : sids) sd.add(profile, s); return sd;
  }

  // ==================== 13. restoreLinkGroups ====================

  @Test
  void restoreLinkGroupsThrowsIfDetectLinksDisabled() {
    var f = new DcwFilter(dc(), null);
    assertThrows(IllegalStateException.class,
        () -> f.restoreLinkGroups(Set.of()));
  }

  @Test
  void restoreLinkGroupsThrowsIfNull() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2));
    assertThrows(NullPointerException.class,
        () -> f.restoreLinkGroups(null));
  }

  @Test
  void restoreLinkGroupsValidatesTvService() {
    var sd = fs("p", 1, 2);
    sd.add("p", 3); // service 3 is TV
    var f = new DcwFilter(cfg(false, false, false, true), sd);
    // service 4 not in directory
    Set<Set<String>> groups = Set.of(Set.of("1:p", "4:p"));
    f.restoreLinkGroups(groups);
    assertTrue(f.linkGroupsSnapshot().isEmpty(), "non-tv ref should be ignored");
  }

  @Test
  void restoreLinkGroupsCrossProfileRejected() {
    var sd = fs("pa", 1, 2);
    sd.add("pb", 3);
    var f = new DcwFilter(cfg(false, false, false, true), sd);
    Set<Set<String>> groups = Set.of(Set.of("1:pa", "3:pb"));
    f.restoreLinkGroups(groups);
    assertTrue(f.linkGroupsSnapshot().isEmpty(), "cross-profile should be rejected");
  }

  @Test
  void restoreLinkGroupsValidGroupAccepted() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2, 3));
    Set<Set<String>> groups = Set.of(Set.of("1:p", "2:p", "3:p"));
    f.restoreLinkGroups(groups);
    var snap = f.linkGroupsSnapshot();
    assertEquals(1, snap.size());
    assertEquals(3, snap.iterator().next().size());
    assertTrue(f.stats().linksDetected() >= 1);
  }

  @Test
  void restoreLinkGroupsClearsPreviousState() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2, 3));
    // First restore group [1,2]
    f.restoreLinkGroups(Set.of(Set.of("1:p", "2:p")));
    assertEquals(1, f.linkGroupsSnapshot().size());

    // Second restore group [2,3] — replaces completely
    f.restoreLinkGroups(Set.of(Set.of("2:p", "3:p")));
    var snap = f.linkGroupsSnapshot();
    assertEquals(1, snap.size());
    Set<String> g = snap.iterator().next();
    assertEquals(2, g.size());
    assertTrue(g.contains("2:p"));
    assertTrue(g.contains("3:p"));
    assertFalse(g.contains("1:p"));
  }

  @Test
  void restoreLinkGroupsIgnoresSoloGroup() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1));
    Set<Set<String>> groups = Set.of(Set.of("1:p"));
    f.restoreLinkGroups(groups);
    assertTrue(f.linkGroupsSnapshot().isEmpty());
  }

  @Test
  void restoreLinkGroupsSnapshotImmutability() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2));
    f.restoreLinkGroups(Set.of(Set.of("1:p", "2:p")));
    var snap = f.linkGroupsSnapshot();
    assertThrows(UnsupportedOperationException.class, () -> snap.add(Set.of()));
    Set<String> g = snap.iterator().next();
    assertThrows(UnsupportedOperationException.class, () -> g.add("3:p"));
  }

  // ==================== 14. restore + runtime mutation ====================

  @Test
  void restoreThenAddThirdServiceViaDetectLinks() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2, 3));
    var s = new FS();

    // Restore group [1:p, 2:p]
    f.restoreLinkGroups(Set.of(Set.of("1:p", "2:p")));
    assertEquals(1, f.linkGroupsSnapshot().size());

    // Now add service 3 sharing same CW — should be added to the SAME
    // mutable set (no UOE), group grows to 3
    byte[] cw = vcw();
    f.evaluateReply(ctx(s, 0, "p", "c"), r("c", cw, 1));
    f.evaluateReply(ctx(s, 5, "p", "c"), r("c", cw, 2));
    // Service 3 shares same CW — should join the existing group
    f.evaluateReply(ctx(s, 10, "p", "c"), r("c", cw, 3));

    var snap = f.linkGroupsSnapshot();
    assertEquals(1, snap.size());
    assertEquals(3, snap.iterator().next().size(),
        "group should contain all 3 services");
    assertTrue(snap.iterator().next().contains(DcwFilter.sidKey(3, "p")));
  }

  @Test
  void restoreThenInvalidateRemovalCoherent() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2, 3, 4));
    var s = new FS();

    // Restore group [1:p, 2:p, 3:p, 4:p]
    f.restoreLinkGroups(Set.of(Set.of("1:p", "2:p", "3:p", "4:p")));
    assertEquals(1, f.linkGroupsSnapshot().size());
    assertEquals(4, f.linkGroupsSnapshot().iterator().next().size());

    // Invalidate between 1 and 2: two strikes = removal of 2 from group
    f.evaluateReply(ctx(s, 20, "p", "c"), r("c", cw2(), 1));
    f.evaluateReply(ctx(s, 25, "p", "c"), r("c", cw3(), 2));
    f.evaluateReply(ctx(s, 40, "p", "c"), r("c", cw2(), 1));
    f.evaluateReply(ctx(s, 45, "p", "c"), r("c", cw3(), 2));

    // Remaining members [1, 3, 4] still coherent
    var snap = f.linkGroupsSnapshot();
    assertFalse(snap.isEmpty(), "remaining group should exist");
    assertEquals(3, snap.iterator().next().size());
    assertFalse(snap.iterator().next().contains(DcwFilter.sidKey(2, "p")));
  }

  // ==================== 15. linkChangeListener ====================

  @Test
  void listenerCalledOnLinkDetected() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2));
    var s = new FS();
    var called = new AtomicBoolean(false);
    f.setLinkChangeListener(() -> called.set(true));

    f.evaluateReply(ctx(s, 0, "p", "c"), r("c", vcw(), 1));
    assertFalse(called.get(), "no link yet");

    f.evaluateReply(ctx(s, 5, "p", "c"), r("c", vcw(), 2));
    assertTrue(called.get(), "link detected -> listener called");
  }

  @Test
  void listenerCalledOnLinkRemoved() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2));
    var s = new FS();
    // create link
    f.evaluateReply(ctx(s, 0, "p", "c"), r("c", vcw(), 1));
    f.evaluateReply(ctx(s, 5, "p", "c"), r("c", vcw(), 2));

    var called = new AtomicBoolean(false);
    f.setLinkChangeListener(() -> called.set(true));

    // two strikes to remove
    f.evaluateReply(ctx(s, 20, "p", "c"), r("c", cw2(), 1));
    f.evaluateReply(ctx(s, 25, "p", "c"), r("c", cw3(), 2));
    f.evaluateReply(ctx(s, 40, "p", "c"), r("c", cw2(), 1));
    f.evaluateReply(ctx(s, 45, "p", "c"), r("c", cw3(), 2));
    assertTrue(called.get(), "link removed -> listener called");
  }

  @Test
  void listenerCalledOnRestore() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2));
    var called = new AtomicBoolean(false);
    f.setLinkChangeListener(() -> called.set(true));

    f.restoreLinkGroups(Set.of(Set.of("1:p", "2:p")));
    assertTrue(called.get(), "restore -> listener called");
  }

  @Test
  void listenerNotCalledOnNoChange() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2));
    var s = new FS();
    var counter = new int[1];
    f.setLinkChangeListener(() -> counter[0]++);

    // valid ACK, no link (only one sid)
    f.evaluateReply(ctx(s, 0, "p", "c"), r("c", vcw(), 1));
    assertEquals(0, counter[0], "no link group yet");

    // bad length -> drop, no link change
    f.evaluateReply(ctx(s, 10, "p", "c"), r("c", new byte[]{0, 1}));
    assertEquals(0, counter[0], "drop not link change");
  }

  @Test
  void listenerExceptionDoesNotBreakDatapath() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2));
    var s = new FS();
    f.setLinkChangeListener(() -> { throw new RuntimeException("boom"); });

    // should still work
    f.evaluateReply(ctx(s, 0, "p", "c"), r("c", vcw(), 1));
    var d = f.evaluateReply(ctx(s, 5, "p", "c"), r("c", vcw(), 2));
    // Link should still be detected
    assertEquals(1, f.stats().linksDetected());
    assertInstanceOf(FilterDecision.Accept.class, d);
  }

  @Test
  void listenerNullSafe() {
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2));
    var s = new FS();
    f.setLinkChangeListener(null);
    // should not throw
    f.evaluateReply(ctx(s, 0, "p", "c"), r("c", vcw(), 1));
    f.evaluateReply(ctx(s, 5, "p", "c"), r("c", vcw(), 2));
    assertEquals(1, f.stats().linksDetected());
  }

  @Test
  void listenerSameContext() {
    // The listener is called inline in the same thread/context that
    // evaluateReply/restoreLinkGroups uses.
    var f = new DcwFilter(cfg(false, false, false, true), fs("p", 1, 2));
    var s = new FS();
    var capturedThread = new Thread[1];
    f.setLinkChangeListener(() -> capturedThread[0] = Thread.currentThread());

    f.evaluateReply(ctx(s, 0, "p", "c"), r("c", vcw(), 1));
    f.evaluateReply(ctx(s, 5, "p", "c"), r("c", vcw(), 2));
    assertEquals(Thread.currentThread(), capturedThread[0],
        "listener called same-context (inline)");
  }
}
