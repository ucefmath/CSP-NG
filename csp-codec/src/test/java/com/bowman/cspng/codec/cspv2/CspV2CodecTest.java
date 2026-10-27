package com.bowman.cspng.codec.cspv2;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CspV2CodecTest {

  private static final long MAX = CspV2Constants.MAX_MAX_PAYLOAD;

  private static byte[] hexToBytes(String hex) {
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(i*2, i*2+2), 16);
    return out;
  }

  // -------------------------------------------------------------------------
  // Round-trip all payloads
  // -------------------------------------------------------------------------

  @Test
  void helloRoundTrip() {
    var payload = new CspV2Payload.Hello("node-alpha", 12345L, 0x00000001L, 1048576L, 1000L);
    byte[] frame = CspV2Codec.encodeFrame(payload, 0L);
    CspV2Frame decoded = CspV2Codec.decodeFrame(frame, MAX);
    assertEquals(CspV2MessageType.HELLO, decoded.header().type());
    assertEquals(0L, decoded.header().streamId());
    var h = (CspV2Payload.Hello) decoded.payload();
    assertEquals("node-alpha", h.nodeId());
    assertEquals(12345L, h.connectionId());
  }

  @Test
  void helloAckRoundTrip() {
    var payload = new CspV2Payload.HelloAck("node-beta", 0x1122334455667788L, 0xFFFFFFFFL, 4096L, 1L);
    byte[] frame = CspV2Codec.encodeFrame(payload, 0L);
    CspV2Frame decoded = CspV2Codec.decodeFrame(frame, MAX);
    assertEquals(CspV2MessageType.HELLO_ACK, decoded.header().type());
    var ha = (CspV2Payload.HelloAck) decoded.payload();
    assertEquals("node-beta", ha.nodeId());
    assertEquals(0x1122334455667788L, ha.connectionId());
    assertEquals(0xFFFFFFFFL, ha.capabilities());
    assertEquals(4096L, ha.maxPayloadAccepted());
  }

  @Test
  void ecmRequestRoundTripMultipleProfiles() {
    byte[] ecm = new byte[]{(byte)0x80, 0x00, 0x05, 0x01,0x02,0x03,0x04,0x05};
    int hash = CspV2Codec.ecmHash(ecm,3, ecm.length-3);
    var payload = new CspV2Payload.EcmRequest(0x81, 0xFFFF, 0xBEEF, 0x1234, hash, "p", ecm, 50L);
    byte[] frame = CspV2Codec.encodeFrame(payload, 3L);
    CspV2Frame dec = CspV2Codec.decodeFrame(frame, MAX);
    var er = (CspV2Payload.EcmRequest) dec.payload();
    assertEquals(0x81, er.commandTag());
    assertEquals(50L, er.maxWaitMs());
    assertArrayEquals(ecm, er.ecm());
  }

  @Test
  void ecmRequestRoundTripMaxSizes() {
    // profile 64 bytes, ecm 512 bytes
    String profile = "a".repeat(64);
    byte[] ecm = new byte[512];
    ecm[0]=(byte)0x80; ecm[1]=0x01; ecm[2]=0x00;
    Arrays.fill(ecm,3, ecm.length, (byte)0x42);
    int hash = CspV2Codec.ecmHash(ecm,3, ecm.length-3);
    var payload = new CspV2Payload.EcmRequest(0x80, 1,2,3, hash, profile, ecm, 30000L);
    byte[] frame = CspV2Codec.encodeFrame(payload, 5L);
    CspV2Frame dec = CspV2Codec.decodeFrame(frame, MAX);
    var er = (CspV2Payload.EcmRequest) dec.payload();
    assertEquals(64, er.profile().getBytes(StandardCharsets.UTF_8).length);
    assertEquals(512, er.ecm().length);
  }

  @Test
  void dcwReplyOkRoundTrip() {
    byte[] ecm = new byte[]{(byte)0x80,0x00,0x0A,0x11,0x22,0x33,0x44,0x55,0x66,0x77,(byte)0x88,(byte)0x99,(byte)0xAA};
    int hash = CspV2Codec.ecmHash(ecm,3, ecm.length-3);
    byte[] cw = new byte[]{0x11,0x22,0x33,0x66,0x44,0x55,0x66,(byte)0xFF,0x01,0x02,0x03,0x06,0x0A,0x0B,0x0C,0x21};
    var payload = new CspV2Payload.DcwReply(0x80,0x1234,0x0001,0x0B00, hash, 0x80, CspV2Payload.DcwStatus.OK, cw, "connector-42");
    byte[] frame = CspV2Codec.encodeFrame(payload, 7L);
    CspV2Frame dec = CspV2Codec.decodeFrame(frame, MAX);
    var dr = (CspV2Payload.DcwReply) dec.payload();
    assertEquals(CspV2Payload.DcwStatus.OK, dr.status());
    assertArrayEquals(cw, dr.cw());
    assertEquals("connector-42", dr.connectorName());
  }

  @Test
  void dcwReplyNotFoundRoundTrip() {
    byte[] ecm = new byte[]{(byte)0x81,0x00,0x05, 0x0A,0x0B,0x0C,0x0D,0x0E};
    int hash = CspV2Codec.ecmHash(ecm,3, ecm.length-3);
    var payload = new CspV2Payload.DcwReply(0x80, 100,200,300, hash, 0x81, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "");
    byte[] frame = CspV2Codec.encodeFrame(payload, 9L);
    CspV2Frame dec = CspV2Codec.decodeFrame(frame, MAX);
    var dr = (CspV2Payload.DcwReply) dec.payload();
    assertEquals(CspV2Payload.DcwStatus.NOT_FOUND, dr.status());
    assertEquals(0, dr.cw().length);
  }

  @Test
  void dcwReplyDeniedRoundTripWithConnector() {
    byte[] ecm = new byte[]{(byte)0x80,0x01,0x02, 0x55,0x66,0x77};
    int hash = CspV2Codec.ecmHash(ecm,3, ecm.length-3);
    var payload = new CspV2Payload.DcwReply(0x80,1,2,3,hash,0x80, CspV2Payload.DcwStatus.DENIED, new byte[0], "src");
    byte[] frame = CspV2Codec.encodeFrame(payload, 11L);
    CspV2Frame dec = CspV2Codec.decodeFrame(frame, MAX);
    var dr = (CspV2Payload.DcwReply) dec.payload();
    assertEquals(CspV2Payload.DcwStatus.DENIED, dr.status());
    assertEquals("src", dr.connectorName());
  }

  @Test
  void errorRoundTripAllCodes() {
    for (CspV2ErrorCode code : CspV2ErrorCode.values()) {
      var payload = new CspV2Payload.ErrorPayload(code, code.canonicalReason());
      byte[] frame = CspV2Codec.encodeFrame(payload, 0L);
      CspV2Frame dec = CspV2Codec.decodeFrame(frame, MAX);
      var ep = (CspV2Payload.ErrorPayload) dec.payload();
      assertEquals(code, ep.code());
      assertEquals(code.canonicalReason(), ep.reason());
    }
    // empty reason also
    var empty = new CspV2Payload.ErrorPayload(CspV2ErrorCode.TIMEOUT, "");
    byte[] f2 = CspV2Codec.encodeFrame(empty, 5L);
    var dec2 = (CspV2Payload.ErrorPayload) CspV2Codec.decodeFrame(f2, MAX).payload();
    assertEquals("", dec2.reason());
  }

  @Test
  void heartbeatRoundTrip() {
    var payload = new CspV2Payload.Heartbeat();
    byte[] frame = CspV2Codec.encodeFrame(payload, 0L);
    assertEquals(20, frame.length);
    CspV2Frame dec = CspV2Codec.decodeFrame(frame, MAX);
    assertTrue(dec.payload() instanceof CspV2Payload.Heartbeat);
    assertEquals(CspV2MessageType.HEARTBEAT, dec.header().type());
  }

  @Test
  void decodeHeaderBeforeAllocationThenPayload() {
    var hello = new CspV2Payload.Hello("node-x", 999L, 0L, 4096L, 1L);
    byte[] frame = CspV2Codec.encodeFrame(hello, 0L);
    byte[] headerBytes = Arrays.copyOfRange(frame,0,20);
    byte[] payloadBytes = Arrays.copyOfRange(frame,20, frame.length);
    CspV2Header h = CspV2Codec.decodeHeader(headerBytes, MAX);
    assertEquals(CspV2MessageType.HELLO, h.type());
    CspV2Payload p = CspV2Codec.decodePayload(h, payloadBytes);
    assertTrue(p instanceof CspV2Payload.Hello);
  }

  // -------------------------------------------------------------------------
  // Defensive copies
  // -------------------------------------------------------------------------

  @Test
  void ecmDefensiveCopy() {
    byte[] ecm = new byte[]{(byte)0x80,0x00,0x05,0x01,0x02,0x03,0x04,0x05};
    int hash = CspV2Codec.ecmHash(ecm,3, ecm.length-3);
    var payload = new CspV2Payload.EcmRequest(0x80,1,1,1, hash, "prof", ecm, 1000L);
    ecm[3]=0x7F; // mutate original
    byte[] fromPayload = payload.ecm();
    assertNotEquals(0x7F, fromPayload[3] & 0xFF);
    fromPayload[4]=0x7F;
    byte[] again = payload.ecm();
    assertNotEquals(0x7F, again[4] & 0xFF);

    // decode also defensive
    byte[] frame = CspV2Codec.encodeFrame(payload, 1L);
    var decoded = (CspV2Payload.EcmRequest) CspV2Codec.decodeFrame(frame, MAX).payload();
    byte[] decEcm = decoded.ecm();
    decEcm[0]=0x00;
    assertNotEquals(0x00, decoded.ecm()[0] & 0xFF);
  }

  @Test
  void cwDefensiveCopy() {
    byte[] ecm = new byte[]{(byte)0x80,0x00,0x03, 0x01,0x02,0x03};
    int hash = CspV2Codec.ecmHash(ecm,3, ecm.length-3);
    byte[] cw = new byte[]{0x11,0x22,0x33,0x66,0x44,0x55,0x66,(byte)0xFF,0x01,0x02,0x03,0x06,0x0A,0x0B,0x0C,0x21};
    var payload = new CspV2Payload.DcwReply(0x80,1,1,1,hash,0x80, CspV2Payload.DcwStatus.OK, cw, "");
    cw[0]=0x00;
    assertNotEquals(0x00, payload.cw()[0] &0xFF);
    byte[] got = payload.cw();
    got[1]=0x00;
    assertNotEquals(0x00, payload.cw()[1] &0xFF);
  }

  @Test
  void encodeRejectsInvalidNodeId() {
    assertThrows(Exception.class, () -> {
      var bad = new CspV2Payload.Hello("INVALID_UPPER", 1L,0L,4096L,1L);
      CspV2Codec.encodePayload(bad);
    });
  }

  @Test
  void encodeRejectsConnectionIdZero() {
    assertThrows(Exception.class, () -> new CspV2Payload.Hello("node-a",0L,0L,4096L,1L));
  }

  @Test
  void encodeRejectsEcmHashMismatch() {
    byte[] ecm = new byte[]{(byte)0x80,0x00,0x05,0x01,0x02,0x03,0x04,0x05};
    // wrong hash
    var payload = new CspV2Payload.EcmRequest(0x80,1,1,1, 12345, "p", ecm, 1000L);
    assertThrows(Exception.class, () -> CspV2Codec.encodePayload(payload));
  }

  @Test
  void encodeRejectsCwChecksumInvalid() {
    byte[] ecm = new byte[]{(byte)0x80,0x00,0x03, 0x01,0x02,0x03};
    int hash = CspV2Codec.ecmHash(ecm,3, ecm.length-3);
    byte[] badCw = new byte[16]; // all zero not valid checksum? Actually zero sum => 0, but cw 00 00 00 00 fails? 0+0+0=0 => passes, but all zero might be considered valid? Let's craft invalid: 01 02 03 04 where 01+02+03=06 !=04
    Arrays.fill(badCw,(byte)0x01);
    badCw[3]=0x04;
    var payload = new CspV2Payload.DcwReply(0x80,1,1,1,hash,0x80, CspV2Payload.DcwStatus.OK, badCw, "");
    assertThrows(Exception.class, () -> CspV2Codec.encodePayload(payload));
  }

  @Test
  void encodeRejectsErrorReasonNotCanonical() {
    assertThrows(Exception.class, () -> new CspV2Payload.ErrorPayload(CspV2ErrorCode.TIMEOUT, "arbitrary text not allowed"));
  }

  @Test
  void streamSemanticsEncodeRejects() {
    var hello = new CspV2Payload.Hello("node-a",1L,0L,4096L,1L);
    // hello requires stream 0
    assertThrows(Exception.class, () -> CspV2Codec.encodeFrame(hello, 1L));
    var ecmB = new byte[]{(byte)0x80,0x00,0x05,0x01,0x02,0x03,0x04,0x05};
    int hash = CspV2Codec.ecmHash(ecmB,3, ecmB.length-3);
    var ecmReq = new CspV2Payload.EcmRequest(0x80,1,1,1, hash, "prof", ecmB, 100L);
    assertThrows(Exception.class, () -> CspV2Codec.encodeFrame(ecmReq, 0L)); // ECM needs >0
  }

  @Test
  void u32MaxPayloadReading() {
    // craft header with payloadLength = U32 max partially, but ensure frameTooLarge path uses long
    // simple check: readU32 returns long correctly
    byte[] header = new byte[20];
    CspV2Codec.writeInt32BE(header,0, CspV2Constants.MAGIC);
    header[4]=1; header[5]=0x01;
    header[6]=0; header[7]=0;
    CspV2Codec.writeU32BE(header,8, 0xFFFFFFFFL);
    CspV2Codec.writeInt64BE(header,12,0L);
    CspV2ProtocolException ex = assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeHeader(header, MAX));
    assertEquals(CspV2ProtocolException.Category.FRAME_TOO_LARGE, ex.category());
  }

  @Test
  void helloWithSurrogateUnpairedThrows() {
    String bad = "node-\uD800-alpha"; // isolated high surrogate
    assertThrows(IllegalArgumentException.class, () -> new CspV2Payload.Hello(bad, 1L,0L,4096L,1L));
    // direct profile surrogate
    byte[] ecm = new byte[]{(byte)0x80,0x00,0x05,0x01,0x02,0x03,0x04,0x05};
    int h = CspV2Codec.ecmHash(ecm,3, ecm.length-3);
    assertThrows(IllegalArgumentException.class, () -> new CspV2Payload.EcmRequest(0x80,1,1,1,h,"\uD800", ecm, 1000L));
    assertThrows(IllegalArgumentException.class, () -> new CspV2Payload.DcwReply(0x80,1,1,1,h,0x80, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "\uDC00"));
  }

  @Test
  void errorReasonMismatchMustBeRejected() {
    // TIMEOUT with rate_limited (canonical of other code) must be rejected
    assertThrows(IllegalArgumentException.class, () -> new CspV2Payload.ErrorPayload(CspV2ErrorCode.TIMEOUT, "rate_limited"));
    assertThrows(IllegalArgumentException.class, () -> new CspV2Payload.ErrorPayload(CspV2ErrorCode.RATE_LIMITED, "timeout"));
    // correct pairs pass
    assertDoesNotThrow(() -> new CspV2Payload.ErrorPayload(CspV2ErrorCode.TIMEOUT, "timeout"));
    assertDoesNotThrow(() -> new CspV2Payload.ErrorPayload(CspV2ErrorCode.TIMEOUT, ""));
    assertDoesNotThrow(() -> new CspV2Payload.ErrorPayload(CspV2ErrorCode.RATE_LIMITED, "rate_limited"));
  }

  @Test
  void encodeHeaderReservedAndOversizeAndStreamMustFail() {
    // reserved type
    CspV2Header reserved = null;
    try {
      reserved = new CspV2Header(CspV2Constants.MAGIC, CspV2Constants.VERSION, CspV2MessageType.STATE_SNAPSHOT, 0, 0, 0);
      fail("reserved header should be rejected");
    } catch (IllegalArgumentException ok) {}
    // oversize
    try {
      var hello = new CspV2Payload.Hello("node-a",1L,0L,4096L,1L);
      byte[] p = CspV2Codec.encodePayload(hello);
      // try to create header with payloadLength > MAX
      var hdr = new CspV2Header(CspV2Constants.MAGIC, CspV2Constants.VERSION, CspV2MessageType.HELLO,0, CspV2Constants.MAX_MAX_PAYLOAD+1,0);
      fail("oversize header should be rejected");
    } catch (IllegalArgumentException ok) {}
    // stream invalid for hello
    var hello2 = new CspV2Payload.Hello("node-a",1L,0L,4096L,1L);
    assertThrows(Exception.class, () -> CspV2Codec.encodeFrame(hello2, 1L));
    // stream invalid for ecm (0)
    byte[] ecm = new byte[]{(byte)0x80,0x00,0x05,0x01,0x02,0x03,0x04,0x05};
    int h = CspV2Codec.ecmHash(ecm,3, ecm.length-3);
    var ecmReq = new CspV2Payload.EcmRequest(0x80,1,1,1,h,"p",ecm,1000L);
    assertThrows(Exception.class, () -> CspV2Codec.encodeFrame(ecmReq, 0L));
  }

  @Test
  void errorEmptyReasonAllowedForAnyCode() {
    for (CspV2ErrorCode c : CspV2ErrorCode.values()) {
      var ep = new CspV2Payload.ErrorPayload(c, "");
      byte[] frame = CspV2Codec.encodeFrame(ep, 0L);
      var dec = (CspV2Payload.ErrorPayload) CspV2Codec.decodeFrame(frame, MAX).payload();
      assertEquals("", dec.reason());
    }
  }

  @Test
  void profileConnectorSurrogateEncodingFails() {
    byte[] ecm = new byte[]{(byte)0x80,0x00,0x05,0x01,0x02,0x03,0x04,0x05};
    int h = CspV2Codec.ecmHash(ecm,3, ecm.length-3);
    // profile with lone surrogate
    assertThrows(IllegalArgumentException.class, () -> {
      var p = new CspV2Payload.EcmRequest(0x80,1,1,1,h,"bad\uD800",ecm,1000L);
      CspV2Codec.encodePayload(p);
    });
  }
}
