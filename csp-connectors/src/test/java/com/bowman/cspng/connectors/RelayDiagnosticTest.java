package com.bowman.cspng.connectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class RelayDiagnosticTest {

  private List<LogRecord> capturedRecords;
  private Handler testHandler;
  private Logger diagLogger;

  @BeforeEach
  void setUp() {
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
  void tearDown() {
    System.clearProperty(RelayDiagnostic.PROPERTY_DIAG);
    System.clearProperty(RelayDiagnostic.PROPERTY_DIAG_ALT);
    System.clearProperty(RelayDiagnostic.PROPERTY_TTL_MINUTES);
    RelayDiagnostic.resetStateForTest();
    if (diagLogger != null && testHandler != null) {
      diagLogger.removeHandler(testHandler);
    }
  }

  @Test
  void defaultOff_noLogsEmitted() {
    assertFalse(RelayDiagnostic.isEnabled(), "Diagnostic must be disabled by default");

    long reqId = RelayDiagnostic.nextReqId();
    RelayDiagnostic.logCacheHit(reqId, 0x1884, 0, 0x2938);
    RelayDiagnostic.logSelectionCandidates(reqId, 0x1884, 0, 0x2938, 2, 5000L);
    RelayDiagnostic.logAttemptStart(reqId, 0, 0);
    RelayDiagnostic.logAttemptOk(reqId, 0, 0, 150L);
    RelayDiagnostic.logResultResolved(reqId, 150L, 5000L);
    RelayDiagnostic.logHandlerOk(reqId, 150L);

    assertTrue(capturedRecords.isEmpty(), "Zero logs must be emitted when flag is disabled");
  }

  @Test
  void enabledViaSystemProperty() {
    System.setProperty(RelayDiagnostic.PROPERTY_DIAG, "true");
    RelayDiagnostic.reloadConfigForTest();
    assertTrue(RelayDiagnostic.isEnabled());

    long reqId = RelayDiagnostic.nextReqId();
    RelayDiagnostic.logCacheMiss(reqId, 0x1884, 0, 0x2938);
    RelayDiagnostic.logSelectionCandidates(reqId, 0x1884, 0, 0x2938, 1, 5000L);
    RelayDiagnostic.logAttemptStart(reqId, 0, 0);
    RelayDiagnostic.logAttemptOk(reqId, 0, 0, 370L);
    RelayDiagnostic.logResultResolved(reqId, 370L, 5000L);
    RelayDiagnostic.logHandlerOk(reqId, 370L);

    assertEquals(6, capturedRecords.size());
    for (LogRecord r : capturedRecords) {
      assertTrue(r.getMessage().startsWith("[RELAY-DIAG] reqId="));
    }
  }

  @Test
  void autoExpirationAfter10Minutes_windowMonotone() {
    System.setProperty(RelayDiagnostic.PROPERTY_DIAG, "true");
    RelayDiagnostic.reloadConfigForTest();
    long baseTimeNs = 1_000_000_000_000L;
    RelayDiagnostic.setTestClockNanos(baseTimeNs);

    // Première activation à T0
    assertTrue(RelayDiagnostic.isEnabled());
    long reqId = RelayDiagnostic.nextReqId();
    RelayDiagnostic.logCacheHit(reqId, 0x1884, 0, 0x2938);
    assertEquals(1, capturedRecords.size());

    // À T0 + 9 minutes : toujours actif
    RelayDiagnostic.setTestClockNanos(baseTimeNs + TimeUnit.MINUTES.toNanos(9));
    assertTrue(RelayDiagnostic.isEnabled());
    RelayDiagnostic.logCacheMiss(reqId, 0x1884, 0, 0x2938);
    assertEquals(2, capturedRecords.size());

    // À T0 + 10 minutes + 1 nanoseconde : auto-désactivé (expiré)
    RelayDiagnostic.setTestClockNanos(baseTimeNs + TimeUnit.MINUTES.toNanos(10) + 1);
    assertFalse(RelayDiagnostic.isEnabled(), "Le diagnostic doit s'auto-désactiver après 10 minutes");
    RelayDiagnostic.logCacheMiss(reqId, 0x1884, 0, 0x2938);
    assertEquals(2, capturedRecords.size(), "Aucun nouveau log ne doit être émis après expiration");
  }

  @Test
  @DisplayName("TTL borné entre 1 et 10 minutes, fallback à 10 minutes si invalide ou hors limites")
  void ttlBoundedAndFallbackLimits() {
    // Null / non défini -> 10 min
    System.clearProperty(RelayDiagnostic.PROPERTY_TTL_MINUTES);
    assertEquals(TimeUnit.MINUTES.toNanos(10), RelayDiagnostic.parseBoundedTtlNanos());

    // Invalide chaîne -> fallback 10 min
    System.setProperty(RelayDiagnostic.PROPERTY_TTL_MINUTES, "not_a_number");
    assertEquals(TimeUnit.MINUTES.toNanos(10), RelayDiagnostic.parseBoundedTtlNanos());

    // 0 -> fallback 10 min (borne min 1)
    System.setProperty(RelayDiagnostic.PROPERTY_TTL_MINUTES, "0");
    assertEquals(TimeUnit.MINUTES.toNanos(10), RelayDiagnostic.parseBoundedTtlNanos());

    // Négatif -> fallback 10 min
    System.setProperty(RelayDiagnostic.PROPERTY_TTL_MINUTES, "-5");
    assertEquals(TimeUnit.MINUTES.toNanos(10), RelayDiagnostic.parseBoundedTtlNanos());

    // Trop grand (> 10) -> fallback 10 min
    System.setProperty(RelayDiagnostic.PROPERTY_TTL_MINUTES, "100");
    assertEquals(TimeUnit.MINUTES.toNanos(10), RelayDiagnostic.parseBoundedTtlNanos());

    // Overflow possible -> fallback 10 min
    System.setProperty(RelayDiagnostic.PROPERTY_TTL_MINUTES, "9999999999999999999999");
    assertEquals(TimeUnit.MINUTES.toNanos(10), RelayDiagnostic.parseBoundedTtlNanos());

    // Valeur limite basse valide : 1 min
    System.setProperty(RelayDiagnostic.PROPERTY_TTL_MINUTES, "1");
    assertEquals(TimeUnit.MINUTES.toNanos(1), RelayDiagnostic.parseBoundedTtlNanos());

    // Valeur intermédiaire valide : 5 min
    System.setProperty(RelayDiagnostic.PROPERTY_TTL_MINUTES, "5");
    assertEquals(TimeUnit.MINUTES.toNanos(5), RelayDiagnostic.parseBoundedTtlNanos());

    // Valeur limite haute valide : 10 min
    System.setProperty(RelayDiagnostic.PROPERTY_TTL_MINUTES, "10");
    assertEquals(TimeUnit.MINUTES.toNanos(10), RelayDiagnostic.parseBoundedTtlNanos());
  }

  @Test
  void antiLeak_secretsInExceptionsNeverLogged() {
    String secretSentinel = "SECRET_PASSWORD_12345_CW_0102030405060708090A0B0C0D0E0F10";
    Exception exWithSecret = new RuntimeException("connection refused with " + secretSentinel);

    String normalized = RelayDiagnostic.normalizeError(exWithSecret);
    assertEquals("DISCONNECTED", normalized);
    assertFalse(normalized.contains(secretSentinel));
    assertFalse(normalized.contains("SECRET"));
    assertFalse(normalized.contains("12345"));
  }

  @Test
  void maliciousInputAtBoundaryFilteredToOther() {
    System.setProperty(RelayDiagnostic.PROPERTY_DIAG, "true");
    RelayDiagnostic.reloadConfigForTest();
    long reqId = 777L;

    String maliciousSecret = "SUPER_SECRET_CW_1122334455667788";
    RelayDiagnostic.logAttemptFailed(reqId, 0, 0, maliciousSecret, 10L);

    assertEquals(1, capturedRecords.size());
    String msg = capturedRecords.get(0).getMessage();
    assertFalse(msg.contains(maliciousSecret), "L'entrée arbitraire malveillante ne doit jamais fuiter");
    assertTrue(msg.contains("reason=OTHER"), "La raison non répertoriée doit être forcée à OTHER");
  }

  @Test
  void normalizeErrorAllowlistStrictness() {
    assertEquals("TIMEOUT", RelayDiagnostic.normalizeError(new TimeoutException("timeout occurred")));
    assertEquals("TIMEOUT", RelayDiagnostic.normalizeError(new RuntimeException("ECM reply timeout")));
    assertEquals("BUSY", RelayDiagnostic.normalizeError(new RuntimeException("ECM already in flight (MVP: 1 at a time)")));
    assertEquals("BUSY", RelayDiagnostic.normalizeError(new RuntimeException("in-flight capacity exceeded")));
    assertEquals("NO_CARD", RelayDiagnostic.normalizeError(new RuntimeException("No card available for caid")));
    assertEquals("DISCONNECTED", RelayDiagnostic.normalizeError(new RuntimeException("Not connected")));
    assertEquals("DISCONNECTED", RelayDiagnostic.normalizeError(new RuntimeException("Connector stopped")));
    assertEquals("DISCONNECTED", RelayDiagnostic.normalizeError(new RuntimeException("Connection refused")));
    assertEquals("EMPTY", RelayDiagnostic.normalizeError(new RuntimeException("empty response")));
    assertEquals("INVALID", RelayDiagnostic.normalizeError(new RuntimeException("invalid checksum")));
    assertEquals("OTHER", RelayDiagnostic.normalizeError(new RuntimeException("something entirely unexpected")));
    assertEquals("OTHER", RelayDiagnostic.normalizeError(null));
  }

  @Test
  void antiLeak_allLogMethodsNeverExposeSecrets() {
    System.setProperty(RelayDiagnostic.PROPERTY_DIAG, "true");
    RelayDiagnostic.reloadConfigForTest();
    long reqId = 123456L;

    RelayDiagnostic.logCacheHit(reqId, 0x1884, 0x32830, 0x2938);
    RelayDiagnostic.logCacheMiss(reqId, 0x1884, 0, 0x2938);
    RelayDiagnostic.logCacheCollision(reqId, 0x1884, 0, 0x2938);
    RelayDiagnostic.logSelectionEmpty(reqId, 0x1884, 0, 0x2938);
    RelayDiagnostic.logSelectionCandidates(reqId, 0x1884, 0, 0x2938, 3, 5000L);
    RelayDiagnostic.logAttemptStart(reqId, 0, 0);
    RelayDiagnostic.logAttemptOk(reqId, 0, 0, 100L);
    RelayDiagnostic.logAttemptFailed(reqId, 1, 1, "BUSY", 5L);
    RelayDiagnostic.logResultResolved(reqId, 105L, 5000L);
    RelayDiagnostic.logResultExhausted(reqId, 200L, 5000L);
    RelayDiagnostic.logResultTimeout(reqId, 5000L, 5000L, 1);
    RelayDiagnostic.logHandlerOk(reqId, 105L);
    RelayDiagnostic.logHandlerNotFound(reqId, 5000L);

    assertEquals(13, capturedRecords.size());
    for (LogRecord record : capturedRecords) {
      String msg = record.getMessage();
      assertTrue(msg.startsWith("[RELAY-DIAG]"));
      assertFalse(msg.contains("cw="));
      assertFalse(msg.contains("key="));
      assertFalse(msg.contains("pass"));
      assertFalse(msg.contains("user"));
    }
  }

  @Test
  void rateLimiter_boundsOutputRate() {
    System.setProperty(RelayDiagnostic.PROPERTY_DIAG, "true");
    RelayDiagnostic.reloadConfigForTest();
    long reqId = RelayDiagnostic.nextReqId();

    // Consume all 100 tokens
    for (int i = 0; i < 100; i++) {
      RelayDiagnostic.logAttemptOk(reqId, i, 0, 1L);
    }
    assertEquals(100, capturedRecords.size());

    // 101st attempt should be dropped by rate limiter
    RelayDiagnostic.logAttemptOk(reqId, 101, 0, 1L);
    assertEquals(100, capturedRecords.size(), "Logs above 100/sec token budget must be dropped");
  }
}
