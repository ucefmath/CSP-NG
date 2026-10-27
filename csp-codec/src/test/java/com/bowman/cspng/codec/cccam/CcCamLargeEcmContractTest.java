package com.bowman.cspng.codec.cccam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Levée du résidu B4 (docs/INBOUND-CAMS-PROGRESS.md) : ECM &gt; 255 sur le wire CCcam.
 *
 * <p>Contrat OSCam (module-cccam.c l.1860-1873) : {@code ecmbuf[12] = cur_er->ecmlen & 0xff}
 * — le champ [12] est TRONQUÉ à 8 bits, la longueur réelle sur le wire vient de l'en-tête
 * de FRAME ([2..3], CcFrameCodec) ; le serveur lit {@code er->ecmlen = l - 17} (l.3402)
 * et valide par {@code SCT_LEN} 12 bits. Les gros ECM sont donc transportables et
 * VALIDES entre pairs qui lisent la longueur de frame — c'est ce que ce test fige.
 *
 * <p>Limite du domaine csp-ng (inchangée, cohérente OSCam) : frame ≤ CC_MAXMSGSIZE
 * (1024) → section ≤ MAX_ECM_SIZE (1024-17 = 1007) → body amputé ≤ 1004.
 * La validation runtime du SCT_LEN côté réception est celle du test de contrat
 * CcCamOscamWireContractTest (csp-connectors).
 */
@DisplayName("CCcam — ECM > 255 : champ [12] tronqué, longueur wire exacte")
class CcCamLargeEcmContractTest {

  @Test
  @DisplayName("Section 283 octets (> 255) : requête encodable, [12] tronqué, wire 13+283")
  void largeSectionAbove255() {
    byte[] body = new byte[280];
    for (int i = 0; i < body.length; i++) body[i] = (byte) ((i * 13 + 1) & 0xFF);
    byte[] section = CcCardCodec.reconstructSection(0x80, body);

    byte[] req = CcCardCodec.buildEcmRequest(0x0100, 0, 0x0064C0DE, 0x0001, section);

    assertEquals(13 + section.length, req.length, "13 octets d'en-tête ECM + section");
    assertEquals(283 & 0xFF, req[12] & 0xFF,
        "champ [12] TRONQUÉ à 8 bits (l.1872) : 283 & 0xFF = 27");
    // La section DVB porte la longueur exacte en 12 bits : SCT_LEN = 3 + 280 = 283.
    assertEquals(283, 3 + (((section[1] & 0x0F) << 8) | (section[2] & 0xFF)));
  }

  @Test
  @DisplayName("Limite max : section = MAX_ECM_SIZE (1007) passe, au-delà rejeté")
  void maxSectionBoundary() {
    byte[] bodyMax = new byte[1004]; // 3 + 1004 = 1007 = MAX_ECM_SIZE (1024 - 17)
    byte[] sectionMax = CcCardCodec.reconstructSection(0x80, bodyMax);
    assertEquals(CcConstants.MAX_ECM_SIZE, sectionMax.length);
    byte[] req = CcCardCodec.buildEcmRequest(0x0100, 0, 1, 1, sectionMax);
    assertEquals(13 + 1007, req.length);

    byte[] bodyOver = new byte[1005]; // section 1008 > MAX_ECM_SIZE
    byte[] sectionOver = CcCardCodec.reconstructSection(0x80, bodyOver);
    assertThrows(IllegalArgumentException.class,
        () -> CcCardCodec.buildEcmRequest(0x0100, 0, 1, 1, sectionOver),
        "frame totale 4+13+1008 > CC_MAXMSGSIZE(1024) → rejet (contrat CC_MAXMSGSIZE)");
  }

  @Test
  @DisplayName("reconstructSection : flags 0x70 + longueur 12 bits, refus > 4095")
  void reconstructSectionContract() {
    byte[] body = new byte[300];
    byte[] s = CcCardCodec.reconstructSection(0x81, body);
    assertEquals(303, s.length);
    assertEquals(0x81, s[0] & 0xFF, "table_id = commandTag");
    assertEquals(0x70 | ((300 >> 8) & 0x0F), s[1] & 0xFF, "flags 0x70 + len hi (SCT_LEN masque 0x0F)");
    assertEquals(300 & 0xFF, s[2] & 0xFF);
    assertThrows(IllegalArgumentException.class,
        () -> CcCardCodec.reconstructSection(0x80, new byte[0x1000]));
  }
}
