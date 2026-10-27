package com.bowman.cspng.connectors;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Diagnostic d'instrumentation sécurisé pour le routage ECM relais CSPv2.
 *
 * <p>Activable par flag système {@code csp.relay.diag=true} (défaut : OFF).
 * Fenêtre temporelle bornée : auto-désactivation après 1..10 minutes (défaut 10 min, horloge monotone).
 * Débit borné par token bucket (anti-flooding).
 * Filtrage strict à la frontière par allowlist : impossibilité mathématique de fuite.
 * Zéro coût quand inactif (chargement unique à l'initialisation, 0 lookup propriété au runtime, gardes avant formatage).
 */
public final class RelayDiagnostic {

  public static final String LOGGER_NAME = "com.bowman.cspng.relay.diag";
  private static final Logger LOG = Logger.getLogger(LOGGER_NAME);

  public static final String PROPERTY_DIAG = "csp.relay.diag";
  public static final String PROPERTY_DIAG_ALT = "csp.relay.diagnostic";
  public static final String PROPERTY_TTL_MINUTES = "csp.relay.diag.ttl-minutes";

  public static final long MIN_TTL_MINUTES = 1L;
  public static final long MAX_TTL_MINUTES = 10L;
  public static final long DEFAULT_TTL_MINUTES = 10L;
  public static final long DEFAULT_TTL_NANOS = TimeUnit.MINUTES.toNanos(DEFAULT_TTL_MINUTES);

  private static final Set<String> ALLOWED_REASONS = Set.of(
      "BUSY", "NO_CARD", "DISCONNECTED", "TIMEOUT", "EMPTY", "INVALID", "OTHER"
  );

  private static final AtomicLong REQ_COUNTER = new AtomicLong(
      (long) ThreadLocalRandom.current().nextInt(1000, 9999)
  );

  private static final long REFILL_INTERVAL_NS = 1_000_000_000L; // 1 seconde
  private static final long DEFAULT_MAX_TOKENS = 100L;
  private static final AtomicLong TOKENS = new AtomicLong(DEFAULT_MAX_TOKENS);
  private static final AtomicLong LAST_REFILL_TIME = new AtomicLong(System.nanoTime());

  // Hook d'injection d'horloge pour tests sans Thread.sleep
  private static volatile Long testClockNanos = null;

  private static class ConfigHolder {
    final boolean enabled;
    final long ttlNanos;
    final AtomicLong firstActivationNs = new AtomicLong(0L);

    ConfigHolder(boolean enabled, long ttlNanos) {
      this.enabled = enabled;
      this.ttlNanos = ttlNanos;
    }
  }

  private static volatile ConfigHolder config = loadConfig();

  private RelayDiagnostic() {}

  static ConfigHolder loadConfig() {
    boolean enabled = "true".equalsIgnoreCase(System.getProperty(PROPERTY_DIAG))
        || "true".equalsIgnoreCase(System.getProperty(PROPERTY_DIAG_ALT))
        || Boolean.getBoolean(PROPERTY_DIAG)
        || Boolean.getBoolean(PROPERTY_DIAG_ALT);
    long ttlNanos = parseBoundedTtlNanos();
    return new ConfigHolder(enabled, ttlNanos);
  }

  static long parseBoundedTtlNanos() {
    String ttlProp = System.getProperty(PROPERTY_TTL_MINUTES);
    if (ttlProp != null) {
      try {
        long min = Long.parseLong(ttlProp.trim());
        if (min >= MIN_TTL_MINUTES && min <= MAX_TTL_MINUTES) {
          return TimeUnit.MINUTES.toNanos(min);
        }
      } catch (NumberFormatException ignored) {}
    }
    return DEFAULT_TTL_NANOS;
  }

  static long getClockNanos() {
    Long tc = testClockNanos;
    return tc != null ? tc : System.nanoTime();
  }

  public static void setTestClockNanos(Long nanos) {
    testClockNanos = nanos;
  }

  /**
   * Recharge la configuration système (utilisé pour les tests).
   */
  public static void reloadConfigForTest() {
    config = loadConfig();
  }

  /**
   * Vérifie si le diagnostic de relais est activé et dans sa fenêtre temporelle bornée.
   * Coût minimal : 1 lecture volatile en mode OFF, 0 lookup propriété système.
   * Défaut : false (OFF).
   */
  public static boolean isEnabled() {
    ConfigHolder cfg = config;
    if (!cfg.enabled) {
      return false;
    }

    long now = getClockNanos();
    long first = cfg.firstActivationNs.get();
    if (first == 0L) {
      cfg.firstActivationNs.compareAndSet(0L, now);
      first = cfg.firstActivationNs.get();
    }

    if (now - first > cfg.ttlNanos) {
      return false; // Expiré après la fenêtre temporelle
    }
    return true;
  }

  /**
   * Génère un identifiant de corrélation opaque monotone pour la requête.
   */
  public static long nextReqId() {
    return REQ_COUNTER.incrementAndGet();
  }

  /**
   * Tente d'acquérir un token de log (débit borné / fenêtre 1s).
   */
  static boolean tryAcquireToken() {
    long now = getClockNanos();
    long last = LAST_REFILL_TIME.get();
    if (now - last > REFILL_INTERVAL_NS) {
      if (LAST_REFILL_TIME.compareAndSet(last, now)) {
        TOKENS.set(DEFAULT_MAX_TOKENS);
      }
    }
    long current;
    do {
      current = TOKENS.get();
      if (current <= 0) {
        return false;
      }
    } while (!TOKENS.compareAndSet(current, current - 1));
    return true;
  }

  /**
   * Réinitialise le rate limiter, l'horloge et la configuration (pour les tests).
   */
  public static void resetStateForTest() {
    testClockNanos = null;
    config = loadConfig();
    LAST_REFILL_TIME.set(getClockNanos());
    TOKENS.set(DEFAULT_MAX_TOKENS);
  }

  /**
   * Alias de réinitialisation pour compatibilité de test.
   */
  public static void resetRateLimiter() {
    resetStateForTest();
  }

  /**
   * Normalise un motif d'erreur selon une allowlist stricte :
   * {@code BUSY}, {@code NO_CARD}, {@code DISCONNECTED}, {@code TIMEOUT},
   * {@code EMPTY}, {@code INVALID}, {@code OTHER}.
   *
   * <p>Ne retourne JAMAIS le message brut de l'exception ni la stacktrace.
   */
  public static String normalizeError(Throwable t) {
    if (t == null) {
      return "OTHER";
    }
    String msg = t.getMessage();
    String cls = t.getClass().getSimpleName();
    String combined = ((msg != null ? msg : "") + " " + cls).toLowerCase(Locale.ROOT);

    if (t instanceof java.util.concurrent.TimeoutException || combined.contains("timeout")) {
      return "TIMEOUT";
    }
    if (combined.contains("busy") || combined.contains("in flight") || combined.contains("capacity")) {
      return "BUSY";
    }
    if (combined.contains("no card") || combined.contains("nocard")) {
      return "NO_CARD";
    }
    if (combined.contains("disconnect") || combined.contains("not connected")
        || combined.contains("stopped") || combined.contains("refused")
        || combined.contains("closed") || combined.contains("reset")) {
      return "DISCONNECTED";
    }
    if (combined.contains("empty")) {
      return "EMPTY";
    }
    if (combined.contains("invalid") || combined.contains("checksum") || combined.contains("corrupt")) {
      return "INVALID";
    }
    return "OTHER";
  }

  // --- Événements Cache ---

  public static void logCacheHit(long reqId, int caid, int prov, int sid) {
    if (!isEnabled() || !tryAcquireToken()) return;
    LOG.info(String.format("[RELAY-DIAG] reqId=%d cache=HIT caid=0x%04X prov=0x%X sid=0x%04X",
        reqId, caid & 0xFFFF, prov, sid & 0xFFFF));
  }

  public static void logCacheMiss(long reqId, int caid, int prov, int sid) {
    if (!isEnabled() || !tryAcquireToken()) return;
    LOG.info(String.format("[RELAY-DIAG] reqId=%d cache=MISS caid=0x%04X prov=0x%X sid=0x%04X",
        reqId, caid & 0xFFFF, prov, sid & 0xFFFF));
  }

  public static void logCacheCollision(long reqId, int caid, int prov, int sid) {
    if (!isEnabled() || !tryAcquireToken()) return;
    LOG.info(String.format("[RELAY-DIAG] reqId=%d cache=COLLISION caid=0x%04X prov=0x%X sid=0x%04X",
        reqId, caid & 0xFFFF, prov, sid & 0xFFFF));
  }

  // --- Événements Sélection Candidats ---

  public static void logSelectionEmpty(long reqId, int caid, int prov, int sid) {
    if (!isEnabled() || !tryAcquireToken()) return;
    LOG.info(String.format("[RELAY-DIAG] reqId=%d selection=EMPTY caid=0x%04X prov=0x%X sid=0x%04X count=0",
        reqId, caid & 0xFFFF, prov, sid & 0xFFFF));
  }

  public static void logSelectionCandidates(long reqId, int caid, int prov, int sid, int count, long budgetMs) {
    if (!isEnabled() || !tryAcquireToken()) return;
    LOG.info(String.format("[RELAY-DIAG] reqId=%d selection=CANDIDATES caid=0x%04X prov=0x%X sid=0x%04X count=%d budgetMs=%d",
        reqId, caid & 0xFFFF, prov, sid & 0xFFFF, count, budgetMs));
  }

  // --- Événements Dispatch Candidats ---

  public static void logAttemptStart(long reqId, int attempt, int candIndex) {
    if (!isEnabled() || !tryAcquireToken()) return;
    LOG.info(String.format("[RELAY-DIAG] reqId=%d attempt=%d candIndex=%d status=START",
        reqId, attempt, candIndex));
  }

  public static void logAttemptOk(long reqId, int attempt, int candIndex, long durationMs) {
    if (!isEnabled() || !tryAcquireToken()) return;
    LOG.info(String.format("[RELAY-DIAG] reqId=%d attempt=%d candIndex=%d status=OK durationMs=%d",
        reqId, attempt, candIndex, durationMs));
  }

  public static void logAttemptFailed(long reqId, int attempt, int candIndex, String reason, long durationMs) {
    if (!isEnabled() || !tryAcquireToken()) return;
    String safeReason = (reason != null && ALLOWED_REASONS.contains(reason)) ? reason : "OTHER";
    LOG.info(String.format("[RELAY-DIAG] reqId=%d attempt=%d candIndex=%d status=FAILED reason=%s durationMs=%d",
        reqId, attempt, candIndex, safeReason, durationMs));
  }

  // --- Événements Résultat Global ---

  public static void logResultResolved(long reqId, long totalDurationMs, long budgetMs) {
    if (!isEnabled() || !tryAcquireToken()) return;
    LOG.info(String.format("[RELAY-DIAG] reqId=%d result=RESOLVED totalDurationMs=%d budgetMs=%d",
        reqId, totalDurationMs, budgetMs));
  }

  public static void logResultExhausted(long reqId, long totalDurationMs, long budgetMs) {
    if (!isEnabled() || !tryAcquireToken()) return;
    LOG.info(String.format("[RELAY-DIAG] reqId=%d result=EXHAUSTED totalDurationMs=%d budgetMs=%d",
        reqId, totalDurationMs, budgetMs));
  }

  public static void logResultTimeout(long reqId, long totalDurationMs, long budgetMs, int currentCandIndex) {
    if (!isEnabled() || !tryAcquireToken()) return;
    LOG.info(String.format("[RELAY-DIAG] reqId=%d result=TIMEOUT totalDurationMs=%d budgetMs=%d currentCandIndex=%d",
        reqId, totalDurationMs, budgetMs, currentCandIndex));
  }

  // --- Événements Handler ECM ---

  public static void logHandlerOk(long reqId, long totalDurationMs) {
    if (!isEnabled() || !tryAcquireToken()) return;
    LOG.info(String.format("[RELAY-DIAG] reqId=%d handler=OK totalDurationMs=%d",
        reqId, totalDurationMs));
  }

  public static void logHandlerNotFound(long reqId, long totalDurationMs) {
    if (!isEnabled() || !tryAcquireToken()) return;
    LOG.info(String.format("[RELAY-DIAG] reqId=%d handler=NOT_FOUND totalDurationMs=%d",
        reqId, totalDurationMs));
  }
}
