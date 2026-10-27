package com.bowman.cspng.connectors.cacheex;

import com.bowman.cspng.config.CacheExQuorumConfig;
import com.bowman.cspng.config.PeerQuarantineConfig;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Quarantaine temporaire <b>mémoire uniquement</b>, bornée, opt-in (lot 4).
 *
 * <p>Contrat :
 * <ul>
 *   <li>Désactivée par défaut ({@code enabled=false} dans
 *       {@link PeerQuarantineConfig#DEFAULT}). Quand {@code enabled=false},
 *       {@link #shouldSkip} retourne toujours {@code false} et
 *       {@link #evaluate} ne fait rien.</li>
 *   <li>Mode {@code observe} (défaut) : comptabilise les pairs qui <b>seraient</b>
 *       mis en quarantaine sans jamais les exclure du routage.</li>
 *   <li>Mode {@code enforce} : {@link #shouldSkip} retourne {@code true} pour
 *       les pairs quarantinés vivants (exclusion temporaire du routage
 *       CacheEx/UDP uniquement, <b>jamais</b> un bannissement).</li>
 *   <li>Seuil : au moins {@code minSamples} échantillons éligibles
 *       (accepted+invalid, cf. {@link PeerQualityTracker}) dans la fenêtre et
 *       ratio invalide {@code >= invalidRatioBps}, sinon aucune décision.</li>
 *   <li>Expiration automatique ({@code durationMs}) + levée admin explicite
 *       ({@link #release}). Aucune entrée persistante : redémarrage = table
 *       vide (documenté).</li>
 *   <li><b>Jamais de bannissement d'une source UDP seule</b> : l'adresse
 *       d'expédition UDP est usurpable ; la quarantaine ne s'applique qu'aux
 *       identités de transport vérifiées (connecteur TCP authentifié, pair
 *       allowlisté) et ne bloque que le routage, jamais le transport.</li>
 * </ul>
 *
 * <p>Mémoire bornée : {@code min(cfg.maxPeers(), MAX_ENTRIES)} entrées
 * (éviction de la plus proche expiration) — {@value #MAX_ENTRIES} est le
 * plafond absolu de sécurité, aligné sur le maximum de configuration
 * {@code PeerQuarantineConfig.maxPeers} (1024) ; la valeur LIVE configurée
 * ({@code cfg.maxPeers()}) est effectivement appliquée par {@link #evaluate},
 * jamais ignorée. Horloge injectable pour les tests. Thread-safe sans verrou
 * global.
 */
public class PeerQuarantine {

  /** Plafond absolu de sécurité (borne haute de {@code PeerQuarantineConfig.maxPeers}). */
  public static final int MAX_ENTRIES = 1_024;

  /** Entrée de quarantaine (immuable, expiration absolue). */
  public record QuarantineEntry(String peerId, String reason, long quarantinedAtMs,
                                long expiresAtMs, boolean observedOnly) {}

  private final ConcurrentHashMap<String, QuarantineEntry> entries = new ConcurrentHashMap<>();
  private final AtomicLong observedCount = new AtomicLong();
  private final AtomicLong enforcedCount = new AtomicLong();
  private final Clock clock;

  public PeerQuarantine(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public PeerQuarantine() {
    this(Clock.systemUTC());
  }

  /**
   * Évalue un pair au regard de sa qualité fenêtrée.
   *
   * @param peerId         identité de transport vérifiée (jamais une source UDP brute seule)
   * @param eligible       échantillons éligibles (accepted+invalid) dans la fenêtre
   * @param invalidRatioBps ratio invalide en points de base
   * @param cfg            configuration (null = désactivé)
   * @return {@code true} si le pair vient d'être placé (ou replacé après expiration)
   */
  public boolean evaluate(String peerId, long eligible, int invalidRatioBps, PeerQuarantineConfig cfg) {
    if (peerId == null || peerId.isBlank() || cfg == null || !cfg.enabled()) return false;
    if (eligible < cfg.minSamples() || invalidRatioBps < cfg.invalidRatioBps()) return false;
    long now = clock.millis();
    QuarantineEntry live = entries.get(peerId);
    if (live != null && live.expiresAtMs() > now && !live.observedOnly()) return false;
    boolean observe = !"enforce".equalsIgnoreCase(cfg.mode());
    String reason = "invalid-ratio-bps=" + invalidRatioBps + " eligible=" + eligible;
    int cap = Math.min(Math.max(cfg.maxPeers(), 1), MAX_ENTRIES);
    if (observe) {
      observedCount.incrementAndGet();
      put(new QuarantineEntry(peerId, reason, now, now + cfg.durationMs(), true), cap);
      return true;
    }
    enforcedCount.incrementAndGet();
    put(new QuarantineEntry(peerId, reason, now, now + cfg.durationMs(), false), cap);
    return true;
  }

  /**
   * Insère en respectant le plafond LIVE {@code cfg.maxPeers()} (jamais le
   * plafond absolu {@link #MAX_ENTRIES} seul, sinon la borne configurée par
   * l'admin serait silencieusement ignorée).
   */
  private void put(QuarantineEntry e, int cap) {
    if (entries.size() >= cap) {
      entries.entrySet().stream()
          .min(Comparator.comparingLong(en -> en.getValue().expiresAtMs()))
          .ifPresent(oldest -> entries.remove(oldest.getKey(), oldest.getValue()));
    }
    entries.put(e.peerId(), e);
  }

  /**
   * Le pair doit-il être exclu du routage ? {@code true} uniquement en mode
   * {@code enforce} avec entrée vivante non-observée. En {@code observe} ou
   * désactivé : toujours {@code false}.
   */
  public boolean shouldSkip(String peerId, PeerQuarantineConfig cfg) {
    if (peerId == null || cfg == null || !cfg.enabled()) return false;
    if (!"enforce".equalsIgnoreCase(cfg.mode())) return false;
    QuarantineEntry e = entries.get(peerId);
    if (e == null || e.observedOnly()) return false;
    if (e.expiresAtMs() <= clock.millis()) {
      entries.remove(peerId, e);
      return false;
    }
    return true;
  }

  /** Levée admin explicite (centrale : immédiate ; workers : via expiration/relai, cf. doc). */
  public boolean release(String peerId) {
    if (peerId == null) return false;
    return entries.remove(peerId) != null;
  }

  /** Vérifie si une entrée vivante (non expirée) existe pour ce pair dans la mémoire centrale. */
  public boolean hasLiveEntry(String peerId) {
    if (peerId == null) return false;
    QuarantineEntry e = entries.get(peerId);
    return e != null && e.expiresAtMs() > clock.millis();
  }

  /** Purge les entrées expirées (timer périodique du propriétaire, non bloquant). */
  public void evictExpired() {
    long now = clock.millis();
    entries.entrySet().removeIf(en -> en.getValue().expiresAtMs() <= now);
  }

  /** Instantané trié (entrées vivantes uniquement). */
  public List<QuarantineEntry> snapshot() {
    long now = clock.millis();
    List<QuarantineEntry> out = new ArrayList<>();
    for (Map.Entry<String, QuarantineEntry> e : entries.entrySet()) {
      if (e.getValue().expiresAtMs() > now) out.add(e.getValue());
    }
    out.sort(Comparator.comparing(QuarantineEntry::peerId));
    return out;
  }

  public long observedCount() {
    return observedCount.get();
  }

  public long enforcedCount() {
    return enforcedCount.get();
  }

  public void clear() {
    entries.clear();
  }
}
