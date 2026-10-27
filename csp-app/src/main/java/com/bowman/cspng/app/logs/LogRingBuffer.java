package com.bowman.cspng.app.logs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ring buffer borné thread-safe avec numéro de séquence monotone.
 * L'assignation de séquence et l'insertion sont atomiques sous verrou.
 */
public class LogRingBuffer {

  private final int capacity;
  private final LogEntry[] buffer;
  private final AtomicLong seqGenerator = new AtomicLong(0);
  private int head = 0;
  private int size = 0;
  private final Object lock = new Object();

  public LogRingBuffer(int capacity) {
    this.capacity = capacity > 0 ? capacity : 2000;
    this.buffer = new LogEntry[this.capacity];
  }

  public LogEntry add(long timestamp, String level, String logger, String message) {
    synchronized (lock) {
      long seq = seqGenerator.incrementAndGet();
      LogEntry entry = new LogEntry(seq, timestamp, level, logger, message);
      buffer[head] = entry;
      head = (head + 1) % capacity;
      if (size < capacity) {
        size++;
      }
      return entry;
    }
  }

  public void add(LogEntry entry) {
    if (entry == null) return;
    synchronized (lock) {
      buffer[head] = entry;
      head = (head + 1) % capacity;
      if (size < capacity) {
        size++;
      }
      seqGenerator.updateAndGet(current -> Math.max(current, entry.seq()));
    }
  }

  /**
   * Retourne les entrées avec seq > afterSeq bornées par limit et filtrées par minLevel.
   */
  public List<LogEntry> readAfter(long afterSeq, int limit, String minLevel) {
    int max = Math.max(1, Math.min(500, limit));
    List<LogEntry> result = new ArrayList<>();
    synchronized (lock) {
      if (size == 0) return List.of();
      int startIdx = (head - size + capacity) % capacity;
      for (int i = 0; i < size; i++) {
        int idx = (startIdx + i) % capacity;
        LogEntry entry = buffer[idx];
        if (entry != null && entry.seq() > afterSeq) {
          if (matchesLevel(entry.level(), minLevel)) {
            result.add(entry);
            if (result.size() >= max) {
              break;
            }
          }
        }
      }
    }
    return result;
  }

  /**
   * Retourne les N dernières entrées via un parcours inverse O(min(size, limit)).
   */
  public List<LogEntry> readLatest(int limit, String minLevel) {
    int max = Math.max(1, Math.min(500, limit));
    List<LogEntry> reverseMatching = new ArrayList<>();
    synchronized (lock) {
      if (size == 0) return List.of();
      for (int i = 0; i < size; i++) {
        int idx = (head - 1 - i + capacity) % capacity;
        LogEntry entry = buffer[idx];
        if (entry != null && matchesLevel(entry.level(), minLevel)) {
          reverseMatching.add(entry);
          if (reverseMatching.size() >= max) {
            break;
          }
        }
      }
    }
    Collections.reverse(reverseMatching);
    return reverseMatching;
  }

  public long currentMaxSeq() {
    synchronized (lock) {
      return seqGenerator.get();
    }
  }

  public int size() {
    synchronized (lock) {
      return size;
    }
  }

  public int capacity() {
    return capacity;
  }

  public void clear() {
    synchronized (lock) {
      head = 0;
      size = 0;
      for (int i = 0; i < capacity; i++) {
        buffer[i] = null;
      }
    }
  }

  public static boolean matchesLevel(String entryLevel, String filterLevel) {
    if (filterLevel == null || filterLevel.isBlank() || "ALL".equalsIgnoreCase(filterLevel)) {
      return true;
    }
    int entryRank = rankOf(entryLevel);
    int filterRank = rankOf(filterLevel);
    return entryRank >= filterRank;
  }

  private static int rankOf(String level) {
    if (level == null) return 1; // default INFO
    String u = level.toUpperCase(Locale.ROOT).trim();
    return switch (u) {
      case "TRACE", "FINER", "FINEST" -> 0;
      case "DEBUG", "FINE" -> 0;
      case "INFO", "CONFIG" -> 1;
      case "WARN", "WARNING" -> 2;
      case "ERROR", "SEVERE", "FATAL" -> 3;
      default -> 1;
    };
  }
}
