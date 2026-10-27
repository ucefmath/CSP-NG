package com.bowman.cspng.config;

/** Worker revoque/non autorise/inconnu (mappe 403 WORKER_REVOKED). */
public class WorkerRevokedException extends ConfigValidationException {
  public WorkerRevokedException(String message) {
    super(message);
  }
}
