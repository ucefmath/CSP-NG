package com.bowman.cspng.app.runtime;

import com.bowman.cspng.cluster.tcp.CspV2Manager;
import com.bowman.cspng.codec.cspv2.CspV2Payload;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CspV2CacheTransportTest {

    private CspV2DomainMapper mapper;
    private CspV2CacheTransport transport;
    private AtomicReference<EcmRequest> capturedDomainRequest;
    private AtomicReference<CwReply> capturedCacheReply;

    @BeforeEach
    void setUp() {
        mapper = new CspV2DomainMapper(() -> 1000L);
        capturedDomainRequest = new AtomicReference<>();
        capturedCacheReply = new AtomicReference<>();

        transport = new CspV2CacheTransport(mapper,
                req -> Future.failedFuture("not wired"),
                (req, reply) -> {
                    capturedDomainRequest.set(req);
                    capturedCacheReply.set(reply);
                });
    }

    private static byte[] createValidCw() {
        byte[] cw = new byte[16];
        for (int i = 0; i < 16; i++) cw[i] = (byte) (i + 1);
        cw[3] = (byte) ((cw[0] + cw[1] + cw[2]) & 0xFF);
        cw[7] = (byte) ((cw[4] + cw[5] + cw[6]) & 0xFF);
        cw[11] = (byte) ((cw[8] + cw[9] + cw[10]) & 0xFF);
        cw[15] = (byte) ((cw[12] + cw[13] + cw[14]) & 0xFF);
        return cw;
    }

    private static EcmRequest createDomainRequest() {
        byte[] body = new byte[] { (byte) 0x02, (byte) 0x03, (byte) 0x04 };
        int hash = CspV2DomainMapper.ecmHash(body, 0, body.length);
        EcmKey key = new EcmKey(0x80, 0x100, 0x200, 0x300, hash);
        return new EcmRequest(key, body, "profile1", "remote1", "origin1", 0L, null, 5000);
    }

    // -- OK : wire status OK, valid CW → cache.handleReply called exactly 1, provenance cspv2:node-b --

    @Test void onRequestOkCallsCacheOnceWithProvenance() throws Exception {
        EcmRequest domainReq = createDomainRequest();
        byte[] cw = createValidCw();

        CountDownLatch latch = new CountDownLatch(1);
        transport = new CspV2CacheTransport(mapper,
                req -> {
                    CspV2Payload.DcwReply okReply = new CspV2Payload.DcwReply(
                            req.commandTag(), req.serviceId(), req.networkId(), req.caId(),
                            req.dataHash(), 0x42, CspV2Payload.DcwStatus.OK, cw, "connA");
                    CspV2Manager.CspV2PeerReply pr = new CspV2Manager.CspV2PeerReply("node-b", okReply);
                    return Future.succeededFuture(pr);
                },
                (req, reply) -> {
                    capturedDomainRequest.set(req);
                    capturedCacheReply.set(reply);
                    latch.countDown();
                });

        transport.onRequest(domainReq);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertEquals(domainReq, capturedDomainRequest.get());
        assertNotNull(capturedCacheReply.get());
        assertFalse(capturedCacheReply.get().isEmpty());
        assertArrayEquals(cw, capturedCacheReply.get().cw());
        assertEquals("cspv2:node-b", capturedCacheReply.get().originAddress());
        assertEquals("connA", capturedCacheReply.get().connectorName());
    }

    // -- NOT_FOUND → no cache call --

    @Test void onRequestNotFoundNoCacheCall() throws Exception {
        EcmRequest domainReq = createDomainRequest();
        CountDownLatch latch = new CountDownLatch(1);

        transport = new CspV2CacheTransport(mapper,
                req -> {
                    CspV2Payload.DcwReply nf = new CspV2Payload.DcwReply(
                            req.commandTag(), req.serviceId(), req.networkId(), req.caId(),
                            req.dataHash(), 0, CspV2Payload.DcwStatus.NOT_FOUND,
                            new byte[0], "");
                    return Future.succeededFuture(new CspV2Manager.CspV2PeerReply("node-b", nf));
                },
                (req, reply) -> { capturedCacheReply.set(reply); latch.countDown(); });

        transport.onRequest(domainReq);
        assertFalse(latch.await(1, TimeUnit.SECONDS)); // callback should NOT fire
        assertNull(capturedCacheReply.get());
    }

    // -- DENIED → no cache call --

    @Test void onRequestDeniedNoCacheCall() throws Exception {
        EcmRequest domainReq = createDomainRequest();
        CountDownLatch latch = new CountDownLatch(1);

        transport = new CspV2CacheTransport(mapper,
                req -> {
                    CspV2Payload.DcwReply denied = new CspV2Payload.DcwReply(
                            req.commandTag(), req.serviceId(), req.networkId(), req.caId(),
                            req.dataHash(), 0, CspV2Payload.DcwStatus.DENIED,
                            new byte[0], "");
                    return Future.succeededFuture(new CspV2Manager.CspV2PeerReply("node-b", denied));
                },
                (req, reply) -> { capturedCacheReply.set(reply); latch.countDown(); });

        transport.onRequest(domainReq);
        assertFalse(latch.await(1, TimeUnit.SECONDS));
        assertNull(capturedCacheReply.get());
    }

    // -- Future failed → no cache call --

    @Test void onRequestFailureNoCacheCall() throws Exception {
        EcmRequest domainReq = createDomainRequest();
        CountDownLatch latch = new CountDownLatch(1);

        transport = new CspV2CacheTransport(mapper,
                req -> Future.failedFuture("no peers"),
                (req, reply) -> { capturedCacheReply.set(reply); latch.countDown(); });

        transport.onRequest(domainReq);
        assertFalse(latch.await(1, TimeUnit.SECONDS));
        assertNull(capturedCacheReply.get());
    }

    // -- Null PeerReply → no cache call --

    @Test void onRequestNullPeerReplyNoCacheCall() throws Exception {
        EcmRequest domainReq = createDomainRequest();
        CountDownLatch latch = new CountDownLatch(1);

        transport = new CspV2CacheTransport(mapper,
                req -> Future.succeededFuture(null),
                (req, reply) -> { capturedCacheReply.set(reply); latch.countDown(); });

        transport.onRequest(domainReq);
        assertFalse(latch.await(1, TimeUnit.SECONDS));
        assertNull(capturedCacheReply.get());
    }

    // -- Failed checksum in domain mapping → no cache call --

    @Test void onRequestBadChecksumNoCacheCall() throws Exception {
        EcmRequest domainReq = createDomainRequest();
        byte[] badCw = new byte[16];
        for (int i = 0; i < 16; i++) badCw[i] = (byte) (i + 1);
        badCw[3] = (byte) 0xFF; // wrong checksum
        CountDownLatch latch = new CountDownLatch(1);

        transport = new CspV2CacheTransport(mapper,
                req -> {
                    CspV2Payload.DcwReply okReply = new CspV2Payload.DcwReply(
                            req.commandTag(), req.serviceId(), req.networkId(), req.caId(),
                            req.dataHash(), 0x01, CspV2Payload.DcwStatus.OK, badCw, "conn");
                    return Future.succeededFuture(new CspV2Manager.CspV2PeerReply("node-b", okReply));
                },
                (req, reply) -> { capturedCacheReply.set(reply); latch.countDown(); });

        transport.onRequest(domainReq);
        assertFalse(latch.await(1, TimeUnit.SECONDS));
        assertNull(capturedCacheReply.get());
    }

    // -- Mismatched fields → no cache call --

    @Test void onRequestMismatchedFieldsNoCacheCall() throws Exception {
        EcmRequest domainReq = createDomainRequest();
        byte[] cw = createValidCw();
        CountDownLatch latch = new CountDownLatch(1);

        transport = new CspV2CacheTransport(mapper,
                req -> {
                    // Wrong serviceId in reply
                    CspV2Payload.DcwReply okReply = new CspV2Payload.DcwReply(
                            req.commandTag(), 0x9999, req.networkId(), req.caId(),
                            req.dataHash(), 0x01, CspV2Payload.DcwStatus.OK, cw, "conn");
                    return Future.succeededFuture(new CspV2Manager.CspV2PeerReply("node-b", okReply));
                },
                (req, reply) -> { capturedCacheReply.set(reply); latch.countDown(); });

        transport.onRequest(domainReq);
        assertFalse(latch.await(1, TimeUnit.SECONDS));
        assertNull(capturedCacheReply.get());
    }

    // -- Null request → no-op --

    @Test void onRequestNullIsNoop() {
        assertDoesNotThrow(() -> transport.onRequest(null));
        assertNull(capturedCacheReply.get());
    }

    // -- onReply is NOOP --

    @Test void onReplyIsNoop() {
        EcmKey key = new EcmKey(0x80, 0, 0, 0, 42);
        CwReply reply = new CwReply(key, 0, new byte[0], null, "origin", 0L);
        assertDoesNotThrow(() -> transport.onReply(reply));
    }
}
