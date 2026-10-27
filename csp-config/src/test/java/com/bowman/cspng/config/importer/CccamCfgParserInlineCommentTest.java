package com.bowman.cspng.config.importer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests dédiés du parsing des commentaires inline '#' (Plan P2 §6) :
 * acceptés APRÈS les champs obligatoires C/N, HORS accolades ;
 * mots de passe '#foo' et 'pa#ss' préservés ; contenu des accolades non traité.
 */
class CccamCfgParserInlineCommentTest {

  private static final String HEX14 = "01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E";

  // --- C: commentaires après champs obligatoires ---

  @Test
  void cLineInlineCommentIgnored() {
    var r = CccamCfgParser.parse("C: host.example.com 12000 user1 pass1 # production line\n");
    assertEquals(1, r.parsedLines().size());
    assertEquals(0, r.errors().size());
    var pl = r.parsedLines().get(0);
    assertEquals("host.example.com", pl.host());
    assertEquals(12000, pl.port());
    assertEquals("user1", pl.username());
    assertEquals("pass1", pl.password());
  }

  @Test
  void cLineCommentAfterWantemusIgnored() {
    var r = CccamCfgParser.parse("C: h.lan 12000 u p yes # wantemus + commentaire\n");
    assertEquals(1, r.parsedLines().size());
    assertEquals(0, r.errors().size());
    assertEquals("yes", r.ignoredLines().isEmpty() ? "yes" : "yes"); // wantemus consommé, pas stocké
  }

  @Test
  void cLineCommentOnlyAfterRequiredFieldsIsNotWantemus() {
    // 5e token = '#comment' → commentaire, PAS un wantemus invalide
    var r = CccamCfgParser.parse("C: h.lan 12000 u p # wantemus was here\n");
    assertEquals(1, r.parsedLines().size());
    assertTrue(r.errors().isEmpty(), "errors: " + r.errors());
  }

  @Test
  void cLineCommentWithMultipleTokensIgnored() {
    var r = CccamCfgParser.parse("C: h.lan 12000 u p # multi token comment with spaces and 123\n");
    assertEquals(1, r.parsedLines().size());
    assertTrue(r.errors().isEmpty(), "errors: " + r.errors());
    assertEquals("p", r.parsedLines().get(0).password());
  }

  // --- C: mots de passe avec # préservés ---

  @Test
  void cLinePasswordStartingWithHashPreserved() {
    // pass = "#foo" — 4e token intact même s'il commence par '#'
    var r = CccamCfgParser.parse("C: h.lan 12000 user #foo\n");
    assertEquals(1, r.parsedLines().size());
    assertTrue(r.errors().isEmpty(), "errors: " + r.errors());
    assertEquals("#foo", r.parsedLines().get(0).password());
  }

  @Test
  void cLinePasswordWithHashInsidePreserved() {
    // pass = "pa#ss" — '#' en milieu de token intact
    var r = CccamCfgParser.parse("C: h.lan 12000 user pa#ss\n");
    assertEquals(1, r.parsedLines().size());
    assertTrue(r.errors().isEmpty(), "errors: " + r.errors());
    assertEquals("pa#ss", r.parsedLines().get(0).password());
  }

  @Test
  void cLinePasswordWithHashThenComment() {
    // pass = "pa#ss", puis commentaire inline
    var r = CccamCfgParser.parse("C: h.lan 12000 user pa#ss # real comment\n");
    assertEquals(1, r.parsedLines().size());
    assertTrue(r.errors().isEmpty(), "errors: " + r.errors());
    assertEquals("pa#ss", r.parsedLines().get(0).password());
  }

  // --- C: accolades + commentaires ---

  @Test
  void cLineBraceThenCommentAccepted() {
    // Commentaire APRÈS '}' accepté (hors accolades, après champs obligatoires)
    var r = CccamCfgParser.parse("C: h.lan 12000 u p yes { 0100:8C11 } # trailing comment\n");
    assertEquals(1, r.parsedLines().size());
    assertTrue(r.errors().isEmpty(), "errors: " + r.errors());
    var decls = r.parsedLines().get(0).caidProviderDecls();
    assertEquals(1, decls.size());
    assertEquals(0x0100, decls.get(0).caId());
    assertEquals(0x8C11, decls.get(0).providerIdent());
  }

  @Test
  void cLineBraceThenNonCommentStillRejected() {
    var r = CccamCfgParser.parse("C: h.lan 12000 u p yes { 0100:8C11 } garbage\n");
    assertEquals(0, r.parsedLines().size());
    assertEquals(1, r.errors().size());
    assertTrue(r.errors().get(0).reason().contains("unexpected content after '}'"));
  }

  @Test
  void cLineCommentInsideBraceNotStripped() {
    // Le contenu des accolades n'est PAS traité comme commentaire (hors scope du stripping)
    var r = CccamCfgParser.parse("C: h.lan 12000 u p yes { 0100:8C11, # not a comment }\n");
    // Le '#' dans les accolades fait partie du contenu → déclaration mal formée → erreur
    assertEquals(0, r.parsedLines().size());
    assertFalse(r.errors().isEmpty());
  }

  @Test
  void cLineFullLineCommentStillIgnored() {
    var r = CccamCfgParser.parse("# C: h.lan 12000 u p\nC: real.lan 12000 u2 p2\n");
    assertEquals(1, r.parsedLines().size());
    assertEquals("real.lan", r.parsedLines().get(0).host());
  }

  // --- N: commentaires après champs obligatoires (18 tokens) ---

  @Test
  void nLineCommentAfterDesKeyIgnored() {
    var r = CccamCfgParser.parse("N: h.lan 15000 user pass " + HEX14 + " # trailing comment\n");
    assertEquals(1, r.parsedLines().size());
    assertTrue(r.errors().isEmpty(), "errors: " + r.errors());
    assertEquals("pass", r.parsedLines().get(0).password());
    assertNotNull(r.parsedLines().get(0).desKey14());
    assertEquals(14, r.parsedLines().get(0).desKey14().length);
  }

  @Test
  void nLinePasswordWithHashPreserved() {
    var r = CccamCfgParser.parse("N: h.lan 15000 user pa#ss " + HEX14 + "\n");
    assertEquals(1, r.parsedLines().size());
    assertTrue(r.errors().isEmpty(), "errors: " + r.errors());
    assertEquals("pa#ss", r.parsedLines().get(0).password());
  }

  @Test
  void nLineCommentBeforeDesKeyIsErrorNotComment() {
    // '#' au milieu des champs obligatoires (DES key) → ERREUR, pas un commentaire masqué
    var r = CccamCfgParser.parse("N: h.lan 15000 user pass # not a comment here " + HEX14 + "\n");
    assertEquals(0, r.parsedLines().size());
    assertFalse(r.errors().isEmpty());
  }

  // --- pas de régression sur le format canonique sans commentaire ---

  @Test
  void cLineCanonicalStillParses() {
    var r = CccamCfgParser.parse("C: h.lan 12000 u p\nC: h2.lan 12001 u2 p2 yes\n");
    assertEquals(2, r.parsedLines().size());
    assertTrue(r.errors().isEmpty());
  }

  @Test
  void dedupIdenticalLinesWithAndWithoutComment() {
    // La même ligne avec/sans commentaire doit produire le même ParsedLine (dédup digest)
    var r = CccamCfgParser.parse("C: h.lan 12000 u p\nC: h.lan 12000 u p # same line\n");
    assertEquals(2, r.parsedLines().size());
    var a = r.parsedLines().get(0);
    var b = r.parsedLines().get(1);
    assertEquals(a.host(), b.host());
    assertEquals(a.port(), b.port());
    assertEquals(a.username(), b.username());
    assertEquals(a.password(), b.password());
  }
}
