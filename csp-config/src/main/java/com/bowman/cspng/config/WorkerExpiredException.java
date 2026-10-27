package com.bowman.cspng.config;

/** Config worker expiree (mappee 410 WORKER_EXPIRED, jamais via message.contains). */
public class WorkerExpiredException extends ConfigValidationException {
  public WorkerExpiredException(String message) {
    super(message);
  }
}
