package com.bowman.cspng.connectors.cacheex;

/**
 * Garde anti-flood RX par pair (R3) : fenêtre glissante fixe 60 s, compteur borné.
 *
 * <p>NE PAS confondre avec la borne de file d'émission TX ({@code maxSendQueue}) :
 * ici on limite le RYTHME des PUSH ENTRANTS d'un pair (CPU/mémoire d'injection),
 * là on borne la FILE des push sortants en attente. Deux directions, deux bornes.
 *
 * <p>Pour les sessions, on réutilise le budget {@code allowedEcmRate} EXISTANT
 * (req/min par user) via {@code SessionRegistry.checkUserRateLimit} avec une
 * fenêtre DÉDIÉE {@code "<user>/cacheex"} — même valeur configurée, budgets
 * séparés (un flood de push ne doit jamais affamer les ECM, ni l'inverse).
 * Pour les connecteurs (pas d'utilisateur), borne fixe documentée
 * {@link #DEFAULT_MAX_PER_MINUTE}.
 *
 * <p>Thread-safe (synchronized, O(1)). Coût mesuré négligeable devant le parsing.
 */
public final class RxFloodGate {

  /** Borne par défaut des connecteurs : 6000 push/min/pair (~100/s soutenus ;
   * un pair légitime pic en dessous, un flood dépasse par ordres de grandeur). */
  public static final int DEFAULT_MAX_PER_MINUTE = 6000;

  private static final long WINDOW_MS = 60_000L;

  private long windowStartMs;
  private long count;

  /**
   * @param maxPerMinute budget (> 0), sinon toujours accepté (illimité explicite)
   * @return true si le push est admis dans la fenêtre courante
   */
  public synchronized boolean tryAcquire(int maxPerMinute) {
    if (maxPerMinute <= 0) return true;
    long now = System.currentTimeMillis();
    if (windowStartMs == 0 || now - windowStartMs > WINDOW_MS) {
      windowStartMs = now;
      count = 1;
      return true;
    }
    if (count >= maxPerMinute) return false;
    count++;
    return true;
  }

  /** Remise à zéro (tests). */
  public synchronized void reset() {
    windowStartMs = 0;
    count = 0;
  }
}
