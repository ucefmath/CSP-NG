package com.bowman.cspng.codec.radegast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RadegastConstants — tests unitaires")
class RadegastConstantsTest {

  @Test
  @DisplayName("buildReplyFrame : succès avec CW")
  void buildReplySuccess() {
    byte[] cw = new byte[16];
    for (int i = 0; i < 16; i++) cw[i] = (byte) (i + 1);

    byte[] frame = RadegastConstants.buildReplyFrame(cw);
    assertEquals(4 + 16, frame.length);
    assertEquals(RadegastConstants.CMD_ECM_KEY_RESPONSE, frame[0] & 0xFF);
    assertEquals(2 + 16, frame[1] & 0xFF); // length
    assertEquals(RadegastConstants.REPLY_STATUS_SUCCESS, frame[2] & 0xFF);
    assertEquals(16, frame[3] & 0xFF); // CW length
    for (int i = 0; i < 16; i++) {
      assertEquals(cw[i], frame[4 + i], "CW byte mismatch at " + i);
    }
  }

  @Test
  @DisplayName("buildReplyFrame : échec (cannot decode)")
  void buildReplyCannotDecode() {
    byte[] frame = RadegastConstants.buildReplyFrame(new byte[0]);
    assertEquals(4, frame.length);
    assertEquals(RadegastConstants.CMD_ECM_KEY_RESPONSE, frame[0] & 0xFF);
    assertEquals(2, frame[1] & 0xFF);
    assertEquals(RadegastConstants.REPLY_STATUS_CANNOT_DECODE, frame[2] & 0xFF);
    assertEquals(0, frame[3] & 0xFF);
  }

  @Test
  @DisplayName("buildRequestFrame : sans serviceId")
  void buildRequestNoSid() {
    byte[] customData = {0x01, 0x02, 0x03};
    byte[] frame = RadegastConstants.buildRequestFrame(
        0x1234, 0, 0, 0x80, customData);

    assertNotNull(frame);
    assertEquals(33 + 3, frame.length);
    assertEquals(RadegastConstants.CMD_ECM_KEY_ASK, frame[0] & 0xFF);
    assertEquals(33 + 3 - 2, frame[1] & 0xFF);
  }

  @Test
  @DisplayName("buildRequestFrame : avec serviceId")
  void buildRequestWithSid() {
    byte[] customData = {0x01, 0x02};
    byte[] frame = RadegastConstants.buildRequestFrame(
        0x5678, 0, 0xABCD, 0x81, customData);

    assertNotNull(frame);
    assertEquals(37 + 2, frame.length); // 37 header + 2 data
    // Check EXTRA field
    assertEquals(RadegastConstants.TAG_EXTRA, frame[28] & 0xFF);
    assertEquals(2, frame[29] & 0xFF);
    assertEquals((0xAB), frame[30] & 0xFF);
    assertEquals((0xCD), frame[31] & 0xFF);
  }

  @Test
  @DisplayName("parseReplyCw : succès")
  void parseReplySuccess() {
    byte[] frame = RadegastConstants.buildReplyFrame(
        new byte[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16});
    byte[] cw = RadegastConstants.parseReplyCw(frame);
    assertEquals(16, cw.length);
    assertEquals(1, cw[0]);
    assertEquals(16, cw[15]);
  }

  @Test
  @DisplayName("parseReplyCw : échec")
  void parseReplyFailure() {
    byte[] frame = RadegastConstants.buildReplyFrame(new byte[0]);
    byte[] cw = RadegastConstants.parseReplyCw(frame);
    assertEquals(0, cw.length);
  }

  @Test
  @DisplayName("parseRequest : requête valide")
  void parseRequestValid() {
    byte[] customData = {0x10, 0x20, 0x30, 0x40};
    byte[] frame = RadegastConstants.buildRequestFrame(
        0x0B00, 0x123456, 0, 0x80, customData);

    var req = RadegastConstants.parseRequest(frame);
    assertNotNull(req);
    assertTrue(req.isValid());
    assertEquals(0x80, req.commandTag());
    assertEquals(0x0B00, req.caId());
    assertEquals(0, req.serviceId());
    assertEquals(4, req.customData().length);
    assertEquals(0x10, req.customData()[0]);
  }

  @Test
  @DisplayName("parseRequest : round-trip request puis reply")
  void roundTripRequestReply() {
    byte[] ecm = {0x01, 0x02, 0x03, 0x04, 0x05};
    byte[] request = RadegastConstants.buildRequestFrame(
        0x0D00, 0, 0, 0x81, ecm);

    var parsed = RadegastConstants.parseRequest(request);
    assertNotNull(parsed);
    assertTrue(parsed.isValid());
    assertEquals(0x81, parsed.commandTag());
    assertEquals(5, parsed.customData().length);

    // Simuler une réponse CW
    byte[] cw = new byte[16];
    for (int i = 0; i < 16; i++) cw[i] = (byte) i;
    byte[] reply = RadegastConstants.buildReplyFrame(cw);
    byte[] extractedCw = RadegastConstants.parseReplyCw(reply);
    assertEquals(16, extractedCw.length);
    assertArrayEquals(cw, extractedCw);
  }
}
