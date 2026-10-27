package com.bowman.cspng.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("EcmProviderExtractor — Dérivation de provider ident par famille CAS")
class EcmProviderExtractorTest {

  @Test
  @DisplayName("Viaccess 0x0500 : nano 0x90 longueur 3")
  void testViaccessNano90Len3() {
    byte[] ecm = new byte[]{
        (byte) 0x40, (byte) 0x02, (byte) 0x00, (byte) 0x00,
        (byte) 0x90, (byte) 0x03, (byte) 0x03, (byte) 0x28, (byte) 0x30,
        (byte) 0x80, (byte) 0x08, 0, 0, 0, 0, 0, 0, 0, 0
    };
    int prov = EcmProviderExtractor.extractProvider(0x0500, ecm);
    assertEquals(0x032830, prov);
  }

  @Test
  @DisplayName("Viaccess 0x0500 : nano 0x90 longueur 4 avec section DVB complète")
  void testViaccessNano90Len4WithDvbHeader() {
    byte[] ecm = new byte[]{
        (byte) 0x80, (byte) 0x70, (byte) 0x15, // DVB section header
        (byte) 0x40, (byte) 0x02, (byte) 0x00, (byte) 0x00,
        (byte) 0x90, (byte) 0x04, (byte) 0x03, (byte) 0x0B, (byte) 0x00, (byte) 0x08,
        (byte) 0x80, (byte) 0x08, 0, 0, 0, 0, 0, 0, 0, 0
    };
    int prov = EcmProviderExtractor.extractProvider(0x0500, ecm);
    assertEquals(0x030B00, prov);
  }

  @Test
  @DisplayName("Viaccess 0x0500 : nano 0xD2 longueur 2")
  void testViaccessNanoD2Len2() {
    byte[] ecm = new byte[]{
        (byte) 0xD2, (byte) 0x02, (byte) 0x03, (byte) 0x28,
        (byte) 0x80, (byte) 0x08, 0, 0, 0, 0, 0, 0, 0, 0
    };
    int prov = EcmProviderExtractor.extractProvider(0x0500, ecm);
    assertEquals(0x0328, prov);
  }

  @Test
  @DisplayName("Viaccess 0x0500 : scan heuristique si début non aligné")
  void testViaccessHeuristicScan() {
    byte[] ecm = new byte[]{
        0x12, 0x34, 0x56,
        (byte) 0x90, (byte) 0x03, (byte) 0x02, (byte) 0x38, (byte) 0x00,
        (byte) 0x80, (byte) 0x08, 0, 0, 0, 0, 0, 0, 0, 0
    };
    int prov = EcmProviderExtractor.extractProvider(0x0500, ecm);
    assertEquals(0x023800, prov);
  }

  @Test
  @DisplayName("Seca 0x0100 : ident 16-bit d'en-tête (corps amputé)")
  void testSecaAmputatedBody() {
    byte[] ecm = new byte[]{
        (byte) 0x00, (byte) 0x68,
        0x11, 0x22, 0x33, 0x44
    };
    int prov = EcmProviderExtractor.extractProvider(0x0100, ecm);
    assertEquals(0x0068, prov);
  }

  @Test
  @DisplayName("Seca 0x0100 : ident 16-bit avec section DVB complète")
  void testSecaWithDvbHeader() {
    byte[] ecm = new byte[]{
        (byte) 0x80, (byte) 0x70, (byte) 0x10,
        (byte) 0x00, (byte) 0x33,
        0x11, 0x22, 0x33, 0x44
    };
    int prov = EcmProviderExtractor.extractProvider(0x0100, ecm);
    assertEquals(0x0033, prov);
  }

  @Test
  @DisplayName("Cryptoworks 0x0D00 : nano 0x83 et nano 0xDF")
  void testCryptoworks() {
    byte[] ecm83 = new byte[]{
        (byte) 0x83, (byte) 0x02, (byte) 0x00, (byte) 0x04,
        0x11, 0x22
    };
    assertEquals(0x0004, EcmProviderExtractor.extractProvider(0x0D00, ecm83));

    byte[] ecmDf = new byte[]{
        (byte) 0xDF, (byte) 0x01, (byte) 0x08,
        0x11, 0x22
    };
    assertEquals(0x08, EcmProviderExtractor.extractProvider(0x0D02, ecmDf));
  }

  @Test
  @DisplayName("0 réel (famille sans provider) != inconnu (famille encodante illisible)")
  void zeroIsNotUnknownWhenFamilyDoesNotEncode() {
    byte[] ecm = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
    var nagra = EcmProviderExtractor.extractContext(0x1810, ecm);
    assertEquals(EcmProviderExtractor.ProviderContext.Status.NOT_IN_FAMILY, nagra.status());
    assertEquals(0, nagra.policyProvider());
    assertEquals(0, nagra.routingProvider());
    var unreadable = EcmProviderExtractor.extractContext(0x0500, new byte[]{0x01});
    assertEquals(EcmProviderExtractor.ProviderContext.Status.UNKNOWN, unreadable.status());
    assertEquals(-1, unreadable.policyProvider());
    assertEquals(0, unreadable.routingProvider());
    var knownZeroFamily = EcmProviderExtractor.extractContext(0x0100, new byte[]{0x00, 0x68});
    assertEquals(EcmProviderExtractor.ProviderContext.Status.KNOWN, knownZeroFamily.status());
    assertEquals(0x0068, knownZeroFamily.policyProvider());
  }

  @Test
  @DisplayName("Familles sans provider dans l'ECM (Nagra, NDS, Conax) -> retourne 0")
  void testOtherFamiliesReturnZero() {
    byte[] ecm = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
    assertEquals(0, EcmProviderExtractor.extractProvider(0x1810, ecm)); // Nagra
    assertEquals(0, EcmProviderExtractor.extractProvider(0x09CD, ecm)); // NDS
    assertEquals(0, EcmProviderExtractor.extractProvider(0x0B00, ecm)); // Conax
    assertEquals(0, EcmProviderExtractor.extractProvider(0x0604, ecm)); // Irdeto
  }

  @Test
  @DisplayName("Données limites (null, vide, trop court) -> robuste, retourne 0 sans exception")
  void testCornerCases() {
    assertEquals(0, EcmProviderExtractor.extractProvider(0x0500, null));
    assertEquals(0, EcmProviderExtractor.extractProvider(0x0500, new byte[0]));
    assertEquals(0, EcmProviderExtractor.extractProvider(0x0500, new byte[]{0x01}));
    assertEquals(0, EcmProviderExtractor.extractProvider(0x0100, null));
    assertEquals(0, EcmProviderExtractor.extractProvider(0x0100, new byte[0]));
  }
}
