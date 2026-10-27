package com.bowman.cspng.codec.cspv2;

/**
 * Exception protocolaire typée pour CSPv2.
 * Porte une catégorie/code utilisable par le transport pour décider ERROR vs close.
 * Le magic invalide est distinguable via {@link #isInvalidMagic()}.
 */
public class CspV2ProtocolException extends RuntimeException {

  public enum Category {
    INVALID_MAGIC,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_TYPE,
    UNSUPPORTED_FLAGS,
    FRAME_TOO_LARGE,
    MALFORMED_PAYLOAD,
    STREAM_ID_INVALID
  }

  private final Category category;
  private final CspV2ErrorCode errorCode;
  private final boolean invalidMagic;

  public CspV2ProtocolException(Category category, CspV2ErrorCode errorCode, String message) {
    super(message);
    this.category = category;
    this.errorCode = errorCode;
    this.invalidMagic = category == Category.INVALID_MAGIC;
  }

  public CspV2ProtocolException(Category category, CspV2ErrorCode errorCode, String message, Throwable cause) {
    super(message, cause);
    this.category = category;
    this.errorCode = errorCode;
    this.invalidMagic = category == Category.INVALID_MAGIC;
  }

  public Category category() {
    return category;
  }

  public CspV2ErrorCode errorCode() {
    return errorCode;
  }

  public boolean isInvalidMagic() {
    return invalidMagic;
  }

  // Factories
  static CspV2ProtocolException invalidMagic(int actual) {
    return new CspV2ProtocolException(Category.INVALID_MAGIC, null,
        "invalid magic 0x" + Integer.toHexString(actual) + " expected 0x43535032");
  }

  static CspV2ProtocolException unsupportedVersion(int actual) {
    return new CspV2ProtocolException(Category.UNSUPPORTED_VERSION, CspV2ErrorCode.UNSUPPORTED_VERSION,
        "unsupported version " + actual);
  }

  static CspV2ProtocolException unsupportedType(int actual) {
    return new CspV2ProtocolException(Category.UNSUPPORTED_TYPE, CspV2ErrorCode.UNSUPPORTED_TYPE,
        "unsupported type 0x" + Integer.toHexString(actual));
  }

  static CspV2ProtocolException unsupportedFlags(int actual) {
    return new CspV2ProtocolException(Category.UNSUPPORTED_FLAGS, CspV2ErrorCode.UNSUPPORTED_FLAGS,
        "unsupported flags 0x" + Integer.toHexString(actual));
  }

  static CspV2ProtocolException frameTooLarge(long actual, long max) {
    return new CspV2ProtocolException(Category.FRAME_TOO_LARGE, CspV2ErrorCode.FRAME_TOO_LARGE,
        "payloadLength " + actual + " exceeds max " + max);
  }

  static CspV2ProtocolException malformed(String detail) {
    return new CspV2ProtocolException(Category.MALFORMED_PAYLOAD, CspV2ErrorCode.MALFORMED_PAYLOAD,
        detail);
  }

  static CspV2ProtocolException malformed(String detail, Throwable cause) {
    return new CspV2ProtocolException(Category.MALFORMED_PAYLOAD, CspV2ErrorCode.MALFORMED_PAYLOAD,
        detail, cause);
  }

  static CspV2ProtocolException streamInvalid(String detail) {
    return new CspV2ProtocolException(Category.STREAM_ID_INVALID, CspV2ErrorCode.STREAM_ID_INVALID,
        detail);
  }
}
