package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.CaProfilesGlobalConfig;
import com.bowman.cspng.config.CacheConfig;
import com.bowman.cspng.config.ClusterUdpConfig;
import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ConnectionManagerConfig;
import com.bowman.cspng.config.ExtendedNewcamdConfig;
import com.bowman.cspng.config.ListenerCacheLinkConfig;
import com.bowman.cspng.config.LoggingConfig;
import com.bowman.cspng.config.ProfileConfig;
import com.bowman.cspng.config.ProfileListenerConfig;
import com.bowman.cspng.config.RelayCacheConfig;
import com.bowman.cspng.config.RelayNodeConfig;
import com.bowman.cspng.config.RootConfig;
import com.bowman.cspng.config.WebUiConfig;
import com.bowman.cspng.web.admin.AdminRelayResource.LinkDto;
import io.vertx.core.Future;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lien listener x cache kind=udp : la consultation UDP est implementee cote
 * worker ({@code WorkerUdpExchange.consult}) — l'endpoint admin DOIT donc
 * accepter {@code canConsult=true} sur un cache udp (regression de la garde
 * historique "export-only" supprimee). Sans pair allowliste, la consultation
 * echoue fermee au worker (pas une erreur de config).
 */
@DisplayName("AdminRelayResource — lien udp consultable accepte (export-only supprime)")
class AdminRelayUdpConsultLinkTest {

  /** Repository stub : n'atteint jamais PG, capture le dernier lien upsert. */
  private static final class StubRepo extends ConfigRepository {
    volatile ListenerCacheLinkConfig last;
    volatile String lastActor;

    StubRepo() {
      super(null);
    }

    @Override
    public Future<Long> upsertListenerCacheLink(ListenerCacheLinkConfig l, String actor) {
      this.last = l;
      this.lastActor = actor;
      return Future.succeededFuture(l.version() + 1);
    }
  }

  private static ConfigSnapshot snapshotWithUdpCache() {
    ProfileConfig p = new ProfileConfig("default", true, false, false, 0, 0, null, null,
        5000L, null, false, 0, null, null, null, null, 1L);
    ProfileListenerConfig l = new ProfileListenerConfig(7L, "default", "cccam", 12000, "0.0.0.0",
        true, null, null, null, 1L, "manual", true, 0, 512, 32, 5, null, 60);
    RelayCacheConfig u = new RelayCacheConfig("u", "udp", null, true, 8000L, 10000, 512, 1L);
    return new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT,
        CaProfilesGlobalConfig.DEFAULT, List.of(p), List.of(l), List.of(),
        ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(), CacheConfig.DEFAULT,
        ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
        ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), List.of(),
        List.<RelayNodeConfig>of(), List.of(u), List.of(), List.of(), List.of(), List.of(),
        System.currentTimeMillis());
  }

  private AdminRelayResource resource(StubRepo repo, ConfigHolder holder) {
    AdminRelayResource res = new AdminRelayResource();
    res.configRepository = repo;
    res.configHolder = holder;
    res.securityContext = null; // actor() -> "unknown"
    return res;
  }

  @Test
  @DisplayName("canConsult=true sur cache udp => accepte (200), lien persiste")
  void udpConsultLinkAccepted() {
    ConfigHolder holder = new ConfigHolder();
    holder.set(snapshotWithUdpCache());
    StubRepo repo = new StubRepo();
    AdminRelayResource res = resource(repo, holder);

    Response r = res.upsertLink(7L, "u",
        new LinkDto(7L, "u", true, false, false, true, 1L))
        .await().indefinitely();

    assertEquals(200, r.getStatus(), "lien udp consultable accepte (plus de refus export-only)");
    assertNotNull(repo.last, "upsert propage au repository");
    assertEquals("u", repo.last.cacheName());
    assertTrue(repo.last.canConsult());
    assertTrue(repo.last.enabled());
  }

  @Test
  @DisplayName("canExport=false sur cache udp reste accepte (roles independants)")
  void udpLinkWithoutExportAccepted() {
    ConfigHolder holder = new ConfigHolder();
    holder.set(snapshotWithUdpCache());
    StubRepo repo = new StubRepo();
    AdminRelayResource res = resource(repo, holder);

    Response r = res.upsertLink(7L, "u",
        new LinkDto(7L, "u", true, true, false, true, 1L))
        .await().indefinitely();

    assertEquals(200, r.getStatus());
    assertTrue(repo.last.canFeed());
    assertFalse(repo.last.canExport());
  }

  @Test
  @DisplayName("Lien desactive sur cache udp => accepte sans ambiguite")
  void disabledUdpLinkAccepted() {
    ConfigHolder holder = new ConfigHolder();
    holder.set(snapshotWithUdpCache());
    StubRepo repo = new StubRepo();
    AdminRelayResource res = resource(repo, holder);

    Response r = res.upsertLink(7L, "u",
        new LinkDto(7L, "u", true, false, false, false, 1L))
        .await().indefinitely();

    assertEquals(200, r.getStatus());
    assertFalse(repo.last.enabled());
  }

  @Test
  @DisplayName("Validation DTO inchangee : roles tous faux => 400")
  void rolesAllFalseRejected() {
    ConfigHolder holder = new ConfigHolder();
    holder.set(snapshotWithUdpCache());
    StubRepo repo = new StubRepo();
    AdminRelayResource res = resource(repo, holder);

    Response r = res.upsertLink(7L, "u",
        new LinkDto(7L, "u", false, false, false, true, 1L))
        .await().indefinitely();

    assertEquals(400, r.getStatus());
    assertNull(repo.last, "aucun upsert pour un lien sans role");
  }
}