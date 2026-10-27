package com.bowman.cspng.cache;

import com.bowman.cspng.domain.CwReply;

/**
 * Résultat d'un rendez-vous de cache.
 *
 * <p>Équivalent réactif de la sortie de {@code DefaultCache.processRequest}. Le legacy
 * modifiait la requête en place ({@code setTimeOut}, {@code setCacheTime}) et retournait
 * une copie de la CW. En cible réactive, les informations sont rendues explicites dans ce
 * record.
 *
 * @param reply      la CW retournée, ou {@code null} en cas de miss/timeout
 * @param instant    {@code true} si la CW était déjà en cache (hit instantané)
 * @param waitHit    {@code true} si la CW a été obtenue après attente
 * @param timeOut    {@code true} si l'attente a dépassé {@code maxWait}
 * @param cacheTimeMs durée totale passée dans le rendez-vous de cache (ms)
 */
public record CacheResult(
    CwReply reply,
    boolean instant,
    boolean waitHit,
    boolean timeOut,
    long cacheTimeMs) {

  public boolean isHit() {
    return reply != null && !reply.isEmpty();
  }
}
