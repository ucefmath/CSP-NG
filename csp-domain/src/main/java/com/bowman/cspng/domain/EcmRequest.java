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
    long maxWaitMs) {

  public EcmRequest {
    Objects.requireNonNull(key, "key");
    body = body != null ? body.clone() : new byte[0];
  }

  public byte[] body() {
    return body.clone();
  }
}
