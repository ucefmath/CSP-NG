package com.bowman.cspng.connectors;

/** Connecteur temporairement occupé (single-flight) : charge, pas échec de qualité DCW. */
public final class ConnectorBusyException extends RuntimeException {
  public ConnectorBusyException() {
    super("ECM already in flight (MVP: 1 at a time)");
  }
}
