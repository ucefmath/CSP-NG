package com.bowman.cspng.codec.cspv2;

import java.util.Objects;

/**
 * Payloads immuables avec defensive copies.
 * Utilise sealed interface + records imbriqués.
 */
public sealed interface CspV2Payload
    permits CspV2Payload.Hello,
            CspV2Payload.HelloAck,
            CspV2Payload.EcmRequest,
            CspV2Payload.DcwReply,
            CspV2Payload.ErrorPayload,
            CspV2Payload.Heartbeat {

  enum DcwStatus {
    OK(0), NOT_FOUND(1), DENIED(2);
    private final int code;
    DcwStatus(int code) { this.code = code; }
    public int code() { return code; }
    public static DcwStatus fromCode(int c) {
      for (DcwStatus s : values()) if (s.code == c) return s;
      return null;
    }
  }

  // --- HELLO ---------------------------------------------------------------

  record Hello(
      String nodeId,
      long connectionId,
      long capabilities,
      long maxPayloadAccepted,
      long maxInflightAccepted) implements CspV2Payload {
    public Hello {
      Objects.requireNonNull(nodeId, "nodeId");
      if (nodeId.isEmpty()) {
        throw new IllegalArgumentException("nodeId invalid");
      }
      byte[] nidBytes = CspV2Codec.encodeUtf8Strict(nodeId);
      if (nidBytes.length < CspV2Constants.MIN_NODE_ID_LEN || nidBytes.length > CspV2Constants.MAX_NODE_ID_LEN) {
        throw new IllegalArgumentException("nodeId length invalid");
      }
      if (!CspV2Constants.NODE_ID_PATTERN.matcher(nodeId).matches()) {
        throw new IllegalArgumentException("nodeId pattern invalid");
      }
      if (connectionId <= 0) {
        throw new IllegalArgumentException("connectionId invalid");
      }
      if (capabilities < 0 || capabilities > CspV2Constants.U32_MAX) {
        throw new IllegalArgumentException("capabilities out of range");
      }
      if (maxPayloadAccepted < CspV2Constants.MIN_MAX_PAYLOAD || maxPayloadAccepted > CspV2Constants.MAX_MAX_PAYLOAD) {
        throw new IllegalArgumentException("maxPayloadAccepted out of bounds");
      }
      if (maxInflightAccepted < CspV2Constants.MIN_MAX_INFLIGHT || maxInflightAccepted > CspV2Constants.MAX_MAX_INFLIGHT) {
        throw new IllegalArgumentException("maxInflightAccepted out of bounds");
      }
    }
  }

  // --- HELLO_ACK -----------------------------------------------------------

  record HelloAck(
      String nodeId,
      long connectionId,
      long capabilities,
      long maxPayloadAccepted,
      long maxInflightAccepted) implements CspV2Payload {
    public HelloAck {
      Objects.requireNonNull(nodeId, "nodeId");
      if (nodeId.isEmpty()) {
        throw new IllegalArgumentException("nodeId invalid");
      }
      byte[] nidBytes = CspV2Codec.encodeUtf8Strict(nodeId);
      if (nidBytes.length < CspV2Constants.MIN_NODE_ID_LEN || nidBytes.length > CspV2Constants.MAX_NODE_ID_LEN) {
        throw new IllegalArgumentException("nodeId length invalid");
      }
      if (!CspV2Constants.NODE_ID_PATTERN.matcher(nodeId).matches()) {
        throw new IllegalArgumentException("nodeId pattern invalid");
      }
      if (connectionId <= 0) {
        throw new IllegalArgumentException("connectionId invalid");
      }
      if (capabilities < 0 || capabilities > CspV2Constants.U32_MAX) {
        throw new IllegalArgumentException("capabilities out of range");
      }
      if (maxPayloadAccepted < CspV2Constants.MIN_MAX_PAYLOAD || maxPayloadAccepted > CspV2Constants.MAX_MAX_PAYLOAD) {
        throw new IllegalArgumentException("maxPayloadAccepted out of bounds");
      }
      if (maxInflightAccepted < CspV2Constants.MIN_MAX_INFLIGHT || maxInflightAccepted > CspV2Constants.MAX_MAX_INFLIGHT) {
        throw new IllegalArgumentException("maxInflightAccepted out of bounds");
      }
    }
  }

  // --- ECM_REQUEST ---------------------------------------------------------

  record EcmRequest(
      int commandTag,
      int serviceId,
      int networkId,
      int caId,
      int dataHash,
      String profile,
      byte[] ecm,
      long maxWaitMs) implements CspV2Payload {

    public EcmRequest {
      if (commandTag < 0 || commandTag > 255) throw new IllegalArgumentException("commandTag invalid");
      if (serviceId < 0 || serviceId > 0xFFFF) throw new IllegalArgumentException("serviceId invalid");
      if (networkId < 0 || networkId > 0xFFFF) throw new IllegalArgumentException("networkId invalid");
      if (caId < 0 || caId > 0xFFFF) throw new IllegalArgumentException("caId invalid");
      Objects.requireNonNull(profile, "profile");
      byte[] profBytes = CspV2Codec.encodeUtf8Strict(profile);
      if (profBytes.length < CspV2Constants.MIN_PROFILE_LEN || profBytes.length > CspV2Constants.MAX_PROFILE_LEN) {
        throw new IllegalArgumentException("profile length invalid");
      }
      Objects.requireNonNull(ecm, "ecm");
      ecm = ecm.clone();
      if (ecm.length < CspV2Constants.MIN_ECM_LEN || ecm.length > CspV2Constants.MAX_ECM_LEN) {
        throw new IllegalArgumentException("ecm length invalid");
      }
      if (maxWaitMs < CspV2Constants.MIN_MAX_WAIT_MS || maxWaitMs > CspV2Constants.MAX_MAX_WAIT_MS) {
        throw new IllegalArgumentException("maxWaitMs out of bounds");
      }
    }

    @Override
    public byte[] ecm() {
      return ecm.clone();
    }
  }

  // --- DCW_REPLY -----------------------------------------------------------

  record DcwReply(
      int commandTag,
      int serviceId,
      int networkId,
      int caId,
      int dataHash,
      int replyTag,
      DcwStatus status,
      byte[] cw,
      String connectorName) implements CspV2Payload {

    public DcwReply {
      if (commandTag < 0 || commandTag > 255) throw new IllegalArgumentException("commandTag invalid");
      if (serviceId < 0 || serviceId > 0xFFFF) throw new IllegalArgumentException("serviceId invalid");
      if (networkId < 0 || networkId > 0xFFFF) throw new IllegalArgumentException("networkId invalid");
      if (caId < 0 || caId > 0xFFFF) throw new IllegalArgumentException("caId invalid");
      if (replyTag < 0 || replyTag > 255) throw new IllegalArgumentException("replyTag invalid");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(cw, "cw");
      cw = cw.clone();
      Objects.requireNonNull(connectorName, "connectorName");
      byte[] cnBytes = CspV2Codec.encodeUtf8Strict(connectorName);
      if (cnBytes.length < CspV2Constants.MIN_CONNECTOR_NAME_LEN || cnBytes.length > CspV2Constants.MAX_CONNECTOR_NAME_LEN) {
        throw new IllegalArgumentException("connectorName length invalid");
      }
      if (status == DcwStatus.OK) {
        if (cw.length != CspV2Constants.CW_LEN_OK) throw new IllegalArgumentException("CW length invalid");
      } else {
        if (cw.length != CspV2Constants.CW_LEN_EMPTY) throw new IllegalArgumentException("CW length invalid");
      }
    }

    @Override
    public byte[] cw() {
      return cw.clone();
    }
  }

  // --- ERROR ---------------------------------------------------------------

  record ErrorPayload(
      CspV2ErrorCode code,
      String reason) implements CspV2Payload {

    public ErrorPayload {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(reason, "reason");
      byte[] rb = CspV2Codec.encodeUtf8Strict(reason);
      if (rb.length > CspV2Constants.MAX_ERROR_REASON_LEN) {
        throw new IllegalArgumentException("reason length invalid");
      }
      if (!reason.isEmpty() && !reason.equals(code.canonicalReason())) {
        throw new IllegalArgumentException("reason not allowed");
      }
    }
  }

  // --- HEARTBEAT -----------------------------------------------------------

  record Heartbeat() implements CspV2Payload {}
}
