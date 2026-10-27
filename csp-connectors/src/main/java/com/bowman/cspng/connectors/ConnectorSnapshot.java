package com.bowman.cspng.connectors;

import java.util.List;

/**
 * Snapshot immuable sanitizé d'un connecteur.
 * N'expose jamais host/port/secrets/config brut.
 */
public record ConnectorSnapshot(
    String name,
    String protocol,
    String profile,
    boolean connected,
    String loginState,
    String state,
    long hits,
    long ecmSent,
    long ecmFailed,
    long lastSeenMs,
    List<String> caids,
    List<String> providers) {

  public ConnectorSnapshot {
    caids = caids == null ? List.of() : List.copyOf(caids);
    providers = providers == null ? List.of() : List.copyOf(providers);
  }

  /** Constructeur de compatibilité historique. */
  public ConnectorSnapshot(
      String name,
      String protocol,
      String profile,
      boolean connected,
      String loginState) {
    this(name, protocol, profile, connected, loginState, connected ? "CONNECTED" : "DEAD", 0L, 0L, 0L, 0L, List.of(), List.of());
  }

  /**
   * Dérive le protocole autorisé depuis l'instance.
   */
  public static String deriveProtocol(AbstractConnector c) {
    if (c instanceof NewcamdConnector) return "newcamd";
    if (c instanceof RadegastConnector) return "radegast";
    if (c instanceof CcCamConnector) return "cccam";
    if (c instanceof Camd35TcpConnector) return "cs378x";
    return "unknown";
  }

  public static String deriveLoginState(AbstractConnector c) {
    try {
      var fut = c.loginFuture();
      if (fut == null) return "UNKNOWN";
      if (!fut.isComplete()) return "PENDING";
      if (fut.succeeded() && fut.result() != null) return fut.result().name();
      if (fut.failed()) return "FAILED";
      return "UNKNOWN";
    } catch (Exception e) {
      return "UNKNOWN";
    }
  }

  public static String deriveState(AbstractConnector c) {
    if (c == null) return "DEAD";
    return c.getState().name();
  }
}
