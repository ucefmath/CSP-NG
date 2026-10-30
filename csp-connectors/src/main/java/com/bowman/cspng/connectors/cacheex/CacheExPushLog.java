package com.bowman.cspng.connectors.cacheex;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Journal opérationnel des push CacheEx ACCEPTÉS (injectés dans le cache), catégorie
 * {@code com.bowman.cspng.cacheex}, niveau INFO. Désactivé par défaut (volume : ~1 ligne par
 * CW reçue) sur le central comme sur les workers ; activé par la variable d'environnement
 * {@code CSP_LOG_CACHEEX=true} (ou la propriété système {@code csp.log.cacheex=true}), ou à
 * chaud via {@link #setEnabled(boolean)}. Aucune CW n'est journalisée — uniquement les
 * identifiants de service, la parité et le nombre de sauts.
 */
public final class CacheExPushLog {

  public static final String CATEGORY = "com.bowman.cspng.cacheex";
  static final Logger LOG = Logger.getLogger(CATEGORY);

  private static volatile boolean enabled = initialFlag();

  private CacheExPushLog() {}

  static boolean parseFlag(String raw) {
    if (raw == null) return false;
    String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
    return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on")
        || v.equals("info") || v.equals("debug") || v.equals("fine");
  }

  private static boolean initialFlag() {
    String prop = System.getProperty("csp.log.cacheex");
    if (prop != null) return parseFlag(prop);
    return parseFlag(System.getenv("CSP_LOG_CACHEEX"));
  }

  public static void setEnabled(boolean on) {
    enabled = on;
  }

  public static boolean enabled() {
    return enabled && LOG.isLoggable(Level.INFO);
  }

  /** Construit la ligne (pure, testable). */
  public static String format(String protocol, String peer, String origin,
                              int caid, int provid, int sid, int parity, int hops) {
    return String.format("CACHEEX PUSH proto=%s peer=%s origin=%s caid=0x%04X provid=0x%06X sid=0x%04X parity=0x%02X hop=%d",
        protocol, peer, origin, caid & 0xFFFF, provid & 0xFFFFFF, sid & 0xFFFF, parity & 0xFF, Math.max(0, hops));
  }

  public static void accepted(String protocol, String peer, String origin,
                              int caid, int provid, int sid, int parity, int hops) {
    if (!enabled()) return;
    LOG.info(format(protocol, peer, origin, caid, provid, sid, parity, hops));
  }
}
