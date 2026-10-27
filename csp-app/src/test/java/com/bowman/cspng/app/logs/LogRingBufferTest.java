package com.bowman.cspng.app.logs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LogRingBufferTest {

  @Test
  @DisplayName("Ring buffer borné et séquence monotone")
  void testRingBufferBoundedAndMonotonicSeq() {
    LogRingBuffer buffer = new LogRingBuffer(5);

    for (int i = 1; i <= 10; i++) {
      buffer.add(System.currentTimeMillis(), "INFO", "test.Logger", "msg " + i);
    }

    assertEquals(5, buffer.size());
    assertEquals(10, buffer.currentMaxSeq());

    // readLatest(5) renvoie les 5 derniers (6, 7, 8, 9, 10)
    List<LogEntry> latest = buffer.readLatest(5, "ALL");
    assertEquals(5, latest.size());
    assertEquals("msg 6", latest.get(0).message());
    assertEquals(6, latest.get(0).seq());
    assertEquals("msg 10", latest.get(4).message());
    assertEquals(10, latest.get(4).seq());

    // readAfter(8) renvoie les entrées > 8 (9, 10)
    List<LogEntry> after = buffer.readAfter(8, 10, "ALL");
    assertEquals(2, after.size());
    assertEquals("msg 9", after.get(0).message());
    assertEquals("msg 10", after.get(1).message());
  }

  @Test
  @DisplayName("Filtrage par niveau minimum")
  void testLevelFiltering() {
    LogRingBuffer buffer = new LogRingBuffer(10);
    buffer.add(System.currentTimeMillis(), "DEBUG", "test", "debug msg");
    buffer.add(System.currentTimeMillis(), "INFO", "test", "info msg");
    buffer.add(System.currentTimeMillis(), "WARN", "test", "warn msg");
    buffer.add(System.currentTimeMillis(), "ERROR", "test", "error msg");

    List<LogEntry> all = buffer.readLatest(10, "DEBUG");
    assertEquals(4, all.size());

    List<LogEntry> infoPlus = buffer.readLatest(10, "INFO");
    assertEquals(3, infoPlus.size());

    List<LogEntry> warnPlus = buffer.readLatest(10, "WARN");
    assertEquals(2, warnPlus.size());

    List<LogEntry> errorOnly = buffer.readLatest(10, "ERROR");
    assertEquals(1, errorOnly.size());
    assertEquals("error msg", errorOnly.get(0).message());
  }

  @Test
  @DisplayName("M1 : Concurrence multithread stricte sans perte ni duplication de séquence")
  void testMultithreadStrictMonotonicity() throws Exception {
    int capacity = 3000;
    LogRingBuffer buffer = new LogRingBuffer(capacity);
    int threads = 8;
    int insertsPerThread = 200;
    int totalInserts = threads * insertsPerThread;

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threads);

    for (int t = 0; t < threads; t++) {
      final int threadId = t;
      pool.submit(() -> {
        try {
          startLatch.await();
          for (int i = 0; i < insertsPerThread; i++) {
            buffer.add(System.currentTimeMillis(), "INFO", "thread-" + threadId, "msg-" + i);
          }
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          doneLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
    pool.shutdown();

    assertEquals(totalInserts, buffer.currentMaxSeq());
    assertEquals(totalInserts, buffer.size());

    // Lecture complète : vérifier ordre strictement croissant et unicité de seq
    List<LogEntry> all = buffer.readAfter(0, 500, "ALL");
    assertTrue(all.size() <= 500);

    // Lecture par blocs de sequence pour vérifier toutes les entrées
    long currentAfter = 0;
    List<Long> seqs = new ArrayList<>();
    while (true) {
      List<LogEntry> chunk = buffer.readAfter(currentAfter, 500, "ALL");
      if (chunk.isEmpty()) break;
      for (LogEntry e : chunk) {
        seqs.add(e.seq());
      }
      currentAfter = chunk.get(chunk.size() - 1).seq();
    }

    assertEquals(totalInserts, seqs.size());
    for (int i = 0; i < totalInserts; i++) {
      assertEquals((long) (i + 1), seqs.get(i));
    }
  }

  @Test
  @DisplayName("M2 & B3 : Clamping CDI et éviction LRU dans LogBufferManager")
  void testLogBufferManagerClampingAndLruEviction() {
    // Clamping des valeurs extrêmes
    LogBufferManager clamped = new LogBufferManager(10, 100_000, 0);
    assertEquals(100, clamped.getMainRingSize());
    assertEquals(50_000, clamped.getWorkerRingSize());
    assertEquals(1, clamped.getMaxWorkerBuffers());

    // Éviction LRU avec max = 2 buffers workers
    LogBufferManager mgr = new LogBufferManager(100, 100, 2);

    mgr.recordWorkerLogs("w1", List.of(new com.bowman.cspng.config.RelayWorkerContract.WorkerLogEntry(1, 1000L, "INFO", "w1", "msg 1")));
    mgr.recordWorkerLogs("w2", List.of(new com.bowman.cspng.config.RelayWorkerContract.WorkerLogEntry(1, 1000L, "INFO", "w2", "msg 2")));
    assertEquals(List.of("main", "worker:w1", "worker:w2"), mgr.getSources());

    // Accéder à w1 (devient le plus récemment utilisé)
    assertNotNull(mgr.getBuffer("worker:w1"));

    // Ajouter w3 -> éviction du moins récemment utilisé (w2)
    mgr.recordWorkerLogs("w3", List.of(new com.bowman.cspng.config.RelayWorkerContract.WorkerLogEntry(1, 1000L, "INFO", "w3", "msg 3")));
    List<String> sources = mgr.getSources();
    assertEquals(List.of("main", "worker:w1", "worker:w3"), sources);
    assertNull(mgr.getBuffer("worker:w2"));
  }
}
