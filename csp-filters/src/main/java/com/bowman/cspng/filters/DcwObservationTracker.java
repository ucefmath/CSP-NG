package com.bowman.cspng.filters;

import com.bowman.cspng.domain.dcw.DcwDigest;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Traqueur d'observations et d'anomalies DCW (sans auto-BAN).
 *
 * <p>Enregistre les anomalies (checksum, structure, frozen CW, règles OBSERVE)
 * sous forme d'empreintes SHA-256 sans jamais journaliser ni exposer les CW en clair.
 * Ne prononce aucun bannissement automatique irréversible.
 */
public class DcwObservationTracker {

  public enum AnomalyType {
    CHECKSUM_FAIL,
    STRUCTURAL_FAIL,
    FROZEN_CW_SUSPICION,
    OBSERVED_RULE_MATCH,
    ZERO_COUNT_SUSPICION,
    REPAIR_OPPORTUNITY
  }

  public record AnomalyEvent(
      long timestamp,
      String profile,
      int caid,
      int provider,
      int sid,
      String sourceId,
      AnomalyType type,
      String fingerprintSha256,
      int count,
      String message
  ) {}

  public static final int MAX_TRACKED_SERVICES = 2000;
  public static final long CYCLE_TRACKER_TTL_MS = 300_000L; // 5 minutes

  private final int maxEvents;
  private final Deque<AnomalyEvent> events = new ArrayDeque<>();
  private final ConcurrentHashMap<String, CycleTracker> serviceCycles = new ConcurrentHashMap<>();

  private static final class CycleTracker {
    int lastTag = -1;
    int lastDataHash = 0;
    String lastCwDigest = null;
    int consecutiveSameCwCycles = 1;
    long lastUpdatedMs = System.currentTimeMillis();
  }

  public DcwObservationTracker(int maxEvents) {
    this.maxEvents = Math.max(50, maxEvents);
  }

  public synchronized void recordAnomaly(
      String profile, int caid, int provider, int sid, String sourceId,
      AnomalyType type, byte[] cw, String message
  ) {
    String fingerprint = (cw != null) ? DcwDigest.digestSha256Hex(cw) : "null";
    long now = System.currentTimeMillis();

    // Vérifier si un événement similaire récent existe pour incrémenter le compteur
    for (AnomalyEvent e : events) {
      if (e.type() == type && Objects.equals(e.fingerprintSha256(), fingerprint)
          && Objects.equals(e.profile(), profile) && e.sid() == sid
          && (now - e.timestamp()) < 60_000L) {
        // Mettre à jour l'événement existant
        events.remove(e);
        events.addFirst(new AnomalyEvent(
            now, profile, caid, provider, sid, sourceId, type, fingerprint, e.count() + 1, message
        ));
        return;
      }
    }

    if (events.size() >= maxEvents) {
      events.removeLast();
    }
    events.addFirst(new AnomalyEvent(
        now, profile, caid, provider, sid, sourceId, type, fingerprint, 1, message
    ));
  }

  /**
   * Suit les cycles de contrôle ECM (parité 0x80/0x81 et hash) pour détecter les CW gelées (frozen CW).
   * Borné en taille et en TTL pour éviter toute fuite mémoire sur churn de SIDs.
   */
  public void trackCwCycle(String profile, int caid, int provider, int sid,
                           int commandTag, int dataHash, byte[] cw) {
    if (cw == null || cw.length != 16) return;
    String digest = DcwDigest.digestSha256Hex(cw);
    long now = System.currentTimeMillis();
    String key = (profile != null ? profile : "*") + ":" + caid + ":" + provider + ":" + sid;

    // Éviction bornée si la table atteint le plafond
    if (serviceCycles.size() >= MAX_TRACKED_SERVICES && !serviceCycles.containsKey(key)) {
      serviceCycles.entrySet().removeIf(entry -> (now - entry.getValue().lastUpdatedMs) > CYCLE_TRACKER_TTL_MS);
      if (serviceCycles.size() >= MAX_TRACKED_SERVICES) {
        // Supprimer les plus anciens si l'éviction TTL ne suffit pas
        var it = serviceCycles.keySet().iterator();
        int toRemove = (serviceCycles.size() - MAX_TRACKED_SERVICES) + 1;
        while (it.hasNext() && toRemove-- > 0) {
          it.next();
          it.remove();
        }
      }
    }

    serviceCycles.compute(key, (k, existing) -> {
      if (existing == null) {
        CycleTracker t = new CycleTracker();
        t.lastTag = commandTag;
        t.lastDataHash = dataHash;
        t.lastCwDigest = digest;
        t.consecutiveSameCwCycles = 1;
        t.lastUpdatedMs = now;
        return t;
      }

      existing.lastUpdatedMs = now;
      // Un cycle est distinct si le commandTag bascule (0x80 <-> 0x81) OU si le dataHash change
      boolean distinctCycle = (existing.lastTag != commandTag) || (existing.lastDataHash != dataHash);
      if (distinctCycle) {
        if (digest.equals(existing.lastCwDigest)) {
          existing.consecutiveSameCwCycles++;
          if (existing.consecutiveSameCwCycles >= 3) {
            recordAnomaly(profile, caid, provider, sid, null,
                AnomalyType.FROZEN_CW_SUSPICION, cw,
                "Frozen CW detected across " + existing.consecutiveSameCwCycles + " distinct control cycles");
          }
        } else {
          existing.lastCwDigest = digest;
          existing.consecutiveSameCwCycles = 1;
        }
        existing.lastTag = commandTag;
        existing.lastDataHash = dataHash;
      }
      return existing;
    });
  }

  public synchronized List<AnomalyEvent> recentAnomalies() {
    return List.copyOf(events);
  }

  /** Nombre actuel de services suivis pour la détection de cycles (borné à MAX_TRACKED_SERVICES). */
  public int trackedServicesCount() {
    return serviceCycles.size();
  }

  public synchronized void clear() {
    events.clear();
    serviceCycles.clear();
  }
}
