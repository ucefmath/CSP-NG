package com.bowman.cspng.codec.cccam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires pour {@link CcCardCodec}.
 */
@DisplayName("CcCardCodec — tests unitaires")
class CcCardCodecTest {

  // ============================================================
  // MSG_CLI_DATA
  // ============================================================

  @Test
  @DisplayName("buildCliData : taille exacte 93 octets")
  void buildCliDataSize() {
    byte[] nodeId = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
    byte[] data = CcCardCodec.buildCliData("testuser", nodeId, "2.3.2", "4000");

    assertEquals(93, data.length, "CLI_DATA must be 93 bytes");
  }

  @Test
  @DisplayName("buildCliData : username tronqué à 20 octets")
  void buildCliDataUsernameTruncated() {
    byte[] nodeId = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
    String longUser = "a".repeat(30);
    byte[] data = CcCardCodec.buildCliData(longUser, nodeId, "2.3.2", "4000");

    // Vérifier que seuls les 20 premiers octets sont remplis
    for (int i = 0; i < 20; i++) {
      assertEquals((byte) 'a', data[i], "Username byte " + i);
    }
  }

  @Test
  @DisplayName("buildCliData : node_id aux bons offsets")
  void buildCliDataNodeId() {
    byte[] nodeId = {(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD,
                     (byte) 0xEE, (byte) 0xFF, (byte) 0x11, (byte) 0x22};
    byte[] data = CcCardCodec.buildCliData("test", nodeId, "2.3.2", "4000");

    assertArrayEquals(nodeId, java.util.Arrays.copyOfRange(data, 20, 28),
        "node_id at offset 20-27");
    assertEquals(0, data[28], "wantEMU should be 0");
  }

  @Test
  @DisplayName("buildCliData : version et build")
  void buildCliDataVersionBuild() {
    byte[] nodeId = new byte[8];
    byte[] data = CcCardCodec.buildCliData("test", nodeId, "2.3.2", "4000");

    // Version "2.3.2" at offset 29
    String version = new String(data, 29, 5).trim();
    assertEquals("2.3.2", version);
    // Build "4000" at offset 61
    String build = new String(data, 61, 4).trim();
    assertEquals("4000", build);
  }

  // ============================================================
  // MSG_SRV_DATA
  // ============================================================

  @Test
  @DisplayName("parseSrvData : parsing complet")
  void parseSrvData() {
    byte[] payload = new byte[72];
    payload[0] = (byte) 0xAA; // node_id[0]
    payload[7] = (byte) 0xBB; // node_id[7]
    payload[8] = '2'; // version[0]
    byte[] verBytes = "2.3.2".getBytes();
    System.arraycopy(verBytes, 0, payload, 8, verBytes.length);
    byte[] buildBytes = "4000".getBytes();
    System.arraycopy(buildBytes, 0, payload, 40, buildBytes.length);

    CcCardCodec.SrvData srv = CcCardCodec.parseSrvData(payload);

    assertEquals((byte) 0xAA, srv.nodeId()[0]);
    assertEquals((byte) 0xBB, srv.nodeId()[7]);
    assertEquals('2', srv.peerVersion()[0]);
    assertEquals('4', srv.peerBuild()[0]);
  }

  @Test
  @DisplayName("parseSrvData : rejette payload trop court")
  void parseSrvDataRejectsShort() {
    assertThrows(IllegalArgumentException.class, () ->
        CcCardCodec.parseSrvData(new byte[32]));
  }

  // ============================================================
  // MSG_NEW_CARD
  // ============================================================

  @Test
  @DisplayName("parseNewCard : carte simple sans providers")
  void parseNewCardNoProviders() {
    // Construire un payload minimal : 21 octets + nprov=0
    byte[] payload = new byte[21];
    // card_id = 0x12345678 (BE)
    payload[0] = 0x12; payload[1] = 0x34; payload[2] = 0x56; payload[3] = 0x78;
    // remote_id = 0x87654321 (BE)
    payload[4] = (byte) 0x87; payload[5] = 0x65; payload[6] = 0x43; payload[7] = 0x21;
    // caid = 0x0D00 (BE)
    payload[8] = 0x0D; payload[9] = 0x00;
    // hop = 1
    payload[10] = 1;
    // reshare = 2
    payload[11] = 2;
    // hexserial (8 octets)
    payload[12] = 0x01; payload[13] = 0x02; payload[14] = 0x03; payload[15] = 0x04;
    payload[16] = 0x05; payload[17] = 0x06; payload[18] = 0x07; payload[19] = 0x08;
    // nprov = 0 (offset 20)
    payload[20] = 0;

    CcCardCodec.CcCard card = CcCardCodec.parseNewCard(payload);

    assertEquals(0x12345678, card.cardId);
    assertEquals(0x87654321, card.remoteId);
    assertEquals(0x0D00, card.caid);
    assertEquals(1, card.hop);
    assertEquals(2, card.reshare);
    assertEquals(0, card.providers.size());
  }

  @Test
  @DisplayName("parseNewCard : carte avec 2 providers")
  void parseNewCardWithProviders() {
    // 21 + 2*7 = 35 octets minimum
    byte[] payload = new byte[35];
    // card_id = 0x01020304
    payload[0] = 0x01; payload[1] = 0x02; payload[2] = 0x03; payload[3] = 0x04;
    // caid = 0x0500
    payload[8] = 0x05; payload[9] = 0x00;
    // hexserial
    for (int i = 0; i < 8; i++) payload[12 + i] = (byte) (i + 1);
    // nprov = 2
    payload[20] = 2;
    // provider 0 : 0x060708 | SA = 0x090A0B0C
    payload[21] = 0x06; payload[22] = 0x07; payload[23] = 0x08;
    payload[24] = 0x09; payload[25] = 0x0A; payload[26] = 0x0B; payload[27] = 0x0C;
    // provider 1 : 0x0D0E0F | SA = 0x10111213
    payload[28] = 0x0D; payload[29] = 0x0E; payload[30] = 0x0F;
    payload[31] = 0x10; payload[32] = 0x11; payload[33] = 0x12; payload[34] = 0x13;

    CcCardCodec.CcCard card = CcCardCodec.parseNewCard(payload);

    assertEquals(2, card.providers.size());
    assertEquals(0x060708, card.providers.get(0).provider());
    assertEquals(0x0D0E0F, card.providers.get(1).provider());
  }

  @Test
  @DisplayName("parseNewCard : rejette payload trop court")
  void parseNewCardRejectsShort() {
    assertThrows(IllegalArgumentException.class, () ->
        CcCardCodec.parseNewCard(new byte[10]));
  }

  @Test
  @DisplayName("CcCard.matches : filtre caid/provider")
  void ccCardMatches() {
    byte[] hexserial = new byte[8];
    CcCardCodec.CcCard card = new CcCardCodec.CcCard(1, 2, 0x0D00, 1, 2, hexserial);
    card.providers.add(new CcCardCodec.CcProvider(0x123456, new byte[4]));

    assertTrue(card.matches(0x0D00, 0x123456), "Should match exact caid and provider");
    assertFalse(card.matches(0x0D00, 0x999999), "Should NOT match wrong provider");
    assertFalse(card.matches(0x0100, 0x123456), "Should NOT match wrong caid");
  }

  // ============================================================
  // MSG_CARD_REMOVED
  // ============================================================

  @Test
  @DisplayName("parseCardRemoved : parsing correct")
  void parseCardRemoved() {
    byte[] payload = new byte[4];
    payload[0] = 0x12; payload[1] = 0x34; payload[2] = 0x56; payload[3] = 0x78;
    assertEquals(0x12345678, CcCardCodec.parseCardRemoved(payload));
  }

  @Test
  @DisplayName("parseCardRemoved : rejette payload trop court")
  void parseCardRemovedRejectsShort() {
    assertThrows(IllegalArgumentException.class, () ->
        CcCardCodec.parseCardRemoved(new byte[2]));
  }

  // ============================================================
  // Requête ECM
  // ============================================================

  @Test
  @DisplayName("buildEcmRequest + parseEcmRequest : round-trip")
  void ecmRequestRoundTrip() {
    byte[] ecm = "ECM_DATA_1234567890".getBytes();
    byte[] frame = CcCardCodec.buildEcmRequest(0x0D00, 0x123456, 0xABCDEF01, 0x1234, ecm);

    CcCardCodec.EcmRequestHeader hdr = CcCardCodec.parseEcmRequest(frame);
    assertEquals(0x0D00, hdr.caid());
    assertEquals(0x123456, hdr.provider());
    assertEquals(0xABCDEF01, hdr.cardId());
    assertEquals(0x1234, hdr.srvid());
    assertArrayEquals(ecm, hdr.ecm());
  }

  @Test
  @DisplayName("parseEcmRequest : rejette payload trop court")
  void parseEcmRequestRejectsShort() {
    assertThrows(IllegalArgumentException.class, () ->
        CcCardCodec.parseEcmRequest(new byte[10]));
  }

  // ============================================================
  // Réponse CW
  // ============================================================

  @Test
  @DisplayName("parseCwReply : extraction des 16 octets")
  void parseCwReply() {
    byte[] payload = new byte[20];
    for (int i = 0; i < 16; i++) payload[i] = (byte) (i + 1);

    byte[] cw = CcCardCodec.parseCwReply(payload);
    assertEquals(16, cw.length);
    assertEquals(1, cw[0]);
    assertEquals(16, cw[15]);
  }

  @Test
  @DisplayName("parseCwReply : rejette payload trop court")
  void parseCwReplyRejectsShort() {
    assertThrows(IllegalArgumentException.class, () ->
        CcCardCodec.parseCwReply(new byte[8]));
  }

  // ============================================================
  // Utilitaires BE
  // ============================================================

  @Test
  @DisplayName("readShortBE / writeShortBE : round-trip")
  void shortBERoundTrip() {
    byte[] buf = new byte[2];
    CcCardCodec.writeShortBE(buf, 0, 0x1234);
    assertEquals(0x1234, CcCardCodec.readShortBE(buf, 0));
  }

  @Test
  @DisplayName("readIntBE / writeIntBE : round-trip")
  void intBERoundTrip() {
    byte[] buf = new byte[4];
    CcCardCodec.writeIntBE(buf, 0, 0x12345678);
    assertEquals(0x12345678, CcCardCodec.readIntBE(buf, 0));
  }
}
