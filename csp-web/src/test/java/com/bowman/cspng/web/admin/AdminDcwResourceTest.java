package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.DcwPolicyConfig;
import com.bowman.cspng.config.DcwRuleConfig;
import com.bowman.cspng.config.DcwServiceMappingConfig;
import com.bowman.cspng.filters.InterProviderLinkObserver;
import com.bowman.cspng.web.admin.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AdminDcwResource — validation 400 avant DB, contrat provider/action, aucune CW reflétée")
class AdminDcwResourceTest {

  private AdminDcwResource resource;
  private FakeRepo repo;

  static class FakeRepo extends ConfigRepository {
    final List<String> calls = new ArrayList<>();
    DcwRuleConfig lastRule;
    FakeRepo() { super(null); }

    @Override public Future<DcwPolicyConfig> getDcwPolicy() { return Future.succeededFuture(DcwPolicyConfig.DEFAULT); }
    @Override public Future<List<DcwRuleConfig>> listDcwRules() { return Future.succeededFuture(List.of()); }
    @Override public Future<List<DcwServiceMappingConfig>> listDcwServiceMappings() { return Future.succeededFuture(List.of()); }

    @Override
    public Future<Void> saveDcwPolicy(boolean e, boolean a, boolean o, boolean z, String c, String r, boolean h, long v, String u) {
      calls.add("savePolicy"); return Future.succeededFuture();
    }
    @Override public Future<Long> createDcwRule(DcwRuleConfig rule, String user) { calls.add("createRule"); lastRule = rule; return Future.succeededFuture(100L); }
    @Override public Future<Void> updateDcwRule(long id, DcwRuleConfig rule, long v, String user) { calls.add("updateRule"); return Future.succeededFuture(); }
    @Override public Future<Void> deleteDcwRule(long id, String user) { calls.add("deleteRule"); return Future.succeededFuture(); }
    @Override public Future<Long> createDcwServiceMapping(DcwServiceMappingConfig m, String user) { calls.add("createMapping"); return Future.succeededFuture(200L); }
    @Override public Future<Void> updateDcwServiceMapping(long id, DcwServiceMappingConfig m, long v, String user) { calls.add("updateMapping"); return Future.succeededFuture(); }
    @Override public Future<Void> deleteDcwServiceMapping(long id, String user) { calls.add("deleteMapping"); return Future.succeededFuture(); }
  }

  @BeforeEach
  void setUp() {
    resource = new AdminDcwResource();
    repo = new FakeRepo();
    resource.configRepository = repo;
    resource.linkObserver = new InterProviderLinkObserver(50);
  }

  static CreateDcwRuleRequest rule(Integer provider, String digest, String hex, String action, String cm) {
    return new CreateDcwRuleRequest("p1", 0x1884, provider, 0x2938, null, digest, hex, action, cm, "n", true);
  }

  static String body(Response r) {
    return String.valueOf(((ErrorResponse) r.getEntity()).message());
  }

  @Test
  @DisplayName("GET /policy : défaut inherit/off")
  void getPolicy() {
    Response resp = resource.getPolicy().await().indefinitely();
    var pol = (DcwPolicyResponse) resp.getEntity();
    assertEquals("inherit", pol.defaultChecksumMode());
    assertEquals("off", pol.repairMode());
  }

  @Test
  @DisplayName("POST /rules badDcwHex -> digest ; hex jamais reflété ni conservé")
  void createRuleFromHex() {
    String hex = "010203060405060F070809180A0B0C21";
    Response resp = resource.createRule(rule(null, null, hex, "block", "inherit")).await().indefinitely();
    assertEquals(201, resp.getStatus());
    var r = (DcwRuleResponse) resp.getEntity();
    assertEquals(64, r.digestSha256().length());
    assertNull(r.provider(), "provider null = wildcard conservé");
    assertFalse(r.toString().toLowerCase().contains(hex.toLowerCase()));
  }

  @Test
  @DisplayName("Provider 0 légitime conservé (distinct de null)")
  void providerZero() {
    Response resp = resource.createRule(rule(0, "a".repeat(64), null, "block", "inherit")).await().indefinitely();
    assertEquals(201, resp.getStatus());
    assertEquals(Integer.valueOf(0), repo.lastRule.provider());
  }

  @Test
  @DisplayName("Entrées invalides -> 400 AVANT tout appel DB, sans refléter l'entrée")
  void invalidInputs400BeforeDb() {
    String hex = "0102030604050600070809180A0B0CZZ";
    List<CreateDcwRuleRequest> bads = List.of(
        rule(-1, "a".repeat(64), null, "block", "inherit"),
        rule(null, null, null, "block", "inherit"),
        rule(null, "a".repeat(64), null, "allow", "inherit"),
        rule(null, null, null, "none", "inherit"),
        rule(null, "a".repeat(64), "010203060405060F070809180A0B0C21", "block", "inherit"),
        rule(null, null, hex, "block", "inherit"),
        rule(null, "not-a-digest-0102030604050600", null, "block", "inherit"),
        rule(null, null, null, "none", "bogus"));
    for (var b : bads) {
      Response resp = resource.createRule(b).await().indefinitely();
      assertEquals(400, resp.getStatus(), b.toString());
      assertFalse(body(resp).contains("0102030604050600"), "entrée non reflétée");
    }
    assertTrue(repo.calls.isEmpty(), "aucun accès DB : " + repo.calls);
  }

  @Test
  @DisplayName("Règle checksum-only 'none' + mode scopé acceptée")
  void checksumOnlyRule() {
    Response resp = resource.createRule(rule(0x30, null, null, "none", "reject_valid")).await().indefinitely();
    assertEquals(201, resp.getStatus());
    assertEquals("none", repo.lastRule.action());
  }

  @Test
  @DisplayName("PUT /policy : champs obligatoires et modes validés")
  void policyStrict() {
    assertEquals(400, resource.updatePolicy(new UpdateDcwPolicyRequest(true, false, false, false, "inherit", null, true, 1L))
        .await().indefinitely().getStatus());
    assertEquals(400, resource.updatePolicy(new UpdateDcwPolicyRequest(true, false, false, false, "maybe", "off", true, 1L))
        .await().indefinitely().getStatus());
    assertTrue(repo.calls.isEmpty());
    assertEquals(200, resource.updatePolicy(new UpdateDcwPolicyRequest(true, false, false, false, "INHERIT", "observe", true, 1L))
        .await().indefinitely().getStatus());
  }

  @Test
  @DisplayName("Mappings : source==target / champs manquants -> 400 ; valide -> 201")
  void mappings() {
    assertEquals(400, resource.createMapping(new CreateDcwServiceMappingRequest("p", 0x1884, 0x30, 5, 5, true, true, null))
        .await().indefinitely().getStatus());
    assertEquals(400, resource.createMapping(new CreateDcwServiceMappingRequest("p", 0x1884, null, 5, 6, true, true, null))
        .await().indefinitely().getStatus());
    assertTrue(repo.calls.isEmpty());
    Response ok = resource.createMapping(new CreateDcwServiceMappingRequest("p", 0x1884, 0x30, 5, 6, true, true, 0L))
        .await().indefinitely();
    assertEquals(201, ok.getStatus());
  }

  @Test
  @DisplayName("Mapping global entre couples CAID:Provider (sourceSid=targetSid=0) accepté et restitué")
  void caidProviderGlobalMapping() {
    // Ordre des champs aligné sur DcwServiceMappingConfig :
    // profile, caid, provider, targetCaid, targetProvider, sourceSid, targetSid, approved, enabled, expiresAtMs
    Response created = resource.createMapping(
        new CreateDcwServiceMappingRequest("p", 0x1884, 0x000000, 0x1813, 0x000068, 0, 0, true, true, 0L))
        .await().indefinitely();
    assertEquals(201, created.getStatus());
    var m = (DcwServiceMappingResponse) created.getEntity();
    assertEquals(0x1813L, (long) m.targetCaid(), "CAID cible explicite conservé");
    assertEquals(0x000068L, (long) m.targetProvider(), "provider cible explicite conservé");
    assertEquals(0, m.sourceSid(), "mapping global : sourceSid = 0");
    assertEquals(0, m.targetSid(), "mapping global : targetSid = 0");

    Response updated = resource.updateMapping(200L,
        new UpdateDcwServiceMappingRequest("p", 0x1884, 0x000000, 0x1818, 0x00006C, 0, 0, true, true, 0L, 1L))
        .await().indefinitely();
    assertEquals(200, updated.getStatus());
  }

  @Test
  @DisplayName("GET /candidates et /stats sérialisables JSON (DTO, pas d'objet interne)")
  void observationsSerializable() throws Exception {
    long now = System.currentTimeMillis();
    byte[] cw = {1, 2, 3, 6, 4, 5, 6, 15, 7, 8, 9, 24, 10, 11, 12, 33};
    resource.linkObserver.observeReply("p", 0x1884, 0x30, 1, 0x80, 1, cw, now);
    resource.linkObserver.observeReply("p", 0x0500, 0x32830, 2, 0x80, 2, cw, now + 10);
    var mapper = new ObjectMapper();
    String json = mapper.writeValueAsString(resource.getCandidates().await().indefinitely().getEntity());
    assertTrue(json.contains("OBSERVATION"), json);
    assertFalse(json.contains("0102030604"), "pas de CW");
    String stats = mapper.writeValueAsString(resource.getStats().await().indefinitely().getEntity());
    assertTrue(stats.contains("\"candidateLinks\":1"), stats);
  }
}
