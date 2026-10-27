package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.connectors.cacheex.CacheExQuorumTracker;
import com.bowman.cspng.connectors.cacheex.CacheHitAttribution;
import com.bowman.cspng.connectors.cacheex.PeerQualityTracker;
import com.bowman.cspng.connectors.cacheex.PeerQuarantine;
import com.bowman.cspng.web.admin.dto.ReleaseQuarantineRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lots 2-5 : endpoints qualité/hits/quarantaine/quorum — contrats réels,
 * release centrale, validation des configs (400/422), jamais de zéros fictifs.
 */
@DisplayName("AdminPeerQualityResource — qualité/hits/quarantaine/quorum")
class AdminPeerQualityResourceTest {

  private AdminPeerQualityResource resource() {
    AdminPeerQualityResource res = new AdminPeerQualityResource();
    res.qualityTracker = new PeerQualityTracker();
    res.hitAttribution = new CacheHitAttribution();
    res.quarantine = new PeerQuarantine();
    res.quorumTracker = new CacheExQuorumTracker();
    res.configRepository = null;
    ConfigHolder holder = new ConfigHolder();
    holder.set(ConfigSnapshot.empty());
    res.configHolder = holder;
    res.mapper = new ObjectMapper();
    res.securityContext = null;
    return res;
  }

  @Test
  @DisplayName("quality vide sans trafic, hits vides sans service")
  void emptyWithoutTraffic() {
    var res = resource();
    var quality = (AdminPeerQualityResource.QualityResponse) res.quality()
        .await().indefinitely().getEntity();
    assertTrue(quality.peers().isEmpty(), "jamais de zéros fabriqués");
    var hits = (AdminPeerQualityResource.HitsResponse) res.hits()
        .await().indefinitely().getEntity();
    assertTrue(hits.origins().isEmpty());
  }

  @Test
  @DisplayName("quality reflète accepted/invalid, quarantine config off par défaut")
  void qualityReflectsOutcomes() {
    var res = resource();
    res.qualityTracker.recordAccepted("connA");
    res.qualityTracker.recordInvalid("connA");
    var quality = (AdminPeerQualityResource.QualityResponse) res.quality()
        .await().indefinitely().getEntity();
    assertEquals(1, quality.peers().size());
    var row = quality.peers().get(0);
    assertEquals("central", row.nodeId());
    assertEquals("connA", row.peerId());
    assertEquals(1, row.accepted());
    assertEquals(1, row.invalid());
    assertFalse(row.quarantined(), "quarantaine off par défaut");

    var q = (AdminPeerQualityResource.QuarantineResponse) res.getQuarantine()
        .await().indefinitely().getEntity();
    assertNotNull(q.config());
    assertFalse(q.config().enabled(), "opt-in : désactivée par défaut");
    assertEquals("observe", q.config().mode(), "observe par défaut");
    assertTrue(q.entries().isEmpty());
  }

  @Test
  @DisplayName("hits : unknown explicite, pas de confusion volume/hits")
  void hitsUnknownExplicit() {
    var res = resource();
    res.hitAttribution.recordServedHit(null, null);
    res.hitAttribution.recordServedHit("10.0.0.1:12000", "connA");
    var hits = (AdminPeerQualityResource.HitsResponse) res.hits()
        .await().indefinitely().getEntity();
    assertEquals(2, hits.origins().size());
    assertEquals("10.0.0.1:12000", hits.origins().get(0).origin());
    assertEquals(CacheHitAttribution.UNKNOWN, hits.origins().get(1).origin());
  }

  @Test
  @DisplayName("release : 400 sans peerId, 422 si worker-scoped, levée effective sinon")
  void releaseFlow() {
    var res = resource();
    var bad = res.releaseQuarantine(new ReleaseQuarantineRequest("  ")).await().indefinitely();
    assertEquals(400, bad.getStatus());

    var workerScope1 = res.releaseQuarantine(new ReleaseQuarantineRequest("connX", "worker-1")).await().indefinitely();
    assertEquals(422, workerScope1.getStatus());

    var workerScope2 = res.releaseQuarantine(new ReleaseQuarantineRequest("worker-1/connX")).await().indefinitely();
    assertEquals(422, workerScope2.getStatus());

    var ok = (AdminPeerQualityResource.ReleaseResponse) res.releaseQuarantine(
        new ReleaseQuarantineRequest("connX")).await().indefinitely().getEntity();
    assertFalse(ok.released(), "rien à lever (non trouvé)");
    assertNotNull(ok.note());
  }

  @Test
  @DisplayName("MF1 : échec audit BD (500) préserve l'état de quarantaine mémoire intact (shouldSkip reste true)")
  void releaseAuditFailurePreservesMemoryState() {
    var res = resource();
    var qcfg = new com.bowman.cspng.config.PeerQuarantineConfig(true, "enforce", 2, 5000, 300000L, 600000L, 256, 1L);
    assertTrue(res.quarantine.evaluate("connActive", 10, 8000, qcfg));
    assertTrue(res.quarantine.hasLiveEntry("connActive"));
    assertTrue(res.quarantine.shouldSkip("connActive", qcfg));

    // Mock ConfigRepository qui échoue lors de l'audit
    res.configRepository = new com.bowman.cspng.config.ConfigRepository(null) {
      @Override
      public io.vertx.core.Future<Void> recordQuarantineReleaseAudit(String peerId, String actor) {
        return io.vertx.core.Future.failedFuture(new RuntimeException("DB disk full"));
      }
    };

    var failResp = res.releaseQuarantine(new ReleaseQuarantineRequest("connActive")).await().indefinitely();
    assertEquals(500, failResp.getStatus());

    // Vérification essentielle : l'état mémoire n'a PAS été muté !
    assertTrue(res.quarantine.hasLiveEntry("connActive"), "le pair doit rester en quarantaine si l'audit échoue");
    assertTrue(res.quarantine.shouldSkip("connActive", qcfg), "shouldSkip doit rester true");

    // Maintenant mock avec succès d'audit
    res.configRepository = new com.bowman.cspng.config.ConfigRepository(null) {
      @Override
      public io.vertx.core.Future<Void> recordQuarantineReleaseAudit(String peerId, String actor) {
        return io.vertx.core.Future.succeededFuture();
      }
    };

    var successResp = res.releaseQuarantine(new ReleaseQuarantineRequest("connActive")).await().indefinitely();
    assertEquals(200, successResp.getStatus());
    var body = (AdminPeerQualityResource.ReleaseResponse) successResp.getEntity();
    assertTrue(body.released());
    assertFalse(res.quarantine.hasLiveEntry("connActive"), "le pair doit être libéré après succès de l'audit");
    assertFalse(res.quarantine.shouldSkip("connActive", qcfg));
  }

  @Test
  @DisplayName("quorum : off par défaut, stats vides")
  void quorumOffByDefault() {
    var res = resource();
    var q = (AdminPeerQualityResource.QuorumResponse) res.getQuorum()
        .await().indefinitely().getEntity();
    assertEquals("off", q.config().mode(), "quorum off par défaut");
    assertEquals(0, q.stats().keys());
    assertTrue(q.keys().isEmpty());
  }

  @Test
  @DisplayName("updateQuarantine : version requise, IAE => 400")
  void updateQuarantineValidation() {
    var res = resource();
    var noVersion = res.updateQuarantine(new com.bowman.cspng.web.admin.dto.UpdateQuarantineRequest(
        true, "enforce", 10, 5000, 300000L, 600000L, 256, null)).await().indefinitely();
    assertEquals(400, noVersion.getStatus());
    var badMode = res.updateQuarantine(new com.bowman.cspng.web.admin.dto.UpdateQuarantineRequest(
        true, "ban", 10, 5000, 300000L, 600000L, 256, 1L)).await().indefinitely();
    assertEquals(400, badMode.getStatus(), "mode invalide refusé avant DB");
  }

  @Test
  @DisplayName("R5 : 503 lorsque ConfigHolder n'est pas initialisé")
  void uninitializedReturns503() {
    AdminPeerQualityResource res = new AdminPeerQualityResource();
    res.configHolder = new ConfigHolder(); // not initialized
    var qResp = res.quality().await().indefinitely();
    assertEquals(503, qResp.getStatus());
    var quarResp = res.getQuarantine().await().indefinitely();
    assertEquals(503, quarResp.getStatus());
    var qmResp = res.getQuorum().await().indefinitely();
    assertEquals(503, qmResp.getStatus());
  }

  @Test
  @DisplayName("B5 : parsing des stats worker pour quality, hits, quarantine, quorum avec nodeId explicite")
  void parseWorkerStatsSections() {
    var res = resource();
    String workerJson = "{"
        + "\"peerQuality\": [{\"peerId\":\"connW\",\"accepted\":10,\"invalid\":2,\"total\":12,\"invalidRatioBps\":1666,\"windowMs\":300000,\"quarantined\":false}],"
        + "\"cacheHits\": [{\"origin\":\"10.1.2.3:12000\",\"hits\":99,\"lastHitAtMs\":1700000000000}],"
        + "\"quarantine\": [{\"peerId\":\"connBad\",\"reason\":\"ratio\",\"quarantinedAtMs\":1700000000000,\"expiresAtMs\":1700000600000,\"observedOnly\":false}],"
        + "\"quorum\": {\"keys\":4,\"observed\":20,\"quorum\":15,\"pending\":3,\"mismatch\":2,\"expired\":0}"
        + "}";

    var qRows = res.parseWorkerQuality("worker-1", workerJson);
    assertEquals(1, qRows.size());
    assertEquals("worker-1", qRows.get(0).nodeId());
    assertEquals("connW", qRows.get(0).peerId());
    assertEquals(10, qRows.get(0).accepted());

    var hits = res.parseWorkerHits("worker-1", workerJson);
    assertEquals(1, hits.size());
    assertEquals("worker-1", hits.get(0).nodeId());
    assertEquals("10.1.2.3:12000", hits.get(0).origin());
    assertEquals(99, hits.get(0).hits());

    var quar = res.parseWorkerQuarantine("worker-1", workerJson);
    assertEquals(1, quar.size());
    assertEquals("worker-1", quar.get(0).nodeId());
    assertEquals("connBad", quar.get(0).peerId());

    var qm = res.parseWorkerQuorum(workerJson);
    assertNotNull(qm);
    assertEquals(4, qm.keys());
    assertEquals(20, qm.observed());
    assertEquals(15, qm.quorum());
  }
}
