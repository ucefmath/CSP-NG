package com.bowman.cspng.sessions;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Helper d'audit logging unifié pour fail2ban et journald.
 *
 * <p>Format des lignes d'audit :
 * <ul>
 *   <li>WARNING : {@code AUTH_FAIL protocol=<p> user=<u> ip=<ip> reason=<r>}</li>
 *   <li>INFO    : {@code AUTH_OK protocol=<p> user=<u> ip=<ip>}</li>
 *   <li>WARNING : {@code PREAUTH_REJECT ip=<ip> reason=<r>}</li>
 * </ul>
 *
 * <p>Toutes les valeurs (protocol, user, ip, reason) font l'objet d'une sanitization stricte
 * (ASCII imprimable [32..126], limitation de longueur, remplacement des caractères
 * non imprimables/CRLF par '?', rejet des injections de log).
 */
public final class AuthAudit {

  public static final String LOGGER_NAME = "com.bowman.cspng.sessions.audit";
  private static final Logger LOG = Logger.getLogger(LOGGER_NAME);

  private AuthAudit() {}

  public static Logger getLogger() {
    return LOG;
  }

  public static void authOk(String protocol, String user, String ip) {
    String msg = "AUTH_OK protocol=" + sanitizeProtocol(protocol)
        + " user=" + sanitizeUser(user)
        + " ip=" + sanitizeIp(ip);
    LOG.log(Level.INFO, msg);
  }

  public static void authFail(String protocol, String user, String ip, String reason) {
    String msg = "AUTH_FAIL protocol=" + sanitizeProtocol(protocol)
        + " user=" + sanitizeUser(user)
        + " ip=" + sanitizeIp(ip)
        + " reason=" + sanitizeReason(reason);
    LOG.log(Level.WARNING, msg);
  }

  public static void preauthReject(String ip, String reason) {
    String msg = "PREAUTH_REJECT ip=" + sanitizeIp(ip)
        + " reason=" + sanitizeReason(reason);
    LOG.log(Level.WARNING, msg);
  }

  public static String sanitizeUser(String user) {
    if (user == null || user.isEmpty()) return "?";
    StringBuilder sb = new StringBuilder();
    int limit = Math.min(user.length(), 64);
    for (int i = 0; i < limit; i++) {
      char c = user.charAt(i);
      if (c > 32 && c <= 126 && c != '=') {
        sb.append(c);
      } else {
        sb.append('?');
      }
    }
    String result = sb.toString();
    return result.isEmpty() ? "?" : result;
  }

  public static String sanitizeIp(String raw) {
    if (raw == null || raw.isBlank()) return "?";
    String s = raw.trim();
    if (s.startsWith("/")) {
      s = s.substring(1).trim();
    }
    if (s.isEmpty()) return "?";

    // Format [IPv6]:port ou [IPv6]
    if (s.startsWith("[")) {
      int close = s.indexOf(']');
      if (close > 1) {
        s = s.substring(1, close).trim();
      }
    } else if (s.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+$")) {
      // IPv4:port
      int colon = s.lastIndexOf(':');
      s = s.substring(0, colon);
    } else if (s.indexOf(':') == s.lastIndexOf(':') && s.indexOf(':') > 0) {
      // host:port générique
      int colon = s.indexOf(':');
      String after = s.substring(colon + 1);
      if (after.matches("\\d+")) {
        s = s.substring(0, colon);
      }
    }

    // Nettoyage strict des caractères de l'adresse IP (ASCII 33..126, pas d'espaces ni contrôles)
    StringBuilder sb = new StringBuilder();
    int limit = Math.min(s.length(), 64);
    for (int i = 0; i < limit; i++) {
      char c = s.charAt(i);
      if (c > 32 && c <= 126) {
        sb.append(c);
      } else {
        sb.append('?');
      }
    }
    String result = sb.toString();
    return result.isEmpty() ? "?" : result;
  }

  public static String sanitizeProtocol(String protocol) {
    if (protocol == null || protocol.isEmpty()) return "?";
    return sanitizeToken(protocol, 32);
  }

  public static String sanitizeReason(String reason) {
    if (reason == null || reason.isEmpty()) return "?";
    return sanitizeToken(reason, 64);
  }

  private static String sanitizeToken(String token, int maxLen) {
    StringBuilder sb = new StringBuilder();
    int limit = Math.min(token.length(), maxLen);
    for (int i = 0; i < limit; i++) {
      char c = token.charAt(i);
      if (c > 32 && c <= 126 && c != '=') {
        sb.append(c);
      } else {
        sb.append('?');
      }
    }
    String result = sb.toString();
    return result.isEmpty() ? "?" : result;
  }
}
