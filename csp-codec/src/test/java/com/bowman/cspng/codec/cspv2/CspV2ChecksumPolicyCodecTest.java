package com.bowman.cspng.codec.cspv2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CspV2Codec — Négociation de capacité CAP_CHECKSUM_POLICY et frames DCW_REPLY")
class CspV2ChecksumPolicyCodecTest {

  private static final byte[] VALID_CHECKSUM_CW = new byte[]{
      0x01, 0x02, 0x03, 0x06,
      0x04, 0x05, 0x06, 0x0F,
      0x07, 0x08, 0x09, 0x18,
      0x0A, 0x0B, 0x0C, 0x21
  };

  private static final byte[] NON_CHECKSUM_CW = new byte[]{
      0x01, 0x02, 0x03, 0x00, // checksum 0x00 != 0x06
      0x04, 0x05, 0x06, 0x00,
      0x07, 0x08, 0x09, 0x00,
      0x0A, 0x0B, 0x0C, 0x00
  };

  @Test
  @DisplayName("Sans négociation (legacy peer) : CW sans checksum rejetée à l'encodage et décodage")
  void testLegacyPeerRequiresChecksum() {
    CspV2Payload.DcwReply reply = new CspV2Payload.DcwReply(
        0x80, 0x2938, 0, 0x1884, 0x12345678, 0x80,
        CspV2Payload.DcwStatus.OK, NON_CHECKSUM_CW, "c1"
    );

    // Encodage standard sans cap -> IllegalArgumentException
    assertThrows(IllegalArgumentException.class, () -> CspV2Codec.encodeFrame(reply, 1L));

    // Décodage standard sans cap -> CspV2ProtocolException
    byte[] encodedWithCap = CspV2Codec.encodeFrame(reply, 1L, true);
    byte[] headerBytes = java.util.Arrays.copyOfRange(encodedWithCap, 0, 20);
    CspV2Header header = CspV2Codec.decodeHeader(headerBytes, 65536);
    byte[] payloadBytes = java.util.Arrays.copyOfRange(encodedWithCap, 20, encodedWithCap.length);

    assertThrows(CspV2ProtocolException.class, () -> CspV2Codec.decodePayload(header, payloadBytes, false));
  }

  @Test
  @DisplayName("Avec négociation CAP_CHECKSUM_POLICY (new peer) : CW sans checksum acceptée dans DCW_REPLY")
  void testNegotiatedPeerAllowsNonChecksumReply() {
    CspV2Payload.DcwReply reply = new CspV2Payload.DcwReply(
        0x80, 0x2938, 0, 0x0500, 0x12345678, 0x80,
        CspV2Payload.DcwStatus.OK, NON_CHECKSUM_CW, "c1"
    );

    byte[] frame = CspV2Codec.encodeFrame(reply, 1L, true);
    assertNotNull(frame);

    byte[] headerBytes = java.util.Arrays.copyOfRange(frame, 0, 20);
    CspV2Header header = CspV2Codec.decodeHeader(headerBytes, 65536);
    byte[] payloadBytes = java.util.Arrays.copyOfRange(frame, 20, frame.length);
    CspV2Payload decoded = CspV2Codec.decodePayload(header, payloadBytes, true);

    assertTrue(decoded instanceof CspV2Payload.DcwReply);
    CspV2Payload.DcwReply decReply = (CspV2Payload.DcwReply) decoded;
    assertArrayEquals(NON_CHECKSUM_CW, decReply.cw());
    assertEquals("c1", decReply.connectorName());
  }

  @Test
  @DisplayName("Validation de la constante de capacité CAP_CHECKSUM_POLICY = 0x08")
  void testCapConstant() {
    assertEquals(0x08L, CspV2Constants.CAP_CHECKSUM_POLICY);
  }
}
