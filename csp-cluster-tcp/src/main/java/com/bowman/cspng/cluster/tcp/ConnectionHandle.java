package com.bowman.cspng.cluster.tcp;

import com.bowman.cspng.codec.cspv2.CspV2Payload;
import io.vertx.core.Future;
import java.util.Objects;

/**
 * Handle interne pour une connexion CSPv2. Package-private.
 * Permet des FakeHandle dans les tests sans mock framework.
 */
interface ConnectionHandle {

  CspV2ConnectionState state();

  boolean isClosed();

  Future<CspV2Payload.DcwReply> request(CspV2Payload.EcmRequest req);

  /** Vrai seulement après HELLO/ACK mutuels avec CAP_CHECKSUM_POLICY. Défaut : non négocié. */
  default boolean supportsChecksumPolicy() { return false; }

  void drain();

  void close();

  String peerNodeId();

  CspV2ConnectionRole role();

  // --- RealHandle --------------------------------------------------------

  record RealHandle(CspV2Connection conn) implements ConnectionHandle {
    public RealHandle {
      Objects.requireNonNull(conn, "conn");
    }
    @Override public CspV2ConnectionState state() { return conn.state(); }
    @Override public boolean isClosed() { return conn.isClosed(); }
    @Override public Future<CspV2Payload.DcwReply> request(CspV2Payload.EcmRequest req) { return conn.request(req); }
    @Override public boolean supportsChecksumPolicy() { return conn.supportsChecksumPolicy(); }
    @Override public void drain() { conn.drain(); }
    @Override public void close() { conn.close(); }
    @Override public String peerNodeId() { return conn.peerNodeId(); }
    @Override public CspV2ConnectionRole role() { return conn.role(); }
  }
}
