package com.bowman.cspng.domain;

import java.util.Objects;

/**
 * Requête ECM en cours de traitement.
 */
public record EcmRequest(
    EcmKey key,
    byte[] body,
    String profileName,
    String remoteAddress,
    String originId,
    long timestamp,
    Double arbiterNumber,
    long maxWaitMs,
    int provider) {

  public EcmRequest {
    Objects.requireNonNull(key, "key");
    body = body != null ? body.clone() : new byte[0];
  }

  public EcmRequest(
      EcmKey key,
      byte[] body,
      String profileName,
      String remoteAddress,
      String originId,
      long timestamp,
      Double arbiterNumber,
      long maxWaitMs) {
    this(key, body, profileName, remoteAddress, originId, timestamp, arbiterNumber, maxWaitMs, 0);
  }

  public EcmRequest withProvider(int newProvider) {
    return new EcmRequest(key, body, profileName, remoteAddress, originId, timestamp, arbiterNumber, maxWaitMs, newProvider);
  }

  public byte[] body() {
    return body.clone();
  }
}
