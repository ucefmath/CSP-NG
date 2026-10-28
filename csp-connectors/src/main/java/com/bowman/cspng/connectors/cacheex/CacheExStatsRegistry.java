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

  /**
   * Nombre maximum de SID (service) trackés par paire.
   * Au-delà, les entrées les moins récemment actives sont évincées (anti-flood).
   */
  public static final int MAX_SIDS_PER_PAIR = 256;

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

  /**
   * Clé composite (caid, provid, srvid) pour la map des stats par service.
   * Le provid est normalisé à {@code Math.max(0, provid)} à l'entrée.
   */
  record SidKey(int caid, int provid, int srvid) {}

  /** Entrée mutable par service (compteurs atomiques). */
  static final class SidEntry {
    final AtomicLong pushedCw = new AtomicLong();
    final AtomicLong receivedCw = new AtomicLong();
    final AtomicLong hits = new AtomicLong();
    final AtomicLong droppedCw = new AtomicLong();
    final AtomicLong lastActivityMs = new AtomicLong(System.currentTimeMillis());
  }

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
    /** IP distante du pair (identité réseau, mise à jour par updatePeerIdentity). */
    volatile String remoteIp = "";
    /** Identifiant de nœud distant (mis à jour par updatePeerIdentity). */
    volatile String remoteNodeId = "";
    final AtomicLong pushedCw = new AtomicLong();
    final AtomicLong receivedCw = new AtomicLong();
    final AtomicLong droppedCw = new AtomicLong();
    /** Compteur de hits servis depuis ce pair (alias → cache exchange). */
    final AtomicLong hits = new AtomicLong();
    final Map<String, AtomicLong> dropReasons = new LinkedHashMap<>();
    volatile Long lastPushAtMs;
    volatile Long lastReceiveAtMs;
    final AtomicLong lastActivityMs = new AtomicLong(System.currentTimeMillis());
    /** Stats par service (caid, provid, srvid) — bornées à MAX_SIDS_PER_PAIR. */
    final ConcurrentHashMap<SidKey, SidEntry> sids = new ConcurrentHashMap<>();

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
   * Formate une clé de service lisible : {@code "CAID@PROVID:SID"}.
   * Exemple : {@code "1884@000000:32DC"}.
   */
  public static String formatServiceKey(int caid, int provid, int srvid) {
    return String.format("%04X@%06X:%04X", caid & 0xFFFF, provid & 0xFFFFFF, srvid & 0xFFFF);
  }

  /**
   * Vue immuable des statistiques par service (caid/provid/srvid).
   */
  public record SidStats(
      int caid,
      int provid,
      int srvid,
      String serviceKey,
      String channelName,
      long pushedCw,
      long receivedCw,
      long hits,
      long droppedCw,
      Long lastActivityAtMs) {

    public SidStats withChannelName(String name) {
      return new SidStats(caid, provid, srvid, serviceKey, name,
          pushedCw, receivedCw, hits, droppedCw, lastActivityAtMs);
    }
  }

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
   * @param peerId   "L&lt;listenerId&gt;:&lt;userName&gt;"
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

  /**
   * Met à jour l'identité réseau du pair (IP distante et identifiant de nœud).
   * Ne-op si le pair n'est pas enregistré. Les valeurs null ou vides sont ignorées
   * (ne pas écraser une valeur déjà connue par une valeur inconnue).
   */
  public void updatePeerIdentity(String peerId, String remoteIp, String remoteNodeId) {
    Entry e = pairs.get(peerId);
    if (e == null) return;
    if (remoteIp != null && !remoteIp.isBlank()) e.remoteIp = remoteIp;
    if (remoteNodeId != null && !remoteNodeId.isBlank()) e.remoteNodeId = remoteNodeId;
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

  /**
   * CW poussée PAR NOUS vers le pair, avec identifiants de service.
   * Crée ou met à jour l'entrée SID (bornée à {@value #MAX_SIDS_PER_PAIR}).
   */
  public void countPushed(String peerId, int caid, int provid, int srvid) {
    Entry e = pairs.get(peerId);
    if (e == null) return;
    e.pushedCw.incrementAndGet();
    long now = System.currentTimeMillis();
    e.lastPushAtMs = now;
    e.lastActivityMs.set(now);
    if (caid > 0 && srvid > 0) {
      SidEntry sid = getOrCreateSid(e, caid, Math.max(0, provid), srvid);
      if (sid != null) {
        sid.pushedCw.incrementAndGet();
        sid.lastActivityMs.set(now);
      }
    }
  }

  /** CW reçue DU pair (mode 2 sortant / mode 3 entrant). */
  public void countReceived(String peerId) {
    Entry e = pairs.get(peerId);
    if (e == null) return;
    e.receivedCw.incrementAndGet();
    e.lastReceiveAtMs = System.currentTimeMillis();
    e.lastActivityMs.set(e.lastReceiveAtMs);
  }

  /**
   * CW reçue DU pair, avec identifiants de service.
   * Crée ou met à jour l'entrée SID (bornée à {@value #MAX_SIDS_PER_PAIR}).
   */
  public void countReceived(String peerId, int caid, int provid, int srvid) {
    Entry e = pairs.get(peerId);
    if (e == null) return;
    e.receivedCw.incrementAndGet();
    long now = System.currentTimeMillis();
    e.lastReceiveAtMs = now;
    e.lastActivityMs.set(now);
    if (caid > 0 && srvid > 0) {
      SidEntry sid = getOrCreateSid(e, caid, Math.max(0, provid), srvid);
      if (sid != null) {
        sid.receivedCw.incrementAndGet();
        sid.lastActivityMs.set(now);
      }
    }
  }

  /**
   * Incrémente le compteur de hits servis depuis ce pair (la CW en cache a été servie à un client).
   * Ne-op si le pair n'est pas enregistré. Met à jour l'entrée SID si caid et srvid sont valides.
   */
  public void countHit(String peerId, int caid, int provid, int srvid) {
    Entry e = pairs.get(peerId);
    if (e == null) return;
    e.hits.incrementAndGet();
    long now = System.currentTimeMillis();
    e.lastActivityMs.set(now);
    if (caid > 0 && srvid > 0) {
      SidEntry sid = getOrCreateSid(e, caid, Math.max(0, provid), srvid);
      if (sid != null) {
        sid.hits.incrementAndGet();
        sid.lastActivityMs.set(now);
      }
    }
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

  /**
   * CW rejetée avec identifiants de service.
   * Incrémente le compteur global de drops, puis incrémente le compteur de drops SID
   * <b>uniquement si l'entrée SID existe déjà</b> (protection anti-flood : on ne crée
   * JAMAIS de nouvelle entrée SID sur un drop).
   */
  public void countDropped(String peerId, String reason, int caid, int provid, int srvid) {
    countDropped(peerId, reason);
    Entry e = pairs.get(peerId);
    if (e == null) return;
    if (caid > 0 && srvid > 0) {
      int effProvid = Math.max(0, provid);
      SidKey key = new SidKey(caid, effProvid, srvid);
      SidEntry sid = e.sids.get(key);
      if (sid == null && effProvid == 0) {
        for (Map.Entry<SidKey, SidEntry> entry : e.sids.entrySet()) {
          if (entry.getKey().caid() == caid && entry.getKey().srvid() == srvid) {
            sid = entry.getValue();
            break;
          }
        }
      }
      if (sid != null) {
        sid.droppedCw.incrementAndGet();
        sid.lastActivityMs.set(System.currentTimeMillis());
      }
      // SID inconnu → NE CRÉE JAMAIS d'entrée (anti-flood)
    }
  }

  /**
   * Obtient ou crée une entrée SID, en évictant les entrées les moins actives si la
   * borne {@value #MAX_SIDS_PER_PAIR} est atteinte. L'éviction est effectuée HORS
   * de tout {@code computeIfAbsent} pour éviter les modifications récursives de la map.
   * Lorsque {@code provid == 0}, s'il existe déjà dans {@code e.sids} une entrée avec
   * le même caid et srvid, celle-ci est réutilisée.
   */
  private SidEntry getOrCreateSidEntry(Entry e, int caid, int provid, int srvid) {
    SidKey key = new SidKey(caid, provid, srvid);
    SidEntry existing = e.sids.get(key);
    if (existing != null) return existing;
    if (provid == 0) {
      for (Map.Entry<SidKey, SidEntry> entry : e.sids.entrySet()) {
        if (entry.getKey().caid() == caid && entry.getKey().srvid() == srvid) {
          return entry.getValue();
        }
      }
    }
    // Éviction préventive si plein
    if (e.sids.size() >= MAX_SIDS_PER_PAIR) {
      e.sids.entrySet().stream()
          .min(Comparator.comparingLong(en -> en.getValue().lastActivityMs.get()))
          .ifPresent(oldest -> e.sids.remove(oldest.getKey(), oldest.getValue()));
    }
    SidEntry created = new SidEntry();
    SidEntry prev = e.sids.putIfAbsent(key, created);
    return prev != null ? prev : created;
  }

  private SidEntry getOrCreateSid(Entry e, int caid, int provid, int srvid) {
    return getOrCreateSidEntry(e, caid, provid, srvid);
  }

  /** Snapshot immuable trié par peerId — uniquement les paires ENREGISTRÉES (pas de zéros fictifs). */
  public List<PairStats> snapshot() {
    List<Entry> sorted = new ArrayList<>(pairs.values());
    sorted.sort(Comparator.comparing(en -> en.peerId));
    List<PairStats> out = new ArrayList<>(sorted.size());
    for (Entry e : sorted) {
      Map<String, Long> reasons = new LinkedHashMap<>();
      for (String r : REASONS) reasons.put(r, e.dropReasons.get(r).get());
      // Snapshot des SID : trié par (hits desc, receivedCw+pushedCw desc, serviceKey asc)
      List<SidStats> sidList = new ArrayList<>(e.sids.size());
      for (Map.Entry<SidKey, SidEntry> se : e.sids.entrySet()) {
        SidKey k = se.getKey();
        SidEntry sv = se.getValue();
        String serviceKey = formatServiceKey(k.caid(), k.provid(), k.srvid());
        long lastAct = sv.lastActivityMs.get();
        sidList.add(new SidStats(k.caid(), k.provid(), k.srvid(), serviceKey, null,
            sv.pushedCw.get(), sv.receivedCw.get(), sv.hits.get(), sv.droppedCw.get(),
            lastAct > 0 ? lastAct : null));
      }
      sidList.sort(Comparator
          .<SidStats, Long>comparing(SidStats::hits).reversed()
          .thenComparing(Comparator.<SidStats, Long>comparing(s -> s.receivedCw() + s.pushedCw()).reversed())
          .thenComparing(SidStats::serviceKey));
      out.add(new PairStats(
          e.peerId, e.direction, e.protocol, e.mode, e.profile, e.online, e.state,
          e.maxHop, e.maxSendQueue, e.filterActive,
          e.pushedCw.get(), e.receivedCw.get(), e.droppedCw.get(), reasons,
          e.lastPushAtMs, e.lastReceiveAtMs,
          e.remoteIp, e.remoteNodeId, e.hits.get(), sidList));
    }
    return out;
  }

  /** Remise à zéro des compteurs (pas de la config ni des états). */
  public void reset() {
    for (Entry e : pairs.values()) {
      e.pushedCw.set(0);
      e.receivedCw.set(0);
      e.droppedCw.set(0);
      e.hits.set(0);
      for (AtomicLong c : e.dropReasons.values()) c.set(0);
      e.lastPushAtMs = null;
      e.lastReceiveAtMs = null;
      e.sids.clear();
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
      Long lastReceiveAtMs,
      String remoteIp,
      String remoteNodeId,
      long hits,
      List<SidStats> sids) {

    /**
     * Constructeur rétrocompatible à 16 paramètres (avant l'ajout de remoteIp/remoteNodeId/hits/sids).
     * Délègue au constructeur canonique avec des valeurs par défaut.
     */
    public PairStats(String peerId, String direction, String protocol, int mode, String profile,
                     boolean online, String state, int maxHop, int maxSendQueue, boolean filterActive,
                     long pushedCw, long receivedCw, long droppedCw, Map<String, Long> dropReasons,
                     Long lastPushAtMs, Long lastReceiveAtMs) {
      this(peerId, direction, protocol, mode, profile, online, state, maxHop, maxSendQueue, filterActive,
          pushedCw, receivedCw, droppedCw, dropReasons, lastPushAtMs, lastReceiveAtMs,
          "", "", 0L, List.of());
    }
  }
}
