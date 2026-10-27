package com.bowman.cspng.domain;

import java.util.Objects;

/**
 * Réponse CW (control word) issue d'un connecteur ou du cache.
 */
public record CwReply(
    EcmKey key,
    int commandTag,
    byte[] cw,
    String connectorName,
    String originAddress,
    long timestamp) {

  public CwReply {
    Objects.requireNonNull(key, "key");
    cw = cw != null ? cw.clone() : new byte[0];
  }

  public byte[] cw() {
    return cw.clone();
  }

  public boolean isEmpty() {
    return cw.length == 0;
  }

  public static CwReply empty(EcmKey key) {
    return new CwReply(key != null ? key : new EcmKey(0, 0, 0, 0, 0), 0, new byte[0], null, null, System.currentTimeMillis());
  }
}
