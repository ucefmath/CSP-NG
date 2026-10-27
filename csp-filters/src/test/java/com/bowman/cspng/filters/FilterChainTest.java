package com.bowman.cspng.filters;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class FilterChainTest {

  // --- helpers ---

  static CwReply testReply(String connector) {
    return new CwReply(new EcmKey(0x80, 1, 2, 0x1810, 12345), 0x80,
        new byte[]{4, 5, 6, 15, 7, 8, 9, 24, 10, 11, 12, 33, 13, 14, 15, 42},
        connector, "127.0.0.1", System.currentTimeMillis());
  }

  static CwReply emptyReply() {
    return new CwReply(new EcmKey(0x80, 1, 2, 0x1810, 12345), 0x80,
        new byte[0], "c1", "127.0.0.1", System.currentTimeMillis());
  }

  static EcmRequest testRequest() {
    return new EcmRequest(new EcmKey(0x80, 1, 2, 0x1810, 12345),
        new byte[]{1, 2, 3}, "profile-a", "192.168.1.1", "origin-1",
        System.currentTimeMillis(), 0.5, 5000);
  }

  static FilterContext ctx(String connector) {
    return new FilterContext(testRequest(), connector, "profile-a", System.currentTimeMillis());
  }

  static FilterContext ctx(String connector, FilterContext.ReplyRelease release,
                            FilterContext.FilterScheduler scheduler) {
    return new FilterContext(testRequest(), connector, "profile-a",
        System.currentTimeMillis(), release, scheduler);
  }

  // --- no filters: empty chain accepts ---

  @Test
  void emptyChainReturnsAccept() {
    FilterChain chain = new FilterChain(List.of());
    CwReply reply = testReply("c1");
    FilterDecision d = chain.evaluate(ctx("c1"), reply);
    assertInstanceOf(FilterDecision.Accept.class, d);
    assertSame(reply, ((FilterDecision.Accept) d).reply());
  }

  // --- filter order ---

  @Test
  void filtersAppliedInOrder() {
    var executionOrder = new java.util.ArrayList<String>();
    CwFilter f1 = new TestFilter("f1", 100, (ctx, reply) -> {
      executionOrder.add("f1");
      return new FilterDecision.Accept(reply);
    });
    CwFilter f2 = new TestFilter("f2", 50, (ctx, reply) -> {
      executionOrder.add("f2");
      return new FilterDecision.Accept(reply);
    });

    FilterChain chain = new FilterChain(List.of(f1, f2));
    CwReply reply = testReply("c1");
    FilterDecision d = chain.evaluate(ctx("c1"), reply);
    assertInstanceOf(FilterDecision.Accept.class, d);
    assertEquals(List.of("f2", "f1"), executionOrder,
        "f2 (order 50) should run before f1 (order 100)");
  }

  // --- ACCEPT ---

  @Test
  void acceptPassesThroughChain() {
    CwFilter acceptFilter = new TestFilter("passthrough", 10, (ctx, reply) ->
        new FilterDecision.Accept(reply));
    FilterChain chain = new FilterChain(List.of(acceptFilter));
    CwReply reply = testReply("c1");
    FilterDecision d = chain.evaluate(ctx("c1"), reply);
    assertInstanceOf(FilterDecision.Accept.class, d);
    assertSame(reply, ((FilterDecision.Accept) d).reply());
  }

  // --- ACCEPT with empty CW → DROP ---

  @Test
  void acceptWithEmptyCwTreatedAsDrop() {
    CwFilter acceptAll = new TestFilter("accept", 10, (ctx, r) -> new FilterDecision.Accept(r));
    FilterChain chain = new FilterChain(List.of(acceptAll));
    FilterDecision d = chain.evaluate(ctx("c1"), emptyReply());
    assertInstanceOf(FilterDecision.Drop.class, d);
  }

  // --- DROP ---

  @Test
  void dropStopsChain() {
    AtomicInteger ran = new AtomicInteger(0);
    CwFilter dropFilter = new TestFilter("dropper", 10, (ctx, reply) -> {
      ran.incrementAndGet();
      return FilterDecision.Drop.INSTANCE;
    });
    CwFilter afterDrop = new TestFilter("should-not-run", 20, (ctx, reply) -> {
      ran.incrementAndGet();
      return new FilterDecision.Accept(reply);
    });
    FilterChain chain = new FilterChain(List.of(dropFilter, afterDrop));
    FilterDecision d = chain.evaluate(ctx("c1"), testReply("c1"));
    assertInstanceOf(FilterDecision.Drop.class, d);
    assertEquals(1, ran.get(), "only drop filter should have run");
  }

  // --- HOLD ---

  @Test
  void holdStopsChain() {
    AtomicInteger ran = new AtomicInteger(0);
    CwFilter holdFilter = new TestFilter("holder", 10, (ctx, reply) -> {
      ran.incrementAndGet();
      return FilterDecision.Hold.INSTANCE;
    });
    CwFilter after = new TestFilter("after", 20, (ctx, reply) -> {
      ran.incrementAndGet();
      return new FilterDecision.Accept(reply);
    });
    FilterChain chain = new FilterChain(List.of(holdFilter, after));
    FilterDecision d = chain.evaluate(ctx("c1"), testReply("c1"));
    assertInstanceOf(FilterDecision.Hold.class, d);
    assertEquals(1, ran.get());
  }

  // --- REWRITE ---

  @Test
  void rewriteModifiesCw() {
    byte[] newCw = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88,
        0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88};
    CwFilter rewriteFilter = new TestFilter("rewriter", 10, (ctx, reply) ->
        new FilterDecision.Rewrite(
            new CwReply(reply.key(), reply.commandTag(), newCw,
                reply.connectorName(), reply.originAddress(), reply.timestamp())));

    CwFilter afterRewrite = new TestFilter("verifier", 20, (ctx, reply) -> {
      assertArrayEquals(newCw, reply.cw());
      return new FilterDecision.Accept(reply);
    });

    FilterChain chain = new FilterChain(List.of(rewriteFilter, afterRewrite));
    FilterDecision d = chain.evaluate(ctx("c1"), testReply("c1"));
    assertInstanceOf(FilterDecision.Accept.class, d);
    assertArrayEquals(newCw, ((FilterDecision.Accept) d).reply().cw());
  }

  // --- REWRITE with invalid (empty) CW throws ---

  @Test
  void rewriteWithEmptyCwThrows() {
    CwReply empty = new CwReply(new EcmKey(0x80, 1, 2, 0x1810, 12345), 0x80,
        new byte[0], "c1", "127.0.0.1", System.currentTimeMillis());
    assertThrows(IllegalArgumentException.class, () -> new FilterDecision.Rewrite(empty));
  }

  // --- RELEASE_PREVIOUS ---

  @Test
  void releasePreviousStopsChain() {
    CwReply held = testReply("c2");
    AtomicInteger ran = new AtomicInteger(0);
    CwFilter releaseFilter = new TestFilter("releaser", 10, (ctx, reply) -> {
      ran.incrementAndGet();
      return new FilterDecision.ReleasePrevious(held, "c2");
    });
    CwFilter after = new TestFilter("after", 20, (ctx, reply) -> {
      ran.incrementAndGet();
      return new FilterDecision.Accept(reply);
    });
    FilterChain chain = new FilterChain(List.of(releaseFilter, after));
    FilterDecision d = chain.evaluate(ctx("c1"), testReply("c1"));
    assertInstanceOf(FilterDecision.ReleasePrevious.class, d);
    assertEquals("c2", ((FilterDecision.ReleasePrevious) d).connectorRef());
    assertSame(held, ((FilterDecision.ReleasePrevious) d).reply());
    assertEquals(1, ran.get());
  }

  // --- RELEASE_PREVIOUS with empty CW throws ---

  @Test
  void releasePreviousWithEmptyCwThrows() {
    CwReply empty = new CwReply(new EcmKey(0x80, 1, 2, 0x1810, 12345), 0x80,
        new byte[0], "c1", "127.0.0.1", System.currentTimeMillis());
    assertThrows(IllegalArgumentException.class, () ->
        new FilterDecision.ReleasePrevious(empty, "c1"));
  }

  @Test
  void releasePreviousWithNullConnectorRefThrows() {
    CwReply reply = testReply("c1");
    assertThrows(NullPointerException.class, () ->
        new FilterDecision.ReleasePrevious(reply, null));
  }

  @Test
  void releasePreviousWithBlankConnectorRefThrows() {
    CwReply reply = testReply("c1");
    assertThrows(IllegalArgumentException.class, () ->
        new FilterDecision.ReleasePrevious(reply, "  "));
  }

  // --- filter exception = fail-closed (DROP) ---

  @Test
  void filterExceptionTreatsAsDrop() {
    CwFilter throwing = new TestFilter("thrower", 10, (ctx, reply) -> {
      throw new RuntimeException("simulated filter failure");
    });
    CwFilter after = new TestFilter("after", 20, (ctx, reply) ->
        new FilterDecision.Accept(reply));
    FilterChain chain = new FilterChain(List.of(throwing, after));
    FilterDecision d = chain.evaluate(ctx("c1"), testReply("c1"));
    assertInstanceOf(FilterDecision.Drop.class, d);
  }

  // --- fan-out indicator ---

  @Test
  void fanOutFalseWhenNoFilters() {
    FilterChain chain = new FilterChain(List.of());
    assertFalse(chain.requiresMultipleReplies());
  }

  @Test
  void fanOutFalseByDefault() {
    CwFilter f = new TestFilter("f", 10, (ctx, reply) -> new FilterDecision.Accept(reply));
    FilterChain chain = new FilterChain(List.of(f));
    assertFalse(chain.requiresMultipleReplies());
  }

  @Test
  void fanOutTrueWhenFilterRequiresIt() {
    CwFilter f = new TestFilter("f", 10, (ctx, reply) -> new FilterDecision.Accept(reply), true);
    FilterChain chain = new FilterChain(List.of(f));
    assertTrue(chain.requiresMultipleReplies());
  }

  // --- FilterDecision.isTerminating ---

  @Test
  void acceptIsTerminating() {
    assertTrue(new FilterDecision.Accept(testReply("c1")).isTerminating());
  }

  @Test
  void rewriteIsTerminating() {
    assertTrue(new FilterDecision.Rewrite(testReply("c1")).isTerminating());
  }

  @Test
  void releasePreviousIsTerminating() {
    assertTrue(new FilterDecision.ReleasePrevious(testReply("c1"), "c1").isTerminating());
  }

  @Test
  void dropIsNotTerminating() {
    assertFalse(FilterDecision.Drop.INSTANCE.isTerminating());
  }

  @Test
  void holdIsNotTerminating() {
    assertFalse(FilterDecision.Hold.INSTANCE.isTerminating());
  }

  // --- FilterContext validation ---

  @Test
  void filterContextRejectsNulls() {
    assertThrows(NullPointerException.class, () ->
        new FilterContext(null, "c1", "p", 0L));
    assertThrows(NullPointerException.class, () ->
        new FilterContext(testRequest(), null, "p", 0L));
    assertThrows(NullPointerException.class, () ->
        new FilterContext(testRequest(), "c1", null, 0L));
  }

  @Test
  void filterContextWithReleaseChannelRejectsNull() {
    assertThrows(NullPointerException.class, () ->
        new FilterContext(testRequest(), "c1", "p", 0L, null,
            FilterContext.FilterScheduler.UNSUPPORTED));
    assertThrows(NullPointerException.class, () ->
        new FilterContext(testRequest(), "c1", "p", 0L,
            FilterContext.ReplyRelease.UNSUPPORTED, null));
  }

  // --- ReplyRelease UNSUPPORTED ---

  @Test
  void replyReleaseUnsupportedThrows() {
    assertThrows(UnsupportedOperationException.class, () ->
        FilterContext.ReplyRelease.UNSUPPORTED.release(testReply("c1"), "c1"));
  }

  // --- FilterScheduler UNSUPPORTED ---

  @Test
  void filterSchedulerUnsupportedThrows() {
    assertThrows(UnsupportedOperationException.class, () ->
        FilterContext.FilterScheduler.UNSUPPORTED.schedule(100, () -> {}));
  }

  // --- scheduler captured by filter ---

  @Test
  void filterCanCaptureScheduler() {
    AtomicReference<FilterContext.FilterScheduler> captured = new AtomicReference<>();
    CwFilter capturer = new TestFilter("cap", 10, (ctx, reply) -> {
      captured.set(ctx.scheduler());
      return new FilterDecision.Accept(reply);
    });
    FilterContext.FilterScheduler sched = (delay, task) -> () -> {};
    FilterContext context = ctx("c1", FilterContext.ReplyRelease.UNSUPPORTED, sched);
    FilterChain chain = new FilterChain(List.of(capturer));
    chain.evaluate(context, testReply("c1"));
    assertSame(sched, captured.get());
  }

  // --- async release channel captured by filter ---

  @Test
  void filterCanCaptureReleaseChannel() {
    AtomicReference<FilterContext.ReplyRelease> captured = new AtomicReference<>();
    CwFilter capturer = new TestFilter("cap", 10, (ctx, reply) -> {
      captured.set(ctx.releaseChannel());
      return FilterDecision.Hold.INSTANCE;
    });
    FilterContext.ReplyRelease custom = (r, ref) -> {};
    FilterContext context = ctx("c1", custom,
        FilterContext.FilterScheduler.UNSUPPORTED);
    FilterChain chain = new FilterChain(List.of(capturer));
    chain.evaluate(context, testReply("c1"));
    assertSame(custom, captured.get());
  }

  // --- backward compatibility: old filterReply via default evaluateReply ---

  @Test
  void legacyFilterReplyNullMapsToDrop() {
    CwFilter legacy = new CwFilter() {
      @Override public String name() { return "legacy"; }
      @Override public int order() { return 10; }
      @Override public CwReply filterReply(EcmRequest req, CwReply reply) { return null; }
    };
    FilterChain chain = new FilterChain(List.of(legacy));
    FilterDecision d = chain.evaluate(ctx("c1"), testReply("c1"));
    assertInstanceOf(FilterDecision.Drop.class, d);
  }

  @Test
  void legacyFilterReplyNonNullMapsToAccept() {
    CwFilter legacy = new CwFilter() {
      @Override public String name() { return "legacy"; }
      @Override public int order() { return 10; }
    };
    FilterChain chain = new FilterChain(List.of(legacy));
    CwReply reply = testReply("c1");
    FilterDecision d = chain.evaluate(ctx("c1"), reply);
    assertInstanceOf(FilterDecision.Accept.class, d);
    assertSame(reply, ((FilterDecision.Accept) d).reply());
  }

  // --- DcwFilter is backward-compatible ---

  @Test
  void dcwFilterIsCompatibleWithNewChain() {
    DcwFilter dcw = new DcwFilter(new DcwFilterConfig(), null);
    FilterChain chain = new FilterChain(List.of(dcw));
    CwReply reply = testReply("c1");
    FilterDecision d = chain.evaluate(ctx("c1"), reply);
    assertInstanceOf(FilterDecision.Accept.class, d);
    assertSame(reply, ((FilterDecision.Accept) d).reply());
  }

  // --- filters() returns immutable order-preserving list ---

  @Test
  void filtersReturnsSortedByOrder() {
    CwFilter f10 = new TestFilter("f10", 10, null);
    CwFilter f5 = new TestFilter("f5", 5, null);
    CwFilter f7 = new TestFilter("f7", 7, null);
    FilterChain chain = new FilterChain(List.of(f10, f5, f7));
    List<CwFilter> sorted = chain.filters();
    assertEquals(3, sorted.size());
    assertEquals("f5", sorted.get(0).name());
    assertEquals("f7", sorted.get(1).name());
    assertEquals("f10", sorted.get(2).name());
    assertThrows(UnsupportedOperationException.class, () -> sorted.add(null));
  }

  // --- null/empty filter lists are safe ---

  @Test
  void nullFilterListIsSafe() {
    FilterChain chain = new FilterChain(null);
    assertTrue(chain.filters().isEmpty());
    assertFalse(chain.requiresMultipleReplies());
    FilterDecision d = chain.evaluate(ctx("c1"), testReply("c1"));
    assertInstanceOf(FilterDecision.Accept.class, d);
  }

  // --- helper ---

  @FunctionalInterface
  interface DecisionFn {
    FilterDecision apply(FilterContext ctx, CwReply reply);
  }

  static class TestFilter implements CwFilter {
    private final String name;
    private final int order;
    private final DecisionFn fn;
    private final boolean multi;

    TestFilter(String name, int order, DecisionFn fn) {
      this(name, order, fn, false);
    }

    TestFilter(String name, int order, DecisionFn fn, boolean multi) {
      this.name = name;
      this.order = order;
      this.fn = fn;
      this.multi = multi;
    }

    @Override public String name() { return name; }
    @Override public int order() { return order; }
    @Override public FilterDecision evaluateReply(FilterContext ctx, CwReply reply) {
      return fn.apply(ctx, reply);
    }
    @Override public boolean requiresMultipleReplies() { return multi; }
  }
}
