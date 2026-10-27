package com.bowman.cspng.codec.cspv2;

import java.util.Objects;

/**
 * Trame complète CSPv2 (header + payload typé).
 */
public record CspV2Frame(CspV2Header header, CspV2Payload payload) {
  public CspV2Frame {
    Objects.requireNonNull(header, "header");
    Objects.requireNonNull(payload, "payload");
  }
}
