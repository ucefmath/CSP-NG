package com.bowman.cspng.app.logs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

class AdminLogHandlerTest {

  @Test
  @DisplayName("Filtre strict par préfixe autorisé (com.bowman.cspng)")
  void testStrictPrefixFiltering() {
    LogBufferManager mgr = new LogBufferManager(100, 100, 10);
    AdminLogHandler handler = new AdminLogHandler(mgr, "com.bowman.cspng");

    // 1. Logger sous com.bowman.cspng -> capturé
    LogRecord r1 = new LogRecord(Level.INFO, "msg 1");
    r1.setLoggerName("com.bowman.cspng.ecm.CwsConnectorManager");
    handler.publish(r1);

    // 2. Logger avec préfixe exact -> capturé
    LogRecord r2 = new LogRecord(Level.INFO, "msg 2");
    r2.setLoggerName("com.bowman.cspng");
    handler.publish(r2);

    // 3. Logger externe (Vert.x, driver) -> ignoré
    LogRecord r3 = new LogRecord(Level.INFO, "msg 3");
    r3.setLoggerName("io.vertx.core.http");
    handler.publish(r3);

    // 4. Logger prefix collision trompeuse (com.bowman.cspngX) -> rejeté grâce à l'ancrage strict
    LogRecord r4 = new LogRecord(Level.INFO, "msg 4");
    r4.setLoggerName("com.bowman.cspngX.evil");
    handler.publish(r4);

    assertEquals(2, mgr.getMainBuffer().size());
    assertEquals("msg 1", mgr.getMainBuffer().readLatest(10, "INFO").get(0).message());
    assertEquals("msg 2", mgr.getMainBuffer().readLatest(10, "INFO").get(1).message());
  }

  @Test
  @DisplayName("Troncature des messages volumineux (> 2048 chars)")
  void testMessageTruncation() {
    LogBufferManager mgr = new LogBufferManager(100, 100, 10);
    AdminLogHandler handler = new AdminLogHandler(mgr, "com.bowman.cspng");

    String hugeMsg = "X".repeat(5000);
    LogRecord r = new LogRecord(Level.SEVERE, hugeMsg);
    r.setLoggerName("com.bowman.cspng.test");
    handler.publish(r);

    assertEquals(1, mgr.getMainBuffer().size());
    LogEntry entry = mgr.getMainBuffer().readLatest(1, "ERROR").get(0);
    assertEquals(2048, entry.message().length());
  }
}
