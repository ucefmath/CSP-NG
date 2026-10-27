package com.bowman.cspng.codec.cccam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires pour {@link CcFrameCodec}.
 */
@DisplayName("CcFrameCodec — tests unitaires")
class CcFrameCodecTest {

  @Test
  @DisplayName("encode/decodeHeader : round-trip")
  void encodeDecodeRoundTrip() {
    byte[] payload = "Hello World".getBytes();
    byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CW_ECM, payload);

    assertEquals(4 + payload.length, frame.length, "Frame length = 4 + payload");

    int[] header = CcFrameCodec.decodeHeader(frame);
    assertEquals(0, header[0], "Flag should be 0");
    assertEquals(CcConstants.MSG_CW_ECM, header[1], "Command should match");
    assertEquals(payload.length, header[2], "Payload length should match");
  }

  @Test
  @DisplayName("encode : payload nul")
  void encodeNullPayload() {
    byte[] frame = CcFrameCodec.encode(CcConstants.MSG_KEEPALIVE, null);
    assertEquals(4, frame.length, "Frame length = 4");
    int[] header = CcFrameCodec.decodeHeader(frame);
    assertEquals(CcConstants.MSG_KEEPALIVE, header[1]);
    assertEquals(0, header[2]);
  }

  @Test
  @DisplayName("encode : payload vide")
  void encodeEmptyPayload() {
    byte[] frame = CcFrameCodec.encode(CcConstants.MSG_KEEPALIVE, new byte[0]);
    assertEquals(4, frame.length, "Frame length = 4");
    int[] header = CcFrameCodec.decodeHeader(frame);
    assertEquals(0, header[2], "Payload length should be 0");
  }

  @Test
  @DisplayName("encode/decodeHeader : payload 256 octets")
  void encodeDecode256Payload() {
    byte[] payload = new byte[256];
    new SecureRandom().nextBytes(payload);
    byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CMD_05, payload);

    int[] header = CcFrameCodec.decodeHeader(frame);
    assertEquals(CcConstants.MSG_CMD_05, header[1]);
    assertEquals(256, header[2]);

    // Extraire le payload
    byte[] extracted = new byte[header[2]];
    System.arraycopy(frame, 4, extracted, 0, header[2]);
    assertArrayEquals(payload, extracted, "Payload round-trip");
  }

  @Test
  @DisplayName("encode/decodeHeader : payload 1024 octets (CC_MAXMSGSIZE)")
  void encodeDecodeMaxPayload() {
    byte[] payload = new byte[1024];
    byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CARD_REMOVED, payload);
    int[] header = CcFrameCodec.decodeHeader(frame);
    assertEquals(1024, header[2]);
  }

  @Test
  @DisplayName("decodeHeader : rejette en-tête trop court")
  void decodeHeaderRejectsShortInput() {
    assertThrows(IllegalArgumentException.class, () ->
        CcFrameCodec.decodeHeader(new byte[2]));
  }

  @Test
  @DisplayName("cmdName : toutes les commandes connues")
  void cmdNameAllKnown() {
    assertEquals("CLI_DATA", CcFrameCodec.cmdName(0x00));
    assertEquals("CW_ECM", CcFrameCodec.cmdName(0x01));
    assertEquals("KEEPALIVE", CcFrameCodec.cmdName(0x06));
    assertEquals("NEW_CARD", CcFrameCodec.cmdName(0x07));
    assertEquals("SRV_DATA", CcFrameCodec.cmdName(0x08));
    assertEquals("CMD_0B", CcFrameCodec.cmdName(0x0B));
    assertEquals("CMD_0C", CcFrameCodec.cmdName(0x0C));
    assertEquals("CW_NOK1", CcFrameCodec.cmdName(0xFE));
    assertEquals("CW_NOK2", CcFrameCodec.cmdName(0xFF));
    assertTrue(CcFrameCodec.cmdName(0x99).contains("UNKNOWN"), "Unknown cmd should show UNKNOWN");
  }

  @Test
  @DisplayName("isKnownCommand : vérification")
  void isKnownCommand() {
    assertTrue(CcFrameCodec.isKnownCommand(CcConstants.MSG_CLI_DATA));
    assertTrue(CcFrameCodec.isKnownCommand(CcConstants.MSG_CW_ECM));
    assertTrue(CcFrameCodec.isKnownCommand(CcConstants.MSG_KEEPALIVE));
    assertTrue(CcFrameCodec.isKnownCommand(CcConstants.MSG_CW_NOK1));
    assertTrue(CcFrameCodec.isKnownCommand(CcConstants.MSG_CW_NOK2));
    assertFalse(CcFrameCodec.isKnownCommand(0x99));
  }

  @Test
  @DisplayName("encode avec flag non nul")
  void encodeWithFlag() {
    byte[] frame = CcFrameCodec.encode(0x42, CcConstants.MSG_CW_ECM, new byte[]{0x01, 0x02});
    int[] header = CcFrameCodec.decodeHeader(frame);
    assertEquals(0x42, header[0], "Flag should be preserved");
    assertEquals(CcConstants.MSG_CW_ECM, header[1]);
    assertEquals(2, header[2]);
  }
}
