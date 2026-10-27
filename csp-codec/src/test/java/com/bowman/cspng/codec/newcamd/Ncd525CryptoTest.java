package com.bowman.cspng.codec.newcamd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Ncd525CryptoTest — Dérivation de clé explicite dialecte OSCam NCD525")
class Ncd525CryptoTest {

  @Test
  @DisplayName("loginKeyNcd525 : vecteur exact issu de nc_des_login_key_get (module-newcamd-des.c l.490)")
  void ncd525LoginKeyVector() {
    byte[] keymod = new byte[14];
    byte[] cfg = new byte[14];
    for (int i = 0; i < 14; i++) {
      keymod[i] = (byte) (0x10 + i);
      cfg[i] = (byte) (i + 1);
    }
    byte[] loginKey16 = NewcamdCrypto.loginKeyNcd525(keymod, cfg, 14);

    StringBuilder sb = new StringBuilder();
    for (byte b : loginKey16) sb.append(String.format("%02x", b & 0xFF));
    // Vecteur exact mesuré par harnais C compilé contre module-newcamd-des.c (passage 1)
    assertEquals("2654981884462100224ca8389c523100", sb.toString());
  }
}
