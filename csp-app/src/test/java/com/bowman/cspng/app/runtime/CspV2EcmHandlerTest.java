package com.bowman.cspng.app.runtime;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.cspv2.CspV2Payload;
import com.bowman.cspng.connectors.AbstractConnector;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.connectors.catalog.CardCapability;
import com.bowman.cspng.connectors.catalog.CardDiscoveryService;
import com.bowman.cspng.connectors.catalog.CardSource;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.domain.dcw.ChecksumMode;
import com.bowman.cspng.domain.dcw.DcwDigest;
import com.bowman.cspng.domain.dcw.DcwQualityPolicy;
import com.bowman.cspng.domain.dcw.DcwRule;
import com.bowman.cspng.domain.dcw.DcwRuleAction;
import com.bowman.cspng.domain.dcw.DcwRuleScope;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class CspV2EcmHandlerTest {

    private CspV2DomainMapper mapper;
    private CspV2EcmHandler handler;
    private Vertx vertx;

    @BeforeEach
    void setUp() {
        mapper = new CspV2DomainMapper(() -> 1000L);
        // Package-private test constructor
        handler = new CspV2EcmHandler(mapper, false,
                key -> null,
                req -> Future.failedFuture("not wired"));
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (vertx != null) vertx.close().onComplete(ctx.succeedingThenComplete());
        else ctx.completeNow();
    }

    private static CspV2Payload.EcmRequest createWireRequest() {
        byte[] ecm = new byte[] { (byte) 0x80, 0x00, 0x01, 0x02, 0x03, 0x04 };
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        return new CspV2Payload.EcmRequest(0x80, 0x100, 0x200, 0x300, hash, "profile1", ecm, 5000);
    }

    private static CspV2Payload.EcmRequest viaccessWireRequest() {
        byte[] ecm = {(byte) 0x80, 0, 6, (byte) 0x90, 3, 3, 0x28, 0x30, 0};
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        return new CspV2Payload.EcmRequest(0x80, 0x100, 0x200, 0x0500, hash,
            "profile1", ecm, 5000);
    }

    @Test
    @DisplayName("IGNORE provider connu : nouvelle paire negotiated transporte CW sans checksum ; ancien pair strict")
    void providerScopedIgnoreAcrossNegotiatedHandler() {
        var wire = viaccessWireRequest();
        byte[] cw = createValidCw();
        cw[3] = 0;
        handler = new CspV2EcmHandler(mapper, false, k -> null,
            req -> Future.succeededFuture(new CwReply(req.key(), 0x80, cw,
                "conn1", "origin", 1000L)));
        DcwRule rule = new DcwRule(1L, new DcwRuleScope("profile1", 0x0500, 0x032830, 0, null),
            null, DcwRuleAction.NO_ACTION, ChecksumMode.IGNORE, "", true, 0L, 0L);
        handler.setDcwPolicySupplier(() -> new DcwQualityPolicy(true, false, false,
            ChecksumMode.INHERIT, true, List.of(rule)));
        assertEquals(CspV2Payload.DcwStatus.OK,
            handler.onEcmRequest("peer01", wire, true).result().status());
        assertEquals(CspV2Payload.DcwStatus.NOT_FOUND,
            handler.onEcmRequest("peer01", wire, false).result().status());
    }

    @Test
    @DisplayName("Négociation sans règle scoped : checksum historique conservé ; 0 Nagra n'est pas inconnu")
    void negotiatedCapDoesNotImplyGlobalPermissive() {
        byte[] ecm = new byte[]{(byte) 0x80, 0, 2, 0x01, 0x02};
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        var wire = new CspV2Payload.EcmRequest(0x80, 0x100, 0x200, 0x1800, hash, "profile1", ecm, 5000);
        byte[] bad = createValidCw();
        bad[3] = 0;
        handler = new CspV2EcmHandler(mapper, false, k -> null,
            req -> Future.succeededFuture(new CwReply(req.key(), 0x80, bad, "conn1", "origin", 1000L)));
        handler.setDcwPolicySupplier(() -> new DcwQualityPolicy(true, false, false,
            ChecksumMode.INHERIT, true, List.of()));
        assertEquals(CspV2Payload.DcwStatus.NOT_FOUND,
            handler.onEcmRequest("peer01", wire, true).result().status(),
            "CAP négociée ne relaxe pas le défaut historique");
        assertFalse(handler.acceptsReply("peer01", wire,
            new CspV2Payload.DcwReply(0x80, 0x100, 0x200, 0x1800, hash, 0x80,
                CspV2Payload.DcwStatus.OK, bad, ""), true));
    }

    @Test
    @DisplayName("REJECT_VALID provider connu : checksum valide rejeté, non-checksum admis sur pair négocié")
    void providerScopedRejectValidAcrossNegotiatedHandler() {
        var wire = viaccessWireRequest();
        byte[] goodChecksum = createValidCw();
        byte[] noChecksum = createValidCw();
        noChecksum[3] = 0;
        java.util.concurrent.atomic.AtomicReference<byte[]> value = new java.util.concurrent.atomic.AtomicReference<>(goodChecksum);
        handler = new CspV2EcmHandler(mapper, false, k -> null,
            req -> Future.succeededFuture(new CwReply(req.key(), 0x80, value.get(),
                "conn1", "origin", 1000L)));
        DcwRule rule = new DcwRule(1L, new DcwRuleScope("profile1", 0x0500, 0x032830, 0, null),
            null, DcwRuleAction.NO_ACTION, ChecksumMode.REJECT_VALID, "", true, 0L, 0L);
        handler.setDcwPolicySupplier(() -> new DcwQualityPolicy(true, false, false,
            ChecksumMode.INHERIT, true, List.of(rule)));
        assertEquals(CspV2Payload.DcwStatus.NOT_FOUND,
            handler.onEcmRequest("peer01", wire, true).result().status());
        value.set(noChecksum);
        assertEquals(CspV2Payload.DcwStatus.OK,
            handler.onEcmRequest("peer01", wire, true).result().status());
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

    // -- Cache hit → wire OK, connector NOT called --

    @Test void cacheHitReturnsWireOk() {
        CspV2Payload.EcmRequest wireReq = createWireRequest();
        byte[] cw = createValidCw();
        EcmKey key = new EcmKey(0x80, 0x100, 0x200, 0x300, wireReq.dataHash());
        CwReply cachedReply = new CwReply(key, 0x42, cw, "conn1",
                CspV2DomainMapper.originId("peer01"), 500L);

        AtomicBoolean connectorCalled = new AtomicBoolean(false);
        handler = new CspV2EcmHandler(mapper, false,
                k -> cachedReply,
                req -> { connectorCalled.set(true); return Future.failedFuture("no"); });

        CspV2Payload.DcwReply result = handler.onEcmRequest("peer01", wireReq).result();
        assertNotNull(result);
        assertEquals(CspV2Payload.DcwStatus.OK, result.status());
        assertEquals(0x42, result.replyTag());
        assertFalse(connectorCalled.get());
    }

    @Test
    @DisplayName("Règle BLOCK activée après stockage : cache hit rejeté, fallback connecteur avec CW différente")
    void cacheHitRevalidatedOnLiveRuleChange() {
        CspV2Payload.EcmRequest wireReq = createWireRequest();
        byte[] blocked = createValidCw();
        byte[] replacement = createValidCw();
        replacement[0] = 2;
        replacement[3] = (byte) (replacement[0] + replacement[1] + replacement[2]);
        EcmKey key = new EcmKey(0x80, 0x100, 0x200, 0x300, wireReq.dataHash());
        CwReply cached = new CwReply(key, 0x41, blocked, "conn1", "origin", 500L);
        CwReply fresh = new CwReply(key, 0x42, replacement, "conn2", "origin", 1000L);
        AtomicInteger dispatches = new AtomicInteger();
        handler = new CspV2EcmHandler(mapper, false, k -> cached,
                req -> { dispatches.incrementAndGet(); return Future.succeededFuture(fresh); });
        DcwRule block = new DcwRule(1L, new DcwRuleScope("profile1", 0x300, null, 0x100, null),
                DcwDigest.digestSha256Hex(blocked), DcwRuleAction.BLOCK, ChecksumMode.INHERIT,
                "", true, 0L, 0L);
        handler.setDcwPolicySupplier(() -> new DcwQualityPolicy(true, false, false,
                ChecksumMode.INHERIT, true, List.of(block)));
        CspV2Payload.DcwReply result = handler.onEcmRequest("peer01", wireReq).result();
        assertEquals(1, dispatches.get());
        assertEquals(CspV2Payload.DcwStatus.OK, result.status());
        assertArrayEquals(replacement, result.cw());
    }

    // -- Cache hit but fields mismatch → fallthrough connector --

    @Test void cacheHitCollisionFallsThroughToConnector() {
        CspV2Payload.EcmRequest wireReq = createWireRequest();
        byte[] cw = createValidCw();
        EcmKey differentKey = new EcmKey(0x80, 0x999, 0x200, 0x300, wireReq.dataHash());
        CwReply cachedReply = new CwReply(differentKey, 0x01, cw, "bad", "origin", 0L);

        EcmKey correctKey = new EcmKey(0x80, 0x100, 0x200, 0x300, wireReq.dataHash());
        CwReply connectorReply = new CwReply(correctKey, 0x02, cw, "connA",
                CspV2DomainMapper.originId("peer01"), 1000L);

        handler = new CspV2EcmHandler(mapper, false,
                k -> cachedReply,
                req -> Future.succeededFuture(connectorReply));

        CspV2Payload.DcwReply result = handler.onEcmRequest("peer01", wireReq).result();
        assertEquals(CspV2Payload.DcwStatus.OK, result.status());
        assertEquals(0x02, result.replyTag());
    }

    // -- Cache hit invalid CW → fallthrough connector --

    @Test void cacheHitInvalidCwFallsThroughToConnector() {
        CspV2Payload.EcmRequest wireReq = createWireRequest();
        byte[] cw = createValidCw();
        byte[] badCw = new byte[16];
        for (int i = 0; i < 16; i++) badCw[i] = (byte) (i + 1);
        badCw[3] = (byte) 0xFF;

        EcmKey key = new EcmKey(0x80, 0x100, 0x200, 0x300, wireReq.dataHash());
        CwReply badCached = new CwReply(key, 0x01, badCw, "bad", "origin", 0L);
        CwReply connectorReply = new CwReply(key, 0x02, cw, "connA",
                CspV2DomainMapper.originId("peer01"), 1000L);

        handler = new CspV2EcmHandler(mapper, false,
                k -> badCached,
                req -> Future.succeededFuture(connectorReply));

        CspV2Payload.DcwReply result = handler.onEcmRequest("peer01", wireReq).result();
        assertEquals(CspV2Payload.DcwStatus.OK, result.status());
        assertEquals(0x02, result.replyTag());
    }

    // -- Cache miss → connector OK --

    @Test void cacheMissConnectorOk() {
        CspV2Payload.EcmRequest wireReq = createWireRequest();
        byte[] cw = createValidCw();
        EcmKey key = new EcmKey(0x80, 0x100, 0x200, 0x300, wireReq.dataHash());
        CwReply domainReply = new CwReply(key, 0x01, cw, "connA",
                CspV2DomainMapper.originId("peer01"), 1000L);

        handler = new CspV2EcmHandler(mapper, false,
                k -> null,
                req -> Future.succeededFuture(domainReply));

        CspV2Payload.DcwReply result = handler.onEcmRequest("peer01", wireReq).result();
        assertEquals(CspV2Payload.DcwStatus.OK, result.status());
        assertEquals(0x01, result.replyTag());
    }

    // -- Connector failure → NOT_FOUND --

    @Test void connectorFailureReturnsNotFound() {
        CspV2Payload.EcmRequest wireReq = createWireRequest();
        handler = new CspV2EcmHandler(mapper, false,
                k -> null, req -> Future.failedFuture("down"));
        assertEquals(CspV2Payload.DcwStatus.NOT_FOUND,
                handler.onEcmRequest("peer01", wireReq).result().status());
    }

    // -- Connector returns null/empty → NOT_FOUND --

    @Test void nullFutureReturnsNotFound() {
        CspV2Payload.EcmRequest wireReq = createWireRequest();
        handler = new CspV2EcmHandler(mapper, false, k -> null, req -> null);
        assertEquals(CspV2Payload.DcwStatus.NOT_FOUND,
                handler.onEcmRequest("peer01", wireReq).result().status());
    }

    @Test void nullReplyReturnsNotFound() {
        CspV2Payload.EcmRequest wireReq = createWireRequest();
        handler = new CspV2EcmHandler(mapper, false,
                k -> null, req -> Future.succeededFuture(null));
        assertEquals(CspV2Payload.DcwStatus.NOT_FOUND,
                handler.onEcmRequest("peer01", wireReq).result().status());
    }

    @Test void emptyReplyReturnsNotFound() {
        CspV2Payload.EcmRequest wireReq = createWireRequest();
        EcmKey emptyKey = new EcmKey(0x80, 0, 0, 0, wireReq.dataHash());
        CwReply emptyReply = new CwReply(emptyKey, 0, new byte[0], null, "origin", 0L);
        handler = new CspV2EcmHandler(mapper, false,
                k -> null, req -> Future.succeededFuture(emptyReply));
        assertEquals(CspV2Payload.DcwStatus.NOT_FOUND,
                handler.onEcmRequest("peer01", wireReq).result().status());
    }

    // -- Connector returns mismatched CW → NOT_FOUND --

    @Test void mismatchedCwReturnsNotFound() {
        CspV2Payload.EcmRequest wireReq = createWireRequest();
        byte[] badCw = new byte[16];
        for (int i = 0; i < 16; i++) badCw[i] = (byte) (i + 1);
        badCw[3] = (byte) 0xFF;
        EcmKey key = new EcmKey(0x80, 0x100, 0x200, 0x300, wireReq.dataHash());
        CwReply badReply = new CwReply(key, 0x01, badCw, "c", "origin", 0L);
        handler = new CspV2EcmHandler(mapper, false,
                k -> null, req -> Future.succeededFuture(badReply));
        assertEquals(CspV2Payload.DcwStatus.NOT_FOUND,
                handler.onEcmRequest("peer01", wireReq).result().status());
    }

    // -- Invalid peer → NOT_FOUND --

    @Test void invalidPeerReturnsNotFound() {
        CspV2Payload.EcmRequest wireReq = createWireRequest();
        assertEquals(CspV2Payload.DcwStatus.NOT_FOUND,
                handler.onEcmRequest("INVALID!", wireReq).result().status());
    }

    @Test void onEstablishedAndClosedAreNoOp() {
        handler.onEstablished("peer01");
        handler.onClosed("peer01", "reason");
    }

    // ====================================================================
    // Production wiring test: uses real CwsConnectorManager + StubCacheVerticle,
    // no strategy overrides. Proves cache lookup is called and connector is not.
    // ====================================================================

    /**
     * StubCacheVerticle that overrides {@code peekLocalCache} to return a known
     * valid cached CW and count lookup calls. Never deployed as a verticle.
     */
    static class StubCacheVerticle extends CacheVerticle {
        final AtomicInteger lookupCalls = new AtomicInteger();
        volatile CwReply cachedReply;

        @Override
        public CwReply peekLocalCache(com.bowman.cspng.domain.EcmKey key) {
            lookupCalls.incrementAndGet();
            return cachedReply;
        }
    }

    @Test void productionWiringCacheHitOkConnectorNotCalled(Vertx vertx, VertxTestContext ctx) {
        this.vertx = vertx;
        StubCacheVerticle stubCache = new StubCacheVerticle();
        CwsConnectorManager connectorMgr = new CwsConnectorManager(vertx, stubCache);

        CspV2Payload.EcmRequest wireReq = createWireRequest();
        byte[] cw = createValidCw();
        EcmKey key = new EcmKey(0x80, 0x100, 0x200, 0x300, wireReq.dataHash());
        CwReply cachedReply = new CwReply(key, 0x42, cw, "conn1",
                CspV2DomainMapper.originId("peer01"), 500L);
        stubCache.cachedReply = cachedReply;

        CspV2EcmHandler prodHandler = new CspV2EcmHandler(stubCache, connectorMgr, mapper, false);

        prodHandler.onEcmRequest("peer01", wireReq).onComplete(ar -> {
            ctx.verify(() -> {
                assertTrue(ar.succeeded());
                assertEquals(CspV2Payload.DcwStatus.OK, ar.result().status());
                assertEquals(0x42, ar.result().replyTag());
                assertArrayEquals(cw, ar.result().cw());
                // Cache lookup was called exactly once
                assertEquals(1, stubCache.lookupCalls.get());
            });
            ctx.completeNow();
        });
    }

    private static class TestDummyConnector extends AbstractConnector {
        private final String name;
        private final String profile;
        private final boolean connected;
        private final java.util.function.Function<EcmRequest, Future<CwReply>> handler;

        TestDummyConnector(String name, String profile, boolean connected,
                           java.util.function.Function<EcmRequest, Future<CwReply>> handler) {
            super(null, null, name, "127.0.0.1", 1000, profile);
            this.name = name;
            this.profile = profile;
            this.connected = connected;
            this.handler = handler;
        }

        @Override public boolean isConnected() { return connected; }
        @Override public Future<CwReply> sendEcm(EcmRequest request) {
            return handler != null ? handler.apply(request) : Future.succeededFuture(CwReply.empty(request.key()));
        }
        @Override public Future<Void> start() { return Future.succeededFuture(); }
        @Override public void stop() {}
        @Override public Future<LoginState> loginFuture() { return Future.succeededFuture(LoginState.OK); }
    }

    @Test
    @DisplayName("Regression #1 : 2+ connecteurs même profil CAIDs différents -> requête CAID B résolue par connecteur B PAS le premier")
    void multiConnectorsSameProfileDifferentCaidsResolvesSecondConnector(Vertx vertx, VertxTestContext ctx) {
        this.vertx = vertx;
        StubCacheVerticle stubCache = new StubCacheVerticle();
        CwsConnectorManager connectorMgr = new CwsConnectorManager(vertx, stubCache);
        CardDiscoveryService discovery = new CardDiscoveryService(vertx);

        byte[] cwA = createValidCw();
        byte[] cwB = createValidCw();
        cwB[0] = (byte) 0xBB;
        cwB[3] = (byte) ((cwB[0] + cwB[1] + cwB[2]) & 0xFF);

        AtomicInteger connACalls = new AtomicInteger(0);
        AtomicInteger connBCalls = new AtomicInteger(0);

        TestDummyConnector connA = new TestDummyConnector("connA", "default", true, req -> {
            connACalls.incrementAndGet();
            if (req.key().caId() == 0x0100) {
                return Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), cwA, "connA", "127.0.0.1", 1000L));
            }
            return Future.failedFuture("Wrong CAID 0x" + Integer.toHexString(req.key().caId()) + " on connA");
        });

        TestDummyConnector connB = new TestDummyConnector("connB", "default", true, req -> {
            connBCalls.incrementAndGet();
            if (req.key().caId() == 0x0500) {
                return Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), cwB, "connB", "127.0.0.1", 1000L));
            }
            return Future.failedFuture("Wrong CAID 0x" + Integer.toHexString(req.key().caId()) + " on connB");
        });

        connectorMgr.addTestConnector(connA);
        connectorMgr.addTestConnector(connB);

        discovery.onConnectorConnected("connA", 1L);
        discovery.onConnectorConnected("connB", 1L);

        // connA -> 0x0100 / 0x000068
        discovery.onCardDiscovered("connA", "default", 0x0100, 0x000068, 101L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);
        discovery.updateCapabilityState("default", 0x0100, 0x000068, CardCapability.State.PUBLISHED);

        // connB -> 0x0500 / 0x032830
        discovery.onCardDiscovered("connB", "default", 0x0500, 0x032830, 201L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);
        discovery.updateCapabilityState("default", 0x0500, 0x032830, CardCapability.State.PUBLISHED);

        CspV2EcmHandler handler = new CspV2EcmHandler(stubCache, connectorMgr, discovery, mapper, true);

        // ECM Viaccess avec nano 0x90 len 3 (provider 0x032830, section len 19 -> 0x13)
        byte[] ecmViaccess = new byte[] {
            (byte) 0x80, (byte) 0x70, (byte) 0x13,
            (byte) 0x40, (byte) 0x02, (byte) 0x00, (byte) 0x00,
            (byte) 0x90, (byte) 0x03, (byte) 0x03, (byte) 0x28, (byte) 0x30,
            (byte) 0x80, (byte) 0x08, 0, 0, 0, 0, 0, 0, 0, 0
        };
        int hashB = CspV2DomainMapper.ecmHash(ecmViaccess, 3, ecmViaccess.length - 3);
        CspV2Payload.EcmRequest wireReqB = new CspV2Payload.EcmRequest(
            0x80, 0x0002, 0x0000, 0x0500, hashB, "default", ecmViaccess, 5000);

        handler.onEcmRequest("peer01", wireReqB).onComplete(ar -> {
            ctx.verify(() -> {
                assertTrue(ar.succeeded(), "Requête CAID B doit réussir");
                CspV2Payload.DcwReply reply = ar.result();
                assertEquals(CspV2Payload.DcwStatus.OK, reply.status());
                assertArrayEquals(cwB, reply.cw(), "CW doit provenir de connB");
                assertEquals("connB", reply.connectorName(), "Connector name exposé doit être connB");
                assertEquals(1, connBCalls.get(), "connB doit avoir été appelé exactement 1 fois");
                assertEquals(0, connACalls.get(), "connA ne doit PAS avoir été appelé car filtré par capacité 0x0500");
            });
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("Regression #1 bis : Fallback par CAID si provider indéterminable dans l'ECM")
    void multiConnectorsFallbackByCaidWhenProviderUndetermined(Vertx vertx, VertxTestContext ctx) {
        this.vertx = vertx;
        StubCacheVerticle stubCache = new StubCacheVerticle();
        CwsConnectorManager connectorMgr = new CwsConnectorManager(vertx, stubCache);
        CardDiscoveryService discovery = new CardDiscoveryService(vertx);

        byte[] cwB = createValidCw();
        cwB[0] = (byte) 0xBB;
        cwB[3] = (byte) ((cwB[0] + cwB[1] + cwB[2]) & 0xFF);

        AtomicInteger connACalls = new AtomicInteger(0);
        AtomicInteger connBCalls = new AtomicInteger(0);

        TestDummyConnector connA = new TestDummyConnector("connA", "default", true, req -> {
            connACalls.incrementAndGet();
            return Future.failedFuture("connA fail");
        });

        TestDummyConnector connB = new TestDummyConnector("connB", "default", true, req -> {
            connBCalls.incrementAndGet();
            return Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), cwB, "connB", "127.0.0.1", 1000L));
        });

        connectorMgr.addTestConnector(connA);
        connectorMgr.addTestConnector(connB);

        discovery.onConnectorConnected("connA", 1L);
        discovery.onConnectorConnected("connB", 1L);

        discovery.onCardDiscovered("connA", "default", 0x0100, 0, 101L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);
        discovery.updateCapabilityState("default", 0x0100, 0, CardCapability.State.PUBLISHED);

        discovery.onCardDiscovered("connB", "default", 0x0500, 0x032830, 201L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);
        discovery.updateCapabilityState("default", 0x0500, 0x032830, CardCapability.State.PUBLISHED);

        CspV2EcmHandler handler = new CspV2EcmHandler(stubCache, connectorMgr, discovery, mapper, true);

        // ECM générique pour 0x0500 sans nano (provider indéterminé)
        byte[] ecmGeneric = new byte[] { (byte) 0x80, 0x00, 0x01, 0x02, 0x03, 0x04 };
        int hash = CspV2DomainMapper.ecmHash(ecmGeneric, 3, ecmGeneric.length - 3);
        CspV2Payload.EcmRequest wireReq = new CspV2Payload.EcmRequest(
            0x80, 0x0002, 0x0000, 0x0500, hash, "default", ecmGeneric, 5000);

        handler.onEcmRequest("peer01", wireReq).onComplete(ar -> {
            ctx.verify(() -> {
                assertTrue(ar.succeeded());
                CspV2Payload.DcwReply reply = ar.result();
                assertEquals(CspV2Payload.DcwStatus.OK, reply.status());
                assertArrayEquals(cwB, reply.cw());
                assertEquals("connB", reply.connectorName());
                assertEquals(1, connBCalls.get());
                assertEquals(0, connACalls.get());
            });
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("Regression Prod : Capacité à l'état DISCOVERED (non publiée en mémoire) + source online -> CW servie via wildcard")
    void exactProdRegressionDiscoveredCapabilityServedWithWildcard(Vertx vertx, VertxTestContext ctx) {
        this.vertx = vertx;
        StubCacheVerticle stubCache = new StubCacheVerticle();
        CwsConnectorManager connectorMgr = new CwsConnectorManager(vertx, stubCache);
        CardDiscoveryService discovery = new CardDiscoveryService(vertx);

        byte[] cw = createValidCw();
        AtomicInteger connCalls = new AtomicInteger(0);

        TestDummyConnector conn = new TestDummyConnector("connSeca", "default", true, req -> {
            connCalls.incrementAndGet();
            return Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), cw, "connSeca", "127.0.0.1", 1000L));
        });

        connectorMgr.addTestConnector(conn);
        discovery.onConnectorConnected("connSeca", 1L);

        // Sur la centrale, seule onCardDiscovered est appelée (état DISCOVERED en mémoire, jamais PUBLISHED)
        discovery.onCardDiscovered("connSeca", "default", 0x0100, 0x000068, 101L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);

        // Handler réel de production avec discovery
        CspV2EcmHandler handler = new CspV2EcmHandler(stubCache, connectorMgr, discovery, mapper, true);

        byte[] ecm = new byte[] { (byte) 0x80, 0x00, 0x01, 0x02, 0x03, 0x04 };
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        CspV2Payload.EcmRequest wireReq = new CspV2Payload.EcmRequest(
            0x80, 0x0001, 0x0000, 0x0100, hash, "default", ecm, 5000);

        handler.onEcmRequest("peer01", wireReq).onComplete(ar -> {
            ctx.verify(() -> {
                assertTrue(ar.succeeded(), "La requête pour capacité DISCOVERED doit réussir en mode relais wildcard");
                CspV2Payload.DcwReply reply = ar.result();
                assertEquals(CspV2Payload.DcwStatus.OK, reply.status());
                assertArrayEquals(cw, reply.cw());
                assertEquals("connSeca", reply.connectorName());
                assertEquals(1, connCalls.get(), "Le connecteur doit avoir été sélectionné et appelé");
            });
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("Sécurité : Capacité à l'état BLOCKED toujours exclue même en wildcard -> NOT_FOUND")
    void blockedCapabilityExcludedEvenInWildcard(Vertx vertx, VertxTestContext ctx) {
        this.vertx = vertx;
        StubCacheVerticle stubCache = new StubCacheVerticle();
        CwsConnectorManager connectorMgr = new CwsConnectorManager(vertx, stubCache);
        CardDiscoveryService discovery = new CardDiscoveryService(vertx);

        byte[] cw = createValidCw();
        AtomicInteger connCalls = new AtomicInteger(0);

        TestDummyConnector conn = new TestDummyConnector("connSeca", "default", true, req -> {
            connCalls.incrementAndGet();
            return Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), cw, "connSeca", "127.0.0.1", 1000L));
        });

        connectorMgr.addTestConnector(conn);
        discovery.onConnectorConnected("connSeca", 1L);

        discovery.onCardDiscovered("connSeca", "default", 0x0100, 0x000068, 101L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);
        // Explicitement marqué BLOCKED
        discovery.updateCapabilityState("default", 0x0100, 0x000068, CardCapability.State.BLOCKED);

        CspV2EcmHandler handler = new CspV2EcmHandler(stubCache, connectorMgr, discovery, mapper, true);

        byte[] ecm = new byte[] { (byte) 0x80, 0x00, 0x01, 0x02, 0x03, 0x04 };
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        CspV2Payload.EcmRequest wireReq = new CspV2Payload.EcmRequest(
            0x80, 0x0001, 0x0000, 0x0100, hash, "default", ecm, 5000);

        handler.onEcmRequest("peer01", wireReq).onComplete(ar -> {
            ctx.verify(() -> {
                assertTrue(ar.succeeded());
                CspV2Payload.DcwReply reply = ar.result();
                assertEquals(CspV2Payload.DcwStatus.NOT_FOUND, reply.status(), "Une capacité BLOCKED doit retourner NOT_FOUND");
                assertEquals(0, connCalls.get(), "Le connecteur ne doit JAMAIS être appelé pour une capacité BLOCKED");
            });
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("Invariant B5 Fix : Le connecteur reçoit un corps amputé et reconstructSection reproduit la section originale exacte sans double en-tête")
    void connectorReceivesAmputatedBodyAndReconstructsOriginalSection(Vertx vertx, VertxTestContext ctx) {
        this.vertx = vertx;
        StubCacheVerticle stubCache = new StubCacheVerticle();
        CwsConnectorManager connectorMgr = new CwsConnectorManager(vertx, stubCache);
        CardDiscoveryService discovery = new CardDiscoveryService(vertx);

        byte[] cw = createValidCw();
        byte[] originalSectionViaccess = new byte[] {
            (byte) 0x80, (byte) 0x70, (byte) 0x13,
            (byte) 0x40, (byte) 0x02, (byte) 0x00, (byte) 0x00,
            (byte) 0x90, (byte) 0x03, (byte) 0x03, (byte) 0x28, (byte) 0x30,
            (byte) 0x80, (byte) 0x08, 0, 0, 0, 0, 0, 0, 0, 0
        };
        int hash = CspV2DomainMapper.ecmHash(originalSectionViaccess, 3, originalSectionViaccess.length - 3);

        java.util.concurrent.atomic.AtomicReference<byte[]> capturedDomainBody = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<byte[]> capturedReconstructedSection = new java.util.concurrent.atomic.AtomicReference<>();

        TestDummyConnector conn = new TestDummyConnector("connViaccess", "default", true, req -> {
            capturedDomainBody.set(req.body());
            byte[] reconstructed = com.bowman.cspng.codec.cccam.CcCardCodec.reconstructSection(
                req.key().commandTag(), req.body());
            capturedReconstructedSection.set(reconstructed);
            return Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), cw, "connViaccess", "127.0.0.1", 1000L));
        });

        connectorMgr.addTestConnector(conn);
        discovery.onConnectorConnected("connViaccess", 1L);
        discovery.onCardDiscovered("connViaccess", "default", 0x0500, 0x032830, 101L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);
        discovery.updateCapabilityState("default", 0x0500, 0x032830, CardCapability.State.PUBLISHED);

        CspV2EcmHandler handler = new CspV2EcmHandler(stubCache, connectorMgr, discovery, mapper, true);
        CspV2Payload.EcmRequest wireReq = new CspV2Payload.EcmRequest(
            0x80, 0x0002, 0x0000, 0x0500, hash, "default", originalSectionViaccess, 5000);

        handler.onEcmRequest("peer01", wireReq).onComplete(ar -> {
            ctx.verify(() -> {
                assertTrue(ar.succeeded());
                CspV2Payload.DcwReply reply = ar.result();
                assertEquals(CspV2Payload.DcwStatus.OK, reply.status());
                assertArrayEquals(cw, reply.cw());

                // 1. Vérifier que le corps reçu dans le domaine est amputé des 3 octets d'en-tête
                byte[] expectedAmputatedBody = java.util.Arrays.copyOfRange(
                    originalSectionViaccess, 3, originalSectionViaccess.length);
                assertArrayEquals(expectedAmputatedBody, capturedDomainBody.get(),
                    "Le corps du domaine EcmRequest doit être amputé des 3 octets de section DVB");

                // 2. Vérifier que le hash du corps correspond exactement au dataHash de la clé
                int bodyHash = CspV2DomainMapper.ecmHash(capturedDomainBody.get(), 0, capturedDomainBody.get().length);
                assertEquals(wireReq.dataHash(), bodyHash,
                    "Le hash du corps amputé doit correspondre au dataHash");

                // 3. Vérifier que la reconstruction par le connecteur (contrat CcCamConnector) reproduit fidèlement la section originale SANS double en-tête
                assertArrayEquals(originalSectionViaccess, capturedReconstructedSection.get(),
                    "La section reconstruite par le connecteur doit être STRICTEMENT IDENTIQUE à la section originale (pas de double en-tête)");
            });
            ctx.completeNow();
        });
    }
}
