package com.bowman.cspng.codec.cspv2;

/**
 * Types de trames CSPv2 v1.
 * Six implémentés + trois réservés (unsupported).
 */
public enum CspV2MessageType {
  HELLO(0x01, true),
  HELLO_ACK(0x02, true),
  ECM_REQUEST(0x10, true),
  DCW_REPLY(0x11, true),
  ERROR(0x31, true),
  HEARTBEAT(0x40, true),

  // Réservés v1 -> unsupported
  STATE_SNAPSHOT(0x20, false),
  STATE_DELTA(0x21, false),
  ACK(0x30, false);

  private final int code;
  private final boolean implemented;

  CspV2MessageType(int code, boolean implemented) {
    this.code = code;
    this.implemented = implemented;
  }

  public int code() {
    return code;
  }

  public boolean implemented() {
    return implemented;
  }

  public boolean reserved() {
    return !implemented;
  }

  public static CspV2MessageType fromCode(int code) {
    for (CspV2MessageType t : values()) {
      if (t.code == code) return t;
    }
    return null;
  }

  public static boolean isImplementedCode(int code) {
    CspV2MessageType t = fromCode(code);
    return t != null && t.implemented;
  }

  public static boolean isKnownCode(int code) {
    return fromCode(code) != null;
  }
}
