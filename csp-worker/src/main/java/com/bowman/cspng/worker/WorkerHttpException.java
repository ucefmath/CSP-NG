package com.bowman.cspng.worker;

/**
 * Erreur HTTP typée du canal contrôle (Gemini FIX : jamais de
 * {@code err.message.contains(...)} côté appelant — matcher sur
 * {@link #statusCode()} / {@link #code()}).
 */
public class WorkerHttpException extends RuntimeException {

  private final int statusCode;
  private final String code;

  public WorkerHttpException(int statusCode, String code, String message) {
    super(message);
    this.statusCode = statusCode;
    this.code = code != null ? code : "HTTP_" + statusCode;
  }

  public int statusCode() {
    return statusCode;
  }

  public String code() {
    return code;
  }

  public boolean isAuthFailure() {
    return statusCode == 403;
  }

  public boolean isExpired() {
    return statusCode == 410;
  }

  public boolean isRevoked() {
    return isAuthFailure() || isExpired();
  }
}
