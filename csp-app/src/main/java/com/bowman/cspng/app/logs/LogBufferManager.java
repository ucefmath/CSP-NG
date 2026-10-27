package com.bowman.cspng.app.logs;

import com.bowman.cspng.config.RelayWorkerContract;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gestionnaire des ring buffers de logs (serveur principal + workers relais connectés).
 * Supporte le bornage CDI et l'éviction LRU des buffers workers.
 */
@ApplicationScoped
public class LogBufferManager {

  private final int mainRingSize;
  private final int workerRingSize;
  private final int maxWorkerBuffers;
  private final LogRingBuffer mainBuffer;
  private final Map<String, LogRingBuffer> workerBuffers;

  @Inject
  public LogBufferManager(
      @ConfigProperty(name = "csp.logs.ring-size", defaultValue = "2000") int mainRingSize,
      @ConfigProperty(name = "csp.logs.worker-ring-size", defaultValue = "2000") int workerRingSize,
      @ConfigProperty(name = "csp.logs.max-worker-buffers", defaultValue = "64") int maxWorkerBuffers) {
    this.mainRingSize = clamp(mainRingSize, 100, 50_000);
    this.workerRingSize = clamp(workerRingSize, 100, 50_000);
    this.maxWorkerBuffers = clamp(maxWorkerBuffers, 1, 256);
    this.mainBuffer = new LogRingBuffer(this.mainRingSize);
    this.workerBuffers = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<String, LogRingBuffer> eldest) {
        return size() > LogBufferManager.this.maxWorkerBuffers;
      }
    };
  }

  // CDI fallback / tests
  public LogBufferManager() {
    this(2000, 2000, 64);
  }

  public static int clamp(int val, int min, int max) {
    if (val < min) return min;
    if (val > max) return max;
    return val;
  }

  public int getMainRingSize() {
    return mainRingSize;
  }

  public int getWorkerRingSize() {
    return workerRingSize;
  }

  public int getMaxWorkerBuffers() {
    return maxWorkerBuffers;
  }

  public LogRingBuffer getMainBuffer() {
    return mainBuffer;
  }

  public LogRingBuffer getBuffer(String source) {
    if (source == null || source.isBlank() || "main".equalsIgnoreCase(source.trim())) {
      return mainBuffer;
    }
    String s = source.trim();
    synchronized (workerBuffers) {
      return workerBuffers.get(s);
    }
  }

  public List<String> getSources() {
    List<String> sources = new ArrayList<>();
    sources.add("main");
    synchronized (workerBuffers) {
      List<String> workers = new ArrayList<>(workerBuffers.keySet());
      Collections.sort(workers);
      sources.addAll(workers);
    }
    return Collections.unmodifiableList(sources);
  }

  public void recordWorkerLogs(String workerNodeId, List<RelayWorkerContract.WorkerLogEntry> logs) {
    if (workerNodeId == null || workerNodeId.isBlank() || logs == null || logs.isEmpty()) {
      return;
    }
    String sourceKey = "worker:" + workerNodeId.trim();
    LogRingBuffer buffer;
    synchronized (workerBuffers) {
      buffer = workerBuffers.computeIfAbsent(sourceKey, k -> new LogRingBuffer(workerRingSize));
    }
    for (RelayWorkerContract.WorkerLogEntry wLog : logs) {
      if (wLog != null) {
        buffer.add(wLog.timestamp(), wLog.level(), wLog.logger(), wLog.message());
      }
    }
  }
}
