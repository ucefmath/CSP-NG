package com.bowman.cspng.codec.cspv2;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Codec pur CSPv2 wire v1. Aucune I/O réseau, aucune dépendance Quarkus/Vert.x.
 * <p>
 * API :
 * <ul>
 *   <li>{@link #decodeHeader(byte[], long)} avant allocation</li>
 *   <li>{@link #decodePayload(CspV2Header, byte[])}</li>
 *   <li>{@link #decodeFrame(byte[], long)}</li>
 *   <li>{@link #encodeFrame(CspV2Header, CspV2Payload)}</li>
 *   <li>{@link #encodeFrame(CspV2Payload, long)}</li>
 * </ul>
 */
public final class CspV2Codec {

  private CspV2Codec() {}

  // ---------------------------------------------------------------------------
  // Header
  // ---------------------------------------------------------------------------

  /**
   * Décode et valide l'en-tête de 20 octets. N'alloue pas le payload.
   *
   * @param headerBytes exactement 20 octets
   * @param maxPayload cap local / négocié [4096..16777216]; utilisé pour FRAME_TOO_LARGE
   */
  public static CspV2Header decodeHeader(byte[] headerBytes, long maxPayload) {
    if (headerBytes == null) {
      throw CspV2ProtocolException.malformed("header null");
    }
    if (headerBytes.length != CspV2Constants.HEADER_SIZE) {
      throw CspV2ProtocolException.malformed(
          "header size must be 20 got " + headerBytes.length);
    }
    if (maxPayload < CspV2Constants.MIN_MAX_PAYLOAD || maxPayload > CspV2Constants.MAX_MAX_PAYLOAD) {
      throw new IllegalArgumentException("maxPayload out of bounds [4096..16777216]: " + maxPayload);
    }
    int magic = readInt32BE(headerBytes, 0);
    if (magic != CspV2Constants.MAGIC) {
      throw CspV2ProtocolException.invalidMagic(magic);
    }
    int version = headerBytes[4] & 0xFF;
    if (version != CspV2Constants.VERSION) {
      throw CspV2ProtocolException.unsupportedVersion(version);
    }
    int typeCode = headerBytes[5] & 0xFF;
    CspV2MessageType type = CspV2MessageType.fromCode(typeCode);
    if (type == null || !type.implemented()) {
      throw CspV2ProtocolException.unsupportedType(typeCode);
    }
    int flags = readU16BE(headerBytes, 6);
    if (flags != CspV2Constants.FLAGS_REQUIRED) {
      throw CspV2ProtocolException.unsupportedFlags(flags);
    }
    long payloadLength = readU32BE(headerBytes, 8);
    if (payloadLength > maxPayload) {
      throw CspV2ProtocolException.frameTooLarge(payloadLength, maxPayload);
    }
    // payloadLength is u32, so always <= 0xFFFFFFFFL, but also enforce absolute max
    if (payloadLength > CspV2Constants.MAX_MAX_PAYLOAD) {
      throw CspV2ProtocolException.frameTooLarge(payloadLength, CspV2Constants.MAX_MAX_PAYLOAD);
    }
    long streamId = readInt64BE(headerBytes, 12);
    if (streamId < 0) {
      throw CspV2ProtocolException.streamInvalid("streamId sign bit set");
    }
    // stream semantics per type (codec knows only 0 vs >0, not odd/even)
    validateStreamForType(type, streamId);

    return new CspV2Header(magic, version, type, flags, payloadLength, streamId);
  }

  private static void validateStreamForType(CspV2MessageType type, long streamId) {
    switch (type) {
      case HELLO, HELLO_ACK, HEARTBEAT -> {
        if (streamId != 0) {
          throw CspV2ProtocolException.streamInvalid(
              type + " requires streamId 0 got " + streamId);
        }
      }
      case ECM_REQUEST -> {
        if (streamId <= 0) {
          throw CspV2ProtocolException.streamInvalid(
              "ECM_REQUEST requires streamId >0 got " + streamId);
        }
      }
      case DCW_REPLY -> {
        if (streamId <= 0) {
          throw CspV2ProtocolException.streamInvalid(
              "DCW_REPLY requires streamId >0 got " + streamId);
        }
      }
      case ERROR -> {
        if (streamId < 0) {
          throw CspV2ProtocolException.streamInvalid("ERROR streamId invalid");
        }
        // 0 or >0 allowed
      }
      default -> { /* should not happen for implemented */ }
    }
  }

  // ---------------------------------------------------------------------------
  // Payload
  // ---------------------------------------------------------------------------

  public static CspV2Payload decodePayload(CspV2Header header, byte[] payload) {
    return decodePayload(header, payload, false);
  }

  public static CspV2Payload decodePayload(CspV2Header header, byte[] payload, boolean allowNonChecksumPolicy) {
    Objects.requireNonNull(header, "header");
    if (payload == null) payload = new byte[0];
    if (payload.length != header.payloadLength()) {
      throw CspV2ProtocolException.malformed(
          "payload length mismatch header=" + header.payloadLength() + " actual=" + payload.length);
    }
    // HEARTBEAT expects 0 length already validated, but still dispatch
    return switch (header.type()) {
      case HELLO -> decodeHello(payload);
      case HELLO_ACK -> decodeHelloAck(payload);
      case ECM_REQUEST -> decodeEcmRequest(payload);
      case DCW_REPLY -> decodeDcwReply(payload, allowNonChecksumPolicy);
      case ERROR -> decodeError(payload);
      case HEARTBEAT -> decodeHeartbeat(payload);
      default -> throw CspV2ProtocolException.unsupportedType(header.type().code());
    };
  }

  private static CspV2Payload.Hello decodeHello(byte[] p) {
    int off = 0;
    if (p.length < 2) throw CspV2ProtocolException.malformed("HELLO truncated nodeIdLen");
    int nodeIdLen = readU16BE(p, off); off += 2;
    if (nodeIdLen < CspV2Constants.MIN_NODE_ID_LEN || nodeIdLen > CspV2Constants.MAX_NODE_ID_LEN) {
      throw CspV2ProtocolException.malformed("nodeIdLen invalid");
    }
    if (p.length < 2 + nodeIdLen + 8 + 4 + 4 + 4) {
      throw CspV2ProtocolException.malformed("HELLO truncated");
    }
    if (off + nodeIdLen > p.length) throw CspV2ProtocolException.malformed("HELLO nodeId truncated");
    String nodeId = decodeUtf8Strict(p, off, nodeIdLen); off += nodeIdLen;
    if (!CspV2Constants.NODE_ID_PATTERN.matcher(nodeId).matches()) {
      throw CspV2ProtocolException.malformed("nodeId regex mismatch");
    }
    // Validate UTF8 bytes length already == nodeIdLen, but also ensure no trailing; already done.

    if (off + 8 > p.length) throw CspV2ProtocolException.malformed("HELLO truncated connectionId");
    long connectionId = readInt64BE(p, off); off += 8;
    if (connectionId <= 0) {
      throw CspV2ProtocolException.malformed("connectionId invalid");
    }
    // connectionId sign already checked (>0)
    if (off + 4 > p.length) throw CspV2ProtocolException.malformed("HELLO truncated caps");
    long caps = readU32BE(p, off); off += 4;
    if (off + 4 > p.length) throw CspV2ProtocolException.malformed("HELLO truncated maxPayload");
    long maxPayload = readU32BE(p, off); off += 4;
    if (maxPayload < CspV2Constants.MIN_MAX_PAYLOAD || maxPayload > CspV2Constants.MAX_MAX_PAYLOAD) {
      throw CspV2ProtocolException.malformed("maxPayloadAccepted out of bounds");
    }
    if (off + 4 > p.length) throw CspV2ProtocolException.malformed("HELLO truncated maxInflight");
    long maxInflight = readU32BE(p, off); off += 4;
    if (maxInflight < CspV2Constants.MIN_MAX_INFLIGHT || maxInflight > CspV2Constants.MAX_MAX_INFLIGHT) {
      throw CspV2ProtocolException.malformed("maxInflightAccepted out of bounds");
    }
    if (off != p.length) throw CspV2ProtocolException.malformed("HELLO trailing bytes");
    if (p.length != 22 + nodeIdLen) throw CspV2ProtocolException.malformed("HELLO size mismatch");
    return new CspV2Payload.Hello(nodeId, connectionId, caps, maxPayload, maxInflight);
  }

  private static CspV2Payload.HelloAck decodeHelloAck(byte[] p) {
    if (p.length < 2) throw CspV2ProtocolException.malformed("HELLO_ACK truncated nodeIdLen");
    int nodeIdLen = readU16BE(p, 0);
    if (nodeIdLen < CspV2Constants.MIN_NODE_ID_LEN || nodeIdLen > CspV2Constants.MAX_NODE_ID_LEN) {
      throw CspV2ProtocolException.malformed("nodeIdLen out of bounds");
    }
    if (p.length < 22 + nodeIdLen) throw CspV2ProtocolException.malformed("HELLO_ACK truncated");
    String nodeId = decodeUtf8Strict(p, 2, nodeIdLen);
    if (!CspV2Constants.NODE_ID_PATTERN.matcher(nodeId).matches()) {
      throw CspV2ProtocolException.malformed("nodeId regex mismatch");
    }
    int off = 2 + nodeIdLen;
    long connectionId = readInt64BE(p, off); off += 8;
    if (connectionId <= 0) throw CspV2ProtocolException.malformed("connectionId invalid");
    long caps = readU32BE(p, off); off += 4;
    long maxPayload = readU32BE(p, off); off += 4;
    if (maxPayload < CspV2Constants.MIN_MAX_PAYLOAD || maxPayload > CspV2Constants.MAX_MAX_PAYLOAD) {
      throw CspV2ProtocolException.malformed("maxPayloadAccepted invalid");
    }
    long maxInflight = readU32BE(p, off); off += 4;
    if (maxInflight < CspV2Constants.MIN_MAX_INFLIGHT || maxInflight > CspV2Constants.MAX_MAX_INFLIGHT) {
      throw CspV2ProtocolException.malformed("maxInflightAccepted invalid");
    }
    if (off != p.length) throw CspV2ProtocolException.malformed("HELLO_ACK trailing bytes");
    return new CspV2Payload.HelloAck(nodeId, connectionId, caps, maxPayload, maxInflight);
  }

  private static CspV2Payload.EcmRequest decodeEcmRequest(byte[] p) {
    int off = 0;
    if (p.length < 11 + 2 + 1) throw CspV2ProtocolException.malformed("ECM_REQUEST truncated");
    int commandTag = p[off++] & 0xFF;
    int serviceId = readU16BE(p, off); off += 2;
    int networkId = readU16BE(p, off); off += 2;
    int caId = readU16BE(p, off); off += 2;
    int dataHash = readInt32BE(p, off); off += 4;

    if (off + 2 > p.length) throw CspV2ProtocolException.malformed("ECM_REQUEST truncated profileLen");
    int profileLen = readU16BE(p, off); off += 2;
    if (profileLen < CspV2Constants.MIN_PROFILE_LEN || profileLen > CspV2Constants.MAX_PROFILE_LEN) {
      throw CspV2ProtocolException.malformed("profileLen invalid");
    }
    if (off + profileLen > p.length) throw CspV2ProtocolException.malformed("ECM_REQUEST truncated profile");
    String profile = decodeUtf8Strict(p, off, profileLen); off += profileLen;

    if (off + 2 > p.length) throw CspV2ProtocolException.malformed("ECM_REQUEST truncated ecmLen");
    int ecmLen = readU16BE(p, off); off += 2;
    if (ecmLen < CspV2Constants.MIN_ECM_LEN || ecmLen > CspV2Constants.MAX_ECM_LEN) {
      throw CspV2ProtocolException.malformed("ecmLen invalid");
    }
    if (off + ecmLen > p.length) throw CspV2ProtocolException.malformed("ECM_REQUEST truncated ecm");
    byte[] ecm = Arrays.copyOfRange(p, off, off + ecmLen); off += ecmLen;

    if (off + 4 > p.length) throw CspV2ProtocolException.malformed("ECM_REQUEST truncated maxWaitMs");
    long maxWaitMs = readU32BE(p, off); off += 4;
    if (maxWaitMs < CspV2Constants.MIN_MAX_WAIT_MS || maxWaitMs > CspV2Constants.MAX_MAX_WAIT_MS) {
      throw CspV2ProtocolException.malformed("maxWaitMs invalid");
    }
    if (off != p.length) throw CspV2ProtocolException.malformed("ECM_REQUEST trailing bytes");
    if (p.length != 19 + profileLen + ecmLen) {
      throw CspV2ProtocolException.malformed("ECM_REQUEST size mismatch");
    }
    // hash validation
    int computed = ecmHash(ecm, 3, ecm.length - 3);
    if (computed != dataHash) {
      throw CspV2ProtocolException.malformed("hash mismatch");
    }
    return new CspV2Payload.EcmRequest(commandTag, serviceId, networkId, caId, dataHash, profile, ecm, maxWaitMs);
  }

  private static CspV2Payload.DcwReply decodeDcwReply(byte[] p) {
    return decodeDcwReply(p, false);
  }

  private static CspV2Payload.DcwReply decodeDcwReply(byte[] p, boolean allowNonChecksumPolicy) {
    int off = 0;
    if (p.length < 16) throw CspV2ProtocolException.malformed("DCW_REPLY truncated base");
    int commandTag = p[off++] & 0xFF;
    int serviceId = readU16BE(p, off); off += 2;
    int networkId = readU16BE(p, off); off += 2;
    int caId = readU16BE(p, off); off += 2;
    int dataHash = readInt32BE(p, off); off += 4;
    int replyTag = p[off++] & 0xFF;
    int statusCode = p[off++] & 0xFF;
    CspV2Payload.DcwStatus status = CspV2Payload.DcwStatus.fromCode(statusCode);
    if (status == null) throw CspV2ProtocolException.malformed("status invalid");
    int cwLen = p[off++] & 0xFF;
    if (status == CspV2Payload.DcwStatus.OK) {
      if (cwLen != CspV2Constants.CW_LEN_OK) throw CspV2ProtocolException.malformed("cwLen must be 16 for OK");
    } else {
      if (cwLen != CspV2Constants.CW_LEN_EMPTY) throw CspV2ProtocolException.malformed("cwLen must be 0 for NOT_FOUND/DENIED");
    }
    if (off + cwLen > p.length) throw CspV2ProtocolException.malformed("DCW_REPLY truncated cw");
    byte[] cw = cwLen == 0 ? new byte[0] : Arrays.copyOfRange(p, off, off + cwLen); off += cwLen;

    if (status == CspV2Payload.DcwStatus.OK) {
      if (!allowNonChecksumPolicy && !checksumDcw(cw)) {
        throw CspV2ProtocolException.malformed("cw checksum invalid");
      }
    }

    if (off + 2 > p.length) throw CspV2ProtocolException.malformed("DCW_REPLY truncated connectorNameLen");
    int connectorNameLen = readU16BE(p, off); off += 2;
    if (connectorNameLen < CspV2Constants.MIN_CONNECTOR_NAME_LEN || connectorNameLen > CspV2Constants.MAX_CONNECTOR_NAME_LEN) {
      throw CspV2ProtocolException.malformed("connectorNameLen invalid");
    }
    if (off + connectorNameLen > p.length) throw CspV2ProtocolException.malformed("DCW_REPLY truncated connectorName");
    String connectorName = "";
    if (connectorNameLen > 0) {
      connectorName = decodeUtf8Strict(p, off, connectorNameLen);
    }
    off += connectorNameLen;
    if (off != p.length) throw CspV2ProtocolException.malformed("DCW_REPLY trailing bytes");
    if (p.length != 16 + cwLen + connectorNameLen) {
      throw CspV2ProtocolException.malformed("DCW_REPLY size mismatch");
    }
    return new CspV2Payload.DcwReply(commandTag, serviceId, networkId, caId, dataHash, replyTag, status, cw, connectorName);
  }

  private static CspV2Payload.ErrorPayload decodeError(byte[] p) {
    int off = 0;
    if (p.length < 4) throw CspV2ProtocolException.malformed("ERROR truncated");
    int codeVal = readU16BE(p, off); off += 2;
    CspV2ErrorCode code = CspV2ErrorCode.fromCode(codeVal);
    if (code == null) throw CspV2ProtocolException.malformed("error code invalid");
    int reasonLen = readU16BE(p, off); off += 2;
    if (reasonLen < 0 || reasonLen > CspV2Constants.MAX_ERROR_REASON_LEN) {
      throw CspV2ProtocolException.malformed("reasonLen invalid");
    }
    if (off + reasonLen > p.length) throw CspV2ProtocolException.malformed("ERROR truncated reason");
    String reason = "";
    if (reasonLen > 0) {
      reason = decodeUtf8Strict(p, off, reasonLen);
      if (!reason.isEmpty() && !reason.equals(code.canonicalReason())) {
        throw CspV2ProtocolException.malformed("reason not allowed");
      }
    }
    off += reasonLen;
    if (off != p.length) throw CspV2ProtocolException.malformed("ERROR trailing bytes");
    if (p.length != 4 + reasonLen) throw CspV2ProtocolException.malformed("ERROR size mismatch");
    return new CspV2Payload.ErrorPayload(code, reason);
  }

  private static CspV2Payload.Heartbeat decodeHeartbeat(byte[] p) {
    if (p.length != 0) throw CspV2ProtocolException.malformed("HEARTBEAT must be empty");
    return new CspV2Payload.Heartbeat();
  }

  // ---------------------------------------------------------------------------
  // Frame full
  // ---------------------------------------------------------------------------

  public static CspV2Frame decodeFrame(byte[] frame, long maxPayload) {
    if (frame == null) throw CspV2ProtocolException.malformed("frame null");
    if (frame.length < CspV2Constants.HEADER_SIZE) {
      throw CspV2ProtocolException.malformed("frame truncated <20");
    }
    byte[] headerBytes = Arrays.copyOfRange(frame, 0, CspV2Constants.HEADER_SIZE);
    CspV2Header header = decodeHeader(headerBytes, maxPayload);
    long expected = CspV2Constants.HEADER_SIZE + header.payloadLength();
    if (frame.length != expected) {
      throw CspV2ProtocolException.malformed(
          "frame length mismatch expected " + expected + " got " + frame.length);
    }
    byte[] payloadBytes = frame.length == CspV2Constants.HEADER_SIZE ? new byte[0]
        : Arrays.copyOfRange(frame, CspV2Constants.HEADER_SIZE, frame.length);
    CspV2Payload payload = decodePayload(header, payloadBytes);
    return new CspV2Frame(header, payload);
  }

  // ---------------------------------------------------------------------------
  // Encode
  // ---------------------------------------------------------------------------

  public static byte[] encodeFrame(CspV2Header header, CspV2Payload payload) {
    Objects.requireNonNull(header, "header");
    Objects.requireNonNull(payload, "payload");
    byte[] payloadBytes = encodePayload(payload);
    if (payloadBytes.length != header.payloadLength()) {
      throw new IllegalArgumentException(
          "payload length " + payloadBytes.length + " does not match header " + header.payloadLength());
    }
    // also validate stream per type again (defense)
    validateStreamForType(header.type(), header.streamId());
    // ensure type matches payload
    validateTypeMatchesPayload(header.type(), payload);
    byte[] frame = new byte[CspV2Constants.HEADER_SIZE + payloadBytes.length];
    writeHeader(frame, 0, header);
    System.arraycopy(payloadBytes, 0, frame, CspV2Constants.HEADER_SIZE, payloadBytes.length);
    return frame;
  }

  public static byte[] encodeFrame(CspV2Payload payload, long streamId) {
    return encodeFrame(payload, streamId, false);
  }

  public static byte[] encodeFrame(CspV2Payload payload, long streamId, boolean allowNonChecksumPolicy) {
    Objects.requireNonNull(payload, "payload");
    if (streamId < 0) throw new IllegalArgumentException("streamId must be >=0");
    CspV2MessageType type = typeForPayload(payload);
    validateStreamForType(type, streamId);
    byte[] payloadBytes = encodePayload(payload, allowNonChecksumPolicy);
    if (payloadBytes.length > CspV2Constants.MAX_MAX_PAYLOAD) {
      throw CspV2ProtocolException.frameTooLarge(payloadBytes.length, CspV2Constants.MAX_MAX_PAYLOAD);
    }
    CspV2Header header = new CspV2Header(
        CspV2Constants.MAGIC,
        CspV2Constants.VERSION,
        type,
        CspV2Constants.FLAGS_REQUIRED,
        payloadBytes.length,
        streamId);
    byte[] frame = new byte[CspV2Constants.HEADER_SIZE + payloadBytes.length];
    writeHeader(frame, 0, header);
    System.arraycopy(payloadBytes, 0, frame, CspV2Constants.HEADER_SIZE, payloadBytes.length);
    return frame;
  }

  /** Encode header only (20 bytes). Useful for tests. */
  public static byte[] encodeHeader(CspV2Header header) {
    byte[] out = new byte[CspV2Constants.HEADER_SIZE];
    writeHeader(out, 0, header);
    return out;
  }

  public static byte[] encodePayload(CspV2Payload payload) {
    return encodePayload(payload, false);
  }

  public static byte[] encodePayload(CspV2Payload payload, boolean allowNonChecksumPolicy) {
    Objects.requireNonNull(payload, "payload");
    if (payload instanceof CspV2Payload.Hello h) return encodeHello(h);
    if (payload instanceof CspV2Payload.HelloAck ha) return encodeHelloAck(ha);
    if (payload instanceof CspV2Payload.EcmRequest er) return encodeEcmRequest(er);
    if (payload instanceof CspV2Payload.DcwReply dr) return encodeDcwReply(dr, allowNonChecksumPolicy);
    if (payload instanceof CspV2Payload.ErrorPayload ep) return encodeError(ep);
    if (payload instanceof CspV2Payload.Heartbeat) return new byte[0];
    throw new IllegalArgumentException("unknown payload type");
  }

  private static byte[] encodeHello(CspV2Payload.Hello h) {
    byte[] nodeIdBytes = encodeUtf8Strict(h.nodeId());
    if (nodeIdBytes.length < CspV2Constants.MIN_NODE_ID_LEN || nodeIdBytes.length > CspV2Constants.MAX_NODE_ID_LEN) {
      throw new IllegalArgumentException("nodeId UTF8 length out of bounds 1..63");
    }
    if (!CspV2Constants.NODE_ID_PATTERN.matcher(h.nodeId()).matches()) {
      throw new IllegalArgumentException("nodeId regex mismatch");
    }
    byte[] out = new byte[22 + nodeIdBytes.length];
    int off = 0;
    writeU16BE(out, off, nodeIdBytes.length); off += 2;
    System.arraycopy(nodeIdBytes, 0, out, off, nodeIdBytes.length); off += nodeIdBytes.length;
    writeInt64BE(out, off, h.connectionId()); off += 8;
    writeU32BE(out, off, h.capabilities()); off += 4;
    writeU32BE(out, off, h.maxPayloadAccepted()); off += 4;
    writeU32BE(out, off, h.maxInflightAccepted()); off += 4;
    return out;
  }

  private static byte[] encodeHelloAck(CspV2Payload.HelloAck ha) {
    byte[] nodeIdBytes = encodeUtf8Strict(ha.nodeId());
    if (nodeIdBytes.length < CspV2Constants.MIN_NODE_ID_LEN || nodeIdBytes.length > CspV2Constants.MAX_NODE_ID_LEN) {
      throw new IllegalArgumentException("nodeId length out of bounds");
    }
    if (!CspV2Constants.NODE_ID_PATTERN.matcher(ha.nodeId()).matches()) {
      throw new IllegalArgumentException("nodeId regex mismatch");
    }
    byte[] out = new byte[22 + nodeIdBytes.length];
    int off = 0;
    writeU16BE(out, off, nodeIdBytes.length); off += 2;
    System.arraycopy(nodeIdBytes, 0, out, off, nodeIdBytes.length); off += nodeIdBytes.length;
    writeInt64BE(out, off, ha.connectionId()); off += 8;
    writeU32BE(out, off, ha.capabilities()); off += 4;
    writeU32BE(out, off, ha.maxPayloadAccepted()); off += 4;
    writeU32BE(out, off, ha.maxInflightAccepted()); off += 4;
    return out;
  }

  private static byte[] encodeEcmRequest(CspV2Payload.EcmRequest er) {
    byte[] profileBytes = encodeUtf8Strict(er.profile());
    if (profileBytes.length < CspV2Constants.MIN_PROFILE_LEN || profileBytes.length > CspV2Constants.MAX_PROFILE_LEN) {
      throw new IllegalArgumentException("profile length out of bounds 1..64 got " + profileBytes.length);
    }
    byte[] ecm = er.ecm();
    if (ecm.length < CspV2Constants.MIN_ECM_LEN || ecm.length > CspV2Constants.MAX_ECM_LEN) {
      throw new IllegalArgumentException("ecm length out of bounds");
    }
    // validate hash matches
    int computed = ecmHash(ecm, 3, ecm.length - 3);
    if (computed != er.dataHash()) {
      throw new IllegalArgumentException("hash mismatch");
    }
    byte[] out = new byte[19 + profileBytes.length + ecm.length];
    int off = 0;
    out[off++] = (byte) (er.commandTag() & 0xFF);
    writeU16BE(out, off, er.serviceId()); off += 2;
    writeU16BE(out, off, er.networkId()); off += 2;
    writeU16BE(out, off, er.caId()); off += 2;
    writeInt32BE(out, off, er.dataHash()); off += 4;
    writeU16BE(out, off, profileBytes.length); off += 2;
    System.arraycopy(profileBytes, 0, out, off, profileBytes.length); off += profileBytes.length;
    writeU16BE(out, off, ecm.length); off += 2;
    System.arraycopy(ecm, 0, out, off, ecm.length); off += ecm.length;
    writeU32BE(out, off, er.maxWaitMs()); off += 4;
    return out;
  }

  private static byte[] encodeDcwReply(CspV2Payload.DcwReply dr) {
    return encodeDcwReply(dr, false);
  }

  private static byte[] encodeDcwReply(CspV2Payload.DcwReply dr, boolean allowNonChecksumPolicy) {
    byte[] connectorBytes = encodeUtf8Strict(dr.connectorName());
    if (connectorBytes.length > CspV2Constants.MAX_CONNECTOR_NAME_LEN) {
      throw new IllegalArgumentException("connectorName exceeds 64 UTF8 bytes");
    }
    if (dr.status() == CspV2Payload.DcwStatus.OK) {
      if (dr.cw().length != 16) throw new IllegalArgumentException("CW must be 16 for OK");
      if (!allowNonChecksumPolicy && !checksumDcw(dr.cw())) throw new IllegalArgumentException("cw checksum invalid for OK");
    } else {
      if (dr.cw().length != 0) throw new IllegalArgumentException("CW must be 0 for NOT_FOUND/DENIED");
    }
    int cwLen = dr.cw().length;
    byte[] out = new byte[16 + cwLen + connectorBytes.length];
    int off = 0;
    out[off++] = (byte) (dr.commandTag() & 0xFF);
    writeU16BE(out, off, dr.serviceId()); off += 2;
    writeU16BE(out, off, dr.networkId()); off += 2;
    writeU16BE(out, off, dr.caId()); off += 2;
    writeInt32BE(out, off, dr.dataHash()); off += 4;
    out[off++] = (byte) (dr.replyTag() & 0xFF);
    out[off++] = (byte) (dr.status().code() & 0xFF);
    out[off++] = (byte) (cwLen & 0xFF);
    if (cwLen > 0) {
      System.arraycopy(dr.cw(), 0, out, off, cwLen); off += cwLen;
    }
    writeU16BE(out, off, connectorBytes.length); off += 2;
    if (connectorBytes.length > 0) {
      System.arraycopy(connectorBytes, 0, out, off, connectorBytes.length); off += connectorBytes.length;
    }
    return out;
  }

  private static byte[] encodeError(CspV2Payload.ErrorPayload ep) {
    byte[] reasonBytes = encodeUtf8Strict(ep.reason());
    if (reasonBytes.length > CspV2Constants.MAX_ERROR_REASON_LEN) {
      throw new IllegalArgumentException("reason invalid");
    }
    if (!ep.reason().isEmpty() && !ep.reason().equals(ep.code().canonicalReason())) {
      throw new IllegalArgumentException("reason not allowed");
    }
    byte[] out = new byte[4 + reasonBytes.length];
    int off = 0;
    writeU16BE(out, off, ep.code().code()); off += 2;
    writeU16BE(out, off, reasonBytes.length); off += 2;
    if (reasonBytes.length > 0) {
      System.arraycopy(reasonBytes, 0, out, off, reasonBytes.length);
    }
    return out;
  }

  private static CspV2MessageType typeForPayload(CspV2Payload p) {
    if (p instanceof CspV2Payload.Hello) return CspV2MessageType.HELLO;
    if (p instanceof CspV2Payload.HelloAck) return CspV2MessageType.HELLO_ACK;
    if (p instanceof CspV2Payload.EcmRequest) return CspV2MessageType.ECM_REQUEST;
    if (p instanceof CspV2Payload.DcwReply) return CspV2MessageType.DCW_REPLY;
    if (p instanceof CspV2Payload.ErrorPayload) return CspV2MessageType.ERROR;
    if (p instanceof CspV2Payload.Heartbeat) return CspV2MessageType.HEARTBEAT;
    throw new IllegalArgumentException("unknown payload");
  }

  private static void validateTypeMatchesPayload(CspV2MessageType t, CspV2Payload p) {
    CspV2MessageType expected = typeForPayload(p);
    if (t != expected) {
      throw new IllegalArgumentException("type " + t + " does not match payload " + expected);
    }
  }

  // ---------------------------------------------------------------------------
  // Low-level helpers
  // ---------------------------------------------------------------------------

  static int readInt32BE(byte[] buf, int off) {
    return (buf[off] << 24)
        | ((buf[off + 1] & 0xFF) << 16)
        | ((buf[off + 2] & 0xFF) << 8)
        | (buf[off + 3] & 0xFF);
  }

  static void writeInt32BE(byte[] buf, int off, int v) {
    buf[off] = (byte) (v >>> 24);
    buf[off + 1] = (byte) (v >>> 16);
    buf[off + 2] = (byte) (v >>> 8);
    buf[off + 3] = (byte) v;
  }

  static int readU16BE(byte[] buf, int off) {
    return ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
  }

  static void writeU16BE(byte[] buf, int off, int v) {
    buf[off] = (byte) (v >>> 8);
    buf[off + 1] = (byte) v;
  }

  static long readU32BE(byte[] buf, int off) {
    return ((buf[off] & 0xFFL) << 24)
        | ((buf[off + 1] & 0xFFL) << 16)
        | ((buf[off + 2] & 0xFFL) << 8)
        | (buf[off + 3] & 0xFFL);
  }

  static void writeU32BE(byte[] buf, int off, long v) {
    if (v < 0 || v > CspV2Constants.U32_MAX) throw new IllegalArgumentException("u32 out of range");
    buf[off] = (byte) (v >>> 24);
    buf[off + 1] = (byte) (v >>> 16);
    buf[off + 2] = (byte) (v >>> 8);
    buf[off + 3] = (byte) v;
  }

  static long readInt64BE(byte[] buf, int off) {
    long v = 0;
    for (int i = 0; i < 8; i++) {
      v = (v << 8) | (buf[off + i] & 0xFFL);
    }
    return v;
  }

  static void writeInt64BE(byte[] buf, int off, long v) {
    buf[off] = (byte) (v >>> 56);
    buf[off + 1] = (byte) (v >>> 48);
    buf[off + 2] = (byte) (v >>> 40);
    buf[off + 3] = (byte) (v >>> 32);
    buf[off + 4] = (byte) (v >>> 24);
    buf[off + 5] = (byte) (v >>> 16);
    buf[off + 6] = (byte) (v >>> 8);
    buf[off + 7] = (byte) v;
  }

  static void writeHeader(byte[] buf, int off, CspV2Header h) {
    if (!h.type().implemented()) {
      throw new IllegalArgumentException("reserved type not allowed");
    }
    if (h.payloadLength() > CspV2Constants.MAX_MAX_PAYLOAD) {
      throw new IllegalArgumentException("payloadLength exceeds max");
    }
    // stream semantics already validated in header, double-check
    validateStreamForType(h.type(), h.streamId());
    writeInt32BE(buf, off, h.magic()); off += 4;
    buf[off++] = (byte) (h.version() & 0xFF);
    buf[off++] = (byte) (h.type().code() & 0xFF);
    writeU16BE(buf, off, h.flags()); off += 2;
    writeU32BE(buf, off, h.payloadLength()); off += 4;
    writeInt64BE(buf, off, h.streamId());
  }

  // UTF-8 strict with REPORT
  static String decodeUtf8Strict(byte[] buf, int off, int len) {
    if (off < 0 || len < 0 || off + len > buf.length) {
      throw CspV2ProtocolException.malformed("utf8 bounds invalid");
    }
    try {
      CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT);
      ByteBuffer bb = ByteBuffer.wrap(buf, off, len);
      CharBuffer cb = dec.decode(bb);
      // ensure exact consumption: decoder will consume all if not malformed
      // also verify no trailing bytes: bb should have no remaining
      if (bb.hasRemaining()) {
        throw CspV2ProtocolException.malformed("utf8 trailing bytes");
      }
      return cb.toString();
    } catch (CharacterCodingException e) {
      throw CspV2ProtocolException.malformed("utf8 malformed", e);
    }
  }

  static byte[] encodeUtf8Strict(String s) {
    Objects.requireNonNull(s, "string must not be null");
    try {
      java.nio.charset.CharsetEncoder enc = StandardCharsets.UTF_8.newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT);
      java.nio.ByteBuffer bb = enc.encode(java.nio.CharBuffer.wrap(s));
      byte[] out = new byte[bb.remaining()];
      bb.get(out);
      return out;
    } catch (java.nio.charset.CharacterCodingException e) {
      throw new IllegalArgumentException("invalid UTF-8 string");
    }
  }

  // ECM hash (same as ClusterUdpCodec.ecmHash)
  static int ecmHash(byte[] body, int offset, int length) {
    if (body == null) throw new IllegalArgumentException("body null");
    if (offset < 0 || length < 0 || offset + length > body.length) {
      throw new IllegalArgumentException("invalid offset/length");
    }
    int h = 0;
    for (int i = offset; i < offset + length; i++) {
      h = 31 * h + (body[i] & 0xFF);
    }
    return h;
  }

  // Local checksum equivalent to DcwValidator.checksumDcw
  public static boolean checksumDcw(byte[] cw) {
    if (cw == null || cw.length != 16) return false;
    for (int i = 0; i < 16; i += 4) {
      int expected = ((cw[i] & 0xFF) + (cw[i + 1] & 0xFF) + (cw[i + 2] & 0xFF)) & 0xFF;
      if ((cw[i + 3] & 0xFF) != expected) return false;
    }
    return true;
  }
}
