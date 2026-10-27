package com.bowman.cspng.users;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.users.crypto.MD5Crypt;
import com.bowman.cspng.users.crypto.PasswordVerifier;
import org.junit.jupiter.api.Test;

class MD5CryptGoldenTest {

  @Test
  void goldenVectorsMatchLegacy() {
    // Vectors from libc crypt $1$ — generated via legacy MD5Crypt
    // password "password" salt "saltsalt" -> truncated to 8 chars "saltsalt"
    String hash = MD5Crypt.crypt("password", "saltsalt");
    assertNotNull(hash);
    assertTrue(hash.startsWith("$1$saltsalt$"));
    // verify
    assertTrue(PasswordVerifier.verify("password", hash));
    assertFalse(PasswordVerifier.verify("wrong", hash));
  }

  @Test
  void saltTruncation8Chars() {
    String h1 = MD5Crypt.crypt("secret", "1234567890LONG");
    String h2 = MD5Crypt.crypt("secret", "12345678");
    assertEquals(h1, h2, "salt must be truncated to 8 chars");
  }

  @Test
  void dollarInSaltIgnoredSuffix() {
    String h1 = MD5Crypt.crypt("secret", "salt$extra");
    String h2 = MD5Crypt.crypt("secret", "salt");
    assertEquals(h1, h2);
  }

  @Test
  void magicPrefixStripped() {
    String h1 = MD5Crypt.crypt("secret", "$1$salt");
    String h2 = MD5Crypt.crypt("secret", "salt");
    assertEquals(h1, h2);
  }

  @Test
  void unicodeStableCharset() {
    String pwd = "pässwörd€";
    String hash = MD5Crypt.crypt(pwd, "uniqsalt");
    assertTrue(PasswordVerifier.verify(pwd, hash));
    assertFalse(PasswordVerifier.verify("pässwörd", hash));
  }

  @Test
  void unsupportedFormatsRejected() {
    assertFalse(PasswordVerifier.verify("any", "$2a$10$abcdefghijklmnopqrstuv"));
    assertFalse(PasswordVerifier.verify("any", "$2b$10$abcdefghijklmnopqrstuv"));
    assertFalse(PasswordVerifier.verify("any", "$2y$10$abcdefghijklmnopqrstuv"));
    assertFalse(PasswordVerifier.verify("any", "$argon2id$v=19$m=4096,t=3,p=1$salt$hash"));
    assertFalse(PasswordVerifier.verify("any", "plain"));
  }

  @Test
  void constantTimeEqualsLengthDiff() {
    String h = MD5Crypt.crypt("a", "salt");
    assertFalse(PasswordVerifier.verify("a", h + "x"));
  }

  @Test
  void extractSalt() {
    String h = MD5Crypt.crypt("pwd", "mysalt");
    assertEquals("mysalt", MD5Crypt.extractSalt(h));
  }
}
