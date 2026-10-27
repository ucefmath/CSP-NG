package com.bowman.cspng.cluster.tcp;

public class CspV2ConnectionException extends RuntimeException {
  public CspV2ConnectionException(String message) { super(message); }
  public CspV2ConnectionException(String message, Throwable cause) { super(message, cause); }
}
