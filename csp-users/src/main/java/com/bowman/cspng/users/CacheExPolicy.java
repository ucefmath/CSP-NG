package com.bowman.cspng.users;

/**
 * Politique CacheEx d'un compte protocole entrant (V15, table
 * {@code csp_user_protocol_secret}).
 *
 * <p>Sémantique (arbitrage D2, vérifiée module-cacheex.c l.584-604/625-672/722-748) :
 * <ul>
 *   <li>mode 2 : NOUS émettons les push vers le client (il reçoit) — la gate
 *       d'émission est le filtre reçu du client à la session ;</li>
 *   <li>mode 3 : NOUS recevons les push du client (il émet) — notre filtre
 *       configuré ({@code filter}) est annoncé au client (gate d'acceptation) ;</li>
 *   <li>mode 0 / null : cacheex désactivé pour ce compte.</li>
 * </ul>
 *
 * <p>{@code maxHop} : 0..10 (défaut 10) — gate de réception (mode 3) et borne
 * du chemin de nodes des pushes émis (mode 2). {@code filter} : spec
 * {@code "caid:provid"} (vide/null = accept-all).
 */
public record CacheExPolicy(int mode, int maxHop, String filter) {

  public static final CacheExPolicy OFF = new CacheExPolicy(0, 10, null);

  public CacheExPolicy {
    if (mode != 0 && mode != 2 && mode != 3) {
      throw new IllegalArgumentException("cacheex mode must be 0, 2 or 3");
    }
    if (maxHop < 0 || maxHop > 10) {
      throw new IllegalArgumentException("cacheex maxHop must be 0..10");
    }
    filter = filter != null && filter.isBlank() ? null : filter;
  }

  /** Politique depuis les colonnes SQL (null mode = off). */
  public static CacheExPolicy fromDb(Integer mode, Integer maxHop, String filter) {
    if (mode == null || mode == 0) return OFF;
    return new CacheExPolicy(mode, maxHop != null ? maxHop : 10, filter);
  }

  public boolean enabled() {
    return mode == 2 || mode == 3;
  }
}
