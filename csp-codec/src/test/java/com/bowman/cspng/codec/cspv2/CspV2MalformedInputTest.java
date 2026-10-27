package com.bowman.cspng.codec.cspv2;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CspV2MalformedInputTest {

  private static final long MAX = CspV2Constants.MAX_MAX_PAYLOAD;

  private static byte[] frameFor(CspV2Payload p, long stream) {
    return CspV2Codec.encodeFrame(p, stream);
  }

  private static CspV2Header headerWith(long payloadLen, int type, long stream) {
    // helper to craft header bytes directly
    byte[] h = new byte[20];
    CspV2Codec.writeInt32BE(h,0,CspV2Constants.MAGIC);
    h[4]=1; h[5]=(byte)type;
    CspV2Codec.writeU16BE(h,6,0);
    CspV2Codec.writeU32BE(h,8,payloadLen);
    CspV2Codec.writeInt64BE(h,12,stream);
    return CspV2Codec.decodeHeader(h, MAX);
  }

  // -------------------------------------------------------------------------
  // Header malformed
  // -------------------------------------------------------------------------

  @Test
  void magicInvalidIsDistinguishable() {
    byte[] h = new byte[20];
    CspV2Codec.writeInt32BE(h,0, 0xDEADBEEF);
    h[4]=1; h[5]=0x01;
    CspV2Codec.writeU16BE(h,6,0);
    CspV2Codec.writeU32BE(h,8,0);
    CspV2Codec.writeInt64BE(h,12,0);
    CspV2ProtocolException ex = assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeHeader(h, MAX));
    assertTrue(ex.isInvalidMagic());
    assertEquals(CspV2ProtocolException.Category.INVALID_MAGIC, ex.category());
  }

  @Test
  void versionInvalid() {
    byte[] h = new byte[20];
    CspV2Codec.writeInt32BE(h,0,CspV2Constants.MAGIC);
    h[4]=2; h[5]=0x01;
    CspV2Codec.writeU16BE(h,6,0);
    CspV2Codec.writeU32BE(h,8,0);
    CspV2Codec.writeInt64BE(h,12,0);
    var ex = assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeHeader(h, MAX));
    assertEquals(CspV2ErrorCode.UNSUPPORTED_VERSION, ex.errorCode());
  }

  @Test
  void flagsNonZero() {
    byte[] h = new byte[20];
    CspV2Codec.writeInt32BE(h,0,CspV2Constants.MAGIC);
    h[4]=1; h[5]=0x01;
    CspV2Codec.writeU16BE(h,6,1);
    CspV2Codec.writeU32BE(h,8,22);
    CspV2Codec.writeInt64BE(h,12,0);
    var ex = assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeHeader(h, MAX));
    assertEquals(CspV2ErrorCode.UNSUPPORTED_FLAGS, ex.errorCode());
  }

  @Test
  void typeReservedIsUnsupported() {
    for (int t : new int[]{0x20,0x21,0x30}) {
      byte[] h = new byte[20];
      CspV2Codec.writeInt32BE(h,0,CspV2Constants.MAGIC);
      h[4]=1; h[5]=(byte)t;
      CspV2Codec.writeU16BE(h,6,0);
      CspV2Codec.writeU32BE(h,8,0);
      CspV2Codec.writeInt64BE(h,12,0);
      var ex = assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeHeader(h, MAX));
      assertEquals(CspV2ErrorCode.UNSUPPORTED_TYPE, ex.errorCode());
    }
  }

  @Test
  void typeUnknownIsUnsupported() {
    byte[] h = new byte[20];
    CspV2Codec.writeInt32BE(h,0,CspV2Constants.MAGIC);
    h[4]=1; h[5]=(byte)0x99;
    CspV2Codec.writeU16BE(h,6,0);
    CspV2Codec.writeU32BE(h,8,0);
    CspV2Codec.writeInt64BE(h,12,0);
    var ex = assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeHeader(h, MAX));
    assertEquals(CspV2ErrorCode.UNSUPPORTED_TYPE, ex.errorCode());
  }

  @Test
  void payloadLengthOversizeU32() {
    byte[] h = new byte[20];
    CspV2Codec.writeInt32BE(h,0,CspV2Constants.MAGIC);
    h[4]=1; h[5]=0x01;
    CspV2Codec.writeU16BE(h,6,0);
    CspV2Codec.writeU32BE(h,8, MAX+1);
    CspV2Codec.writeInt64BE(h,12,0);
    var ex = assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeHeader(h, MAX));
    assertEquals(CspV2ErrorCode.FRAME_TOO_LARGE, ex.errorCode());
  }

  @Test
  void streamSignBitSet() {
    byte[] h = new byte[20];
    CspV2Codec.writeInt32BE(h,0,CspV2Constants.MAGIC);
    h[4]=1; h[5]=0x01;
    CspV2Codec.writeU16BE(h,6,0);
    CspV2Codec.writeU32BE(h,8,0);
    // streamId = 0x8000000000000000 = Long.MIN_VALUE negative
    CspV2Codec.writeInt64BE(h,12, Long.MIN_VALUE);
    var ex = assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeHeader(h, MAX));
    assertEquals(CspV2ErrorCode.STREAM_ID_INVALID, ex.errorCode());
  }

  @Test
  void streamSemanticsHelloRequiresZero() {
    // HELLO with stream 1 should be invalid
    byte[] h = new byte[20];
    CspV2Codec.writeInt32BE(h,0,CspV2Constants.MAGIC);
    h[4]=1; h[5]=0x01;
    CspV2Codec.writeU16BE(h,6,0);
    CspV2Codec.writeU32BE(h,8,0);
    CspV2Codec.writeInt64BE(h,12,1L);
    var ex = assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeHeader(h, MAX));
    assertEquals(CspV2ErrorCode.STREAM_ID_INVALID, ex.errorCode());
  }

  @Test
  void streamSemanticsEcmRequiresPositive() {
    byte[] h = new byte[20];
    CspV2Codec.writeInt32BE(h,0,CspV2Constants.MAGIC);
    h[4]=1; h[5]=0x10;
    CspV2Codec.writeU16BE(h,6,0);
    CspV2Codec.writeU32BE(h,8,0);
    CspV2Codec.writeInt64BE(h,12,0L);
    var ex = assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeHeader(h, MAX));
    assertEquals(CspV2ErrorCode.STREAM_ID_INVALID, ex.errorCode());
  }

  @Test
  void truncationAndTrailing() {
    var hb = new CspV2Payload.Heartbeat();
    byte[] frame = CspV2Codec.encodeFrame(hb, 0L);
    // truncated header
    byte[] shortFrame = Arrays.copyOf(frame, 10);
    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(shortFrame, MAX));
    // truncated payload
    var hello = new CspV2Payload.Hello("node-a",1L,0L,4096L,1L);
    byte[] full = CspV2Codec.encodeFrame(hello, 0L);
    byte[] truncated = Arrays.copyOf(full, full.length-1);
    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(truncated, MAX));
    // trailing byte
    byte[] trailing = Arrays.copyOf(full, full.length+1);
    trailing[trailing.length-1]=0x00;
    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(trailing, MAX));
  }

  // -------------------------------------------------------------------------
  // Payload malformed
  // -------------------------------------------------------------------------

  @Test
  void helloMalformedNodeIdUtf8() {
    // construct hello payload with invalid UTF-8 in nodeId (0xFF 0xFF)
    byte[] payload = new byte[22+2];
    CspV2Codec.writeU16BE(payload,0,2);
    payload[2]=(byte)0xFF; payload[3]=(byte)0xFF;
    CspV2Codec.writeInt64BE(payload,4,1L);
    CspV2Codec.writeU32BE(payload,12,0L);
    CspV2Codec.writeU32BE(payload,16,4096L);
    CspV2Codec.writeU32BE(payload,18,1L);
    // header
    byte[] frame = craftFrame(CspV2MessageType.HELLO.code(), 0L, payload);
    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(frame, MAX));
  }

  @Test
  void helloMalformedNodeIdRegex() {
    // nodeId "Invalid" uppercase fails regex
    String nodeId="Invalid";
    byte[] nid = nodeId.getBytes(StandardCharsets.UTF_8);
    byte[] payload = new byte[22+nid.length];
    CspV2Codec.writeU16BE(payload,0,nid.length);
    System.arraycopy(nid,0,payload,2,nid.length);
    CspV2Codec.writeInt64BE(payload,2+nid.length,1L);
    CspV2Codec.writeU32BE(payload,10+nid.length,0L);
    CspV2Codec.writeU32BE(payload,14+nid.length,4096L);
    CspV2Codec.writeU32BE(payload,18+nid.length,1L);
    byte[] frame = craftFrame(CspV2MessageType.HELLO.code(),0L,payload);
    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(frame, MAX));
  }

  @Test
  void helloConnectionIdZero() {
    String nodeId="node-a";
    byte[] nid = nodeId.getBytes(StandardCharsets.UTF_8);
    byte[] payload = new byte[22+nid.length];
    CspV2Codec.writeU16BE(payload,0,nid.length);
    System.arraycopy(nid,0,payload,2,nid.length);
    CspV2Codec.writeInt64BE(payload,2+nid.length,0L); // zero
    CspV2Codec.writeU32BE(payload,10+nid.length,1L);
    CspV2Codec.writeU32BE(payload,14+nid.length,4096L);
    CspV2Codec.writeU32BE(payload,18+nid.length,1L);
    byte[] frame = craftFrame(CspV2MessageType.HELLO.code(),0L,payload);
    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(frame, MAX));
  }

  @Test
  void maxPayloadAcceptedOutOfBounds() {
    var bad = new String[]{"node-a"};
    for (long v : new long[]{4095L, 16777217L}) {
      byte[] nid = "node-a".getBytes(StandardCharsets.UTF_8);
      byte[] payload = new byte[22+nid.length];
      CspV2Codec.writeU16BE(payload,0,nid.length);
      System.arraycopy(nid,0,payload,2,nid.length);
      CspV2Codec.writeInt64BE(payload,2+nid.length,1L);
      CspV2Codec.writeU32BE(payload,10+nid.length,1L);
      CspV2Codec.writeU32BE(payload,14+nid.length,v);
      CspV2Codec.writeU32BE(payload,18+nid.length,1L);
      byte[] frame = craftFrame(CspV2MessageType.HELLO.code(),0L,payload);
      assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(frame, MAX), "value "+v);
    }
  }

  @Test
  void ecmHashMismatch() {
    byte[] ecm = new byte[]{(byte)0x80,0x00,0x05,0x01,0x02,0x03,0x04,0x05};
    int correct = CspV2Codec.ecmHash(ecm,3, ecm.length-3);
    int wrong = correct ^ 0xFFFFFFFF;
    byte[] profile = "p".getBytes(StandardCharsets.UTF_8);
    var payload = new CspV2Payload.EcmRequest(0x80,1,1,1, wrong, "p", ecm, 1000L);
    // encode should reject mismatch, but decode path: craft bytes manually with wrong hash
    byte[] payloadBytes = new byte[19+profile.length+ecm.length];
    int off=0;
    payloadBytes[off++]=(byte)0x80;
    CspV2Codec.writeU16BE(payloadBytes,off,1); off+=2;
    CspV2Codec.writeU16BE(payloadBytes,off,1); off+=2;
    CspV2Codec.writeU16BE(payloadBytes,off,1); off+=2;
    CspV2Codec.writeInt32BE(payloadBytes,off, wrong); off+=4;
    CspV2Codec.writeU16BE(payloadBytes,off, profile.length); off+=2;
    System.arraycopy(profile,0,payloadBytes,off, profile.length); off+=profile.length;
    CspV2Codec.writeU16BE(payloadBytes,off, ecm.length); off+=2;
    System.arraycopy(ecm,0,payloadBytes,off, ecm.length); off+=ecm.length;
    CspV2Codec.writeU32BE(payloadBytes,off, 1000L);
    byte[] frame = craftFrame(CspV2MessageType.ECM_REQUEST.code(),1L,payloadBytes);
    var ex = assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(frame, MAX));
    assertEquals(CspV2ErrorCode.MALFORMED_PAYLOAD, ex.errorCode());
  }

  @Test
  void ecmLenBounds() {
    byte[] profile = "p".getBytes(StandardCharsets.UTF_8);
    for (int len : new int[]{2,513}) {
      byte[] payload = new byte[19+profile.length+ (len<3?len:3)]; // just to trigger length check via header? Instead craft header payload len
      // We'll craft invalid payload length via manual frame
      // For len=2, we create payload with ecmLen=2
      byte[] p = new byte[11+2+profile.length+2+len+4];
      // simplified: directly test encode reject
    }
    // encode should reject ecm <3 via constructor's clone check? Constructor throws IllegalArgumentException
    byte[] small = new byte[2];
    int h = 0;
    assertThrows(Exception.class, () -> new CspV2Payload.EcmRequest(0x80,1,1,1,h,"p", small, 1000L));
    byte[] big = new byte[513];
    big[0]=(byte)0x80; big[1]=0; big[2]=5;
    int h2 = CspV2Codec.ecmHash(big,3,big.length-3);
    assertThrows(Exception.class, () -> new CspV2Payload.EcmRequest(0x80,1,1,1,h2,"p", big, 1000L));
  }

  @Test
  void dcwCwChecksumInvalid() {
    byte[] ecm = new byte[]{(byte)0x80,0x00,0x03,0x01,0x02,0x03};
    int hash = CspV2Codec.ecmHash(ecm,3, ecm.length-3);
    byte[] badCw = new byte[]{0x01,0x02,0x03,0x06,0x04,0x05,0x06,0x0F, 0x01,0x02,0x03,0x06,0x0A,0x0B,0x0C,0x21}; // first group: 01+02+03=06 correct, second:04+05+06=0x0F correct? 4+5+6=15=0x0F -> we craft second group wrong: use FF instead of 0F -> invalid
    byte[] invalid = new byte[]{0x11,0x22,0x33,0x66,0x44,0x55,0x66,(byte)0xFF,0x01,0x02,0x03,0x06,0x0A,0x0B,0x0C,(byte)0xFF}; // last group wrong
    // craft payload manually with invalid checksum and try decode
    byte[] payload = new byte[16+16+0];
    int off=0;
    payload[off++]=(byte)0x80;
    CspV2Codec.writeU16BE(payload,off,1); off+=2;
    CspV2Codec.writeU16BE(payload,off,1); off+=2;
    CspV2Codec.writeU16BE(payload,off,1); off+=2;
    CspV2Codec.writeInt32BE(payload,off,hash); off+=4;
    payload[off++]=(byte)0x80;
    payload[off++]=(byte)0;
    payload[off++]=(byte)16;
    System.arraycopy(invalid,0,payload,off,16); off+=16;
    CspV2Codec.writeU16BE(payload,off,0);
    byte[] frame = craftFrame(CspV2MessageType.DCW_REPLY.code(),1L,payload);
    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(frame, MAX));
  }

  @Test
  void dcwStatusInvalid() {
    byte[] ecm = new byte[]{(byte)0x80,0x00,0x03,0x01,0x02,0x03};
    int hash = CspV2Codec.ecmHash(ecm,3, ecm.length-3);
    byte[] payload = new byte[16+0+0];
    int off=0;
    payload[off++]=(byte)0x80;
    CspV2Codec.writeU16BE(payload,off,1); off+=2;
    CspV2Codec.writeU16BE(payload,off,1); off+=2;
    CspV2Codec.writeU16BE(payload,off,1); off+=2;
    CspV2Codec.writeInt32BE(payload,off,hash); off+=4;
    payload[off++]=(byte)0x80;
    payload[off++]=(byte)5; // invalid status
    payload[off++]=(byte)0;
    CspV2Codec.writeU16BE(payload,off,0);
    byte[] frame = craftFrame(CspV2MessageType.DCW_REPLY.code(),1L,payload);
    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(frame, MAX));
  }

  @Test
  void errorReasonNonCanonical() {
    byte[] reason = "arbitrary_text_not_allowed".getBytes(StandardCharsets.UTF_8);
    byte[] payload = new byte[4+reason.length];
    CspV2Codec.writeU16BE(payload,0, CspV2ErrorCode.TIMEOUT.code());
    CspV2Codec.writeU16BE(payload,2, reason.length);
    System.arraycopy(reason,0,payload,4,reason.length);
    byte[] frame = craftFrame(CspV2MessageType.ERROR.code(),0L,payload);
    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(frame, MAX));
  }

  @Test
  void errorReasonTooLong() {
    String longReason = "a".repeat(121);
    assertThrows(Exception.class, () -> new CspV2Payload.ErrorPayload(CspV2ErrorCode.TIMEOUT, longReason));
  }

  @Test
  void errorCodeUnknown() {
    byte[] payload = new byte[4];
    CspV2Codec.writeU16BE(payload,0, 0x1234);
    CspV2Codec.writeU16BE(payload,2,0);
    byte[] frame = craftFrame(CspV2MessageType.ERROR.code(),0L,payload);
    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(frame, MAX));
  }

  @Test
  void heartbeatWithPayloadFails() {
    byte[] payload = new byte[]{0x01};
    byte[] frame = craftFrame(CspV2MessageType.HEARTBEAT.code(),0L,payload);
    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(frame, MAX));
  }

  @Test
  void utf8MalformedProfile() {
    // profile bytes invalid UTF8 0xFF
    byte[] ecm = new byte[]{(byte)0x80,0x00,0x05,0x01,0x02,0x03,0x04,0x05};
    int hash = CspV2Codec.ecmHash(ecm,3, ecm.length-3);
    byte[] payload = new byte[11+2+1+2+ecm.length+4];
    int off=0;
    payload[off++]=(byte)0x80;
    CspV2Codec.writeU16BE(payload,off,1); off+=2;
    CspV2Codec.writeU16BE(payload,off,1); off+=2;
    CspV2Codec.writeU16BE(payload,off,1); off+=2;
    CspV2Codec.writeInt32BE(payload,off,hash); off+=4;
    CspV2Codec.writeU16BE(payload,off,1); off+=2;
    payload[off++]=(byte)0xFF; // invalid
    CspV2Codec.writeU16BE(payload,off, ecm.length); off+=2;
    System.arraycopy(ecm,0,payload,off, ecm.length); off+=ecm.length;
    CspV2Codec.writeU32BE(payload,off,1000L);
    byte[] frame = craftFrame(CspV2MessageType.ECM_REQUEST.code(),1L,payload);
    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(frame, MAX));
  }

  @Test
  void lengthOverflowHeaderVsActual() {
    var hello = new CspV2Payload.Hello("node-a",1L,0L,4096L,1L);
    byte[] frame = CspV2Codec.encodeFrame(hello, 0L);
    // tamper header payloadLength to be +1
    CspV2Codec.writeU32BE(frame,8, hello.toString().length()+100); // arbitrary wrong
    // But we need actual payloadLen mismatch: frame length still original, header declares larger
    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(frame, MAX));
  }

  @Test
  void helloNodeIdSurrogateAndEmptyWire() {
    // empty nodeId via wire
    byte[] payloadEmpty = new byte[22];
    CspV2Codec.writeU16BE(payloadEmpty,0,0);
    CspV2Codec.writeInt64BE(payloadEmpty,2,1L);
    CspV2Codec.writeU32BE(payloadEmpty,10,1L);
    CspV2Codec.writeU32BE(payloadEmpty,14,4096L);
    CspV2Codec.writeU32BE(payloadEmpty,18,1L);
    byte[] frameEmpty = craftFrame(CspV2MessageType.HELLO.code(),0L,payloadEmpty);
    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(frameEmpty, MAX));
    // surrogate-like invalid UTF-8 sequence for nodeId (overlong)
    byte[] payloadSurrogate = new byte[22+3];
    CspV2Codec.writeU16BE(payloadSurrogate,0,3);
    payloadSurrogate[2]=(byte)0xED; payloadSurrogate[3]=(byte)0xA0; payloadSurrogate[4]=(byte)0x80; // isolated high surrogate bytes
    CspV2Codec.writeInt64BE(payloadSurrogate,5,1L);
    CspV2Codec.writeU32BE(payloadSurrogate,13,1L);
    CspV2Codec.writeU32BE(payloadSurrogate,17,4096L);
    CspV2Codec.writeU32BE(payloadSurrogate,21,1L);
    byte[] frameSur = craftFrame(CspV2MessageType.HELLO.code(),0L,payloadSurrogate);
    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(frameSur, MAX));
    // constructor direct with surrogate should also be rejected without leaking raw string
    try {
      new CspV2Payload.Hello("bad\uD800",1L,0L,4096L,1L);
      fail("should reject surrogate");
    } catch (IllegalArgumentException ex) {
      assertFalse(ex.getMessage().contains("bad\uD800"), "message must not contain raw nodeId");
    }
  }

  @Test
  void errorReasonMismatchWireMustBeRejected() {
    // TIMEOUT with rate_limited (mismatch) should be MALFORMED_PAYLOAD
    String mismatch = "rate_limited";
    byte[] rb = mismatch.getBytes(StandardCharsets.UTF_8);
    byte[] payload = new byte[4+rb.length];
    CspV2Codec.writeU16BE(payload,0, CspV2ErrorCode.TIMEOUT.code());
    CspV2Codec.writeU16BE(payload,2, rb.length);
    System.arraycopy(rb,0,payload,4,rb.length);
    byte[] frame = craftFrame(CspV2MessageType.ERROR.code(),0L,payload);
    var ex = assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(frame, MAX));
    assertEquals(CspV2ErrorCode.MALFORMED_PAYLOAD, ex.errorCode());
    assertFalse(ex.getMessage().contains(mismatch), "exception must not contain reason");
    // correct pair must pass
    String ok = CspV2ErrorCode.TIMEOUT.canonicalReason();
    byte[] okb = ok.getBytes(StandardCharsets.UTF_8);
    byte[] payloadOk = new byte[4+okb.length];
    CspV2Codec.writeU16BE(payloadOk,0, CspV2ErrorCode.TIMEOUT.code());
    CspV2Codec.writeU16BE(payloadOk,2, okb.length);
    System.arraycopy(okb,0,payloadOk,4,okb.length);
    byte[] frameOk = craftFrame(CspV2MessageType.ERROR.code(),0L,payloadOk);
    assertDoesNotThrow(() -> CspV2Codec.decodeFrame(frameOk, MAX));
    // empty reason allowed for any code
    byte[] payloadEmpty = new byte[4];
    CspV2Codec.writeU16BE(payloadEmpty,0, CspV2ErrorCode.TIMEOUT.code());
    CspV2Codec.writeU16BE(payloadEmpty,2,0);
    byte[] frameEmpty = craftFrame(CspV2MessageType.ERROR.code(),5L,payloadEmpty);
    assertDoesNotThrow(() -> CspV2Codec.decodeFrame(frameEmpty, MAX));
  }

  @Test
  void encodeHeaderReservedOversizeStreamInvalid() {
    // reserved header via public API must be rejected
    assertThrows(IllegalArgumentException.class, () -> new CspV2Header(CspV2Constants.MAGIC,1,CspV2MessageType.STATE_SNAPSHOT,0,0,0));
    assertThrows(IllegalArgumentException.class, () -> new CspV2Header(CspV2Constants.MAGIC,1,CspV2MessageType.ACK,0,0,0));
    // oversize payloadLength > MAX via header construction must be rejected
    assertThrows(IllegalArgumentException.class, () -> new CspV2Header(CspV2Constants.MAGIC,1,CspV2MessageType.HELLO,0, CspV2Constants.MAX_MAX_PAYLOAD+1,0));
    // encodeHeader must also reject
    var hello = new CspV2Payload.Hello("node-a",1L,0L,4096L,1L);
    // stream invalid for hello (non-zero) via encodeFrame already tested, but also header
    assertThrows(IllegalArgumentException.class, () -> new CspV2Header(CspV2Constants.MAGIC,1,CspV2MessageType.HELLO,0,22,1L));
    assertThrows(IllegalArgumentException.class, () -> new CspV2Header(CspV2Constants.MAGIC,1,CspV2MessageType.ECM_REQUEST,0,0,0L));
    // hostile bytes manual still decodable as exception (not via API)
    byte[] hostileRes = new byte[20];
    CspV2Codec.writeInt32BE(hostileRes,0,CspV2Constants.MAGIC);
    hostileRes[4]=1; hostileRes[5]=(byte)0x20;
    CspV2Codec.writeU16BE(hostileRes,6,0);
    CspV2Codec.writeU32BE(hostileRes,8,0);
    CspV2Codec.writeInt64BE(hostileRes,12,0);
    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeHeader(hostileRes, MAX));
    byte[] hostileOver = new byte[20];
    CspV2Codec.writeInt32BE(hostileOver,0,CspV2Constants.MAGIC);
    hostileOver[4]=1; hostileOver[5]=0x01;
    CspV2Codec.writeU16BE(hostileOver,6,0);
    CspV2Codec.writeU32BE(hostileOver,8, CspV2Constants.MAX_MAX_PAYLOAD+1);
    CspV2Codec.writeInt64BE(hostileOver,12,0);
    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeHeader(hostileOver, MAX));
  }

  @Test
  void exceptionMessagesDoNotLeakContent() {
    // ECM hash mismatch must not leak hash values
    byte[] ecm = new byte[]{(byte)0x80,0x00,0x05,0x01,0x02,0x03,0x04,0x05};
    int correct = CspV2Codec.ecmHash(ecm,3, ecm.length-3);
    int wrong = correct ^ 1;
    byte[] prof = "p".getBytes(StandardCharsets.UTF_8);
    byte[] payload = new byte[11+2+prof.length+2+ecm.length+4];
    int off=0;
    payload[off++]=(byte)0x80;
    CspV2Codec.writeU16BE(payload,off,1); off+=2;
    CspV2Codec.writeU16BE(payload,off,1); off+=2;
    CspV2Codec.writeU16BE(payload,off,1); off+=2;
    CspV2Codec.writeInt32BE(payload,off,wrong); off+=4;
    CspV2Codec.writeU16BE(payload,off,prof.length); off+=2;
    System.arraycopy(prof,0,payload,off,prof.length); off+=prof.length;
    CspV2Codec.writeU16BE(payload,off,ecm.length); off+=2;
    System.arraycopy(ecm,0,payload,off,ecm.length); off+=ecm.length;
    CspV2Codec.writeU32BE(payload,off,1000L);
    byte[] frame = craftFrame(CspV2MessageType.ECM_REQUEST.code(),1L,payload);
    var ex = assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodeFrame(frame, MAX));
    assertFalse(ex.getMessage().contains(String.valueOf(correct)), "must not leak hash");
    assertFalse(ex.getMessage().contains(String.valueOf(wrong)));
  }

  // -------------------------------------------------------------------------
  // Fuzz determinist
  // -------------------------------------------------------------------------

  @Test
  void fuzzHeadersDoNotOomOrThrowUnchecked() {
    Random rnd = new Random(0xC5A9L);
    for (int i=0;i<5000;i++) {
      byte[] hdr = new byte[20];
      rnd.nextBytes(hdr);
      // ensure we don't generate valid magic accidentally too often? It's okay
      try {
        CspV2Codec.decodeHeader(hdr, MAX);
      } catch (CspV2ProtocolException | IllegalArgumentException e) {
        // expected
      } catch (Exception e) {
        fail("unexpected exception at fuzz header iteration " + i + ": " + e);
      }
    }
  }

  @Test
  void fuzzPayloadsDoNotOomOrThrowUnchecked() {
    Random rnd = new Random(0xDEAD1234L);
    for (int i=0;i<2000;i++) {
      int total = rnd.nextInt(100); // 0..99
      byte[] frame = new byte[20+total];
      rnd.nextBytes(frame);
      // force magic to be correct half the time to exercise payload path
      if (i %2==0) {
        CspV2Codec.writeInt32BE(frame,0,CspV2Constants.MAGIC);
        frame[4]=1;
        // pick a valid type randomly among implemented
        int[] valid = {0x01,0x02,0x10,0x11,0x31,0x40};
        frame[5]=(byte)valid[rnd.nextInt(valid.length)];
        frame[6]=0; frame[7]=0;
        CspV2Codec.writeU32BE(frame,8, total);
        // streamId positive
        long sid = rnd.nextLong() & Long.MAX_VALUE;
        // adjust for types requiring 0 vs >0 roughly
        if (frame[5]==0x01 || frame[5]==0x02 || frame[5]==0x40) sid=0L;
        else if (sid==0) sid=1;
        CspV2Codec.writeInt64BE(frame,12, sid);
      }
      try {
        CspV2Codec.decodeFrame(frame, MAX);
      } catch (CspV2ProtocolException | IllegalArgumentException e) {
        // expected
      } catch (Exception e) {
        fail("unexpected exception at fuzz payload iteration " + i + ": " + e);
      }
    }
  }

  // helper to craft frame with header+payload
  private static byte[] craftFrame(int type, long streamId, byte[] payload) {
    byte[] frame = new byte[20 + payload.length];
    CspV2Codec.writeInt32BE(frame,0,CspV2Constants.MAGIC);
    frame[4]=1;
    frame[5]=(byte)type;
    frame[6]=0; frame[7]=0;
    CspV2Codec.writeU32BE(frame,8, payload.length);
    CspV2Codec.writeInt64BE(frame,12, streamId);
    System.arraycopy(payload,0,frame,20,payload.length);
    return frame;
  }
}
