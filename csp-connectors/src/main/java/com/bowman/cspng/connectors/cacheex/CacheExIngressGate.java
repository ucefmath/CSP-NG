package com.bowman.cspng.connectors.cacheex;

import com.bowman.cspng.config.CacheExQuorumConfig;
import com.bowman.cspng.config.PeerQuarantineConfig;
import com.bowman.cspng.domain.dcw.DcwQualityPolicy;
import com.bowman.cspng.domain.dcw.DcwValidationResult;
import com.bowman.cspng.domain.dcw.LegacyGuard;

import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * Porte d'ingress CacheEx partagée centrale/worker (lots 1/2/4/5).
 *
 * <p>Ordre strict avant injection :
 * <ol>
 *   <li><b>Quarantaine</b> (lot 4) : en {@code enforce}, un pair quarantiné
 *       vivant est exclu du routage (drop {@code quarantine}, exclu de la
 *       population éligible — un skip n'est pas un verdict contenu).</li>
 *   <li><b>Validation policy contextuelle</b> (lot 1) : {@code DcwQualityPolicy}
 *       avec le contexte réel (profil/CAID/provider/SID, sourceId = identité
 *       du pair, garde de l'appelant). Les exceptions légitimes de la policy
 *       (modes scopés, demi-CW tolérée, digest) sont préservées. Échec
 *       imputable =&gt; événement {@code invalid} ; succès =&gt; rien (le
 *       {@code accepted} est enregistré par {@link #postInjected} après
 *       injection réelle).</li>
 *   <li><b>Quorum</b> (lot 5) : sur CW déjà validée uniquement.
 *       En mode {@code enforce} : pas de quorum (&lt; {@code minOrigins} origines
 *       distinctes) =&gt; rejet explicite {@code REASON_QUORUM}, jamais de repli
 *       silencieux.
 *       En mode {@code observe} (B2) : toute CW valide est acceptée et injectée,
 *       les métriques de quorum étant enregistrées sans altérer le routage.</li>
 * </ol>
 *
 * <p>Tous les collaborateurs sont nullables (fonction désactivée si absent).
 * Aucun log de CW, aucun secret. Petits calculs synchrones sur l'event-loop
 * (pas de {@code executeBlocking}).
 */
public class CacheExIngressGate {

  private static final HexFormat HF = HexFormat.of();

  /**
   * Dérive l'identité de l'origine réelle depuis la liste des node_ids du wire
   * (OSCam/CCcam CacheEx, B1).
   *
   * <p>Ordre protocolaire : chaque relais préfixe son propre node_id en tête
   * de liste (index 0). Le créateur originel de la CW se trouve donc au dernier
   * index ({@code nodeIds.get(nodeIds.size() - 1)}). Si absent ou vide, repli
   * vers l'endpoint de transport direct.
   */
  public static String extractOrigin(List<byte[]> nodeIds, String transportFallback) {
    if (nodeIds != null && !nodeIds.isEmpty()) {
      byte[] originNode = nodeIds.get(nodeIds.size() - 1);
      if (originNode != null && originNode.length == 8) {
        return "node:" + HF.formatHex(originNode);
      }
    }
    return transportFallback != null && !transportFallback.isBlank() ? transportFallback : "unknown";
  }

  /** Verdict de pré-injection. */
  public record Verdict(boolean accept, String dropReason, String detail) {
    public static Verdict accepted() {
      return new Verdict(true, null, null);
    }

    public static Verdict rejected(String reason, String detail) {
      return new Verdict(false, reason, detail);
    }
  }

  private volatile Supplier<DcwQualityPolicy> dcwPolicySupplier;
  private volatile PeerQualityTracker qualityTracker;
  private volatile PeerQuarantine quarantine;
  private volatile Supplier<PeerQuarantineConfig> quarantineConfigSupplier;
  private volatile CacheExQuorumTracker quorumTracker;
  private volatile Supplier<CacheExQuorumConfig> quorumConfigSupplier;

  public void setDcwPolicySupplier(Supplier<DcwQualityPolicy> supplier) {
    this.dcwPolicySupplier = supplier;
  }

  public void setQuality(PeerQualityTracker tracker, PeerQuarantine quarantine,
                         Supplier<PeerQuarantineConfig> quarantineConfigSupplier) {
    this.qualityTracker = tracker;
    this.quarantine = quarantine;
    this.quarantineConfigSupplier = quarantineConfigSupplier;
  }

  public void setQuorum(CacheExQuorumTracker tracker, Supplier<CacheExQuorumConfig> quorumConfigSupplier) {
    this.quorumTracker = tracker;
    this.quorumConfigSupplier = quorumConfigSupplier;
  }

  /**
   * Porte centrale : fournisseurs résolus depuis le snapshot live
   * (jamais de secret, jamais de CW). Trackers partagés du runtime central.
   */
  public static CacheExIngressGate central(com.bowman.cspng.config.ConfigHolder holder,
                                           PeerQualityTracker tracker, PeerQuarantine quarantine,
                                           CacheExQuorumTracker quorum) {
    CacheExIngressGate g = new CacheExIngressGate();
    g.setDcwPolicySupplier(() -> {
      try {
        var snap = holder != null && holder.isInitialized() ? holder.get() : null;
        var cfg = snap != null ? snap.dcwPolicy() : null;
        return cfg != null ? cfg.toDomain() : DcwQualityPolicy.DEFAULT;
      } catch (Exception ignored) {
        return DcwQualityPolicy.DEFAULT;
      }
    });
    g.setQuality(tracker, quarantine, () -> {
      try {
        var snap = holder != null && holder.isInitialized() ? holder.get() : null;
        return snap != null && snap.quarantine() != null ? snap.quarantine()
            : PeerQuarantineConfig.DEFAULT;
      } catch (Exception ignored) {
        return PeerQuarantineConfig.DEFAULT;
      }
    });
    g.setQuorum(quorum, () -> {
      try {
        var snap = holder != null && holder.isInitialized() ? holder.get() : null;
        return snap != null && snap.quorum() != null ? snap.quorum()
            : CacheExQuorumConfig.DEFAULT;
      } catch (Exception ignored) {
        return CacheExQuorumConfig.DEFAULT;
      }
    });
    return g;
  }

  DcwQualityPolicy effectivePolicy() {
    Supplier<DcwQualityPolicy> s = dcwPolicySupplier;
    if (s != null) {
      try {
        DcwQualityPolicy p = s.get();
        if (p != null) return p;
      } catch (Exception ignored) {
        // fail-closed vers DEFAULT
      }
    }
    return DcwQualityPolicy.DEFAULT;
  }

  PeerQuarantineConfig quarantineConfig() {
    Supplier<PeerQuarantineConfig> s = quarantineConfigSupplier;
    if (s != null) {
      try {
        PeerQuarantineConfig c = s.get();
        if (c != null) return c;
      } catch (Exception ignored) {
        // désactivé
      }
    }
    return PeerQuarantineConfig.DEFAULT;
  }

  CacheExQuorumConfig quorumConfig() {
    Supplier<CacheExQuorumConfig> s = quorumConfigSupplier;
    if (s != null) {
      try {
        CacheExQuorumConfig c = s.get();
        if (c != null) return c;
      } catch (Exception ignored) {
        // off
      }
    }
    return CacheExQuorumConfig.DEFAULT;
  }

  /**
   * Pré-injection d'une CW reçue d'un pair.
   *
   * @param profile         profil (nullable, contexte réel de l'appelant)
   * @param caid            CAID
   * @param provider        provider réel (ou {@code UNKNOWN_PROVIDER} si indisponible)
   * @param sid             SID
   * @param parity          parité exacte (0 OSCam préservé tel quel)
   * @param dataHash        hash ECM disponible
   * @param cw              CW 16 octets (jamais loguée)
   * @param peerId          identité du pair pour qualité/quarantaine (transport vérifié)
   * @param realOrigin      identité de source originaire pour le quorum (dédup réelle B1)
   * @param legacyGuard     garde historique de l'appelant
   */
  public Verdict preInject(String profile, int caid, int provider, int sid, int parity, int dataHash,
                           byte[] cw, String peerId, String realOrigin, LegacyGuard legacyGuard) {
    if (cw == null || cw.length != 16) {
      recordInvalid(peerId);
      return Verdict.rejected(CacheExStatsRegistry.REASON_INVALID, "invalid-length");
    }
    // 1. Quarantaine enforce : skip de routage, hors population éligible.
    PeerQuarantine q = quarantine;
    PeerQuarantineConfig qcfg = quarantineConfig();
    if (q != null && qcfg.enabled() && q.shouldSkip(peerId, qcfg)) {
      return Verdict.rejected(CacheExStatsRegistry.REASON_QUARANTINE, "quarantined");
    }
    // 2. Validation policy contextuelle (exceptions légitimes préservées).
    DcwValidationResult validation = effectivePolicy().validate(cw, profile, caid, provider, sid, peerId,
        legacyGuard != null ? legacyGuard : LegacyGuard.CHECKSUM_REQUIRED);
    if (!validation.isValid()) {
      recordInvalid(peerId);
      String reason = validation.reason() == DcwValidationResult.Reason.CHECKSUM_FAIL
          || validation.reason() == DcwValidationResult.Reason.CHECKSUM_VALID_REJECTED
          ? CacheExStatsRegistry.REASON_CHECKSUM : CacheExStatsRegistry.REASON_INVALID;
      return Verdict.rejected(reason, String.valueOf(validation.reason()));
    }
    // 3. Quorum sur CW déjà validée (comparaison exacte, double exigence pairs + origines B1/B2).
    CacheExQuorumTracker qt = quorumTracker;
    CacheExQuorumConfig qmcfg = quorumConfig();
    if (qt != null && qmcfg.appliesTo(profile, caid)) {
      CacheExQuorumTracker.Decision d = qt.observe(
          new CacheExQuorumTracker.QuorumKey(profile, caid, provider, sid, parity, dataHash),
          cw, peerId, realOrigin, qmcfg);
      if (CacheExQuorumConfig.MODE_ENFORCE.equalsIgnoreCase(qmcfg.mode())) {
        switch (d) {
          case PENDING, MISMATCH, EXPIRED -> {
            return Verdict.rejected(CacheExStatsRegistry.REASON_QUORUM, "quorum-" + d.name().toLowerCase());
          }
          default -> {
            // QUORUM / BYPASSED : accept
          }
        }
      }
      // Mode OBSERVE (B2) : toute CW valide est acceptée, les métriques sont enregistrées.
    }
    return Verdict.accepted();
  }

  /**
   * Post-injection réussie (transport authentifié TCP) : enregistre
   * {@code accepted} et évalue la quarantaine sur la fenêtre configurée.
   */
  public void postInjected(String peerId) {
    PeerQualityTracker t = qualityTracker;
    if (t == null || peerId == null || peerId.isBlank()) return;
    t.recordAccepted(peerId);
    maybeEvaluateQuarantine(peerId);
  }

  /** Variante UDP : qualité seule, jamais de quarantaine (source usurpable). */
  public void postInjectedUdp(String peerId) {
    PeerQualityTracker t = qualityTracker;
    if (t == null || peerId == null || peerId.isBlank()) return;
    t.recordAccepted(peerId);
  }

  /** Enregistrement direct d'invalid UDP sans quarantaine. */
  public void recordContentInvalidUdp(String peerId) {
    PeerQualityTracker t = qualityTracker;
    if (t == null || peerId == null || peerId.isBlank()) return;
    t.recordInvalid(peerId);
  }

  private void recordInvalid(String peerId) {
    PeerQualityTracker t = qualityTracker;
    if (t == null || peerId == null || peerId.isBlank()) return;
    t.recordInvalid(peerId);
    maybeEvaluateQuarantine(peerId);
  }

  private void maybeEvaluateQuarantine(String peerId) {
    PeerQuarantine q = quarantine;
    PeerQualityTracker t = qualityTracker;
    if (q == null || t == null) return;
    PeerQuarantineConfig cfg = quarantineConfig();
    if (!cfg.enabled()) return;
    t.quality(peerId, cfg.windowMs()).ifPresent(snap ->
        q.evaluate(peerId, snap.total(), snap.invalidRatioBps(), cfg));
  }
}
