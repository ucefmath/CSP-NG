package com.bowman.cspng.codec.cspv2;

import java.util.regex.Pattern;

/**
 * Constantes normatives CSPv2 wire v1.
 */
public final class CspV2Constants {

  private CspV2Constants() {}

  public static final int MAGIC = 0x43535032; // "CSP2"
  public static final int VERSION = 0x01;
  public static final int HEADER_SIZE = 20;
  public static final int FLAGS_REQUIRED = 0x0000;

  public static final long MIN_MAX_PAYLOAD = 4096L;
  public static final long MAX_MAX_PAYLOAD = 16777216L;

  public static final long MIN_MAX_INFLIGHT = 1L;
  public static final long MAX_MAX_INFLIGHT = 65535L;

  public static final int MIN_NODE_ID_LEN = 1;
  public static final int MAX_NODE_ID_LEN = 63;
  public static final Pattern NODE_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,62}");

  public static final int MIN_PROFILE_LEN = 1;
  public static final int MAX_PROFILE_LEN = 64;

  public static final int MIN_ECM_LEN = 3;
  public static final int MAX_ECM_LEN = 512;

  public static final int MIN_CONNECTOR_NAME_LEN = 0;
  public static final int MAX_CONNECTOR_NAME_LEN = 64;

  public static final int MAX_ERROR_REASON_LEN = 120;

  public static final long MIN_MAX_WAIT_MS = 50L;
  public static final long MAX_MAX_WAIT_MS = 30000L;

  public static final int CW_LEN_OK = 16;
  public static final int CW_LEN_EMPTY = 0;

  // capabilities bits
  public static final long CAP_CHECKSUM_POLICY = 0x00000008L;

  // payload base sizes
  public static final int HELLO_BASE = 22; // 2 + N +8+4+4+4 =22+N
  public static final int ECM_BASE = 19; // 11+2+P+2+E+4 =19+P+E
  public static final int DCW_BASE = 16; // 11+1+1+1 +C+2+K =16+C+K   (11 EcmKey +5 fixed)
  public static final int ERROR_BASE = 4; // 2+2+R

  // u32 max as long for unsigned checks
  public static final long U32_MAX = 0xFFFFFFFFL;
}
