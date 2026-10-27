package com.bowman.cspng.worker;

import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.config.RelayWorkerContract;
import com.bowman.cspng.connectors.catalog.CardDiscoveryService;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkerClusterCardsDiscoveryTest — Annonces cartes cluster synthétiques sur worker sans connecteur")
class WorkerClusterCardsDiscoveryTest {

  private Vertx vertx;
  private WorkerRuntime runtime;
  private WorkerState state;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    state = new WorkerState("node-relay");
    runtime = new WorkerRuntime(vertx, "node-relay", state);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) runtime.close();
    if (vertx != null) {
      CountDownLatch l = new CountDownLatch(1);
      vertx.close(v -> l.countDown());
      assertTrue(l.await(10, TimeUnit.SECONDS));
    }
  }

  @Test
  @DisplayName("Worker sans connecteur + catalogue publié -> getInitialAnnouncements retourne N cartes triées et bornées par maxCards")
  void workerWithoutLocalConnectorsPublishesCardsAndBoundsMaxCards() throws Exception {
    long now = System.currentTimeMillis();
    var catalog = List.of(
        new RelayWorkerContract.CatalogView("default", 0x1811, 0x003311, "published", 103L),
        new RelayWorkerContract.CatalogView("default", 0x0100, 0, "published", 101L),
        new RelayWorkerContract.CatalogView("default", 0x0500, 0x032830, "published", 102L),
        new RelayWorkerContract.CatalogView("default", 0x09CD, 0, "discovered", 104L) // non published -> ignoré
    );

    var desired = new RelayWorkerContract.DesiredWorkerConfig(
        "node-relay", 1L, now + 30000L, 30000L,
        List.of(new RelayWorkerContract.ListenerView(7L, "default", "cccam", 14881, "127.0.0.1",
            "manual", 0, 512, 32, 5, 60, null, null)),
        List.of(), List.of(), List.of(), List.of(), List.of(), catalog, List.of(), List.of());

    Boolean applied = runtime.applyDesired(desired, now).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    assertTrue(applied);

    byte[] serverNode = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
    CardDiscoveryService.UserAclContext unconstrained = CardDiscoveryService.UserAclContext.unconstrained();

    // 1. Annonces initiales complètes : 3 cartes publiées attendues
    List<byte[]> announcements = runtime.discovery().getInitialAnnouncements(
        "default", "manual", 10, 512, serverNode, unconstrained);
    assertEquals(3, announcements.size(), "3 capacités publiées doivent être annoncées");

    // Vérifier le tri déterministe par caid (0x0100 -> 0x0500 -> 0x1811)
    CcCardCodec.CcCard card1 = CcCardCodec.parseNewCard(announcements.get(0));
    assertEquals(101, card1.cardId);
    assertEquals(0x0100, card1.caid);
    assertEquals(2, card1.hop); // hop 1 + 1 = 2
    assertEquals(0, card1.reshare);

    CcCardCodec.CcCard card2 = CcCardCodec.parseNewCard(announcements.get(1));
    assertEquals(102, card2.cardId);
    assertEquals(0x0500, card2.caid);
    assertEquals(2, card2.hop);
    assertEquals(0, card2.reshare);

    CcCardCodec.CcCard card3 = CcCardCodec.parseNewCard(announcements.get(2));
    assertEquals(103, card3.cardId);
    assertEquals(0x1811, card3.caid);
    assertEquals(2, card3.hop);
    assertEquals(0, card3.reshare);

    // 2. Bornage par maxCards = 2
    List<byte[]> bounded = runtime.discovery().getInitialAnnouncements(
        "default", "manual", 10, 2, serverNode, unconstrained);
    assertEquals(2, bounded.size(), "maxCards=2 doit borner à 2 cartes");
    assertEquals(101, CcCardCodec.parseNewCard(bounded.get(0)).cardId);
    assertEquals(102, CcCardCodec.parseNewCard(bounded.get(1)).cardId);
  }

  @Test
  @DisplayName("ACL: allowedConnectors restrictif sans 'cluster' exclut les cartes cluster, allowed avec 'cluster' ou vide les inclut")
  void userAclAllowedConnectorsFiltersClusterCards() throws Exception {
    long now = System.currentTimeMillis();
    var catalog = List.of(
        new RelayWorkerContract.CatalogView("default", 0x0100, 0, "published", 201L),
        new RelayWorkerContract.CatalogView("default", 0x0500, 0, "published", 202L)
    );

    var desired = new RelayWorkerContract.DesiredWorkerConfig(
        "node-relay", 1L, now + 30000L, 30000L,
        List.of(new RelayWorkerContract.ListenerView(7L, "default", "cccam", 14882, "127.0.0.1",
            "manual", 0, 512, 32, 5, 60, null, null)),
        List.of(), List.of(), List.of(), List.of(), List.of(), catalog, List.of(), List.of());

    assertTrue(runtime.applyDesired(desired, now).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));

    byte[] serverNode = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};

    // 1. ACL restrictive sans 'cluster' -> 0 cartes annoncées
    CardDiscoveryService.UserAclContext aclRestrictiveWithoutCluster =
        new CardDiscoveryService.UserAclContext(Set.of("default"), Set.of("local_reader1", "peer_reader2"));
    List<byte[]> forRestrictedUser = runtime.discovery().getInitialAnnouncements(
        "default", "manual", 10, 512, serverNode, aclRestrictiveWithoutCluster);
    assertEquals(0, forRestrictedUser.size(), "allowedConnectors sans 'cluster' doit exclure les cartes cluster");

    // 2. ACL explicite contenant 'cluster' -> 2 cartes annoncées
    CardDiscoveryService.UserAclContext aclWithCluster =
        new CardDiscoveryService.UserAclContext(Set.of("default"), Set.of("cluster", "local_reader1"));
    List<byte[]> forClusterAllowedUser = runtime.discovery().getInitialAnnouncements(
        "default", "manual", 10, 512, serverNode, aclWithCluster);
    assertEquals(2, forClusterAllowedUser.size(), "allowedConnectors avec 'cluster' doit inclure les cartes cluster");

    // 3. ACL sans restriction de connecteurs -> 2 cartes annoncées
    CardDiscoveryService.UserAclContext aclUnrestricted =
        new CardDiscoveryService.UserAclContext(Set.of("default"), Set.of());
    List<byte[]> forUnrestrictedUser = runtime.discovery().getInitialAnnouncements(
        "default", "manual", 10, 512, serverNode, aclUnrestricted);
    assertEquals(2, forUnrestrictedUser.size(), "allowedConnectors vide (non restreint) doit inclure les cartes cluster");
  }

  @Test
  @DisplayName("Re-apply : capacité retirée du catalogue à la v2 -> plus annoncée (invalidation par génération)")
  void capabilityRemovedFromCatalogOnReapplyIsWithdrawnFromAnnouncements() throws Exception {
    long now = System.currentTimeMillis();
    var catalogV1 = List.of(
        new RelayWorkerContract.CatalogView("default", 0x0100, 0, "published", 301L),
        new RelayWorkerContract.CatalogView("default", 0x0500, 0, "published", 302L)
    );

    var desiredV1 = new RelayWorkerContract.DesiredWorkerConfig(
        "node-relay", 1L, now + 30000L, 30000L,
        List.of(new RelayWorkerContract.ListenerView(7L, "default", "cccam", 14883, "127.0.0.1",
            "manual", 0, 512, 32, 5, 60, null, null)),
        List.of(), List.of(), List.of(), List.of(), List.of(), catalogV1, List.of(), List.of());

    assertTrue(runtime.applyDesired(desiredV1, now).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));

    byte[] serverNode = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
    CardDiscoveryService.UserAclContext acl = CardDiscoveryService.UserAclContext.unconstrained();

    List<byte[]> annV1 = runtime.discovery().getInitialAnnouncements(
        "default", "manual", 10, 512, serverNode, acl);
    assertEquals(2, annV1.size(), "v1 doit annoncer 2 cartes");

    // v2 : retrait de 0x0500 (302L) du catalogue
    var catalogV2 = List.of(
        new RelayWorkerContract.CatalogView("default", 0x0100, 0, "published", 301L)
    );
    var desiredV2 = new RelayWorkerContract.DesiredWorkerConfig(
        "node-relay", 2L, now + 60000L, 30000L,
        List.of(new RelayWorkerContract.ListenerView(7L, "default", "cccam", 14883, "127.0.0.1",
            "manual", 0, 512, 32, 5, 60, null, null)),
        List.of(), List.of(), List.of(), List.of(), List.of(), catalogV2, List.of(), List.of());

    assertTrue(runtime.applyDesired(desiredV2, now).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));

    List<byte[]> annV2 = runtime.discovery().getInitialAnnouncements(
        "default", "manual", 10, 512, serverNode, acl);
    assertEquals(1, annV2.size(), "v2 doit annoncer uniquement la carte 301L restante");
    assertEquals(301, CcCardCodec.parseNewCard(annV2.get(0)).cardId);
  }

  @Test
  @DisplayName("Garde anti-collision : si un connecteur réel s'appelle 'cluster', la source synthétique n'est pas créée")
  void collisionGuardWithRealClusterConnector() throws Exception {
    long now = System.currentTimeMillis();
    var catalog = List.of(
        new RelayWorkerContract.CatalogView("default", 0x0100, 0, "published", 401L)
    );

    java.nio.file.Path secretFile = java.nio.file.Path.of("target/test-cluster-conn-pass.txt");
    java.nio.file.Files.createDirectories(secretFile.getParent());
    java.nio.file.Files.writeString(secretFile, "secretpass");

    // Desired avec un connecteur réel nommé "cluster" (pointant vers un port local)
    var connectors = List.of(
        new RelayWorkerContract.ConnectorView("cluster", "cccam", "default", true,
            "127.0.0.1", 19999, "testuser", "file:" + secretFile.toAbsolutePath())
    );

    var desired = new RelayWorkerContract.DesiredWorkerConfig(
        "node-relay", 1L, now + 30000L, 30000L,
        List.of(new RelayWorkerContract.ListenerView(7L, "default", "cccam", 14884, "127.0.0.1",
            "manual", 0, 512, 32, 5, 60, null, null)),
        List.of(), List.of(), connectors, List.of(), List.of(), catalog, List.of(), List.of());

    assertTrue(runtime.applyDesired(desired, now).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));

    // Puisque le connecteur réel "cluster" est présent dans desired.connectors(),
    // la source synthétique ne doit pas être générée en double/collision.
    // Le connecteur réel "cluster" a été enregistré via la boucle connecteurs réels.
    byte[] serverNode = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
    List<byte[]> announcements = runtime.discovery().getInitialAnnouncements(
        "default", "manual", 10, 512, serverNode, CardDiscoveryService.UserAclContext.unconstrained());
    assertEquals(1, announcements.size());
  }
}
