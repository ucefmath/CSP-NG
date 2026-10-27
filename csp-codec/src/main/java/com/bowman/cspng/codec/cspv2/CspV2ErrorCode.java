package com.bowman.cspng.codec.cspv2;

/**
 * Vocabulaire fermé des codes d'erreur normatifs (section 3.3.6).
 */
public enum CspV2ErrorCode {
  UNSUPPORTED_VERSION(0x0001, "unsupported_version"),
  UNSUPPORTED_TYPE(0x0002, "unsupported_type"),
  UNSUPPORTED_FLAGS(0x0003, "unsupported_flags"),
  FRAME_TOO_LARGE(0x0004, "frame_too_large"),
  MALFORMED_PAYLOAD(0x0005, "malformed_payload"),
  STREAM_ID_INVALID(0x0006, "stream_id_invalid"),
  TOO_MANY_INFLIGHT(0x0007, "too_many_inflight"),
  IDENTITY_MISMATCH(0x0008, "identity_mismatch"),
  NOT_AUTHORIZED(0x0009, "not_authorized"),
  TIMEOUT(0x000A, "timeout"),
  SHUTTING_DOWN(0x000B, "shutting_down"),
  RATE_LIMITED(0x000C, "rate_limited");

  private final int code;
  private final String canonicalReason;

  CspV2ErrorCode(int code, String canonicalReason) {
    this.code = code;
    this.canonicalReason = canonicalReason;
  }

  public int code() {
    return code;
  }

  public String canonicalReason() {
    return canonicalReason;
  }

  public static CspV2ErrorCode fromCode(int code) {
    for (CspV2ErrorCode c : values()) {
      if (c.code == code) return c;
    }
    return null;
  }

  public static boolean isKnownCode(int code) {
    return fromCode(code) != null;
  }
}
