package com.bowman.cspng.connectors.catalog;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CardDiscoveryServiceTest — Suite complète de tests de découverte, génération, ACL, reshare et flux async PG")
class CardDiscoveryServiceTest {

  private Vertx vertx;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (vertx != null) {
      CountDownLatch latch = new CountDownLatch(1);
      vertx.close(v -> latch.countDown());
      latch.await(5, TimeUnit.SECONDS);
    }
  }

  private static final byte[] SERVER_NODE_ID = { 1, 2, 3, 4, 5, 6, 7, 8 };

  @Test
  @DisplayName("1. Délai Future PG : AUCUNE annonce n'est émise tant que la persistance PG n'est pas complétée")
  void noAnnouncementBeforePgFutureCompletes() throws Exception {
    Promise<CardCapability> capPromise = Promise.promise();
    Promise<CardSource> srcPromise = Promise.promise();

    CardCatalogRepository catalogRepo = new CardCatalogRepository(null) {
      @Override
      public Future<CardCapability> allocateOrGetCapability(String profile, int caid, int provider) {
        return capPromise.future();
      }

      @Override
      public Future<CardSource> upsertSource(long capabilityId, String connectorName, Long originCardId,
                                             CardSource.Trust trust, int hop, int effectiveReshare) {
        return srcPromise.future();
      }
    };

    CardDiscoveryService discovery = new CardDiscoveryService(null, catalogRepo);
    discovery.onConnectorConnected("conn1", 1L);

    AtomicBoolean announcementReceived = new AtomicBoolean(false);
    discovery.addListener(new CardDiscoveryService.CardAnnouncementListener() {
      @Override public void onCapabilityAvailable(CardDiscoveryService.CapabilityRuntimeState cap) {
        announcementReceived.set(true);
      }
      @Override public void onCapabilityWithdrawn(long cardId) {}
      @Override public CardDiscoveryService.UserAclContext getAclContext() { return CardDiscoveryService.UserAclContext.unconstrained(); }
      @Override public String getCardMode() { return "wildcard"; }
      @Override public int getListenerCap() { return 5; }
      @Override public byte[] getServerNodeId() { return SERVER_NODE_ID; }
    });

    // 1. Découverte d'une carte -> PG est en attente
    Future<Void> discFuture = discovery.onCardDiscovered(
        "conn1", "default", 0x0100, 0x000068, 1001L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);

    assertFalse(discFuture.isComplete(), "Le flow de découverte doit attendre le Future PG");
    assertNull(discovery.getCapability("default", 0x0100, 0x000068), "Aucune capacité en mémoire avant retour PG");
    assertFalse(announcementReceived.get(), "AUCUNE annonce émise pendant l'attente PG");

    // 2. Résoudre la capacité et la source PG
    capPromise.complete(new CardCapability(42L, "default", 0x0100, 0x000068, CardCapability.State.PUBLISHED, 999999L, 1L, Instant.now()));
    srcPromise.complete(new CardSource(101L, 42L, "conn1", 1001L, CardSource.Trust.DISCOVERED, 2, 1, Instant.now()));

    CountDownLatch latch = new CountDownLatch(1);
    discFuture.onComplete(ar -> {
      assertTrue(ar.succeeded());
      latch.countDown();
    });
    assertTrue(latch.await(2, TimeUnit.SECONDS));

    var cap = discovery.getCapability("default", 0x0100, 0x000068);
    assertNotNull(cap);
    assertEquals(999999L, cap.persistentCardId, "Doit utiliser le vrai cardId PG");
    assertTrue(announcementReceived.get(), "L'annonce est émise après complétion PG");
  }

  @Test
  @DisplayName("2. Protection de génération : les callbacks de génération obsolète sont ignorés")
  void staleGenerationIgnoredOnCardDiscoveredAndDisconnected() {
    CardDiscoveryService discovery = new CardDiscoveryService(null);
    discovery.onConnectorConnected("conn1", 2L); // Génération courante = 2

    // Arrivée tardive d'une carte avec generation = 1 -> IGNORÉE
    discovery.onCardDiscovered("conn1", "default", 0x0100, 0, 1001L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);
    assertNull(discovery.getCapability("default", 0x0100, 0), "Carte de génération obsolète ne doit pas être enregistrée");

    // Arrivée avec generation = 2 -> ACCEPTÉE
    discovery.onCardDiscovered("conn1", "default", 0x0100, 0, 1001L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 2L);
    assertNotNull(discovery.getCapability("default", 0x0100, 0));

    // Déconnexion d'une ancienne génération 1 -> NE DOIT PAS retirer les sources de la gen 2
    discovery.onConnectorDisconnected("conn1", 1L);
    assertNotNull(discovery.getCapability("default", 0x0100, 0));
    assertTrue(discovery.isSourceOnline("conn1", 2L));
  }

  @Test
  @DisplayName("3. Multi-sources n:n : la perte d'une source ne retire pas la capacité si une autre reste active")
  void multiSourcesMaintainsCapabilityIfOneRemains() {
    CardDiscoveryService discovery = new CardDiscoveryService(null);
    discovery.onConnectorConnected("conn1", 1L);
    discovery.onConnectorConnected("conn2", 1L);

    discovery.onCardDiscovered("conn1", "default", 0x0100, 0, 1001L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);
    discovery.onCardDiscovered("conn2", "default", 0x0100, 0, 2001L, 2, 3, CardSource.Trust.DISCOVERED, 5, null, 1L);

    List<Long> withdrawn = new CopyOnWriteArrayList<>();
    discovery.addListener(new CardDiscoveryService.CardAnnouncementListener() {
      @Override public void onCapabilityAvailable(CardDiscoveryService.CapabilityRuntimeState cap) {}
      @Override public void onCapabilityWithdrawn(long cardId) { withdrawn.add(cardId); }
      @Override public CardDiscoveryService.UserAclContext getAclContext() { return CardDiscoveryService.UserAclContext.unconstrained(); }
      @Override public String getCardMode() { return "wildcard"; }
      @Override public int getListenerCap() { return 5; }
      @Override public byte[] getServerNodeId() { return SERVER_NODE_ID; }
    });

    // Déconnexion de conn1 -> conn2 reste en ligne -> PAS de retrait
    discovery.onConnectorDisconnected("conn1", 1L);
    assertTrue(withdrawn.isEmpty(), "La capacité ne doit PAS être retirée car conn2 est toujours en ligne");

    // Déconnexion de conn2 -> Plus de source -> Retrait émis
    discovery.onConnectorDisconnected("conn2", 1L);
    assertEquals(1, withdrawn.size(), "La capacité doit être retirée une fois la dernière source hors-ligne");
  }

  @Test
  @DisplayName("4. ACL par session : filtrage des annonces par profils et connecteurs autorisés")
  void perUserAclFiltersAnnouncements() {
    CardDiscoveryService discovery = new CardDiscoveryService(null);
    discovery.onConnectorConnected("connA", 1L);
    discovery.onConnectorConnected("connB", 1L);

    discovery.onCardDiscovered("connA", "profile1", 0x0100, 0, 101L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);
    discovery.onCardDiscovered("connB", "profile2", 0x0500, 0, 201L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);

    // Utilisateur restreint à profile1 et connA
    CardDiscoveryService.UserAclContext userAcl = new CardDiscoveryService.UserAclContext(
        Set.of("profile1"), Set.of("connA"));

    List<byte[]> initialAnnouncements = discovery.getInitialAnnouncements(
        "profile1", "wildcard", 5, 512, SERVER_NODE_ID, userAcl);
    assertEquals(1, initialAnnouncements.size(), "L'utilisateur ne doit recevoir que sa carte profile1");

    List<byte[]> forbiddenAnnouncements = discovery.getInitialAnnouncements(
        "profile2", "wildcard", 5, 512, SERVER_NODE_ID, userAcl);
    assertEquals(0, forbiddenAnnouncements.size(), "L'utilisateur ne doit recevoir aucune carte pour profile2");
  }

  @Test
  @DisplayName("5. Même originCardId sur connecteurs/CAIDs distincts gérés sans collision")
  void sameOriginCardIdDifferentCaidSupportedDistinctly() {
    CardDiscoveryService discovery = new CardDiscoveryService(null);
    discovery.onConnectorConnected("conn1", 1L);
    discovery.onConnectorConnected("conn2", 1L);

    // Même originCardId = 1234L sur 2 CAIDs distincts
    discovery.onCardDiscovered("conn1", "default", 0x0100, 0x000068, 1234L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);
    discovery.onCardDiscovered("conn2", "default", 0x0500, 0x032830, 1234L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);

    var cap1 = discovery.getCapability("default", 0x0100, 0x000068);
    var cap2 = discovery.getCapability("default", 0x0500, 0x032830);

    assertNotNull(cap1);
    assertNotNull(cap2);
    assertNotEquals(cap1.persistentCardId, cap2.persistentCardId, "Chaque capacité doit avoir son propre cardId persistant");
  }

  @Test
  @DisplayName("6. Règles de reshare strictes : CCcam amont 0 ou Newcamd sans grant rejetés, grant 0 client-only")
  void reshareStrictRejectionRules() {
    CardDiscoveryService discovery = new CardDiscoveryService(null);
    discovery.onConnectorConnected("conn1", 1L);

    // CCcam amont reshare = 0 -> STRICTEMENT REJETÉ
    discovery.onCardDiscovered("conn1", "default", 0x0100, 0, 1001L, 1, 0, CardSource.Trust.DISCOVERED, 5, null, 1L);
    assertNull(discovery.getCapability("default", 0x0100, 0), "CCcam amont 0 ne doit pas créer de capacité");

    // Newcamd sans grant (grant == null) -> STRICTEMENT REJETÉ
    discovery.onCardDiscovered("conn1", "default", 0x0500, 0x032830, 2001L, 1, 2, CardSource.Trust.DECLARED_UNVERIFIED, 5, null, 1L);
    assertNull(discovery.getCapability("default", 0x0500, 0x032830), "Newcamd sans grant ne doit pas créer de capacité");

    // Newcamd avec grant = 0 -> ACCEPTÉ en mode client-only (effectiveReshare = 0)
    discovery.onCardDiscovered("conn1", "default", 0x0500, 0x032830, 2001L, 1, 2, CardSource.Trust.DECLARED_UNVERIFIED, 5, 0, 1L);
    var capGrant0 = discovery.getCapability("default", 0x0500, 0x032830);
    assertNotNull(capGrant0);
    assertEquals(0, capGrant0.sources.get("conn1:2001").effectiveReshare(), "Grant 0 donne effectiveReshare = 0");

    // Newcamd avec grant = 3 -> ACCEPTÉ borné par connectorCap=2 -> effectiveReshare = 2
    discovery.onCardDiscovered("conn1", "default", 0x0604, 0, 3001L, 1, 2, CardSource.Trust.DECLARED_UNVERIFIED, 2, 3, 1L);
    var capGrant3 = discovery.getCapability("default", 0x0604, 0);
    assertNotNull(capGrant3);
    assertEquals(2, capGrant3.sources.get("conn1:3001").effectiveReshare(), "Grant 3 borné par connectorCap 2 donne 2");
  }

  @Test
  @DisplayName("7. Transitions d'état : PUBLISHED et BLOCKED réconciliés")
  void capabilityStateTransitions() {
    CardDiscoveryService discovery = new CardDiscoveryService(null);
    discovery.onConnectorConnected("conn1", 1L);
    discovery.onCardDiscovered("conn1", "default", 0x0100, 0, 1001L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);

    List<Long> withdrawn = new CopyOnWriteArrayList<>();
    discovery.addListener(new CardDiscoveryService.CardAnnouncementListener() {
      @Override public void onCapabilityAvailable(CardDiscoveryService.CapabilityRuntimeState cap) {}
      @Override public void onCapabilityWithdrawn(long cardId) { withdrawn.add(cardId); }
      @Override public CardDiscoveryService.UserAclContext getAclContext() { return CardDiscoveryService.UserAclContext.unconstrained(); }
      @Override public String getCardMode() { return "wildcard"; }
      @Override public int getListenerCap() { return 5; }
      @Override public byte[] getServerNodeId() { return SERVER_NODE_ID; }
    });

    discovery.updateCapabilityState("default", 0x0100, 0, CardCapability.State.BLOCKED);
    assertEquals(1, withdrawn.size(), "Mise à l'état BLOCKED doit immédiatement retirer la carte");
  }

  @Test
  @DisplayName("8. onCardRemoved retire la carte et envoie retrait quand dernière source supprimée")
  void onCardRemovedWithdrawsCapability() {
    CardDiscoveryService discovery = new CardDiscoveryService(null);
    discovery.onConnectorConnected("conn1", 1L);
    discovery.onCardDiscovered("conn1", "default", 0x0100, 0, 1001L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);

    List<Long> withdrawn = new CopyOnWriteArrayList<>();
    discovery.addListener(new CardDiscoveryService.CardAnnouncementListener() {
      @Override public void onCapabilityAvailable(CardDiscoveryService.CapabilityRuntimeState cap) {}
      @Override public void onCapabilityWithdrawn(long cardId) { withdrawn.add(cardId); }
      @Override public CardDiscoveryService.UserAclContext getAclContext() { return CardDiscoveryService.UserAclContext.unconstrained(); }
      @Override public String getCardMode() { return "wildcard"; }
      @Override public int getListenerCap() { return 5; }
      @Override public byte[] getServerNodeId() { return SERVER_NODE_ID; }
    });

    discovery.onCardRemoved("conn1", "default", 0x0100, 0, 1001L, 1L);
    assertEquals(1, withdrawn.size(), "onCardRemoved doit retirer la carte");
  }
}
