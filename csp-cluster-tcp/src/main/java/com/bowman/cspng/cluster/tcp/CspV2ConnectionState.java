package com.bowman.cspng.cluster.tcp;

/**
 * États explicites de la connexion CSPv2.
 */
public enum CspV2ConnectionState {
  PRE_HANDSHAKE,
  HELLO_SENT,
  ESTABLISHED,
  DRAINING,
  CLOSED
}
