package com.bowman.cspng.filters;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Contrôle de cycle CW (V15, miroir comportemental du noyau OSCam {@code CW_CYCLE_CHECK} —
 * aucune copie de code, NOTICE).
 *
 * <p>Détections (par service scope = profile/caid/provider/sid) :
 * <ul>
 *   <li><b>frozen</b> : la CW d'une parité donnée est IDENTIQUE à la CW de la même parité
 *       du cycle précédent (alors qu'un cycle s'est écoulé) — la carte est figée ;</li>
 *   <li><b>swap</b> : la CW reçue est identique à la dernière CW de parité OPPOSÉE
 *       (inversion odd/even suspecte) ;</li>
 *   <li><b>old</b> ({@code dropOld}) : horodatage antérieur au dernier cycle connu.</li>
 * </ul>
 *
 * <p>Apprentissage du cycle : temps mesuré entre deux CW de même parité, borné par la
 * fenêtre {@code keepTimeSec} ; {@code usePeerCycleMetadata} exploite le cycletime
 * transporté par les push cacheex (bit 0x80 = flag next-cw-cycle, wire CCcam/CS378x —
 * CSPv2 v1 NON modifié).
 *
 * <p>Bornes : {@code maxList} entrées (éjection LRU de l'entrée la plus ancienne),
 * aucune allocation dépendante de l'entrée. Compteurs réels exposés pour
 * {@code GET /api/admin/dcw/stats} (clé {@code cwCycle}).
 *
 * <p>Thread-safety : appelé depuis l'event-loop (sessions/connecteurs) et le contexte
 * HTTP (stats) — verrouillé ({@code synchronized}) : le chemin est froid par rapport au
 * datapath (1 appel par CW reply/push, pas par octet).
 */
public class CwCycleChecker {

  /** Verdict du contrôle d'une CW. */
  public enum Verdict {
    OK, FROZEN, SWAP, OLD,
    /** Parité inconnue (0) : aucun contrôle possible, bypass comptabilisé, jamais rejeté. */
    IGNORED
  }

  /** Entrée d'historique par scope service. */
  private static final class ServiceCycle {
    byte[] lastOddCw;
    byte[] lastEvenCw;
    long lastOddAtMs;
    long lastEvenAtMs;
    /** Cycletime appris (mesure locale ou métadonnée pair), secondes. */
    int learnedCycleTimeSec;
    boolean learnedNextCwCycle;
    long lastUsedMs = System.currentTimeMillis();

    boolean expired(long nowMs, long keepTimeSec) {
      long keepMs = Math.max(keepTimeSec, 1) * 1000L;
      // TTL relatif à l'activité : une entrée inutilisée depuis keepTime est évincée
      return (nowMs - lastUsedMs) > keepMs;
    }
  }

  private volatile int maxList;
  private volatile long keepTimeSec;
  private volatile String onBad; // "log" | "drop"
  private volatile boolean dropOld;
  private volatile boolean usePeerCycleMetadata;
  /** Gate globales (config V15) — appliquées aussi INTERNEMENT (défense en profondeur). */
  private volatile boolean enabled;
  private volatile java.util.List<Integer> caids = java.util.List.of();

  private final LinkedHashMap<String, ServiceCycle> services = new LinkedHashMap<>() {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, ServiceCycle> eldest) {
      return size() > CwCycleChecker.this.maxList;
    }
  };

  // Compteurs réels (jamais de zéros fictifs : absence de trafic = compteurs à 0)
  private final AtomicLong checks = new AtomicLong();
  private final AtomicLong badCycles = new AtomicLong();
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong logged = new AtomicLong();
  private final AtomicLong ignored = new AtomicLong();

  public CwCycleChecker(int maxList, int keepTimeSec, String onBad, boolean dropOld,
                        boolean usePeerCycleMetadata) {
    this(false, null, maxList, keepTimeSec, onBad, dropOld, usePeerCycleMetadata);
  }

  public CwCycleChecker(boolean enabled, java.util.List<Integer> caids,
                        int maxList, int keepTimeSec, String onBad,
                        boolean dropOld, boolean usePeerCycleMetadata) {
    validate(maxList, keepTimeSec, onBad);
    this.enabled = enabled;
    this.caids = caids != null ? List.copyOf(caids) : List.of();
    this.maxList = maxList;
    this.keepTimeSec = keepTimeSec;
    this.onBad = (onBad == null ? "drop" : onBad).toLowerCase(java.util.Locale.ROOT);
    this.dropOld = dropOld;
    this.usePeerCycleMetadata = usePeerCycleMetadata;
  }

  public int maxList() { return maxList; }
  public long keepTimeSec() { return keepTimeSec; }
  public String onBad() { return onBad; }
  public boolean dropOld() { return dropOld; }
  public boolean usePeerCycleMetadata() { return usePeerCycleMetadata; }
  public boolean enabled() { return enabled; }
  public java.util.List<Integer> caids() { return caids; }

  /**
   * Reconfiguration à chaud (reload config V15) : l'INSTANCE reste stable (bean singleton),
   * l'historique est RÉINITIALISÉ (les paramètres changent la sémantique des entrées).
   */
  public synchronized void reconfigure(boolean enabled, java.util.List<Integer> caids,
                                       int maxList, int keepTimeSec, String onBad,
                                       boolean dropOld, boolean usePeerCycleMetadata) {
    validate(maxList, keepTimeSec, onBad);
    this.enabled = enabled;
    this.caids = caids != null ? List.copyOf(caids) : List.of();
    this.maxList = maxList;
    this.keepTimeSec = keepTimeSec;
    this.onBad = onBad.toLowerCase(java.util.Locale.ROOT);
    this.dropOld = dropOld;
    this.usePeerCycleMetadata = usePeerCycleMetadata;
    this.services.clear();
  }

  private static void validate(int maxList, int keepTimeSec, String onBad) {
    if (maxList < 16 || maxList > 4000) throw new IllegalArgumentException("maxList 16..4000");
    if (keepTimeSec < 5 || keepTimeSec > 240) throw new IllegalArgumentException("keepTimeSec 5..240");
    String o = onBad == null ? "drop" : onBad.toLowerCase(java.util.Locale.ROOT);
    if (!"log".equals(o) && !"drop".equals(o)) {
      throw new IllegalArgumentException("onBad must be log or drop");
    }
  }

  /**
   * Contrôle + apprentissage d'une CW.
   *
   * @param profile      profil (scope)
   * @param caid         CAID (scope) — la gate caids est appliquée par l'APPELANT
   *                     ({@code CwCycleConfig.appliesToCaid}) ; ici le contrôle porte
   *                     sur le scope fourni
   * @param provider     provider (scope)
   * @param sid          service (scope)
   * @param oddEven      0x80 / 0x81
   * @param cw           CW 16 octets (jamais loggée)
   * @param peerCycleTimeSec cycletime transporté par le pair (0 = absent)
   * @param peerNextCwCycle   flag next-cycle du pair
   * @param timestampMs  horodatage de la CW (pour dropOld / mesure du cycle)
   * @return verdict ; l'appelant applique {@code onBad} (drop/log) pour FROZEN/SWAP
   *         et {@code dropOld} pour OLD
   */
  public synchronized Verdict check(String profile, int caid, int provider, int sid,
                                    int oddEven, byte[] cw,
                                    int peerCycleTimeSec, boolean peerNextCwCycle,
                                    long timestampMs) {
    // Gate interne (défense en profondeur — les appelants appliquent déjà la gate config)
    if (!enabled || (caids != null && !caids.isEmpty() && !caids.contains(caid))) {
      return Verdict.OK;
    }
    // Parité inconnue (0, compat OSCam) : bypass SANS apprentissage (aucune
    // contamination des moitiés odd/even) et SANS fausse détection frozen/swap.
    // Comptabilisé séparément (ni checks utiles, ni bad, ni dropped/logged).
    if (oddEven != 0x80 && oddEven != 0x81) {
      ignored.incrementAndGet();
      return Verdict.IGNORED;
    }
    checks.incrementAndGet();
    if (cw == null || cw.length != 16) return Verdict.OK;

    String key = scopeKey(profile, caid, provider, sid);
    ServiceCycle sc = services.get(key);
    if (sc == null) {
      sc = new ServiceCycle();
      services.put(key, sc);
    }
    sc.lastUsedMs = System.currentTimeMillis();

    // Métadonnée pair : enregistre le cycletime annoncé (wire cacheex [18])
    if (usePeerCycleMetadata && peerCycleTimeSec > 0 && peerCycleTimeSec < 128) {
      sc.learnedCycleTimeSec = peerCycleTimeSec;
      sc.learnedNextCwCycle = peerNextCwCycle;
    }

    boolean odd = oddEven == 0x80;
    byte[] sameParity = odd ? sc.lastOddCw : sc.lastEvenCw;
    byte[] oppositeParity = odd ? sc.lastEvenCw : sc.lastOddCw;
    long sameParityAt = odd ? sc.lastOddAtMs : sc.lastEvenAtMs;

    Verdict verdict = Verdict.OK;

    // dropOld : CW plus ancienne que le dernier cycle connu (miroir cwcycle_dropold)
    long lastActivity = Math.max(sc.lastOddAtMs, sc.lastEvenAtMs);
    if (dropOld && lastActivity > 0 && timestampMs < lastActivity) {
      verdict = Verdict.OLD;
    }

    // swap : CW identique à la dernière CW de parité OPPOSÉE
    if (verdict == Verdict.OK && oppositeParity != null && java.util.Arrays.equals(oppositeParity, cw)) {
      verdict = Verdict.SWAP;
    }

    // frozen : CW identique à la même parité du cycle précédent, un cycle (~cycletime) écoulé
    if (verdict == Verdict.OK && sameParity != null && java.util.Arrays.equals(sameParity, cw)) {
      long cycleMs = (sc.learnedCycleTimeSec > 0 ? sc.learnedCycleTimeSec : (int) keepTimeSec) * 1000L;
      long elapsed = timestampMs - sameParityAt;
      // Un cycle complet (ou plus) s'est écoulé avec la MÊME CW => figée
      if (sameParityAt == 0 || elapsed >= cycleMs) {
        verdict = Verdict.FROZEN;
      }
      // Sinon : même parité reçue deux fois en < 1 cycle — retard/dup, on ne marque pas
    }

    // Apprentissage (toujours : la référence glisse)
    if (odd) {
      sc.lastOddCw = cw.clone();
      sc.lastOddAtMs = timestampMs;
    } else {
      sc.lastEvenCw = cw.clone();
      sc.lastEvenAtMs = timestampMs;
    }

    if (verdict != Verdict.OK) {
      badCycles.incrementAndGet();
      if (shouldReject(verdict)) {
        dropped.incrementAndGet();
      } else {
        logged.incrementAndGet();
      }
      evictExpired(System.currentTimeMillis());
    }
    return verdict;
  }

  /** Verdict = rejet effectif ? (onBad=drop pour FROZEN/SWAP ; dropOld pour OLD ;
   * IGNORED n'est jamais rejeté) */
  public boolean shouldReject(Verdict v) {
    if (v == Verdict.OK || v == Verdict.IGNORED) return false;
    if (v == Verdict.OLD) return dropOld;
    return "drop".equals(onBad);
  }

  /**
   * Métadonnée de cycle à EMETTRE pour un scope (wire cacheex [18]) :
   * {@code [cycletime, nextFlag]} ou null si aucune connaissance.
   */
  public synchronized int[] cycleMetadataFor(String profile, int caid, int provider, int sid) {
    ServiceCycle sc = services.get(scopeKey(profile, caid, provider, sid));
    if (sc == null || sc.learnedCycleTimeSec <= 0) return null;
    return new int[] {sc.learnedCycleTimeSec, sc.learnedNextCwCycle ? 1 : 0};
  }

  /**
   * Lookup large par (caid, sid) — côté émission le provider n'est pas reconstructible
   * depuis la {@code CwReply} ; on prend la première entrée apprise pour ce service.
   * Balayage borné (≤ maxList entrées).
   */
  public synchronized int[] cycleMetadataFor(int caid, int sid) {
    for (Map.Entry<String, ServiceCycle> e : services.entrySet()) {
      ServiceCycle sc = e.getValue();
      if (sc.learnedCycleTimeSec <= 0) continue;
      String key = e.getKey();
      // scopeKey = profile/caid/provider/sid
      int firstSlash = key.indexOf('/');
      int secondSlash = key.indexOf('/', firstSlash + 1);
      int thirdSlash = key.indexOf('/', secondSlash + 1);
      if (thirdSlash < 0) continue;
      try {
        int entryCaid = Integer.parseInt(key.substring(firstSlash + 1, secondSlash));
        int entrySid = Integer.parseInt(key.substring(thirdSlash + 1));
        if (entryCaid == caid && entrySid == sid) {
          return new int[] {sc.learnedCycleTimeSec, sc.learnedNextCwCycle ? 1 : 0};
        }
      } catch (NumberFormatException ignored) {
        // clé corrompue — impossible par construction
      }
    }
    return null;
  }

  /**
   * Nettoyage des entrées expirées (fenêtre keepTime) — appelé post-rejet.
   * Balayage COMPLET borné (≤ maxList) : l'ordre d'insertion ne reflète pas
   * l'ordre d'expiration (lastUsedMs mis à jour à chaque accès), donc aucun
   * {@code break} anticipé — sinon des entrées expirées seraient sautées.
   */
  private void evictExpired(long nowMs) {
    Iterator<Map.Entry<String, ServiceCycle>> it = services.entrySet().iterator();
    while (it.hasNext()) {
      if (it.next().getValue().expired(nowMs, keepTimeSec)) {
        it.remove();
      }
    }
  }

  public synchronized int trackedEntries() {
    return services.size();
  }

  public long checks() { return checks.get(); }
  public long badCycles() { return badCycles.get(); }
  public long droppedCount() { return dropped.get(); }
  public long loggedCount() { return logged.get(); }
  /** Push à parité inconnue contournés (bypass comptabilisé, jamais rejetés). */
  public long ignoredCount() { return ignored.get(); }

  /** Remise à zéro des compteurs (tests). */
  public void resetCounters() {
    checks.set(0);
    badCycles.set(0);
    dropped.set(0);
    logged.set(0);
    ignored.set(0);
  }

  /** Clé de scope — jamais de CW ni de hash dedans. */
  static String scopeKey(String profile, int caid, int provider, int sid) {
    return (profile != null ? profile : "") + "/" + caid + "/" + provider + "/" + sid;
  }
}
