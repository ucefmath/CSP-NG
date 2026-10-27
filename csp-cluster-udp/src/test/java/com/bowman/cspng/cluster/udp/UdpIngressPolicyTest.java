package com.bowman.cspng.cluster.udp;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.cluster.ClusterUdpCodec;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.domain.dcw.DcwQualityPolicy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramPacket;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lot 1 : validation contextuelle AVANT injection au cache sur le chemin central,
 * aucune pré-validation aveugle devant le hook worker (qui connaît le pending).
 * Replies vides protocolaires toujours transmises. Parité 0 OSCam préservée.
 */
@DisplayName("UdpLegacyTransport — validation DCW contextuelle avant injection")
class UdpIngressPolicyTest {

  private static final EcmKey KEY =
      new EcmKey(0x80, 0x1234, 0x0001, 0x0B00, 0x12345678);

  private static final byte[] CW_VALID = {
      0x01, 0x02, 0x03, 0x06, 0x04, 0x05, 0x06, 0x0F,
      0x07, 0x08, 0x09, 0x18, 0x0A, 0x0B, 0x0C, 0x21
  };

  private Vertx vertx;
  private CacheVerticle cacheVerticle;

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    cacheVerticle = new CacheVerticle();
    CountDownLatch deployLatch = new CountDownLatch(1);
    vertx.deployVerticle(cacheVerticle).onComplete(ar -> deployLatch.countDown());
    assertTrue(deployLatch.await(5, TimeUnit.SECONDS));
  }

  @AfterEach
  void tearDown() throws Exception {
    if (vertx != null) {
      CountDownLatch closeLatch = new CountDownLatch(1);
      vertx.close(ar -> closeLatch.countDown());
      closeLatch.await(5, TimeUnit.SECONDS);
    }
  }

  private static byte[] badChecksumCw() {
    byte[] cw = CW_VALID.clone();
    cw[3] = (byte) (cw[3] + 1); // casse le premier triplet
    return cw;
  }

  /** Demi-CW nulle tolérée par défaut (allowZeroHalf=true) si checksums valides. */
  private static byte[] halfZeroValidCw() {
    byte[] cw = new byte[16];
    // première moitié nulle (checksums triviaux 0), seconde moitié valide
    cw[8] = 0x04; cw[9] = 0x05; cw[10] = 0x06; cw[11] = 0x0F;
    cw[12] = 0x07; cw[13] = 0x08; cw[14] = 0x09; cw[15] = 0x18;
    return cw;
  }

  private void barrier() throws Exception {
    CountDownLatch b = new CountDownLatch(1);
    cacheVerticle.runOnContext(b::countDown);
    assertTrue(b.await(2, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("central : CW valide injectée, marquée udp:<host>")
  void centralValidInjected() throws Exception {
    PeerRegistry reg = new PeerRegistry();
    reg.setAutoAddPeers(true);
    UdpStats stats = new UdpStats();
    SpyTransport t = new SpyTransport(null, reg, stats, cacheVerticle, 18091, 1000);
    CwReply original = new CwReply(KEY, 0x80, CW_VALID, "origin-conn", null, System.currentTimeMillis());
    t.handlePacket(pkt(ClusterUdpCodec.encodeReply(original), "10.0.0.11", 54321));
    barrier();
    CwReply cached = cacheVerticle.peekLocalCache(KEY);
    assertNotNull(cached);
    assertEquals("udp:10.0.0.11", cached.originAddress());
    assertEquals(0, stats.receivedRejectedDcw());
  }

  @Test
  @DisplayName("central : CW checksum invalide rejetée AVANT cache (comptée, non injectée)")
  void centralInvalidRejectedBeforeCache() throws Exception {
    PeerRegistry reg = new PeerRegistry();
    reg.setAutoAddPeers(true);
    UdpStats stats = new UdpStats();
    SpyTransport t = new SpyTransport(null, reg, stats, cacheVerticle, 18092, 1000);
    CwReply original = new CwReply(KEY, 0x80, badChecksumCw(), "origin-conn", null, System.currentTimeMillis());
    t.handlePacket(pkt(ClusterUdpCodec.encodeReply(original), "10.0.0.12", 54321));
    barrier();
    assertNull(cacheVerticle.peekLocalCache(KEY), "CW invalide ne doit pas atteindre le cache");
    assertEquals(1, stats.receivedEntries());
    assertEquals(1, stats.receivedBadChecksum());
    assertEquals(1, stats.receivedRejectedDcw());
  }

  @Test
  @DisplayName("central : demi-CW nulle tolérée par défaut (allowZeroHalf)")
  void centralHalfZeroPreservedByDefault() throws Exception {
    PeerRegistry reg = new PeerRegistry();
    reg.setAutoAddPeers(true);
    UdpStats stats = new UdpStats();
    SpyTransport t = new SpyTransport(null, reg, stats, cacheVerticle, 18093, 1000);
    CwReply original = new CwReply(KEY, 0x80, halfZeroValidCw(), "origin-conn", null, System.currentTimeMillis());
    t.handlePacket(pkt(ClusterUdpCodec.encodeReply(original), "10.0.0.13", 54321));
    barrier();
    assertNotNull(cacheVerticle.peekLocalCache(KEY));
    assertEquals(0, stats.receivedRejectedDcw());
  }

  @Test
  @DisplayName("central : allowZeroHalf=false rejette la demi-CW nulle (exception policy respectée)")
  void centralHalfZeroRejectedWhenDisallowed() throws Exception {
    PeerRegistry reg = new PeerRegistry();
    reg.setAutoAddPeers(true);
    UdpStats stats = new UdpStats();
    SpyTransport t = new SpyTransport(null, reg, stats, cacheVerticle, 18094, 1000);
    DcwQualityPolicy strict = new DcwQualityPolicy(true, false, false, false,
        com.bowman.cspng.domain.dcw.ChecksumMode.INHERIT,
        com.bowman.cspng.domain.dcw.DcwRepairMode.OFF, false, List.of(), List.of());
    t.setDcwPolicySupplier(() -> strict);
    CwReply original = new CwReply(KEY, 0x80, halfZeroValidCw(), "origin-conn", null, System.currentTimeMillis());
    t.handlePacket(pkt(ClusterUdpCodec.encodeReply(original), "10.0.0.14", 54321));
    barrier();
    assertNull(cacheVerticle.peekLocalCache(KEY));
    assertEquals(1, stats.receivedRejectedDcw());
  }

  @Test
  @DisplayName("central : parité 0 OSCam (inconnue) injectée telle quelle, jamais convertie")
  void centralParityZeroPreserved() throws Exception {
    PeerRegistry reg = new PeerRegistry();
    reg.setAutoAddPeers(true);
    UdpStats stats = new UdpStats();
    SpyTransport t = new SpyTransport(null, reg, stats, cacheVerticle, 18095, 1000);
    EcmKey key0 = new EcmKey(0, 0x1234, 0x0001, 0x0B00, 0x12345678);
    CwReply original = new CwReply(key0, 0, CW_VALID, "origin-conn", null, System.currentTimeMillis());
    t.handlePacket(pkt(ClusterUdpCodec.encodeReply(original), "10.0.0.15", 54321));
    barrier();
    CwReply cached = cacheVerticle.peekLocalCache(key0);
    assertNotNull(cached, "parité inconnue OSCam 0 doit rester servable");
    assertEquals(0, cached.commandTag());
  }

  @Test
  @DisplayName("central : reply vide protocolaire transmise (pas de rejet)")
  void centralEmptyReplyForwarded() throws Exception {
    PeerRegistry reg = new PeerRegistry();
    reg.setAutoAddPeers(true);
    UdpStats stats = new UdpStats();
    SpyTransport t = new SpyTransport(null, reg, stats, cacheVerticle, 18096, 1000);
    CwReply empty = CwReply.empty(KEY);
    t.handlePacket(pkt(ClusterUdpCodec.encodeReply(empty), "10.0.0.16", 54321));
    barrier();
    assertEquals(1, stats.receivedDiscarded());
    assertEquals(0, stats.receivedRejectedDcw());
  }

  @Test
  @DisplayName("worker : aucune pré-validation devant le hook (le hook décide avec le pending)")
  void workerHookNotPrevalidated() throws Exception {
    PeerRegistry reg = new PeerRegistry();
    reg.setAutoAddPeers(true);
    UdpStats stats = new UdpStats();
    SpyTransport t = new SpyTransport(null, reg, stats, cacheVerticle, 18097, 1000);
    AtomicReference<CwReply> hooked = new AtomicReference<>();
    t.setWorkerHooks(new UdpLegacyTransport.UdpWorkerHooks() {
      @Override public void onRemoteRequest(EcmRequest request, SocketAddress sender) {}
      @Override public void onRemoteReply(EcmRequest request, CwReply reply, SocketAddress sender) {
        hooked.set(reply);
      }
      @Override public CwReply peekLocal(EcmKey key, SocketAddress sender) { return null; }
    });
    // CW invalide : le transport ne doit PAS la rejeter, le hook la reçoit et décide.
    CwReply original = new CwReply(KEY, 0x80, badChecksumCw(), "origin-conn", null, System.currentTimeMillis());
    t.handlePacket(pkt(ClusterUdpCodec.encodeReply(original), "10.0.0.17", 54321));
    assertNotNull(hooked.get(), "le hook worker doit recevoir la reply pour validation contextuelle");
    assertEquals(0, stats.receivedRejectedDcw(), "aucun rejet transport sur le chemin worker");
  }

  private static DatagramPacket pkt(byte[] frame, String host, int port) {
    return new DatagramPacket() {
      @Override public SocketAddress sender() { return SocketAddress.inetSocketAddress(port, host); }
      @Override public io.vertx.core.buffer.Buffer data() { return Buffer.buffer(frame); }
    };
  }

  static class SpyTransport extends UdpLegacyTransport {
    final AtomicInteger sendCalls = new AtomicInteger(0);
    final List<byte[]> sentFrames = new CopyOnWriteArrayList<>();

    SpyTransport(DatagramSocket socket, PeerRegistry peerRegistry, UdpStats stats,
                 CacheVerticle cacheVerticle, int localPort, int maxPacketsPerSec) {
      super(socket, peerRegistry, stats, cacheVerticle, localPort, maxPacketsPerSec);
    }

    @Override
    void sendToAll(byte[] data) {
      sendCalls.incrementAndGet();
      sentFrames.add(data.clone());
    }

    @Override
    public void sendTo(SocketAddress target, byte[] data) {
      sendCalls.incrementAndGet();
      sentFrames.add(data.clone());
    }
  }
}
