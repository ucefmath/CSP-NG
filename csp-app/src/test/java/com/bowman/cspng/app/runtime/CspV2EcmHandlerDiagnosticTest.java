package com.bowman.cspng.app.runtime;

import com.bowman.cspng.codec.cspv2.CspV2Payload;
import com.bowman.cspng.connectors.RelayDiagnostic;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class CspV2EcmHandlerDiagnosticTest {

    private CspV2DomainMapper mapper;
    private List<LogRecord> capturedRecords;
    private Handler testHandler;
    private Logger diagLogger;

    @BeforeEach
    void setUp() {
        mapper = new CspV2DomainMapper(() -> 1000L);
        System.clearProperty(RelayDiagnostic.PROPERTY_DIAG);
        System.clearProperty(RelayDiagnostic.PROPERTY_DIAG_ALT);
        RelayDiagnostic.resetRateLimiter();

        capturedRecords = new ArrayList<>();
        testHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                capturedRecords.add(record);
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        diagLogger = Logger.getLogger(RelayDiagnostic.LOGGER_NAME);
        diagLogger.addHandler(testHandler);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(RelayDiagnostic.PROPERTY_DIAG);
        System.clearProperty(RelayDiagnostic.PROPERTY_DIAG_ALT);
        if (diagLogger != null && testHandler != null) {
            diagLogger.removeHandler(testHandler);
        }
    }

    private static CspV2Payload.EcmRequest createWireRequest() {
        byte[] ecm = new byte[] { (byte) 0x80, 0x00, 0x01, 0x02, 0x03, 0x04 };
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        return new CspV2Payload.EcmRequest(0x80, 0x2938, 0x0000, 0x1884, hash, "profile1", ecm, 5000);
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

    @Test
    @DisplayName("Cache HIT logge cache=HIT et handler=OK")
    void cacheHitEmitsDiagnostics(VertxTestContext ctx) {
        System.setProperty(RelayDiagnostic.PROPERTY_DIAG, "true");
        RelayDiagnostic.reloadConfigForTest();

        CspV2Payload.EcmRequest wireReq = createWireRequest();
        byte[] cw = createValidCw();
        EcmKey key = new EcmKey(0x80, 0x2938, 0x0000, 0x1884, wireReq.dataHash());
        CwReply cachedReply = new CwReply(key, 0x42, cw, "conn1",
                CspV2DomainMapper.originId("peer01"), 500L);

        CspV2EcmHandler handler = new CspV2EcmHandler(mapper, false,
                k -> cachedReply,
                req -> Future.failedFuture("connector should not be called"));

        handler.onEcmRequest("peer01", wireReq).onComplete(ar -> {
            ctx.verify(() -> {
                assertTrue(ar.succeeded());
                assertEquals(CspV2Payload.DcwStatus.OK, ar.result().status());

                List<String> msgs = capturedRecords.stream().map(LogRecord::getMessage).toList();
                assertTrue(msgs.stream().anyMatch(m -> m.contains("cache=HIT")
                        && m.contains("caid=0x1884") && m.contains("sid=0x2938")));
                assertTrue(msgs.stream().anyMatch(m -> m.contains("handler=OK")));
            });
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("Cache MISS logge cache=MISS")
    void cacheMissEmitsDiagnostics(VertxTestContext ctx) {
        System.setProperty(RelayDiagnostic.PROPERTY_DIAG, "true");
        RelayDiagnostic.reloadConfigForTest();

        CspV2Payload.EcmRequest wireReq = createWireRequest();
        byte[] cw = createValidCw();
        EcmKey key = new EcmKey(0x80, 0x2938, 0x0000, 0x1884, wireReq.dataHash());
        CwReply domainReply = new CwReply(key, 0x01, cw, "connA",
                CspV2DomainMapper.originId("peer01"), 1000L);

        CspV2EcmHandler handler = new CspV2EcmHandler(mapper, false,
                k -> null,
                req -> Future.succeededFuture(domainReply));

        handler.onEcmRequest("peer01", wireReq).onComplete(ar -> {
            ctx.verify(() -> {
                assertTrue(ar.succeeded());
                List<String> msgs = capturedRecords.stream().map(LogRecord::getMessage).toList();
                assertTrue(msgs.stream().anyMatch(m -> m.contains("cache=MISS")
                        && m.contains("caid=0x1884") && m.contains("sid=0x2938")));
                assertTrue(msgs.stream().anyMatch(m -> m.contains("handler=OK")));
            });
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("Cache COLLISION logge cache=COLLISION puis résolution connecteur")
    void cacheCollisionEmitsDiagnostics(VertxTestContext ctx) {
        System.setProperty(RelayDiagnostic.PROPERTY_DIAG, "true");
        RelayDiagnostic.reloadConfigForTest();

        CspV2Payload.EcmRequest wireReq = createWireRequest();
        byte[] cw = createValidCw();
        EcmKey differentKey = new EcmKey(0x80, 0x9999, 0x0000, 0x1884, wireReq.dataHash());
        CwReply badCached = new CwReply(differentKey, 0x01, cw, "bad", "origin", 0L);

        EcmKey correctKey = new EcmKey(0x80, 0x2938, 0x0000, 0x1884, wireReq.dataHash());
        CwReply domainReply = new CwReply(correctKey, 0x02, cw, "connA",
                CspV2DomainMapper.originId("peer01"), 1000L);

        CspV2EcmHandler handler = new CspV2EcmHandler(mapper, false,
                k -> badCached,
                req -> Future.succeededFuture(domainReply));

        handler.onEcmRequest("peer01", wireReq).onComplete(ar -> {
            ctx.verify(() -> {
                assertTrue(ar.succeeded());
                List<String> msgs = capturedRecords.stream().map(LogRecord::getMessage).toList();
                assertTrue(msgs.stream().anyMatch(m -> m.contains("cache=COLLISION")
                        && m.contains("caid=0x1884") && m.contains("sid=0x2938")));
                assertTrue(msgs.stream().anyMatch(m -> m.contains("handler=OK")));
            });
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("Anti-fuite : secrets sentinelles dans les erreurs du connecteur ne fuitent JAMAIS")
    void antiLeakSecretSentinelsNeverInLogs(VertxTestContext ctx) {
        System.setProperty(RelayDiagnostic.PROPERTY_DIAG, "true");
        RelayDiagnostic.reloadConfigForTest();
        String secretSentinel = "CRITICAL_SECRET_PASSWORD_98765_CW_A1B2C3D4E5";

        CspV2Payload.EcmRequest wireReq = createWireRequest();
        CspV2EcmHandler handler = new CspV2EcmHandler(mapper, false,
                k -> null,
                req -> Future.failedFuture("Connector crashed with " + secretSentinel));

        handler.onEcmRequest("peer01", wireReq).onComplete(ar -> {
            ctx.verify(() -> {
                assertTrue(ar.succeeded());
                assertEquals(CspV2Payload.DcwStatus.NOT_FOUND, ar.result().status());

                assertFalse(capturedRecords.isEmpty());
                for (LogRecord r : capturedRecords) {
                    String msg = r.getMessage();
                    assertFalse(msg.contains(secretSentinel), "Le secret sentinelle ne doit JAMAIS apparaître");
                    assertFalse(msg.contains("CRITICAL_SECRET"));
                    assertFalse(msg.contains("Connector crashed"));
                }
                boolean foundNotFound = capturedRecords.stream()
                        .anyMatch(r -> r.getMessage().contains("handler=NOT_FOUND"));
                assertTrue(foundNotFound, "handler=NOT_FOUND doit être loggé");
            });
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("Défaut OFF : zéro log quand le flag système est inactif")
    void disabledFlagEmitsZeroLogs(VertxTestContext ctx) {
        assertFalse(RelayDiagnostic.isEnabled());

        CspV2Payload.EcmRequest wireReq = createWireRequest();
        byte[] cw = createValidCw();
        EcmKey key = new EcmKey(0x80, 0x2938, 0x0000, 0x1884, wireReq.dataHash());
        CwReply domainReply = new CwReply(key, 0x01, cw, "connA",
                CspV2DomainMapper.originId("peer01"), 1000L);

        CspV2EcmHandler handler = new CspV2EcmHandler(mapper, false,
                k -> null,
                req -> Future.succeededFuture(domainReply));

        handler.onEcmRequest("peer01", wireReq).onComplete(ar -> {
            ctx.verify(() -> {
                assertTrue(ar.succeeded());
                assertTrue(capturedRecords.isEmpty(), "Zéro log quand le diagnostic est désactivé");
            });
            ctx.completeNow();
        });
    }
}
