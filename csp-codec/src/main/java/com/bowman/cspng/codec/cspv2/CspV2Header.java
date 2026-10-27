package com.bowman.cspng.codec.cspv2;

import java.util.Objects;

/**
 * En-tête CSPv2 20 octets immuable.
 */
public record CspV2Header(
    int magic,
    int version,
    CspV2MessageType type,
    int flags,
    long payloadLength,
    long streamId) {

  public CspV2Header {
    Objects.requireNonNull(type, "type");
    if (magic != CspV2Constants.MAGIC) {
      throw new IllegalArgumentException("magic must be 0x43535032");
    }
    if (version != CspV2Constants.VERSION) {
      throw new IllegalArgumentException("version must be 1");
    }
    if (flags != CspV2Constants.FLAGS_REQUIRED) {
      throw new IllegalArgumentException("flags must be 0");
    }
    if (payloadLength < 0 || payloadLength > CspV2Constants.U32_MAX) {
      throw new IllegalArgumentException("payloadLength out of u32 range");
    }
    if (payloadLength > CspV2Constants.MAX_MAX_PAYLOAD) {
      throw new IllegalArgumentException("payloadLength exceeds max");
    }
    if (!type.implemented()) {
      throw new IllegalArgumentException("reserved type not allowed");
    }
    if (streamId < 0) {
      throw new IllegalArgumentException("streamId must be >=0 (sign bit 0)");
    }
    // stream semantics per type (codec has no role, only 0 vs >0)
    switch (type) {
      case HELLO, HELLO_ACK, HEARTBEAT -> {
        if (streamId != 0) throw new IllegalArgumentException("streamId must be 0 for control");
      }
      case ECM_REQUEST, DCW_REPLY -> {
        if (streamId <= 0) throw new IllegalArgumentException("streamId must be >0");
      }
      case ERROR -> {
        if (streamId < 0) throw new IllegalArgumentException("streamId invalid");
      }
      default -> {}
    }
  }

  public int typeCode() {
    return type.code();
  }
}
