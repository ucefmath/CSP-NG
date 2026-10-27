package com.bowman.cspng.codec.cccam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires exhaustifs pour {@link CcCrypt}.
 *
 * <p>Vérifie :
 * <ul>
 *   <li>Key schedule (initCrypt) — état initial correct</li>
 *   <li>PRGA avec feedback (crypt)</li>
 *   <li>Round-trip chiffrement/déchiffrement</li>
 *   <li>La variante rc4Crypt (sans XOR state)</li>
 *   <li>xor (obfuscation du seed)</li>
 *   <li>Deux blocs indépendants ne se perturbent pas</li>
 *   <li>Propriété d'auto-inverse (crypt avec mode opposé = inverse)</li>
 * </ul>
 */
@DisplayName("CcCrypt — tests unitaires")
class CcCryptTest {

  // ============================================================
  // XOR (cc_xor)
  // ============================================================

  @Test
  @DisplayName("cc_xor : vecteur connu (seed tout à zéro)")
  void xorAllZeros() {
    byte[] buf = new byte[16]; // tout à zéro
    CcCrypt.xor(buf);

    // octets 0-5 : XOR "CCcam" avec 0 = "CCcam"
    assertEquals('C', buf[0], "buf[0] should be 'C'");
    assertEquals('C', buf[1], "buf[1] should be 'C'");
    assertEquals('c', buf[2], "buf[2] should be 'c'");
    assertEquals('a', buf[3], "buf[3] should be 'a'");
    assertEquals('m', buf[4], "buf[4] should be 'm'");
    assertEquals(0, buf[5], "buf[5] should be 0 (last XOR)");

    // octets 8-15 : i * buf[i] = 0 (car buf[0..7] sont à 0)
    for (int i = 0; i < 8; i++) {
      assertEquals(0, buf[8 + i], "buf[" + (8 + i) + "] should be 0");
    }
  }

  @Test
  @DisplayName("cc_xor : vecteur construit à la main")
  void xorManualVector() {
    // buf = [0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0,0,0,0,0,0,0,0]
    byte[] buf = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0, 0, 0, 0, 0, 0, 0, 0};
    CcCrypt.xor(buf);

    // Traçons manuellement l'algorithme C :
    // i=0: buf[8]=0*0x01=0x00, buf[0]^='C' => 0x01^0x43=0x42
    // i=1: buf[9]=1*buf[1]=1*0x02=0x02, buf[1]^='C' => 0x02^0x43=0x41
    // i=2: buf[10]=2*buf[2]=2*0x03=0x06, buf[2]^='c' => 0x03^0x63=0x60
    // i=3: buf[11]=3*buf[3]=3*0x04=0x0C, buf[3]^='a' => 0x04^0x61=0x65
    // i=4: buf[12]=4*buf[4]=4*0x05=0x14, buf[4]^='m' => 0x05^0x6D=0x68
    // i=5: buf[13]=5*buf[5]=5*0x06=0x1E, buf[5]^='\0'=0x06^0x00=0x06
    // i=6: buf[14]=6*buf[6]=6*0x07=0x2A (pas de XOR)
    // i=7: buf[15]=7*buf[7]=7*0x08=0x38 (pas de XOR)

    assertEquals((byte) 0x42, buf[0], "buf[0] after XOR 'C'");
    assertEquals((byte) 0x41, buf[1], "buf[1] after XOR 'C'");
    assertEquals((byte) 0x60, buf[2], "buf[2] after XOR 'c'");
    assertEquals((byte) 0x65, buf[3], "buf[3] after XOR 'a'");
    assertEquals((byte) 0x68, buf[4], "buf[4] after XOR 'm'");
    assertEquals((byte) 0x06, buf[5], "buf[5] after XOR '\\0' (unchanged)");
    assertEquals((byte) 0x07, buf[6], "buf[6] unchanged");
    assertEquals((byte) 0x08, buf[7], "buf[7] unchanged");

    // buf[8+i] = i * buf[i] AVANT que buf[i] ne soit XORé dans la même itération
    assertEquals((byte) 0x00, buf[8], "buf[8] = 0*buf[0] = 0*0x01 = 0x00");
    assertEquals((byte) 0x02, buf[9], "buf[9] = 1*buf[1] = 1*0x02 = 0x02");
    assertEquals((byte) 0x06, buf[10], "buf[10] = 2*buf[2] = 2*0x03 = 0x06");
    assertEquals((byte) 0x0C, buf[11], "buf[11] = 3*buf[3] = 3*0x04 = 0x0C");
    assertEquals((byte) 0x14, buf[12], "buf[12] = 4*buf[4] = 4*0x05 = 0x14");
    assertEquals((byte) 0x1E, buf[13], "buf[13] = 5*buf[5] = 5*0x06 = 0x1E");
    assertEquals((byte) 0x2A, buf[14], "buf[14] = 6*buf[6] = 6*0x07 = 0x2A");
    assertEquals((byte) 0x38, buf[15], "buf[15] = 7*buf[7] = 7*0x08 = 0x38");
  }

  // ============================================================
  // initCrypt + crypt
  // ============================================================

  @Test
  @DisplayName("initCrypt : état initial correct (state = premier octet de la clé)")
  void initCryptStateIsFirstKeyByte() {
    CcCrypt block = new CcCrypt();
    byte[] key = {(byte) 0xAB, (byte) 0xCD, (byte) 0xEF};
    block.initCrypt(key, 3);

    assertEquals((byte) 0xAB, block.state, "state should be first byte of key");
    assertEquals(0, block.counter, "counter should be 0");
    assertEquals(0, block.sum, "sum should be 0");
  }

  @Test
  @DisplayName("crypt : round-trip chiffrement/déchiffrement")
  void cryptRoundTrip() {
    CcCrypt encrypt = new CcCrypt();
    CcCrypt decrypt = new CcCrypt();

    byte[] key = {0x01, 0x02, 0x03, 0x04, 0x05};
    encrypt.initCrypt(key, 5);
    decrypt.initCrypt(key, 5);

    byte[] plaintext = "Hello, World! This is a test message for CCcam crypto.".getBytes();
    byte[] ciphertext = plaintext.clone();
    encrypt.crypt(ciphertext, ciphertext.length, CcConstants.ENCRYPT);

    // Vérifier que le chiffré est différent du clair
    assertFalse(arraysEqual(plaintext, ciphertext), "Ciphertext should differ from plaintext");

    // Déchiffrer
    decrypt.crypt(ciphertext, ciphertext.length, CcConstants.DECRYPT);

    assertArrayEquals(plaintext, ciphertext, "Decrypted should equal original plaintext");
  }

  @Test
  @DisplayName("crypt : round-trip avec message court (1 octet)")
  void cryptRoundTripOneByte() {
    CcCrypt encrypt = new CcCrypt();
    CcCrypt decrypt = new CcCrypt();
    byte[] key = {0x42};
    encrypt.initCrypt(key, 1);
    decrypt.initCrypt(key, 1);

    byte[] data = {0x7F};
    byte[] orig = data.clone();
    encrypt.crypt(data, 1, CcConstants.ENCRYPT);
    assertNotEquals(orig[0], data[0], "Should be encrypted");
    decrypt.crypt(data, 1, CcConstants.DECRYPT);
    assertArrayEquals(orig, data, "Decrypted should equal original");
  }

  @Test
  @DisplayName("crypt : round-trip avec buffer long (1024 octets)")
  void cryptRoundTripLongBuffer() {
    CcCrypt encrypt = new CcCrypt();
    CcCrypt decrypt = new CcCrypt();
    byte[] key = "testkey123".getBytes();
    encrypt.initCrypt(key, key.length);
    decrypt.initCrypt(key, key.length);

    byte[] plaintext = new byte[1024];
    for (int i = 0; i < 1024; i++) plaintext[i] = (byte) (i & 0xFF);
    byte[] ciphertext = plaintext.clone();
    encrypt.crypt(ciphertext, ciphertext.length, CcConstants.ENCRYPT);
    decrypt.crypt(ciphertext, ciphertext.length, CcConstants.DECRYPT);
    assertArrayEquals(plaintext, ciphertext, "Round-trip 1024 bytes");
  }

  @Test
  @DisplayName("crypt : chiffrement incrémental (l'état se maintient entre appels)")
  void cryptIncremental() {
    CcCrypt encrypt = new CcCrypt();
    CcCrypt decrypt = new CcCrypt();
    byte[] key = "incrementalkey".getBytes();
    encrypt.initCrypt(key, key.length);
    decrypt.initCrypt(key, key.length);

    byte[] plaintext = "Hello World! Test incremental encryption.".getBytes();

    // Chiffrer en deux parties
    byte[] ct1 = Arrays.copyOfRange(plaintext, 0, 6);
    byte[] ct2 = Arrays.copyOfRange(plaintext, 6, plaintext.length);
    encrypt.crypt(ct1, ct1.length, CcConstants.ENCRYPT);
    encrypt.crypt(ct2, ct2.length, CcConstants.ENCRYPT);

    // Déchiffrer en deux parties
    decrypt.crypt(ct1, ct1.length, CcConstants.DECRYPT);
    decrypt.crypt(ct2, ct2.length, CcConstants.DECRYPT);

    // Recombiner
    byte[] result = new byte[plaintext.length];
    System.arraycopy(ct1, 0, result, 0, ct1.length);
    System.arraycopy(ct2, 0, result, ct1.length, ct2.length);

    assertArrayEquals(plaintext, result, "Incremental round-trip");
  }

  @Test
  @DisplayName("crypt : comparaison chiffrement en un bloc vs incremental")
  void cryptOneShotVsIncremental() {
    byte[] key = "testkey123".getBytes();
    byte[] plaintext = "This is a test of one-shot vs incremental encryption.".getBytes();

    // Chiffrement en un seul appel
    CcCrypt e1 = new CcCrypt();
    e1.initCrypt(key, key.length);
    byte[] ct1 = plaintext.clone();
    e1.crypt(ct1, ct1.length, CcConstants.ENCRYPT);

    // Chiffrement incremental
    CcCrypt e2 = new CcCrypt();
    e2.initCrypt(key, key.length);
    byte[] ct2 = plaintext.clone();
    e2.crypt(ct2, 10, CcConstants.ENCRYPT);
    byte[] tail = Arrays.copyOfRange(ct2, 10, ct2.length);
    e2.crypt(tail, tail.length, CcConstants.ENCRYPT);
    System.arraycopy(tail, 0, ct2, 10, tail.length);

    // Même résultat ?
    assertArrayEquals(ct1, ct2, "One-shot and incremental encryption should produce the same output");
  }

  // ============================================================
  // rc4Crypt
  // ============================================================

  @Test
  @DisplayName("rc4Crypt : round-trip")
  void rc4CryptRoundTrip() {
    CcCrypt encrypt = new CcCrypt();
    CcCrypt decrypt = new CcCrypt();
    byte[] key = {0x10, 0x20, 0x30};
    encrypt.initCrypt(key, 3);
    decrypt.initCrypt(key, 3);

    byte[] data = "RC4 test message!".getBytes();
    byte[] orig = data.clone();
    encrypt.rc4Crypt(data, data.length, CcConstants.ENCRYPT);
    decrypt.rc4Crypt(data, data.length, CcConstants.DECRYPT);
    assertArrayEquals(orig, data, "RC4 round-trip");
  }

  @Test
  @DisplayName("rc4Crypt vs crypt : résultat différent (preuve que XOR state existe dans crypt)")
  void rc4CryptDiffersFromCrypt() {
    CcCrypt b1 = new CcCrypt();
    CcCrypt b2 = new CcCrypt();
    byte[] key = {(byte) 0x55, (byte) 0xAA};
    b1.initCrypt(key, 2);
    b2.initCrypt(key, 2);

    byte[] d1 = "test".getBytes();
    byte[] d2 = d1.clone();
    b1.crypt(d1, d1.length, CcConstants.ENCRYPT);
    b2.rc4Crypt(d2, d2.length, CcConstants.ENCRYPT);

    assertFalse(arraysEqual(d1, d2), "rc4Crypt and crypt should produce different outputs");
  }

  // ============================================================
  // Indépendance des blocs
  // ============================================================

  @Test
  @DisplayName("Deux blocs initCrypt avec la même clé produisent les mêmes sorties")
  void twoBlocksSameKeyProduceSameOutput() {
    CcCrypt b1 = new CcCrypt();
    CcCrypt b2 = new CcCrypt();
    byte[] key = {0x01, 0x02, 0x03};
    b1.initCrypt(key, 3);
    b2.initCrypt(key, 3);

    byte[] d1 = "Hello World".getBytes();
    byte[] d2 = d1.clone();
    b1.crypt(d1, d1.length, CcConstants.ENCRYPT);
    b2.crypt(d2, d2.length, CcConstants.ENCRYPT);
    assertArrayEquals(d1, d2, "Two blocks with same key should produce same output");
  }

  @Test
  @DisplayName("Deux blocs avec des clés différentes produisent des sorties différentes")
  void twoBlocksDifferentKeysProduceDifferentOutputs() {
    CcCrypt b1 = new CcCrypt();
    CcCrypt b2 = new CcCrypt();
    b1.initCrypt(new byte[]{0x01, 0x02, 0x03}, 3);
    b2.initCrypt(new byte[]{0x04, 0x05, 0x06}, 3);

    byte[] d1 = "Hello World".getBytes();
    byte[] d2 = d1.clone();
    b1.crypt(d1, d1.length, CcConstants.ENCRYPT);
    b2.crypt(d2, d2.length, CcConstants.ENCRYPT);
    assertFalse(arraysEqual(d1, d2), "Different keys should produce different outputs");
  }

  // ============================================================
  // copy() 
  // ============================================================

  @Test
  @DisplayName("copy() : la copie produit les mêmes sorties que l'original")
  void copyProducesSameOutput() {
    CcCrypt orig = new CcCrypt();
    orig.initCrypt(new byte[]{0x01, 0x02, 0x03}, 3);
    orig.crypt("AAAA".getBytes(), 4, CcConstants.ENCRYPT); // avancer un peu

    CcCrypt copy = orig.copy();

    byte[] d1 = "test".getBytes();
    byte[] d2 = d1.clone();
    orig.crypt(d1, d1.length, CcConstants.ENCRYPT);
    copy.crypt(d2, d2.length, CcConstants.ENCRYPT);
    assertArrayEquals(d1, d2, "Copy should produce same output");
  }

  // ============================================================
  // Cas limites
  // ============================================================

  @Test
  @DisplayName("crypt : données vides (len=0) — pas d'erreur, état inchangé")
  void cryptZeroLength() {
    CcCrypt block = new CcCrypt();
    block.initCrypt(new byte[]{0x01}, 1);
    byte stateBefore = block.state;
    byte counterBefore = block.counter;
    byte sumBefore = block.sum;

    block.crypt(new byte[0], 0, CcConstants.ENCRYPT);

    assertEquals(stateBefore, block.state, "State unchanged after zero-length crypt");
    assertEquals(counterBefore, block.counter, "Counter unchanged after zero-length crypt");
    assertEquals(sumBefore, block.sum, "Sum unchanged after zero-length crypt");
  }

  @Test
  @DisplayName("crypt : clé de longueur 1 (cas limite KSA)")
  void cryptKeyLengthOne() {
    CcCrypt encrypt = new CcCrypt();
    CcCrypt decrypt = new CcCrypt();
    encrypt.initCrypt(new byte[]{0x7F}, 1);
    decrypt.initCrypt(new byte[]{0x7F}, 1);

    byte[] data = "Key of length one".getBytes();
    byte[] orig = data.clone();
    encrypt.crypt(data, data.length, CcConstants.ENCRYPT);
    decrypt.crypt(data, data.length, CcConstants.DECRYPT);
    assertArrayEquals(orig, data, "Round-trip with key length 1");
  }

  // ============================================================
  // Utilitaires
  // ============================================================

  private static boolean arraysEqual(byte[] a, byte[] b) {
    if (a.length != b.length) return false;
    for (int i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }
}
