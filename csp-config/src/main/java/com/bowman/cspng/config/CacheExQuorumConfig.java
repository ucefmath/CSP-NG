package com.bowman.cspng.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Quorum CacheEx ciblé (singleton, migration V16
 * {@code csp_cacheex_quorum_config}).
 *
 * <p>Comparaison <b>exacte</b> (octet-à-octet) de CW <b>déjà valides</b> sous la
 * politique DCW existante, pour une même identité ECM disponible :
 * profil/CAID/provider/SID/parité (exacte, {@code 0} distinct de
 * {@code 0x80}/{@code 0x81})/hash. Les push CacheEx ne transportent pas le
 * corps ECM : la clé ne peut pas inclure le corps (limite documentée, pas de
 * corps inventé). Aucune réparation checksum, aucun recalcul, aucune exigence
 * de demi-CW non nulle au-delà de la politique existante.
 *
 * <p>Modes : {@code off} (défaut, bypass total), {@code observe} (injecte comme
 * avant + comptabilise les opportunités), {@code enforce} (n'injecte que sur
 * quorum atteint dans la fenêtre ; sinon rejet explicite, jamais de repli
 * silencieux vers une CW unique). Minimum 2 origines de transport vérifiées
 * distinctes. Deux relais de la même origine ne comptent qu'une fois. Les
 * origines relayées distinctes ne constituent <b>pas</b> une preuve
 * d'indépendance cryptographique (documenté).
 *
 * <p>Validation stricte (fail-closed) : toute valeur hors bornes lève
 * {@link IllegalArgumentException} à la construction.
 */
public record CacheExQuorumConfig(
    String mode,
    int minOrigins,
    long windowMs,
    int maxKeys,
    List<String> profiles,
    List<Integer> caids,
    long version,
    Long updatedAtMs,
    String updatedBy
) {

    public static final String MODE_OFF = "off";
    public static final String MODE_OBSERVE = "observe";
    public static final String MODE_ENFORCE = "enforce";
    public static final int MAX_PROFILES = 64;
    public static final int MAX_CAIDS = 32;
    /** Plafond absolu de {@link #windowMs} (utilisé par le balayage périodique de sécurité). */
    public static final long MAX_WINDOW_MS = 300_000L;

    public static final CacheExQuorumConfig DEFAULT = new CacheExQuorumConfig(
        MODE_OFF, 2, 30_000L, 1_024, List.of(), List.of(), 1L, null, null);

    public CacheExQuorumConfig {
        if (mode == null) mode = MODE_OFF;
        mode = mode.trim().toLowerCase(Locale.ROOT);
        if (!MODE_OFF.equals(mode) && !MODE_OBSERVE.equals(mode) && !MODE_ENFORCE.equals(mode)) {
            throw new IllegalArgumentException("mode must be off, observe or enforce");
        }
        if (minOrigins < 2 || minOrigins > 8) {
            throw new IllegalArgumentException("minOrigins must be 2..8");
        }
        if (windowMs < 1_000 || windowMs > MAX_WINDOW_MS) {
            throw new IllegalArgumentException("windowMs must be 1000.." + MAX_WINDOW_MS);
        }
        if (maxKeys < 16 || maxKeys > 4_096) {
            throw new IllegalArgumentException("maxKeys must be 16..4096");
        }
        if (profiles == null) profiles = List.of();
        List<String> pn = new ArrayList<>();
        for (String p : profiles) {
            if (p == null || p.isBlank()) throw new IllegalArgumentException("profile must not be blank");
            if (p.length() > 64) throw new IllegalArgumentException("profile too long");
            pn.add(p);
        }
        if (pn.size() > MAX_PROFILES) throw new IllegalArgumentException("profiles limit " + MAX_PROFILES);
        profiles = List.copyOf(pn);
        if (caids == null) caids = List.of();
        List<Integer> cn = new ArrayList<>(caids);
        java.util.Collections.sort(cn);
        for (int i = 0; i < cn.size(); i++) {
            int c = cn.get(i);
            if (c < 1 || c > 65535) throw new IllegalArgumentException("caid out of range 1..65535: " + c);
            if (i > 0 && cn.get(i - 1).intValue() == c) throw new IllegalArgumentException("duplicate caid: " + c);
        }
        if (cn.size() > MAX_CAIDS) throw new IllegalArgumentException("caids limit " + MAX_CAIDS);
        caids = List.copyOf(cn);
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
    }

    /** Compat sans horodatage/acteur. */
    public CacheExQuorumConfig(String mode, int minOrigins, long windowMs, int maxKeys,
                               List<String> profiles, List<Integer> caids, long version) {
        this(mode, minOrigins, windowMs, maxKeys, profiles, caids, version, null, null);
    }

    /** Le quorum s'applique-t-il à ce couple profil/CAID ? (listes vides = tous). */
    public boolean appliesTo(String profile, int caid) {
        if (MODE_OFF.equals(mode)) return false;
        if (!profiles.isEmpty() && (profile == null || !profiles.contains(profile))) return false;
        if (!caids.isEmpty() && !caids.contains(caid)) return false;
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CacheExQuorumConfig that)) return false;
        return Objects.equals(mode, that.mode)
            && minOrigins == that.minOrigins
            && windowMs == that.windowMs
            && maxKeys == that.maxKeys
            && Objects.equals(profiles, that.profiles)
            && Objects.equals(caids, that.caids);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, minOrigins, windowMs, maxKeys, profiles, caids);
    }
}
