package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class CwsConnectorManagerDiagnosticTest {

  private Vertx vertx;
  private CwsConnectorManager manager;
  private List<LogRecord> capturedRecords;
  private Handler testHandler;
  private Logger diagLogger;

  @BeforeEach
  void setUp() {
    this.vertx = Vertx.vertx();
    this.manager = new CwsConnectorManager(vertx, new CacheVerticle());
    System.clearProperty(RelayDiagnostic.PROPERTY_DIAG);
    System.clearProperty(RelayDiagnostic.PROPERTY_DIAG_ALT);
    System.clearProperty(RelayDiagnostic.PROPERTY_TTL_MINUTES);
    RelayDiagnostic.resetStateForTest();

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
  void tearDown() throws Exception {
    System.clearProperty(RelayDiagnostic.PROPERTY_DIAG);
    System.clearProperty(RelayDiagnostic.PROPERTY_DIAG_ALT);
    System.clearProperty(RelayDiagnostic.PROPERTY_TTL_MINUTES);
    RelayDiagnostic.resetStateForTest();
    if (diagLogger != null && testHandler != null) {
      diagLogger.removeHandler(testHandler);
    }
    if (vertx != null) {
      CountDownLatch latch = new CountDownLatch(1);
      vertx.close().onComplete(ar -> latch.countDown());
      latch.await(5, TimeUnit.SECONDS);
    }
  }

  private static byte[] validCw() {
    byte[] cw = new byte[16];
    for (int i = 0; i < 16; i++) cw[i] = (byte) (i + 1);
    cw[3] = (byte) ((cw[0] + cw[1] + cw[2]) & 0xFF);
    cw[7] = (byte) ((cw[4] + cw[5] + cw[6]) & 0xFF);
    cw[11] = (byte) ((cw[8] + cw[9] + cw[10]) & 0xFF);
    cw[15] = (byte) ((cw[12] + cw[13] + cw[14]) & 0xFF);
    return cw;
  }

  private static EcmRequest testEcmRequest(String profile, int caid, int sid, long maxWaitMs) {
    byte[] ecm = new byte[] { (byte) 0x80, 0x00, 0x01, 0x02, 0x03, 0x04 };
    EcmKey key = new EcmKey(0x80, sid, 0, caid, 0x12345678);
    return new EcmRequest(key, ecm, profile, "127.0.0.1", "peer01", System.currentTimeMillis(), null, maxWaitMs);
  }

  private static class StubConnector extends AbstractConnector {
    private final boolean connected;
    private final java.util.function.Function<EcmRequest, Future<CwReply>> handler;

    StubConnector(String name, String profile, boolean connected,
                  java.util.function.Function<EcmRequest, Future<CwReply>> handler) {
      super(null, null, name, "127.0.0.1", 1000, profile);
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
  @DisplayName("Selection vide émet le log selection=EMPTY quand le flag est actif")
  void emptySelectionLogsDiagnostic() throws Exception {
    System.setProperty(RelayDiagnostic.PROPERTY_DIAG, "true");
    RelayDiagnostic.reloadConfigForTest();

    EcmRequest req = testEcmRequest("empty-profile", 0x1884, 0x2938, 5000L);
    CountDownLatch latch = new CountDownLatch(1);
    manager.sendEcmRequest(req, 0, null, "wildcard").onComplete(ar -> {
      assertTrue(ar.failed());
      latch.countDown();
    });
    assertTrue(latch.await(5, TimeUnit.SECONDS));

    assertFalse(capturedRecords.isEmpty());
    boolean foundEmpty = capturedRecords.stream()
        .anyMatch(r -> r.getMessage().contains("selection=EMPTY")
            && r.getMessage().contains("caid=0x1884")
            && r.getMessage().contains("sid=0x2938"));
    assertTrue(foundEmpty, "selection=EMPTY doit être loggé");
  }

  @Test
  @DisplayName("Candidat 0 BUSY puis Candidat 1 OK logge les tentatives START/FAILED/OK et le résultat RESOLVED")
  void busyThenNextCandidateLogs() throws Exception {
    System.setProperty(RelayDiagnostic.PROPERTY_DIAG, "true");
    RelayDiagnostic.reloadConfigForTest();

    byte[] cw = validCw();
    StubConnector conn0 = new StubConnector("c0", "prof1", true,
        req -> Future.failedFuture("ECM already in flight (MVP: 1 at a time)"));
    StubConnector conn1 = new StubConnector("c1", "prof1", true,
        req -> Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), cw, "c1", "peer", 500L)));

    manager.addTestConnector(conn0);
    manager.addTestConnector(conn1);

    EcmRequest req = testEcmRequest("prof1", 0x1884, 0x2938, 5000L);
    CountDownLatch latch = new CountDownLatch(1);
    manager.sendEcmRequest(req, 0, null, "wildcard").onComplete(ar -> {
      assertTrue(ar.succeeded());
      latch.countDown();
    });
    assertTrue(latch.await(5, TimeUnit.SECONDS));

    List<String> msgs = capturedRecords.stream().map(LogRecord::getMessage).toList();
    assertTrue(msgs.stream().anyMatch(m -> m.contains("selection=CANDIDATES") && m.contains("count=2")));
    assertTrue(msgs.stream().anyMatch(m -> m.contains("attempt=0") && m.contains("candIndex=0") && m.contains("status=START")));
    assertTrue(msgs.stream().anyMatch(m -> m.contains("attempt=0") && m.contains("status=FAILED") && m.contains("reason=BUSY")));
    assertTrue(msgs.stream().anyMatch(m -> m.contains("attempt=1") && m.contains("candIndex=1") && m.contains("status=START")));
    assertTrue(msgs.stream().anyMatch(m -> m.contains("attempt=1") && m.contains("status=OK")));
    assertTrue(msgs.stream().anyMatch(m -> m.contains("result=RESOLVED")));
  }

  @Test
  @DisplayName("Tous les candidats échouent -> logge EXHAUSTED")
  void allCandidatesFailLogsExhausted() throws Exception {
    System.setProperty(RelayDiagnostic.PROPERTY_DIAG, "true");
    RelayDiagnostic.reloadConfigForTest();

    StubConnector conn0 = new StubConnector("c0", "prof1", true, req -> Future.failedFuture("No card available"));
    StubConnector conn1 = new StubConnector("c1", "prof1", true, req -> Future.failedFuture("Connector stopped"));

    manager.addTestConnector(conn0);
    manager.addTestConnector(conn1);

    EcmRequest req = testEcmRequest("prof1", 0x0500, 0x1234, 5000L);
    CountDownLatch latch = new CountDownLatch(1);
    manager.sendEcmRequest(req, 0, null, "wildcard").onComplete(ar -> {
      assertTrue(ar.failed());
      latch.countDown();
    });
    assertTrue(latch.await(5, TimeUnit.SECONDS));

    List<String> msgs = capturedRecords.stream().map(LogRecord::getMessage).toList();
    assertTrue(msgs.stream().anyMatch(m -> m.contains("attempt=0") && m.contains("status=START")));
    assertTrue(msgs.stream().anyMatch(m -> m.contains("attempt=0") && m.contains("reason=NO_CARD")));
    assertTrue(msgs.stream().anyMatch(m -> m.contains("attempt=1") && m.contains("status=START")));
    assertTrue(msgs.stream().anyMatch(m -> m.contains("attempt=1") && m.contains("reason=DISCONNECTED")));
    assertTrue(msgs.stream().anyMatch(m -> m.contains("result=EXHAUSTED")));
  }

  @Test
  @DisplayName("Timeout global expiré -> logge TIMEOUT avec currentCandIndex")
  void timeoutLogsTimeoutResult() throws Exception {
    System.setProperty(RelayDiagnostic.PROPERTY_DIAG, "true");
    RelayDiagnostic.reloadConfigForTest();

    StubConnector conn0 = new StubConnector("c0", "prof1", true, req -> Promise.<CwReply>promise().future());
    manager.addTestConnector(conn0);

    EcmRequest req = testEcmRequest("prof1", 0x0100, 0x4321, 100L);
    CountDownLatch latch = new CountDownLatch(1);
    manager.sendEcmRequest(req, 0, null, "wildcard").onComplete(ar -> {
      assertTrue(ar.failed());
      latch.countDown();
    });
    assertTrue(latch.await(5, TimeUnit.SECONDS));

    List<String> msgs = capturedRecords.stream().map(LogRecord::getMessage).toList();
    assertTrue(msgs.stream().anyMatch(m -> m.contains("result=TIMEOUT") && m.contains("currentCandIndex=0")));
  }

  @Test
  @DisplayName("Anti-fuite : les exceptions avec secrets sentinelles ne fuitent JAMAIS dans les logs")
  void antiLeakSecretsNeverInLogs() throws Exception {
    System.setProperty(RelayDiagnostic.PROPERTY_DIAG, "true");
    RelayDiagnostic.reloadConfigForTest();
    String sentinelSecret = "TOP_SECRET_PASSWORD_12345_NODE_KEY_ABCDEF";

    StubConnector conn0 = new StubConnector("c0", "prof1", true,
        req -> Future.failedFuture("Fatal crash with " + sentinelSecret));
    manager.addTestConnector(conn0);

    EcmRequest req = testEcmRequest("prof1", 0x1884, 0x2938, 5000L);
    CountDownLatch latch = new CountDownLatch(1);
    manager.sendEcmRequest(req, 0, null, "wildcard").onComplete(ar -> {
      assertTrue(ar.failed());
      latch.countDown();
    });
    assertTrue(latch.await(5, TimeUnit.SECONDS));

    assertFalse(capturedRecords.isEmpty());
    for (LogRecord record : capturedRecords) {
      String logText = record.getMessage();
      assertFalse(logText.contains(sentinelSecret), "Le secret sentinelle ne doit JAMAIS apparaître");
      assertFalse(logText.contains("TOP_SECRET"));
      assertFalse(logText.contains("Fatal crash"));
    }
  }

  @Test
  @DisplayName("Défaut OFF : aucun log quand le flag système n'est pas activé")
  void defaultOffEmitsNoLogs() throws Exception {
    assertFalse(RelayDiagnostic.isEnabled());

    byte[] cw = validCw();
    StubConnector conn0 = new StubConnector("c0", "prof1", true,
        req -> Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), cw, "c0", "peer", 500L)));
    manager.addTestConnector(conn0);

    EcmRequest req = testEcmRequest("prof1", 0x1884, 0x2938, 5000L);
    CountDownLatch latch = new CountDownLatch(1);
    manager.sendEcmRequest(req, 0, null, "wildcard").onComplete(ar -> {
      assertTrue(ar.succeeded());
      latch.countDown();
    });
    assertTrue(latch.await(5, TimeUnit.SECONDS));

    assertTrue(capturedRecords.isEmpty(), "Aucun log ne doit être émis quand le diagnostic est désactivé");
  }
}
