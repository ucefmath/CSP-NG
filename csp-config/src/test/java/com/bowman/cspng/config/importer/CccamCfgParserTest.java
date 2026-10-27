package com.bowman.cspng.config.importer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CccamCfgParserTest {

  @Test
  void cLineMinimal() {
    String cfg = "C: server.example.com 12000 user1 pass1";
    var result = CccamCfgParser.parse(cfg);
    assertEquals(1, result.parsedLines().size());
    assertEquals(0, result.errors().size());
    var pl = result.parsedLines().get(0);
    assertEquals(CccamCfgParser.Type.C, pl.type());
    assertEquals("server.example.com", pl.host());
    assertEquals(12000, pl.port());
    assertEquals("user1", pl.username());
    assertEquals("pass1", pl.password());
    assertNull(pl.desKey14());
  }

  @Test
  void cLineWithWantemus() {
    var r = CccamCfgParser.parse("C: host 12000 u p yes");
    assertEquals(1, r.parsedLines().size());
    assertEquals(0, r.errors().size());
    r = CccamCfgParser.parse("C: host 12000 u p no");
    assertEquals(1, r.parsedLines().size());
  }

  @Test
  void cLineWithCaidBrace() {
    var r = CccamCfgParser.parse("C: host 12000 u p yes { 0100:000000:1 }");
    assertEquals(1, r.parsedLines().size());
    assertEquals(0, r.errors().size());
  }

  @Test
  void cLineMalformedBraceWithoutWantemus() {
    var r = CccamCfgParser.parse("C: host 12000 u p { 0100:000000:1 }");
    assertEquals(0, r.parsedLines().size());
    assertEquals(1, r.errors().size());
    assertTrue(r.errors().get(0).reason().contains("PARSE_ERROR") || r.errors().get(0).reason().contains("wantemus"));
  }

  @Test
  void cLineMissingPortProducesError() {
    var r = CccamCfgParser.parse("C: myhost myuser mypass");
    assertEquals(0, r.parsedLines().size());
    assertEquals(1, r.errors().size());
    assertTrue(r.errors().get(0).reason().contains("port manquant"));
  }

  @Test
  void cLineNonNumericPortProducesError() {
    var r = CccamCfgParser.parse("C: myhost notaport user pass");
    assertEquals(0, r.parsedLines().size());
    assertEquals(1, r.errors().size());
    assertTrue(r.errors().get(0).reason().contains("port manquant"));

    var r2 = CccamCfgParser.parse("C: myhost 99999 user pass");
    assertEquals(0, r2.parsedLines().size());
    assertEquals(1, r2.errors().size());
  }

  @Test
  void parsedLineToStringMasksSecrets() {
    var r = CccamCfgParser.parse("C: host 12000 user secretpass");
    var pl = r.parsedLines().get(0);
    String s = pl.toString();
    assertFalse(s.contains("secretpass"));
    assertTrue(s.contains("password=***"));
    assertTrue(s.contains("host=host"));

    var r2 = CccamCfgParser.parse("N: h 10000 u p 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E");
    String s2 = r2.parsedLines().get(0).toString();
    assertTrue(s2.contains("desKey14=[REDACTED]"));
    assertFalse(s2.contains("01 02"));
  }

  @Test
  void nLineComplete() {
    String n = "N: nserver 10000 nuser npass 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E";
    var r = CccamCfgParser.parse(n);
    assertEquals(1, r.parsedLines().size());
    assertEquals(0, r.errors().size());
    var pl = r.parsedLines().get(0);
    assertEquals(CccamCfgParser.Type.N, pl.type());
    assertEquals("nserver", pl.host());
    assertEquals(10000, pl.port());
    assertNotNull(pl.desKey14());
    assertEquals(14, pl.desKey14().length);
    assertEquals(0x01, pl.desKey14()[0] & 0xFF);
    assertEquals(0x0E, pl.desKey14()[13] & 0xFF);
  }

  @Test
  void nLineWithExtraHopAndStealthIgnored() {
    String n = "N: h 10000 u p 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 1 2";
    var r = CccamCfgParser.parse(n);
    assertEquals(1, r.parsedLines().size());
    assertEquals(0, r.errors().size());
  }

  @Test
  void nLineMalformedDesKey() {
    String n = "N: h 10000 u p 01 02 03 04 05 06 07 08 09 0A 0B 0C ZZ 0E";
    var r = CccamCfgParser.parse(n);
    assertEquals(0, r.parsedLines().size());
    assertEquals(1, r.errors().size());
    assertTrue(r.errors().get(0).reason().contains("PARSE_ERROR"));

    String n2 = "N: h 10000 u p 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D";
    var r2 = CccamCfgParser.parse(n2);
    assertEquals(0, r2.parsedLines().size());
    assertEquals(1, r2.errors().size());
  }

  @Test
  void crlfHandling() {
    String cfg = "C: host 12000 u p yes\r\nN: h 10000 u p 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E\r\n";
    var r = CccamCfgParser.parse(cfg);
    assertEquals(2, r.parsedLines().size());
    assertEquals("p", r.parsedLines().get(0).password());
    // Ensure no \r in password
    assertFalse(r.parsedLines().get(0).password().contains("\r"));
    assertFalse(r.parsedLines().get(1).password().contains("\r"));
  }

  @Test
  void ignoredLines() {
    String cfg = "# comment\nF: user pass\nR: something\nL: 1\nG: x\nB: y\nSERVER LISTEN PORT : 12000\nDEBUG : yes\n\nC: host 12000 u p";
    var r = CccamCfgParser.parse(cfg);
    assertEquals(1, r.parsedLines().size());
    // 8 ignored: # comment, F:, R:, L:, G:, B:, SERVER..., DEBUG..., empty line
    assertTrue(r.ignoredLines().size() >= 7);
  }

  @Test
  void emptyFile() {
    var r = CccamCfgParser.parse("");
    assertEquals(0, r.parsedLines().size());
    assertEquals(0, r.errors().size());
    var r2 = CccamCfgParser.parse("   \n  \n");
    assertEquals(0, r2.parsedLines().size());
  }

  @Test
  void trailingSpaceInPasswordTrimmed() {
    // Password with trailing space before line end should be trimmed (not included)
    String cfg = "C: host 12000 user pass   ";
    var r = CccamCfgParser.parse(cfg);
    assertEquals(1, r.parsedLines().size());
    assertEquals("pass", r.parsedLines().get(0).password());
  }

  @Test
  void multipleSpacesTolerated() {
    String cfg = "C:   host    12000    user    pass   yes";
    var r = CccamCfgParser.parse(cfg);
    assertEquals(1, r.parsedLines().size());
    assertEquals("host", r.parsedLines().get(0).host());
  }

  @Test
  void caidProviderSingleDecl() {
    var r = CccamCfgParser.parse("C: host 12000 u p yes { 0100:000000 }");
    assertEquals(1, r.parsedLines().size());
    assertEquals(0, r.errors().size());
    var decls = r.parsedLines().get(0).caidProviderDecls();
    assertEquals(1, decls.size());
    assertEquals(0x0100, decls.get(0).caId());
    assertEquals(0x000000, decls.get(0).providerIdent());
  }

  @Test
  void caidProviderMultipleDecls() {
    var r = CccamCfgParser.parse("C: host 12000 u p yes { 0100:000000, 0500:030B00 , 0600:000000 }");
    assertEquals(1, r.parsedLines().size());
    assertEquals(0, r.errors().size());
    var decls = r.parsedLines().get(0).caidProviderDecls();
    assertEquals(3, decls.size());
    assertEquals(0x0100, decls.get(0).caId());
    assertEquals(0x0500, decls.get(1).caId());
    assertEquals(0x030B00, decls.get(1).providerIdent());
  }

  @Test
  void caidProviderHopIgnored() {
    var r = CccamCfgParser.parse("C: host 12000 u p yes { 0100:000000:1 }");
    assertEquals(1, r.parsedLines().size());
    assertEquals(0, r.errors().size());
    var decls = r.parsedLines().get(0).caidProviderDecls();
    assertEquals(1, decls.size());
    assertEquals(0x0100, decls.get(0).caId());
    assertEquals(0x000000, decls.get(0).providerIdent());
    // hop 2 as well
    var r2 = CccamCfgParser.parse("C: host 12000 u p yes { 0100:000000:2, 0500:123456:5 }");
    assertEquals(2, r2.parsedLines().get(0).caidProviderDecls().size());
  }

  @Test
  void caidProviderShortForm() {
    var r = CccamCfgParser.parse("C: host 12000 u p yes { 0100:0 }");
    assertEquals(1, r.parsedLines().size());
    assertEquals(0, r.errors().size());
    var decls = r.parsedLines().get(0).caidProviderDecls();
    assertEquals(0x0100, decls.get(0).caId());
    assertEquals(0x0, decls.get(0).providerIdent());
    var r2 = CccamCfgParser.parse("C: host 12000 u p yes { 1:2 }");
    assertEquals(0x1, r2.parsedLines().get(0).caidProviderDecls().get(0).caId());
    assertEquals(0x2, r2.parsedLines().get(0).caidProviderDecls().get(0).providerIdent());
  }

  @Test
  void caidProviderNoBraceEmptyList() {
    var r = CccamCfgParser.parse("C: host 12000 u p yes");
    assertEquals(1, r.parsedLines().size());
    assertTrue(r.parsedLines().get(0).caidProviderDecls().isEmpty());
    var r2 = CccamCfgParser.parse("C: host 12000 u p");
    assertTrue(r2.parsedLines().get(0).caidProviderDecls().isEmpty());
  }

  @Test
  void caidProviderInvalidHexProducesErrorWithoutSecret() {
    String secret = "SuperSecret123";
    var r = CccamCfgParser.parse("C: host 12000 u " + secret + " yes { ZZ:000000 }");
    assertEquals(0, r.parsedLines().size());
    assertEquals(1, r.errors().size());
    String reason = r.errors().get(0).reason();
    assertFalse(reason.contains(secret));
    assertTrue(reason.contains("PARSE_ERROR"));
  }

  @Test
  void caidProviderOutOfRangeProducesError() {
    var r = CccamCfgParser.parse("C: host 12000 u p yes { 10000:000000 }"); // CAID >FFFF (length 5 >4)
    assertEquals(0, r.parsedLines().size());
    assertEquals(1, r.errors().size());
    assertTrue(r.errors().get(0).reason().contains("PARSE_ERROR"));
    var r2 = CccamCfgParser.parse("C: host 12000 u p yes { 0100:1000000 }"); // prov >FFFFFF (length 7 >6)
    assertEquals(0, r2.parsedLines().size());
    assertEquals(1, r2.errors().size());
    String secret = "MySuperSecretXYZ";
    var r3 = CccamCfgParser.parse("C: host 12000 u " + secret + " yes { 0100:GGGGGG }");
    assertEquals(1, r3.errors().size());
    assertFalse(r3.errors().get(0).reason().contains(secret));
    assertTrue(r3.errors().get(0).reason().contains("PARSE_ERROR"));
  }

  @Test
  void caidProviderMalformedDeclProducesError() {
    var r = CccamCfgParser.parse("C: host 12000 u p yes { 0100 }");
    assertEquals(0, r.parsedLines().size());
    assertEquals(1, r.errors().size());
    var r2 = CccamCfgParser.parse("C: host 12000 u p yes { 0100: }");
    assertEquals(1, r2.errors().size());
  }

  @Test
  void caidProviderDefensiveCopy() {
    var r = CccamCfgParser.parse("C: host 12000 u p yes { 0100:000000 }");
    var pl = r.parsedLines().get(0);
    assertThrows(UnsupportedOperationException.class, () -> pl.caidProviderDecls().add(new CccamCfgParser.CaidProviderDecl(0x0500, 0)));
  }

  @Test
  void parseErrorRawLineRedactedForC() {
    String secret = "SuperSecretXYZ123";
    var r = CccamCfgParser.parse("C: host 12000 u " + secret + " yes { ZZ:000000 }");
    assertEquals(1, r.errors().size());
    var err = r.errors().get(0);
    assertFalse(err.rawLine().contains(secret), "rawLine must not contain password");
    assertFalse(err.rawLine().contains("ZZ"), "rawLine redacted");
    assertEquals("C: [REDACTED]", err.rawLine());
  }

  @Test
  void parseErrorRawLineRedactedForN() {
    String secret = "N_SecretPass987";
    String des = "01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E";
    // malformed DES to trigger error
    var r = CccamCfgParser.parse("N: h 10000 u " + secret + " ZZ ZZ ZZ ZZ ZZ ZZ ZZ ZZ ZZ ZZ ZZ ZZ ZZ ZZ");
    assertEquals(1, r.errors().size());
    var err = r.errors().get(0);
    assertFalse(err.rawLine().contains(secret));
    assertFalse(err.rawLine().contains("ZZ"));
    assertEquals("N: [REDACTED]", err.rawLine());
  }

  @Test
  void rejectContentAfterBrace() {
    var r = CccamCfgParser.parse("C: host 12000 u p yes { 0100:0 } junk");
    assertEquals(0, r.parsedLines().size());
    assertEquals(1, r.errors().size());
    assertTrue(r.errors().get(0).reason().contains("PARSE_ERROR"));

    var r2 = CccamCfgParser.parse("C: host 12000 u p yes { 0100:0 } { 0500:0 }");
    assertEquals(0, r2.parsedLines().size());
    assertEquals(1, r2.errors().size());
  }

  @Test
  void allowWhitespaceAfterBrace() {
    var r = CccamCfgParser.parse("C: host 12000 u p yes { 0100:0 }   ");
    assertEquals(1, r.parsedLines().size());
    assertEquals(0, r.errors().size());
  }
}
