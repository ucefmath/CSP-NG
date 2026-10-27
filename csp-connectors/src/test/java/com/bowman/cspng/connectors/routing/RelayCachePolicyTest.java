package com.bowman.cspng.connectors.routing;

import com.bowman.cspng.config.ListenerCacheLinkConfig;
import com.bowman.cspng.config.RelayCacheConfig;
import com.bowman.cspng.config.RelayNodeConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RelayCachePolicy — consultation/alimentation/export, fraicheur, boucles, provenance, inflight (ADR 0002)")
class RelayCachePolicyTest {

  private static RelayNodeConfig node(String name, boolean enabled, boolean authorized, boolean revoked) {
    return new RelayNodeConfig(name, null, enabled, authorized, revoked, 1L, null, 1L);
  }

  private static RelayCacheConfig cache(String name, String kind, String node, boolean enabled) {
    return new RelayCacheConfig(name, kind, node, enabled, 8000L, 10000, 512, 1L);
  }

  private static ListenerCacheLinkConfig link(long lid, String cache, boolean c, boolean f, boolean e, boolean en) {
    return new ListenerCacheLinkConfig(lid, cache, c, f, e, en, 1L);
  }

  @Test
  @DisplayName("Detache par defaut : aucun lien => refus consult/feed/export")
  void detachedByDefaultFailsClosed() {
    var p = new RelayCachePolicy("local1", List.of(cache("c1", "local", null, true)), List.of(), List.of());
    assertFalse(p.canConsult(7L, "c1"));
    assertFalse(p.canFeed(7L, "c1"));
    assertFalse(p.canExport(7L, "c1"));
    assertTrue(p.consultableCaches(7L).isEmpty());
  }

  @Test
  @DisplayName("Lien desactive => refus immediat (detachement)")
  void disabledLinkRefusesImmediately() {
    var p = new RelayCachePolicy("local1",
        List.of(cache("c1", "local", null, true)),
        List.of(link(7L, "c1", true, true, false, false)),
        List.of());
    assertFalse(p.canConsult(7L, "c1"));
    assertFalse(p.canFeed(7L, "c1"));
  }

  @Test
  @DisplayName("Consult/feed separes : consult seul n'autorise pas feed")
  void consultFeedSeparated() {
    var p = new RelayCachePolicy("local1",
        List.of(cache("c1", "local", null, true)),
        List.of(link(7L, "c1", true, false, false, true)),
        List.of());
    assertTrue(p.canConsult(7L, "c1"));
    assertFalse(p.canFeed(7L, "c1"));
  }

  @Test
  @DisplayName("UDP consultable/alimentable/exportable uniquement via lien explicite (transport cable separement)")
  void udpGatedByLink() {
    var p = new RelayCachePolicy("local1",
        List.of(cache("u1", "udp", null, true)),
        List.of(link(7L, "u1", true, true, true, true)),
        List.of());
    assertTrue(p.canConsult(7L, "u1"));
    assertTrue(p.canFeed(7L, "u1"));
    assertTrue(p.canExport(7L, "u1"));
    // Lien desactive : fail-closed sur les trois roles.
    var q = new RelayCachePolicy("local1",
        List.of(cache("u1", "udp", null, true)),
        List.of(link(7L, "u1", true, true, true, false)),
        List.of());
    assertFalse(q.canConsult(7L, "u1"));
    assertFalse(q.canFeed(7L, "u1"));
    assertFalse(q.canExport(7L, "u1"));
  }

  @Test
  @DisplayName("Node non utilisable (revoque/non autorise) => refus")
  void revokedNodeRefused() {
    var p = new RelayCachePolicy("local1",
        List.of(cache("n1", "node", "peer-a", true)),
        List.of(link(7L, "n1", true, true, true, true)),
        List.of(node("peer-a", true, true, true)));
    assertFalse(p.canConsult(7L, "n1"));
    assertFalse(p.canExport(7L, "n1"));
  }

  @Test
  @DisplayName("Fraicheur bornee : expire apres maxAge")
  void freshnessBounded() {
    long now = 1_000_000L;
    assertTrue(RelayCachePolicy.isFresh(now - 1000, now, 8000));
    assertFalse(RelayCachePolicy.isFresh(now - 9000, now, 8000));
    assertFalse(RelayCachePolicy.isFresh(0, now, 8000));
    assertFalse(RelayCachePolicy.isFresh(now + 100, now, 8000));
  }

  @Test
  @DisplayName("Anti-boucle : chaine contenant le node local => loop")
  void loopGuard() {
    var p = new RelayCachePolicy("local1", List.of(), List.of(), List.of());
    assertTrue(p.isLoop(List.of("peer-a", "local1")));
    assertFalse(p.isLoop(List.of("peer-a", "peer-b")));
    assertFalse(p.isLoop(List.of()));
  }

  @Test
  @DisplayName("Provenance inconnue toujours rejetee")
  void provenanceUnknownRejected() {
    assertFalse(RelayCachePolicy.checkProvenance(null, Set.of("c1")));
    assertFalse(RelayCachePolicy.checkProvenance("", Set.of("c1")));
    assertFalse(RelayCachePolicy.checkProvenance("evil", Set.of("c1")));
    assertTrue(RelayCachePolicy.checkProvenance("c1", Set.of("c1", "c2")));
  }

  @Test
  @DisplayName("Inflight borne : current < max")
  void inflightBounded() {
    assertTrue(RelayCachePolicy.checkInflight(0, 512));
    assertFalse(RelayCachePolicy.checkInflight(512, 512));
    assertFalse(RelayCachePolicy.checkInflight(-1, 512));
  }
}
