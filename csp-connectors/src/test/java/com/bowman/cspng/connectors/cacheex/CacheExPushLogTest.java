package com.bowman.cspng.connectors.cacheex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CacheExPushLog — ligne push acceptée, désactivée par défaut")
class CacheExPushLogTest {

  @AfterEach
  void reset() {
    CacheExPushLog.setEnabled(false);
  }

  @Test
  void formatContainsServiceIdsWithoutCw() {
    String line = CacheExPushLog.format("cs378x", "L3:@STB@", "203.0.113.7", 0x1813, 0, 0x3D55, 0x80, 1);
    assertEquals("CACHEEX PUSH proto=cs378x peer=L3:@STB@ origin=203.0.113.7 caid=0x1813 provid=0x000000 sid=0x3D55 parity=0x80 hop=1", line);
  }

  @Test
  void parseFlagAcceptsCommonTruthyValues() {
    assertTrue(CacheExPushLog.parseFlag("true"));
    assertTrue(CacheExPushLog.parseFlag(" INFO "));
    assertTrue(CacheExPushLog.parseFlag("1"));
    assertFalse(CacheExPushLog.parseFlag(null));
    assertFalse(CacheExPushLog.parseFlag("false"));
    assertFalse(CacheExPushLog.parseFlag("warn"));
  }

  @Test
  void logsOnlyWhenEnabled() {
    List<LogRecord> records = new ArrayList<>();
    Handler h = new Handler() {
      @Override public void publish(LogRecord r) { records.add(r); }
      @Override public void flush() {}
      @Override public void close() {}
    };
    CacheExPushLog.LOG.addHandler(h);
    try {
      CacheExPushLog.setEnabled(false);
      CacheExPushLog.accepted("cccam", "p", "o", 0x1884, 0, 0x32DC, 0x81, 0);
      assertTrue(records.isEmpty(), "désactivé => aucune ligne");
      CacheExPushLog.setEnabled(true);
      CacheExPushLog.accepted("cccam", "p", "o", 0x1884, 0, 0x32DC, 0x81, 0);
      assertEquals(1, records.size());
      assertTrue(records.get(0).getMessage().contains("sid=0x32DC"));
    } finally {
      CacheExPushLog.LOG.removeHandler(h);
    }
  }
}
