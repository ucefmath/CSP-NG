package com.bowman.cspng.infinispan;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import java.util.Arrays;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InfinispanCacheTransportTest {

  private EmbeddedCacheManager manager;
  private Cache<Integer, byte[]> cache;
  private InfinispanCacheTransport transport;

  private static final EcmKey KEY = new EcmKey(0x80, 0x1234, 0x0001, 0x0B00, 42);
  private static final byte[] CW = new byte[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16};

  @BeforeEach
  void setUp() {
    GlobalConfigurationBuilder gcb = new GlobalConfigurationBuilder();
    gcb.nonClusteredDefault();
    gcb.globalState().disable();
    manager = new DefaultCacheManager(gcb.build(), false);
    ConfigurationBuilder cb = new ConfigurationBuilder();
    cb.clustering().cacheMode(CacheMode.LOCAL);
    cb.memory().maxCount(100000);
    cb.expiration().lifespan(30000);
    manager.defineConfiguration("test-cw", cb.build());
    manager.start();
    cache = manager.getCache("test-cw");
    transport = new InfinispanCacheTransport(cache);
  }

  @AfterEach
  void tearDown() {
    if (manager != null) manager.stop();
  }

  @Test
  void implementsCacheTransportWithoutError() {
    EcmRequest req = new EcmRequest(KEY, null, "default", "local", "origin", 0, null, 500);
    CwReply reply = new CwReply(KEY, 0x81, new byte[16], "conn", "origin", 0);
    assertDoesNotThrow(() -> transport.onRequest(req));
    assertDoesNotThrow(() -> transport.onReply(reply));
  }

  @Test
  void onRequestIsNoOp() {
    EcmRequest req = new EcmRequest(KEY, null, "default", "local", "origin", 0, null, 500);
    transport.onRequest(req);
    assertTrue(cache.isEmpty());
  }

  @Test
  void onReplyPublishesAsyncAndDecodes() throws Exception {
    CwReply reply = new CwReply(KEY, 0x80, CW, "connA", "10.0.0.1", System.currentTimeMillis());
    transport.onReply(reply);
    awaitCacheEntry(KEY.dataHash(), 2000);
    assertEquals(1, cache.size());
    byte[] raw = cache.get(KEY.dataHash());
    assertNotNull(raw);
    CwReply decoded = CwCacheCodec.decode(raw);
    assertArrayEquals(CW, decoded.cw());
    assertEquals(KEY.dataHash(), decoded.key().dataHash());
    assertEquals("connA", decoded.connectorName());
  }

  @Test
  void onReplyIgnoresInfinispanPrefixedOrigin() {
    CwReply remote = new CwReply(KEY, 0x80, CW, "connA", "infinispan:remote", System.currentTimeMillis());
    transport.onReply(remote);
    sleep(300);
    assertTrue(cache.isEmpty(), "prefixed origin should not be republished");
  }

  @Test
  void onReplyIgnoresEmptyCw() {
    CwReply empty = new CwReply(KEY, 0x80, new byte[0], "conn", "origin", 0);
    transport.onReply(empty);
    sleep(300);
    assertTrue(cache.isEmpty());
  }

  @Test
  void onReplyIgnoresNullReply() {
    assertDoesNotThrow(() -> transport.onReply(null));
    assertTrue(cache.isEmpty());
  }

  @Test
  void nullOriginAddressIsPublished() throws Exception {
    CwReply reply = new CwReply(KEY, 0x80, CW, "conn", null, System.currentTimeMillis());
    transport.onReply(reply);
    awaitCacheEntry(KEY.dataHash(), 2000);
    assertEquals(1, cache.size());
  }

  @Test
  void onReplyIgnoresUdpPrefixedOrigin() {
    CwReply remote = new CwReply(KEY, 0x80, CW, "connA", "udp:10.0.0.1", System.currentTimeMillis());
    transport.onReply(remote);
    assertTrue(cache.isEmpty(), "udp: prefixed origin should not be republished");
  }

  @Test
  void onReplyIgnoresUdpPrefixedOriginWithHostAndPortLikeSuffix() {
    CwReply remote = new CwReply(KEY, 0x80, CW, "connA", "udp:192.168.1.10", System.currentTimeMillis());
    transport.onReply(remote);
    assertTrue(cache.isEmpty());
  }

  @Test
  void onReplyPublishesWithRawAddressOrigin() throws Exception {
    CwReply reply = new CwReply(KEY, 0x80, CW, "conn", "192.168.1.5", System.currentTimeMillis());
    transport.onReply(reply);
    awaitCacheEntry(KEY.dataHash(), 2000);
    assertEquals(1, cache.size());
    byte[] raw = cache.get(KEY.dataHash());
    CwReply decoded = CwCacheCodec.decode(raw);
    assertArrayEquals(CW, decoded.cw());
    assertEquals("192.168.1.5", decoded.originAddress());
  }

  @Test
  void onReplyPublishesWithBruteCardserverAddress() throws Exception {
    CwReply reply = new CwReply(KEY, 0x80, CW, "conn", "10.0.0.99", System.currentTimeMillis());
    transport.onReply(reply);
    awaitCacheEntry(KEY.dataHash(), 2000);
    assertEquals(1, cache.size());
  }

  @Test
  void codecStrictVersionRejected() {
    CwReply reply = new CwReply(KEY, 0x80, CW, "c", "o", 123L);
    byte[] raw = CwCacheCodec.encode(reply);
    raw[0] = 99; // unknown version
    assertThrows(IllegalArgumentException.class, () -> CwCacheCodec.decode(raw));
  }

  @Test
  void codecTrailingBytesRejected() {
    CwReply reply = new CwReply(KEY, 0x80, CW, "c", "o", 123L);
    byte[] raw = CwCacheCodec.encode(reply);
    byte[] withTrailing = Arrays.copyOf(raw, raw.length + 1);
    withTrailing[withTrailing.length - 1] = 0x00;
    assertThrows(IllegalArgumentException.class, () -> CwCacheCodec.decode(withTrailing));
  }

  @Test
  void codecInvalidCwLengthRejected() {
    CwReply reply = new CwReply(KEY, 0x80, CW, "c", "o", 123L);
    byte[] raw = CwCacheCodec.encode(reply);
    // cwLen is at offset 1+4*6 =25, 4 bytes
    raw[25] = 0; raw[26]=0; raw[27]=0; raw[28]=0x0F; // set to 15 instead of 16
    assertThrows(IllegalArgumentException.class, () -> CwCacheCodec.decode(raw));
  }

  @Test
  void codecRejectsOversizedString() {
    String longStr = "a".repeat(300);
    CwReply reply = new CwReply(KEY, 0x80, CW, longStr, "o", 123L);
    assertThrows(IllegalArgumentException.class, () -> CwCacheCodec.encode(reply));
  }

  private void awaitCacheEntry(int hash, long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (cache.containsKey(hash)) return;
      Thread.sleep(50);
    }
    fail("cache entry not found within " + timeoutMs + "ms");
  }

  private static void sleep(long ms) {
    try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }
}
