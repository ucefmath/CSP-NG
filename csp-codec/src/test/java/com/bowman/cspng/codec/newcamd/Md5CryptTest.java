package com.bowman.cspng.codec.newcamd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests de conformité de {@link Md5Crypt} avec les vecteurs de référence OSCam / FreeBSD md5-crypt.
 */
@DisplayName("Md5Crypt — vecteurs de référence OSCam")
class Md5CryptTest {

  @ParameterizedTest(name = "pwd=\"{0}\" -> hash=\"{1}\"")
  @CsvSource(value = {
      "password, $1$abcdefgh$G//4keteveJp0qb8z2DxG/",
      "canalpanda, $1$abcdefgh$dgR6v6DCYEuFP6SsKX3BA1",
      "test123, $1$abcdefgh$FuRpVTqE/Onxax.jDI2aR/",
      "a, $1$abcdefgh$jUYc1Xi7pkozuzWQ0Dft71",
      "'', $1$abcdefgh$M55TzYaaccxVGbptZWaxX/"
  })
  void oscamGoldenVectors(String password, String expectedHash) {
    assertEquals(expectedHash, Md5Crypt.md5Crypt(password));
    assertEquals(expectedHash, Md5Crypt.md5Crypt(password, "$1$abcdefgh$"));
  }

  @Test
  void nullPasswordTreatedAsEmpty() {
    assertEquals("$1$abcdefgh$M55TzYaaccxVGbptZWaxX/", Md5Crypt.md5Crypt(null));
  }

  @Test
  void nullSaltTreatedAsDefault() {
    assertEquals("$1$abcdefgh$G//4keteveJp0qb8z2DxG/", Md5Crypt.md5Crypt("password", null));
  }

  @Test
  void customSaltRefinement() {
    assertEquals("$1$abcdefgh$G//4keteveJp0qb8z2DxG/", Md5Crypt.md5Crypt("password", "abcdefgh"));
    assertEquals("$1$abcdefgh$G//4keteveJp0qb8z2DxG/", Md5Crypt.md5Crypt("password", "$1$abcdefgh"));
    assertEquals("$1$abcdefgh$G//4keteveJp0qb8z2DxG/", Md5Crypt.md5Crypt("password", "$1$abcdefgh$more"));
  }
}
