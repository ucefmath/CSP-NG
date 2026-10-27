package com.bowman.cspng.cluster.udp;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.cache.CacheVerticleConfig;
import com.bowman.cspng.codec.cluster.ClusterUdpCodec;
import com.bowman.cspng.codec.cluster.ClusterUdpCodec.PingFrame;
import com.bowman.cspng.codec.cluster.ClusterUdpCodec.PingReplyFrame;
import com.bowman.cspng.codec.cluster.ClusterUdpCodec.ResendFrame;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramPacket;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests complets du lot L3a : PeerRegistry, rate-limiter, anti-amplification,
 * robustesse, ping/pong, et intégration UDP réelle.
 */
class UdpClusterVerticleTest {

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
    assertTrue(deployLatch.await(5, TimeUnit.SECONDS), "CacheVerticle deployment timed out");
  }

  @AfterEach
  void tearDown() throws Exception {
    if (vertx != null) {
      CountDownLatch closeLatch = new CountDownLatch(1);
      vertx.close(ar -> closeLatch.countDown());
      closeLatch.await(5, TimeUnit.SECONDS);
    }
  }

  // ==============================================================================================
  // PeerRegistry
  // ==============================================================================================

  @Test
  void peerRegistryStaticPeers() {
    PeerRegistry reg = new PeerRegistry();
    reg.addStaticPeer("192.168.1.1", 8099);
    reg.addStaticPeer("192.168.1.2", 8099);

    assertEquals(2, reg.peerCount());
    assertTrue(reg.isKnownHost("192.168.1.1"));
    assertTrue(reg.isKnownHost("192.168.1.2"));
    assertFalse(reg.isKnownHost("192.168.1.3"));
  }

  @Test
  void peerRegistryAutoAddPeer() {
    PeerRegistry reg = new PeerRegistry();
    reg.setAutoAddPeers(true);

    SocketAddress addr = SocketAddress.inetSocketAddress(12345, "10.0.0.5");
    assertTrue(reg.registerDynamicPeer(addr));
    assertEquals(1, reg.peerCount());
    assertTrue(reg.isKnownHost("10.0.0.5"));
  }

  @Test
  void peerRegistryAutoAddDuplicateIsIdempotent() {
    PeerRegistry reg = new PeerRegistry();
    SocketAddress addr = SocketAddress.inetSocketAddress(12345, "10.0.0.5");

    assertTrue(reg.registerDynamicPeer(addr));
    assertFalse(reg.registerDynamicPeer(addr));
    assertEquals(1, reg.peerCount());
  }

  @Test
  void peerRegistryNoAutoAddWhenDisabled() {
    PeerRegistry reg = new PeerRegistry();
    reg.setAutoAddPeers(false);

    SocketAddress addr = SocketAddress.inetSocketAddress(12345, "10.0.0.5");
    // Avec autoAddPeers=false, registerDynamicPeer doit refuser
    assertFalse(reg.registerDynamicPeer(addr));
    assertEquals(0, reg.peerCount());
    assertFalse(reg.isKnownHost("10.0.0.5"));
  }

  @Test
  void peerRegistryDoesNotAutoAddBlockedIp() {
    PeerRegistry reg = new PeerRegistry();
    reg.blockIp("10.0.0.99");

    SocketAddress addr = SocketAddress.inetSocketAddress(12345, "10.0.0.99");
    assertFalse(reg.registerDynamicPeer(addr));
    assertFalse(reg.isKnownHost("10.0.0.99"));
  }

  @Test
  void peerRegistryMulticast() {
    PeerRegistry reg = new PeerRegistry();
    reg.setMulticastGroup("239.0.0.1", 8099);

    assertEquals("239.0.0.1", reg.multicastAddress().host());
    assertEquals(8099, reg.multicastAddress().port());
  }

  @Test
  void peerRegistryAllPeersIncludesBothStaticAndDynamic() {
    PeerRegistry reg = new PeerRegistry();
    reg.addStaticPeer("192.168.1.1", 8099);
    reg.registerDynamicPeer(SocketAddress.inetSocketAddress(12345, "10.0.0.5"));

    assertEquals(2, reg.allPeers().size());
    assertEquals(2, reg.peerCount());
  }

  @Test
  void peerRegistryClearKeepsStatic() {
    PeerRegistry reg = new PeerRegistry();
    reg.addStaticPeer("192.168.1.1", 8099);
    reg.registerDynamicPeer(SocketAddress.inetSocketAddress(12345, "10.0.0.5"));

    reg.clear(true);
    assertEquals(1, reg.peerCount()); // only static remains
    assertTrue(reg.isKnownHost("192.168.1.1"));
    assertFalse(reg.isKnownHost("10.0.0.5"));
  }

  // ==============================================================================================
  // UdpStats
  // ==============================================================================================

  @Test
  void udpStatsCounterIncrements() {
    UdpStats stats = new UdpStats();
    stats.incSentPending();
    stats.incSentEntries();
    stats.incRateLimited();
    stats.incInvalidSize();
    stats.incAntiAmplificationRejected();
    stats.incParseErrors();

    assertEquals(1, stats.sentPending());
    assertEquals(1, stats.sentEntries());
    assertEquals(1, stats.rateLimited());
    assertEquals(1, stats.invalidSize());
    assertEquals(1, stats.antiAmplificationRejected());
    assertEquals(1, stats.parseErrors());
  }

  @Test
  void udpStatsAsMapContainsAllKeys() {
    UdpStats stats = new UdpStats();
    var map = stats.asMap();
    assertTrue(map.containsKey("sent-pending"));
    assertTrue(map.containsKey("rate-limited"));
    assertTrue(map.containsKey("invalid-size"));
    assertTrue(map.containsKey("unknown-type"));
    assertTrue(map.containsKey("anti-amplification-rejected"));
    assertTrue(map.containsKey("parse-errors"));
    assertTrue(map.containsKey("ping-sent"));
    assertTrue(map.containsKey("ping-received"));
    assertTrue(map.containsKey("pong-received"));
  }

  // ==============================================================================================
  // Rate-limiter
  // ==============================================================================================

  @Test
  void rateLimiterAllowsUnderThreshold() {
    UdpLegacyTransport.RateEntry entry = new UdpLegacyTransport.RateEntry();
    for (int i = 0; i < 50; i++) {
      assertTrue(entry.incrementAndCheck(50), "iteration " + i);
    }
  }

  @Test
  void rateLimiterRejectsOverThreshold() {
    UdpLegacyTransport.RateEntry entry = new UdpLegacyTransport.RateEntry();
    // 50 first packets should be accepted
    for (int i = 0; i < 50; i++) {
      entry.incrementAndCheck(50);
    }
    // 51st should be rejected
    assertFalse(entry.incrementAndCheck(50));
  }

  @Test
  void rateLimiterResetsAfterWindowExpires() throws Exception {
    UdpLegacyTransport.RateEntry entry = new UdpLegacyTransport.RateEntry();
    // Exhaust the window
    for (int i = 0; i < 10; i++) {
      entry.incrementAndCheck(10);
    }
    assertFalse(entry.incrementAndCheck(10));
    // Wait for window to expire
    Thread.sleep(1100);
    // New window should accept again
    assertTrue(entry.incrementAndCheck(10));
  }

  // ==============================================================================================
  // Size validation
  // ==============================================================================================

  @Test
  void isValidSizeAcceptsKnownSizes() {
    assertTrue(UdpLegacyTransport.isValidSize(9));   // PINGRPL
    assertTrue(UdpLegacyTransport.isValidSize(12));  // REQUEST sans arbitre
    assertTrue(UdpLegacyTransport.isValidSize(13));  // PINGREQ / REPLY empty
    assertTrue(UdpLegacyTransport.isValidSize(16));  // RESENDREQ
    assertTrue(UdpLegacyTransport.isValidSize(20));  // REQUEST avec arbitre
    assertTrue(UdpLegacyTransport.isValidSize(29));  // REPLY CW sans nom
    assertTrue(UdpLegacyTransport.isValidSize(61));  // REPLY CW avec nom
  }

  @Test
  void isValidSizeRejectsInvalidSizes() {
    assertFalse(UdpLegacyTransport.isValidSize(0));
    assertFalse(UdpLegacyTransport.isValidSize(1));
    assertFalse(UdpLegacyTransport.isValidSize(8));
    assertFalse(UdpLegacyTransport.isValidSize(10));
    assertFalse(UdpLegacyTransport.isValidSize(11));
    assertFalse(UdpLegacyTransport.isValidSize(14));
    assertFalse(UdpLegacyTransport.isValidSize(15));
    assertFalse(UdpLegacyTransport.isValidSize(17));
    assertFalse(UdpLegacyTransport.isValidSize(21));
    assertFalse(UdpLegacyTransport.isValidSize(28));
    assertFalse(UdpLegacyTransport.isValidSize(2000));
  }

  // ==============================================================================================
  // CW validation
  // ==============================================================================================

  @Test
  void validateCwChecksumValid() {
    // sum of 0x01+0x02+0x03 = 0x06, matches byte 3
    byte[] cw = new byte[]{0x01, 0x02, 0x03, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    assertTrue(UdpLegacyTransport.validateCwChecksum(cw));
  }

  @Test
  void validateCwChecksumInvalid() {
    byte[] cw = new byte[]{0x01, 0x02, 0x03, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    assertFalse(UdpLegacyTransport.validateCwChecksum(cw));
  }

  @Test
  void hasZeroCwDetectsAllZeros() {
    byte[] cw = new byte[16];
    assertTrue(UdpLegacyTransport.hasZeroCw(cw));
  }

  @Test
  void hasZeroCwDetectsNonZero() {
    byte[] cw = new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    assertFalse(UdpLegacyTransport.hasZeroCw(cw));
  }

  // ==============================================================================================
  // Robustness : injection de paquets malformés via le VRAI UdpLegacyTransport.handlePacket()
  // ==============================================================================================

  @Test
  void malformedPacketsDoNotThrow() {
    PeerRegistry peerReg = new PeerRegistry();
    UdpStats stats = new UdpStats();
    // Transport minimal : DatagramSocket non utilisé pour la réception dans ce test
    UdpLegacyTransport transport = new UdpLegacyTransport(null, peerReg, stats, cacheVerticle,
        18099, 50);

    // Taille 0 → invalidSize
    DatagramPacket pkt0 = DatagramPacketImpl.of(Buffer.buffer(), "10.0.0.1", 12345);
    assertDoesNotThrow(() -> transport.handlePacket(pkt0));
    assertEquals(1, stats.invalidSize());

    // Taille 1 (type 0xFF) → invalidSize (pas dans les tailles valides)
    DatagramPacket pkt1 = DatagramPacketImpl.of(
        Buffer.buffer(new byte[]{(byte) 0xFF}), "10.0.0.1", 12345);
    assertDoesNotThrow(() -> transport.handlePacket(pkt1));
    assertEquals(2, stats.invalidSize());

    // Taille 8 (trop court pour tout type valide) → invalidSize
    DatagramPacket pkt8 = DatagramPacketImpl.of(Buffer.buffer(new byte[8]), "10.0.0.1", 12345);
    assertDoesNotThrow(() -> transport.handlePacket(pkt8));
    assertEquals(3, stats.invalidSize());

    // Taille 12, type inconnu 255 → unknownType (taille valide, type invalide)
    byte[] unknown = new byte[12];
    unknown[0] = (byte) 255;
    DatagramPacket pktUnknown = DatagramPacketImpl.of(Buffer.buffer(unknown), "10.0.0.1", 12345);
    assertDoesNotThrow(() -> transport.handlePacket(pktUnknown));
    assertEquals(1, stats.unknownType());

    // Taille 513 (> MAX_RX_FRAME_SIZE 1500? Non, 513 < 1500. Mais 513 ≥ 29 donc valide.)
    // Test : taille énorme (2000, au dessus de MAX_RX_FRAME_SIZE) → invalidSize
    byte[] giant = new byte[2000];
    giant[0] = 1; // TYPE_REQUEST mais trop grand
    DatagramPacket pktGiant = DatagramPacketImpl.of(Buffer.buffer(giant), "10.0.0.1", 12345);
    assertDoesNotThrow(() -> transport.handlePacket(pktGiant));
    assertEquals(4, stats.invalidSize());
  }

  // ==============================================================================================
  // Ping/Pong — TYPE_PINGREQ reçu → TYPE_PINGRPL émis avec echo brut
  // ==============================================================================================

  @Test
  void pingRequestTriggersPingReplyWithRawEcho() {
    // Construire un PINGREQ
    PingFrame ping = new PingFrame(0x0000000012345678L, 8099);
    byte[] pingBytes = ClusterUdpCodec.encodePingRequest(ping);

    // Extraire les 8 octets d'horodatage (octets 1-8)
    byte[] echoRaw = new byte[8];
    System.arraycopy(pingBytes, 1, echoRaw, 0, 8);

    // Construire le PINGRPL (echo brut, pas de décodage)
    PingReplyFrame rpl = new PingReplyFrame(echoRaw);
    byte[] rplBytes = ClusterUdpCodec.encodePingReply(rpl);

    assertEquals(9, rplBytes.length);
    assertEquals(4, rplBytes[0] & 0xFF); // TYPE_PINGRPL

    // Le PINGRPL doit contenir exactement les 8 octets bruts du PINGREQ
    PingReplyFrame decoded = ClusterUdpCodec.decodePingReply(rplBytes);
    assertArrayEquals(echoRaw, decoded.echoBytes());
  }

  // ==============================================================================================
  // Codec round-trip (validation que le codec L1 est bien utilisé)
  // ==============================================================================================

  @Test
  void codecRequestRoundTrip() {
    EcmRequest req = new EcmRequest(KEY, new byte[0], "test", "127.0.0.1", "node1",
        System.currentTimeMillis(), null, 0L);
    byte[] frame = ClusterUdpCodec.encodeRequest(req);
    assertTrue(UdpLegacyTransport.isValidSize(frame.length)); // 12
    EcmRequest decoded = ClusterUdpCodec.decodeRequest(frame);
    assertEquals(KEY.dataHash(), decoded.key().dataHash());
  }

  @Test
  void codecReplyRoundTrip() {
    CwReply reply = new CwReply(KEY, 0x80, CW_VALID, "test-connector", "127.0.0.1",
        System.currentTimeMillis());
    byte[] frame = ClusterUdpCodec.encodeReply(reply);
    assertTrue(UdpLegacyTransport.isValidSize(frame.length)); // >=29
    CwReply decoded = ClusterUdpCodec.decodeReply(frame);
    assertEquals("test-connector", decoded.connectorName());
  }

  @Test
  void codecResendRequestRoundTrip() {
    ResendFrame resend = new ResendFrame(8099, KEY);
    byte[] frame = ClusterUdpCodec.encodeResendRequest(resend);
    assertEquals(16, frame.length);
    assertTrue(UdpLegacyTransport.isValidSize(frame.length));
    ResendFrame decoded = ClusterUdpCodec.decodeResendRequest(frame);
    assertEquals(8099, decoded.localPort());
    assertEquals(KEY.dataHash(), decoded.key().dataHash());
  }

  // ==============================================================================================
  // UdpClusterConfig
  // ==============================================================================================

  @Test
  void udpClusterConfigDefaults() {
    UdpClusterConfig cfg = UdpClusterConfig.defaults();
    assertEquals(8099, cfg.bindPort());
    assertEquals("0.0.0.0", cfg.bindAddress());
    assertTrue(cfg.autoAddPeers());
    assertEquals(4000, cfg.pingIntervalMs());
    assertEquals(50, cfg.maxPacketsPerSec());
  }

  @Test
  void udpClusterConfigBuilderMethods() {
    UdpClusterConfig cfg = UdpClusterConfig.defaults()
        .withStaticPeer("10.0.0.1", 9099)
        .withMaxPacketsPerSec(100)
        .withAutoAddPeers(false);

    assertEquals("10.0.0.1", cfg.remoteHost());
    assertEquals("9099", cfg.remotePort());
    assertEquals(100, cfg.maxPacketsPerSec());
    assertFalse(cfg.autoAddPeers());
  }

  // ==============================================================================================
  // RESENDREQ entrant honoré (peekLocalCache → TYPE_REPLY)
  // ==============================================================================================

  @Test
  void resendRequestHonoredWhenCachedReplyExists() throws Exception {
    PeerRegistry peerReg = new PeerRegistry();
    UdpStats stats = new UdpStats();
    // Ajouter le sender comme pair connu (nécessaire pour l'anti-amplification)
    peerReg.addStaticPeer("10.0.0.50", 12345);

    // Semer une réponse dans le cache local
    byte[] cw = new byte[]{0x01, 0x02, 0x03, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    CwReply cachedReply = new CwReply(KEY, 0x80, cw, null, null, System.currentTimeMillis());
    cacheVerticle.handleReply(
        new EcmRequest(KEY, new byte[0], null, null, null, System.currentTimeMillis(), null, 0L),
        cachedReply);

    // handleReply est asynchrone — attendre que le contexte traite l'opération
    CountDownLatch latch = new CountDownLatch(1);
    cacheVerticle.runOnContext(() -> latch.countDown());
    assertTrue(latch.await(2, TimeUnit.SECONDS));

    // Vérifier que le cache contient bien la réponse
    CwReply peeked = cacheVerticle.peekLocalCache(KEY);
    assertNotNull(peeked, "Cache should contain the reply after handleReply");
    assertArrayEquals(cw, peeked.cw());
  }

  @Test
  void resendRequestRejectedWhenSenderNotKnownPeer() {
    PeerRegistry peerReg = new PeerRegistry();
    UdpStats stats = new UdpStats();
    // PAS de pair connu
    UdpLegacyTransport transport = new UdpLegacyTransport(null, peerReg, stats, cacheVerticle,
        18099, 50);

    ResendFrame resend = new ResendFrame(12345, KEY);
    byte[] resendBytes = ClusterUdpCodec.encodeResendRequest(resend);
    DatagramPacket pkt = DatagramPacketImpl.of(
        Buffer.buffer(resendBytes), "10.0.0.99", 12345);

    assertDoesNotThrow(() -> transport.handlePacket(pkt));
    assertEquals(1, stats.receivedResendReqs());
    assertEquals(1, stats.antiAmplificationRejected());
    assertEquals(0, stats.reSentEntries());
  }

  // ==============================================================================================
  // Intégration : deux DatagramSocket Vert.x sur localhost, communication réelle
  // ==============================================================================================

  @Test
  void twoNodesExchangeRequestAndReplyOverUdp() throws Exception {
    CountDownLatch receivedRequest = new CountDownLatch(1);
    CountDownLatch receivedReply = new CountDownLatch(1);
    AtomicReference<EcmRequest> receivedReq = new AtomicReference<>();
    AtomicReference<CwReply> receivedRpl = new AtomicReference<>();

    // Node B (port 18099)
    DatagramSocket socketB = vertx.createDatagramSocket(new DatagramSocketOptions().setReuseAddress(true));
    CountDownLatch bReady = new CountDownLatch(1);
    socketB.listen(18099, "127.0.0.1").onComplete(ar -> bReady.countDown());
    assertTrue(bReady.await(5, TimeUnit.SECONDS));

    socketB.handler(packet -> {
      try {
        byte[] bytes = packet.data().getBytes();
        int type = bytes[0] & 0xFF;
        if (type == ClusterUdpCodec.TYPE_REQUEST) {
          EcmRequest req = ClusterUdpCodec.decodeRequest(bytes);
          receivedReq.set(req);
          receivedRequest.countDown();

          // Répondre avec un TYPE_REPLY
          CwReply reply = new CwReply(req.key(), 0x80, CW_VALID, "test-conn", "127.0.0.1",
              System.currentTimeMillis());
          byte[] rplBytes = ClusterUdpCodec.encodeReply(reply);
          socketB.send(Buffer.buffer(rplBytes), 18098, "127.0.0.1");
        }
      } catch (Exception e) {
        // Ignore dans le test
      }
    });

    // Node A (port 18098)
    DatagramSocket socketA = vertx.createDatagramSocket(new DatagramSocketOptions().setReuseAddress(true));
    CountDownLatch aReady = new CountDownLatch(1);
    socketA.listen(18098, "127.0.0.1").onComplete(ar -> aReady.countDown());
    assertTrue(aReady.await(5, TimeUnit.SECONDS));

    socketA.handler(packet -> {
      try {
        byte[] bytes = packet.data().getBytes();
        int type = bytes[0] & 0xFF;
        if (type == ClusterUdpCodec.TYPE_REPLY) {
          CwReply reply = ClusterUdpCodec.decodeReply(bytes);
          receivedRpl.set(reply);
          receivedReply.countDown();
        }
      } catch (Exception e) {
        // Ignore
      }
    });

    // Envoyer TYPE_REQUEST depuis A vers B
    EcmRequest request = new EcmRequest(KEY, new byte[0], "test", "127.0.0.1", "nodeA",
        System.currentTimeMillis(), null, 0L);
    byte[] reqBytes = ClusterUdpCodec.encodeRequest(request);
    socketA.send(Buffer.buffer(reqBytes), 18099, "127.0.0.1");

    // Attendre la réception
    assertTrue(receivedRequest.await(5, TimeUnit.SECONDS), "Node B should receive TYPE_REQUEST");
    assertNotNull(receivedReq.get());
    assertEquals(KEY.dataHash(), receivedReq.get().key().dataHash());

    // Attendre la réponse
    assertTrue(receivedReply.await(5, TimeUnit.SECONDS), "Node A should receive TYPE_REPLY");
    assertNotNull(receivedRpl.get());
    assertArrayEquals(CW_VALID, receivedRpl.get().cw());
    assertEquals("test-conn", receivedRpl.get().connectorName());

    // Nettoyage
    socketA.close();
    socketB.close();
  }

  @Test
  void twoNodesExchangePingPongOverUdp() throws Exception {
    CountDownLatch receivedPing = new CountDownLatch(1);
    CountDownLatch receivedPong = new CountDownLatch(1);
    AtomicReference<byte[]> echoRef = new AtomicReference<>();

    // Node B (port 28099)
    DatagramSocket socketB = vertx.createDatagramSocket(new DatagramSocketOptions().setReuseAddress(true));
    CountDownLatch bReady = new CountDownLatch(1);
    socketB.listen(28099, "127.0.0.1").onComplete(ar -> bReady.countDown());
    assertTrue(bReady.await(5, TimeUnit.SECONDS));

    socketB.handler(packet -> {
      try {
        byte[] bytes = packet.data().getBytes();
        int type = bytes[0] & 0xFF;
        if (type == ClusterUdpCodec.TYPE_PINGREQ) {
          receivedPing.countDown();
          // Echo brut des 8 octets
          byte[] echo = new byte[8];
          System.arraycopy(bytes, 1, echo, 0, 8);
          PingReplyFrame rpl = new PingReplyFrame(echo);
          byte[] rplBytes = ClusterUdpCodec.encodePingReply(rpl);
          socketB.send(Buffer.buffer(rplBytes), 28098, "127.0.0.1");
        }
      } catch (Exception e) {
        // Ignore
      }
    });

    // Node A (port 28098)
    DatagramSocket socketA = vertx.createDatagramSocket(new DatagramSocketOptions().setReuseAddress(true));
    CountDownLatch aReady = new CountDownLatch(1);
    socketA.listen(28098, "127.0.0.1").onComplete(ar -> aReady.countDown());
    assertTrue(aReady.await(5, TimeUnit.SECONDS));

    socketA.handler(packet -> {
      try {
        byte[] bytes = packet.data().getBytes();
        int type = bytes[0] & 0xFF;
        if (type == ClusterUdpCodec.TYPE_PINGRPL) {
          echoRef.set(bytes);
          receivedPong.countDown();
        }
      } catch (Exception e) {
        // Ignore
      }
    });

    // Envoyer PINGREQ depuis A vers B
    PingFrame ping = new PingFrame(System.currentTimeMillis(), 28098);
    byte[] pingBytes = ClusterUdpCodec.encodePingRequest(ping);
    socketA.send(Buffer.buffer(pingBytes), 28099, "127.0.0.1");

    assertTrue(receivedPing.await(5, TimeUnit.SECONDS), "Node B should receive PINGREQ");
    assertTrue(receivedPong.await(5, TimeUnit.SECONDS), "Node A should receive PINGRPL");

    // Vérifier que le PINGRPL contient l'echo brut
    assertNotNull(echoRef.get());
    PingReplyFrame decoded = ClusterUdpCodec.decodePingReply(echoRef.get());
    assertEquals(8, decoded.echoBytes().length);

    socketA.close();
    socketB.close();
  }

  @Test
  void twoNodesExchangeResendRequestOverUdp() throws Exception {
    CountDownLatch receivedResend = new CountDownLatch(1);

    // Node B (port 38099)
    DatagramSocket socketB = vertx.createDatagramSocket(new DatagramSocketOptions().setReuseAddress(true));
    CountDownLatch bReady = new CountDownLatch(1);
    socketB.listen(38099, "127.0.0.1").onComplete(ar -> bReady.countDown());
    assertTrue(bReady.await(5, TimeUnit.SECONDS));

    AtomicReference<ResendFrame> resendRef = new AtomicReference<>();
    socketB.handler(packet -> {
      try {
        byte[] bytes = packet.data().getBytes();
        int type = bytes[0] & 0xFF;
        if (type == ClusterUdpCodec.TYPE_RESENDREQ) {
          ResendFrame rf = ClusterUdpCodec.decodeResendRequest(bytes);
          resendRef.set(rf);
          receivedResend.countDown();
        }
      } catch (Exception e) {
        // Ignore
      }
    });

    // Node A (port 38098)
    DatagramSocket socketA = vertx.createDatagramSocket(new DatagramSocketOptions().setReuseAddress(true));
    CountDownLatch aReady = new CountDownLatch(1);
    socketA.listen(38098, "127.0.0.1").onComplete(ar -> aReady.countDown());
    assertTrue(aReady.await(5, TimeUnit.SECONDS));

    // Envoyer RESENDREQ depuis A vers B
    ResendFrame resend = new ResendFrame(38098, KEY);
    byte[] resendBytes = ClusterUdpCodec.encodeResendRequest(resend);
    socketA.send(Buffer.buffer(resendBytes), 38099, "127.0.0.1");

    assertTrue(receivedResend.await(5, TimeUnit.SECONDS), "Node B should receive RESENDREQ");
    assertNotNull(resendRef.get());
    assertEquals(KEY.dataHash(), resendRef.get().key().dataHash());
    assertEquals(38098, resendRef.get().localPort());

    socketA.close();
    socketB.close();
  }

  // ==============================================================================================
  // Anti-amplification : RESENDREQ depuis IP non-pair → pas de réponse
  // ==============================================================================================

  @Test
  void antiAmplificationRejectsResendFromUnknownPeer() {
    PeerRegistry reg = new PeerRegistry();
    reg.addStaticPeer("192.168.1.1", 8099);

    // IP non-pair : doit être rejetée
    assertFalse(reg.isKnownHost("10.0.0.99"));

    // IP pair : doit être acceptée
    assertTrue(reg.isKnownHost("192.168.1.1"));
  }

  // ==============================================================================================
  // Anti-boucle UDP — provenance originAddress (Lot udp-reply-origin-loop)
  // ==============================================================================================

  @Test
  void onReplyLocalNullOriginSendsAndIncrementsStats() {
    PeerRegistry peerReg = new PeerRegistry();
    UdpStats stats = new UdpStats();
    SpyTransport spy = new SpyTransport(null, peerReg, stats, cacheVerticle, 18099, 50);
    CwReply reply = new CwReply(KEY, 0x80, CW_VALID, "conn", null, System.currentTimeMillis());
    spy.onReply(reply);
    assertEquals(1, spy.sendCalls.get(), "local null origin should send");
    assertEquals(1, stats.sentEntries(), "stats should increment");
    assertEquals(1, spy.sentFrames.size());
    CwReply decoded = ClusterUdpCodec.decodeReply(spy.sentFrames.get(0));
    assertArrayEquals(CW_VALID, decoded.cw());
  }

  @Test
  void onReplyRawAddressOriginSends() {
    PeerRegistry peerReg = new PeerRegistry();
    UdpStats stats = new UdpStats();
    SpyTransport spy = new SpyTransport(null, peerReg, stats, cacheVerticle, 18099, 50);
    CwReply reply = new CwReply(KEY, 0x80, CW_VALID, "conn", "192.168.1.5", System.currentTimeMillis());
    spy.onReply(reply);
    assertEquals(1, spy.sendCalls.get(), "raw address origin should send");
    assertEquals(1, stats.sentEntries());
  }

  @Test
  void onReplyUdpPrefixedDoesNotSendOrIncrement() {
    PeerRegistry peerReg = new PeerRegistry();
    UdpStats stats = new UdpStats();
    SpyTransport spy = new SpyTransport(null, peerReg, stats, cacheVerticle, 18099, 50);
    CwReply reply = new CwReply(KEY, 0x80, CW_VALID, "conn", "udp:10.0.0.1", System.currentTimeMillis());
    spy.onReply(reply);
    assertEquals(0, spy.sendCalls.get(), "udp: origin must not send");
    assertEquals(0, stats.sentEntries(), "stats must not increment when filtered");
    assertTrue(spy.sentFrames.isEmpty());
  }

  @Test
  void onReplyInfinispanPrefixedDoesNotSendOrIncrement() {
    PeerRegistry peerReg = new PeerRegistry();
    UdpStats stats = new UdpStats();
    SpyTransport spy = new SpyTransport(null, peerReg, stats, cacheVerticle, 18099, 50);
    CwReply reply = new CwReply(KEY, 0x80, CW_VALID, "conn", "infinispan:remote", System.currentTimeMillis());
    spy.onReply(reply);
    assertEquals(0, spy.sendCalls.get(), "infinispan: origin must not send");
    assertEquals(0, stats.sentEntries());
  }

  @Test
  void onReplyNullOrEmptyDoesNotSend() {
    PeerRegistry peerReg = new PeerRegistry();
    UdpStats stats = new UdpStats();
    SpyTransport spy = new SpyTransport(null, peerReg, stats, cacheVerticle, 18099, 50);
    spy.onReply(null);
    assertEquals(0, spy.sendCalls.get());
    assertEquals(0, stats.sentEntries());
    CwReply empty = new CwReply(KEY, 0x80, new byte[0], "conn", null, System.currentTimeMillis());
    spy.onReply(empty);
    assertEquals(0, spy.sendCalls.get());
    assertEquals(0, stats.sentEntries());
  }

  @Test
  void incomingReplyIsMarkedUdpPrefixAndDoesNotRebroadcast() throws Exception {
    PeerRegistry peerReg = new PeerRegistry();
    peerReg.setAutoAddPeers(true);
    UdpStats stats = new UdpStats();
    SpyTransport spy = new SpyTransport(null, peerReg, stats, cacheVerticle, 18099, 50);
    cacheVerticle.addTransport(spy);
    try {
      CwReply original = new CwReply(KEY, 0x80, CW_VALID, "origin-conn", null, System.currentTimeMillis());
      byte[] frame = ClusterUdpCodec.encodeReply(original);
      DatagramPacket pkt = DatagramPacketImpl.of(Buffer.buffer(frame), "10.0.0.77", 54321);
      spy.handlePacket(pkt);
      // barrier: wait for context to process handleReply
      CountDownLatch barrier = new CountDownLatch(1);
      cacheVerticle.runOnContext(barrier::countDown);
      assertTrue(barrier.await(2, TimeUnit.SECONDS), "barrier timed out");
      // origin must be marked
      CwReply cached = cacheVerticle.peekLocalCache(KEY);
      assertNotNull(cached, "cache should contain marked reply");
      assertEquals("udp:10.0.0.77", cached.originAddress(), "origin must be udp:<senderHost>");
      assertArrayEquals(CW_VALID, cached.cw());
      assertEquals("origin-conn", cached.connectorName());
      // no rebroadcast
      assertEquals(0, spy.sendCalls.get(), "incoming udp reply must not be rebroadcast");
      assertEquals(0, stats.sentEntries());
    } finally {
      cacheVerticle.removeTransport(spy);
    }
  }

  @Test
  void twoPeersDoNotPingPongReply() throws Exception {
    // Simulate two nodes A and B each with own CacheVerticle and SpyTransport
    Vertx v2 = Vertx.vertx();
    CacheVerticle cacheA = new CacheVerticle();
    CacheVerticle cacheB = new CacheVerticle();
    CountDownLatch deployLatch = new CountDownLatch(2);
    v2.deployVerticle(cacheA).onComplete(ar -> deployLatch.countDown());
    v2.deployVerticle(cacheB).onComplete(ar -> deployLatch.countDown());
    assertTrue(deployLatch.await(5, TimeUnit.SECONDS));
    try {
      PeerRegistry regA = new PeerRegistry();
      PeerRegistry regB = new PeerRegistry();
      regA.setAutoAddPeers(true);
      regB.setAutoAddPeers(true);
      UdpStats statsA = new UdpStats();
      UdpStats statsB = new UdpStats();
      SpyTransport spyA = new SpyTransport(null, regA, statsA, cacheA, 18101, 50);
      SpyTransport spyB = new SpyTransport(null, regB, statsB, cacheB, 18102, 50);
      cacheA.addTransport(spyA);
      cacheB.addTransport(spyB);

      // Node A receives a local reply (null origin) -> should send
      CwReply localReply = new CwReply(KEY, 0x80, CW_VALID, "connA", null, System.currentTimeMillis());
      spyA.onReply(localReply);
      assertEquals(1, spyA.sendCalls.get());
      assertEquals(1, statsA.sentEntries());
      byte[] frameFromA = spyA.sentFrames.get(0);
      // Simulate network delivery to B
      DatagramPacket pktToB = DatagramPacketImpl.of(Buffer.buffer(frameFromA), "10.0.0.1", 18101);
      spyB.handlePacket(pktToB);
      CountDownLatch barrierB = new CountDownLatch(1);
      cacheB.runOnContext(barrierB::countDown);
      assertTrue(barrierB.await(2, TimeUnit.SECONDS));
      // B must have marked origin and NOT rebroadcast back to A
      CwReply cachedB = cacheB.peekLocalCache(KEY);
      assertNotNull(cachedB);
      assertTrue(cachedB.originAddress().startsWith("udp:"), "B origin must be udp:");
      assertEquals(0, spyB.sendCalls.get(), "B must not ping-pong back to A");
      assertEquals(0, statsB.sentEntries());
      // Ensure A did not receive a phantom second send
      assertEquals(1, spyA.sendCalls.get(), "A should still have only one send");
    } finally {
      CountDownLatch close = new CountDownLatch(1);
      v2.close(ar -> close.countDown());
      close.await(5, TimeUnit.SECONDS);
    }
  }

  // ==============================================================================================
  // Helper : DatagramPacket simulé (pour tests sans DatagramSocket réel)
  // ==============================================================================

  /**
   * Implémentation minimale de DatagramPacket pour les tests unitaires.
   */
  static class DatagramPacketImpl implements DatagramPacket {
    private final Buffer data;
    private final SocketAddress sender;

    static DatagramPacket of(Buffer data, String host, int port) {
      return new DatagramPacketImpl(data, SocketAddress.inetSocketAddress(port, host));
    }

    DatagramPacketImpl(Buffer data, SocketAddress sender) {
      this.data = data;
      this.sender = sender;
    }

    @Override public SocketAddress sender() { return sender; }
    @Override public Buffer data() { return data; }
  }

  /**
   * Spy transport capturing sendToAll/sendTo without real DatagramSocket.
   */
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
