package com.bowman.cspng.connectors.cacheex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registry des statistiques CacheEx PAR PAIR (contrat v2 §4).
 *
 * <p>Compteurs réels uniquement : une paire sans trafic n'apparaît pas dans le snapshot
 * (jamais de zéros fabriqués). Les compteurs sont monotones depuis l'enregistrement ;
 * {@link #reset()} les remet à zéro sans toucher à la configuration.
 *
 * <p>Paires : sortantes = connecteur cccam/cs378x ({@code cacheexMode != 0}) ;
 * entrantes = (listener, utilisateur) ayant une politique cacheex active.
 *
 * <p>Borne : {@value #MAX_PAIRS} entrées maximum — l'enregistrement au-delà évince
 * l'entrée la moins récemment active (activité = tout compteur ou update d'état).
 *
 * <p>Thread-safe sans verrou : les compteurs vivent sur l'event-loop des connecteurs
 * et le snapshot est lu par le thread HTTP (lecture tolérante aux courses).
 */
public class CacheExStatsRegistry {

  public static final int MAX_PAIRS = 512;

  /** Raisons de drop bornées (contrat v2 §4). */
  public static final String REASON_LOOP = "loop";
  public static final String REASON_HOP = "hop";
  public static final String REASON_CHECKSUM = "checksum";
  public static final String REASON_FILTERED = "filtered";
  public static final String REASON_INVALID = "invalid";
  public static final String REASON_QUEUE = "queue";
  public static final String REASON_FLOOD = "flood";
  /** Pair exclu temporairement du routage par la quarantaine (lot 4, enforce). */
  public static final String REASON_QUARANTINE = "quarantine";
  /**
   * CW valide sous la politique DCW mais retenue par le quorum ciblé (lot 5,
   * enforce) : en attente de corroboration, divergente d'une autre origine,
   * ou fenêtre expirée sans quorum. Distinct de {@link #REASON_INVALID} — un
   * push "quorum-pending" n'est PAS un contenu malformé, jamais compté comme
   * tel (métriques justes : ne pas conflater "en attente de consensus" et
   * "invalide").
   */
  public static final String REASON_QUORUM = "quorum";

  private static final List<String> REASONS =
      List.of(REASON_LOOP, REASON_HOP, REASON_CHECKSUM, REASON_FILTERED, REASON_INVALID, REASON_QUEUE,
          REASON_FLOOD, REASON_QUARANTINE, REASON_QUORUM);

  /** Entrée mutable interne (compteurs atomiques). */
  private static final class Entry {
    final String peerId;
    final String direction;
    final String protocol;
    final int mode;
    volatile String profile = "";
    volatile boolean online = false;
    volatile String state = "disabled";
    volatile int maxHop = 10;
    volatile int maxSendQueue = 2000;
    volatile boolean filterActive = false;
    final AtomicLong pushedCw = new AtomicLong();
    final AtomicLong receivedCw = new AtomicLong();
    final AtomicLong droppedCw = new AtomicLong();
    final Map<String, AtomicLong> dropReasons = new LinkedHashMap<>();
    volatile Long lastPushAtMs;
    volatile Long lastReceiveAtMs;
    final AtomicLong lastActivityMs = new AtomicLong(System.currentTimeMillis());

    Entry(String peerId, String direction, String protocol, int mode) {
      this.peerId = peerId;
      this.direction = direction;
      this.protocol = protocol;
      this.mode = mode;
      for (String r : REASONS) dropReasons.put(r, new AtomicLong());
    }
  }

  private final ConcurrentHashMap<String, Entry> pairs = new ConcurrentHashMap<>();

  /**
   * Enregistre (ou met à jour) une paire sortante (connecteur).
   *
   * @param connectorName nom du connecteur (peerId)
   * @param protocol      "cccam" ou "cs378x"
   * @param mode          2 (nous recevons) ou 3 (nous émettons)
   */
  public void registerOutbound(String connectorName, String protocol, int mode, String profile,
                               int maxHop, int maxSendQueue) {
    Entry e = upsert(connectorName, "outbound", protocol, mode);
    if (e == null) return;
    if (profile != null) e.profile = profile;
    e.maxHop = maxHop;
    e.maxSendQueue = maxSendQueue;
    e.state = "disconnected";
  }

  /**
   * Enregistre (ou met à jour) une paire entrante (listener:user).
   *
   * @param peerId   "L<listenerId>:<userName>"
   * @param mode     2 (nous émettons vers le client) ou 3 (nous recevons du client)
   */
  public void registerInbound(String peerId, String protocol, int mode, String profile,
                              int maxHop, int maxSendQueue) {
    Entry e = upsert(peerId, "inbound", protocol, mode);
    if (e == null) return;
    if (profile != null) e.profile = profile;
    e.maxHop = maxHop;
    e.maxSendQueue = maxSendQueue;
  }

  private Entry upsert(String peerId, String direction, String protocol, int mode) {
    if (peerId == null || peerId.isBlank()) return null;
    Entry created = new Entry(peerId, direction, protocol, mode);
    Entry e = pairs.putIfAbsent(peerId, created);
    if (e == null) {
      evictIfFull();
      return created;
    }
    e.lastActivityMs.set(System.currentTimeMillis());
    return e;
  }

  private void evictIfFull() {
    if (pairs.size() <= MAX_PAIRS) return;
    pairs.values().stream()
        .min(Comparator.comparingLong(en -> en.lastActivityMs.get()))
        .ifPresent(oldest -> pairs.remove(oldest.peerId, oldest));
  }

  /** Mise à jour de l'état runtime d'une paire (sortante : connecteur ; entrante : session). */
  public void updateState(String peerId, boolean online, String state, boolean filterActive) {
    Entry e = pairs.get(peerId);
    if (e == null) return;
    e.online = online;
    e.state = state != null ? state : e.state;
    e.filterActive = filterActive;
    e.lastActivityMs.set(System.currentTimeMillis());
  }

  /** Désenregistrement (arrêt connecteur / fermeture listener). */
  public void unregister(String peerId) {
    if (peerId != null) pairs.remove(peerId);
  }

  /** CW poussée PAR NOUS vers le pair (mode 3 sortant / mode 2 entrant). */
  public void countPushed(String peerId) {
    Entry e = pairs.get(peerId);
    if (e == null) return;
    e.pushedCw.incrementAndGet();
    e.lastPushAtMs = System.currentTimeMillis();
    e.lastActivityMs.set(e.lastPushAtMs);
  }

  /** CW reçue DU pair (mode 2 sortant / mode 3 entrant). */
  public void countReceived(String peerId) {
    Entry e = pairs.get(peerId);
    if (e == null) return;
    e.receivedCw.incrementAndGet();
    e.lastReceiveAtMs = System.currentTimeMillis();
    e.lastActivityMs.set(e.lastReceiveAtMs);
  }

  /** CW rejetée, avec raison bornée (les raisons inconnues sont agrégées sous "invalid"). */
  public void countDropped(String peerId, String reason) {
    Entry e = pairs.get(peerId);
    if (e == null) return;
    e.droppedCw.incrementAndGet();
    AtomicLong counter = e.dropReasons.get(reason != null && e.dropReasons.containsKey(reason)
        ? reason : REASON_INVALID);
    counter.incrementAndGet();
    e.lastActivityMs.set(System.currentTimeMillis());
  }

  /** Snapshot immuable trié par peerId — uniquement les paires ENREGISTRÉES (pas de zéros fictifs). */
  public List<PairStats> snapshot() {
    List<Entry> sorted = new ArrayList<>(pairs.values());
    sorted.sort(Comparator.comparing(en -> en.peerId));
    List<PairStats> out = new ArrayList<>(sorted.size());
    for (Entry e : sorted) {
      Map<String, Long> reasons = new LinkedHashMap<>();
      for (String r : REASONS) reasons.put(r, e.dropReasons.get(r).get());
      out.add(new PairStats(
          e.peerId, e.direction, e.protocol, e.mode, e.profile, e.online, e.state,
          e.maxHop, e.maxSendQueue, e.filterActive,
          e.pushedCw.get(), e.receivedCw.get(), e.droppedCw.get(), reasons,
          e.lastPushAtMs, e.lastReceiveAtMs));
    }
    return out;
  }

  /** Remise à zéro des compteurs (pas de la config ni des états). */
  public void reset() {
    for (Entry e : pairs.values()) {
      e.pushedCw.set(0);
      e.receivedCw.set(0);
      e.droppedCw.set(0);
      for (AtomicLong c : e.dropReasons.values()) c.set(0);
      e.lastPushAtMs = null;
      e.lastReceiveAtMs = null;
    }
  }

  public int size() {
    return pairs.size();
  }

  /** Vue immuable d'une paire (contrat v2 §4 — types stables pour le JSON). */
  public record PairStats(
      String peerId,
      String direction,
      String protocol,
      int mode,
      String profile,
      boolean online,
      String state,
      int maxHop,
      int maxSendQueue,
      boolean filterActive,
      long pushedCw,
      long receivedCw,
      long droppedCw,
      Map<String, Long> dropReasons,
      Long lastPushAtMs,
      Long lastReceiveAtMs) {}
}
