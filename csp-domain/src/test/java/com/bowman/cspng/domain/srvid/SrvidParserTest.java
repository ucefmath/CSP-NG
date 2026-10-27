package com.bowman.cspng.domain.srvid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SrvidParserTest {

  @Test
  @DisplayName("Parse format canonique oscam.srvid (simple et multi-caid)")
  void parseCanonicalFormat() {
    String content = """
        # Commentaire d'en-tête
        ; Autre commentaire
        0B00@000000:1131|Orange Polska Platforma Canal+|Canal+ 360|TV|General
        0600@000000,09C4@000000:1154|Total TV|Blue Hustler SBB|TV|Erotic
        1801:1136|Platforma Canal+|Canal+ Extra 1|TV|General
        """;

    Map<ServiceKey, ServiceInfo> map = OscamSrvidParser.parse(content);
    assertEquals(4, map.size());

    ServiceInfo s1 = map.get(new ServiceKey(0x0B00, 0x1131));
    assertNotNull(s1);
    assertEquals("Canal+ 360", s1.name());
    assertEquals("Orange Polska Platforma Canal+", s1.provider());
    assertEquals("TV", s1.type());
    assertEquals("General", s1.description());

    ServiceInfo s2a = map.get(new ServiceKey(0x0600, 0x1154));
    assertNotNull(s2a);
    assertEquals("Blue Hustler SBB", s2a.name());
    assertEquals("Total TV", s2a.provider());

    ServiceInfo s2b = map.get(new ServiceKey(0x09C4, 0x1154));
    assertNotNull(s2b);
    assertEquals("Blue Hustler SBB", s2b.name());

    ServiceInfo s3 = map.get(new ServiceKey(0x1801, 0x1136));
    assertNotNull(s3);
    assertEquals("Canal+ Extra 1", s3.name());
  }

  @Test
  @DisplayName("Parse ancien format oscam.srvid (CAID:PROVID:SID:nom)")
  void parseAncientFormat() {
    String content = """
        0100:000068:1131:Canal+ 360
        0500:032830:1132:Canal+ Sport
        """;

    Map<ServiceKey, ServiceInfo> map = OscamSrvidParser.parse(content);
    assertEquals(2, map.size());

    ServiceInfo s1 = map.get(new ServiceKey(0x0100, 0x1131));
    assertNotNull(s1);
    assertEquals("Canal+ 360", s1.name());
    assertNull(s1.provider());

    ServiceInfo s2 = map.get(new ServiceKey(0x0500, 0x1132));
    assertNotNull(s2);
    assertEquals("Canal+ Sport", s2.name());
  }

  @Test
  @DisplayName("Parse format srvid2 (SID:CAID@PROVID|name|type|desc|provider)")
  void parseSrvid2Format() {
    String content = """
        1131:0B00@000000,0100@000068|Canal+ 360|TV|General|Orange Polska
        """;

    Map<ServiceKey, ServiceInfo> map = OscamSrvidParser.parse(content);
    assertEquals(2, map.size());

    ServiceInfo s1 = map.get(new ServiceKey(0x0B00, 0x1131));
    assertNotNull(s1);
    assertEquals("Canal+ 360", s1.name());
    assertEquals("Orange Polska", s1.provider());
    assertEquals("TV", s1.type());
    assertEquals("General", s1.description());

    ServiceInfo s2 = map.get(new ServiceKey(0x0100, 0x1131));
    assertNotNull(s2);
    assertEquals("Canal+ 360", s2.name());
    assertEquals("Orange Polska", s2.provider());
  }

  @Test
  @DisplayName("Parse provid et résolution avec fallback catch-all")
  void parseProvidAndResolve() {
    String providContent = """
        # Table providers
        0100:000000|Mediaguard CAid||
        0100:000068|Cyfra+ (13E)||
        0500:032830|CanalSat (19.2E)|19.2E|FR
        """;

    Map<ProviderKey, ProviderInfo> provMap = OscamProvidParser.parse(providContent);
    assertEquals(3, provMap.size());

    String srvidContent = """
        0100@000068:1131|Cyfra+|Canal+ HD|TV|
        """;
    Map<ServiceKey, ServiceInfo> srvMap = OscamSrvidParser.parse(srvidContent);

    ServiceResolver resolver = new DefaultServiceResolver(srvMap, provMap);
    assertEquals(1, resolver.serviceCount());
    assertEquals(3, resolver.providerCount());

    // Résolution chaîne
    assertEquals("Canal+ HD", resolver.resolveChannelName(0x0100, 0x1131));
    assertNull(resolver.resolveChannelName(0x0100, 0x9999));

    // Résolution provider exact
    assertEquals("Cyfra+ (13E)", resolver.resolveProviderName(0x0100, 0x000068));
    assertEquals("CanalSat (19.2E)", resolver.resolveProviderName(0x0500, 0x032830));
    ProviderInfo pInfo = resolver.resolveProvider(0x0500, 0x032830);
    assertNotNull(pInfo);
    assertEquals("19.2E", pInfo.satellite());
    assertEquals("FR", pInfo.language());

    // Résolution provider fallback 0x000000 catch-all
    assertEquals("Mediaguard CAid", resolver.resolveProviderName(0x0100, 0x000011));

    // Inconnu complet
    assertNull(resolver.resolveProviderName(0x1801, 0x000001));
  }

  @Test
  @DisplayName("Tolérance aux lignes vides, commentaires et malformées")
  void robustAgainstMalformedLines() {
    String content = """
        # Comment
        // Comment
        
        malformed line without separator
        INVALID:HEX:STRING:CHANNEL
        :
        |
        0100:1131
        0100:1131|
        """;

    Map<ServiceKey, ServiceInfo> map = OscamSrvidParser.parse(content);
    // Aucune exception levée, seules les lignes valides (ou aucune) sont extraites
    assertNotNull(map);
  }

  @Test
  @DisplayName("R-f : Rejet des hex trop longs pour caid, sid et provid")
  void rejectOversizedHexLengths() {
    // CAID > 4 chars
    Map<ServiceKey, ServiceInfo> map1 = OscamSrvidParser.parse("01000@000000:1131|Prov|Name|TV|Desc\n");
    assertTrue(map1.isEmpty());

    // SID > 4 chars
    Map<ServiceKey, ServiceInfo> map2 = OscamSrvidParser.parse("0100@000000:11310|Prov|Name|TV|Desc\n");
    assertTrue(map2.isEmpty());

    // PROVID > 6 chars
    Map<ServiceKey, ServiceInfo> map3 = OscamSrvidParser.parse("0100@0000001:1131|Prov|Name|TV|Desc\n");
    assertTrue(map3.isEmpty());

    // Provid parser : caid > 4 chars ou provid > 6 chars
    Map<ProviderKey, ProviderInfo> pMap1 = OscamProvidParser.parse("01000:000068|Name||\n");
    assertTrue(pMap1.isEmpty());

    Map<ProviderKey, ProviderInfo> pMap2 = OscamProvidParser.parse("0100:0000068|Name||\n");
    assertTrue(pMap2.isEmpty());
  }
}
