package com.bowman.cspng.app.logs;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Handler java.util.logging non bloquant capturant les logs dans le LogRingBuffer central.
 * Filtre les catégories selon les préfixes autorisés et tronque les messages volumineux.
 */
public class AdminLogHandler extends Handler {

  private final LogBufferManager logBufferManager;
  private final List<String> capturePrefixes;

  public AdminLogHandler(LogBufferManager logBufferManager, String capturePrefixesStr) {
    this.logBufferManager = logBufferManager;
    if (capturePrefixesStr == null || capturePrefixesStr.isBlank()) {
      this.capturePrefixes = List.of("com.bowman.cspng");
    } else {
      this.capturePrefixes = Arrays.stream(capturePrefixesStr.split(","))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .toList();
    }
    setLevel(Level.ALL);
  }

  public AdminLogHandler(LogBufferManager logBufferManager) {
    this(logBufferManager, "com.bowman.cspng");
  }

  @Override
  public void publish(LogRecord record) {
    if (record == null || logBufferManager == null) {
      return;
    }

    String rawLogger = record.getLoggerName();
    if (!matchesPrefix(rawLogger)) {
      return;
    }

    try {
      long timestamp = record.getMillis();
      String level = normalizeLevel(record.getLevel());
      String logger = abbreviateLogger(rawLogger);
      String message = formatMessage(record);

      if (logger != null && logger.length() > 128) {
        logger = logger.substring(0, 128);
      }
      if (message != null && message.length() > 2048) {
        message = message.substring(0, 2048);
      }

      logBufferManager.getMainBuffer().add(timestamp, level, logger, message);
    } catch (Exception ignored) {
      // Jamais d'exception remontée sur l'émetteur de log
    }
  }

  private boolean matchesPrefix(String loggerName) {
    if (capturePrefixes.isEmpty()) return true;
    if (loggerName == null) return false;
    for (String p : capturePrefixes) {
      if (loggerName.equals(p) || loggerName.startsWith(p + ".")) return true;
    }
    return false;
  }

  @Override
  public void flush() {}

  @Override
  public void close() throws SecurityException {}

  static String normalizeLevel(Level level) {
    if (level == null) return "INFO";
    int val = level.intValue();
    if (val >= Level.SEVERE.intValue()) return "ERROR";
    if (val >= Level.WARNING.intValue()) return "WARN";
    if (val >= Level.INFO.intValue()) return "INFO";
    return "DEBUG";
  }

  static String abbreviateLogger(String loggerName) {
    if (loggerName == null || loggerName.isBlank()) return "app";
    String clean = loggerName.trim();
    if (clean.startsWith("com.bowman.cspng.")) {
      clean = clean.substring("com.bowman.cspng.".length());
    }
    return clean;
  }

  static String formatMessage(LogRecord record) {
    String msg = record.getMessage();
    if (msg == null) msg = "";
    Object[] params = record.getParameters();
    if (params != null && params.length > 0) {
      try {
        if (msg.contains("{0}")) {
          msg = MessageFormat.format(msg, params);
        }
      } catch (Exception ignored) {}
    }
    Throwable thrown = record.getThrown();
    if (thrown != null) {
      msg = msg.isEmpty() ? thrown.toString() : msg + " — " + thrown.toString();
    }
    return msg;
  }
}
