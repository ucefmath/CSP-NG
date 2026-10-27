package com.bowman.cspng.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Contrôle de cycle CW (singleton, migration V15 {@code csp_cw_cycle_config}).
 *
 * <p>Miroir comportemental du noyau OSCam {@code CW_CYCLE_CHECK}
 * ({@code cwcycle_check_enable}, {@code cwcycle_check_caid}, {@code cwcycle_maxlist},
 * {@code cwcycle_keeptime}, {@code cwcycle_onbad}, {@code cwcycle_dropold}) — aucune copie
 * de code (NOTICE). {@code usePeerCycleMetadata} exploite le cycletime/flag next-cycle
 * transporté par les push cacheex CCcam/CS378x (buf[18], bit 0x80) — le wire CSPv2 v1
 * (gelé, ADR 0001) n'est PAS modifié.
 *
 * <p>Validation stricte (fail-closed) : toute valeur hors bornes lève
 * {@link IllegalArgumentException} à la construction.
 */
public record CwCycleConfig(
    boolean enabled,
    List<Integer> caids,
    int maxList,
    int keepTimeSec,
    String onBad,
    boolean dropOld,
    boolean usePeerCycleMetadata,
    long version,
    Long updatedAtMs,
    String updatedBy
) {

    public static final int MAX_CAIDS = 32;
    public static final int MIN_LIST = 16;
    public static final int MAX_LIST_LIMIT = 4000;
    public static final int DEFAULT_LIST = 500;
    public static final int MIN_KEEP_SEC = 5;
    public static final int MAX_KEEP_SEC = 240;
    public static final int DEFAULT_KEEP_SEC = 15;

    public static final CwCycleConfig DEFAULT = new CwCycleConfig(
        false, List.of(), DEFAULT_LIST, DEFAULT_KEEP_SEC, "drop", true, false, 1L, null, null);

    public CwCycleConfig {
        if (caids == null) caids = List.of();
        // Copie défensive + normalisation : tri croissant, doublons interdits, bornes 1..65535
        List<Integer> normalized = new ArrayList<>(caids);
        java.util.Collections.sort(normalized);
        for (int i = 0; i < normalized.size(); i++) {
            int c = normalized.get(i);
            if (c < 1 || c > 65535) {
                throw new IllegalArgumentException("caid out of range 1..65535: " + c);
            }
            if (i > 0 && normalized.get(i - 1).intValue() == c) {
                throw new IllegalArgumentException("duplicate caid: " + c);
            }
        }
        if (normalized.size() > MAX_CAIDS) {
            throw new IllegalArgumentException("caids limit " + MAX_CAIDS);
        }
        caids = List.copyOf(normalized);
        if (maxList < MIN_LIST || maxList > MAX_LIST_LIMIT) {
            throw new IllegalArgumentException("maxList must be " + MIN_LIST + ".." + MAX_LIST_LIMIT);
        }
        if (keepTimeSec < MIN_KEEP_SEC || keepTimeSec > MAX_KEEP_SEC) {
            throw new IllegalArgumentException("keepTimeSec must be " + MIN_KEEP_SEC + ".." + MAX_KEEP_SEC);
        }
        if (onBad == null) onBad = "drop";
        onBad = onBad.trim().toLowerCase(Locale.ROOT);
        if (!"log".equals(onBad) && !"drop".equals(onBad)) {
            throw new IllegalArgumentException("onBad must be log or drop");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
    }

    /** Compat sans horodatage/acteur (tests, défauts). */
    public CwCycleConfig(boolean enabled, List<Integer> caids, int maxList, int keepTimeSec,
                         String onBad, boolean dropOld, boolean usePeerCycleMetadata, long version) {
        this(enabled, caids, maxList, keepTimeSec, onBad, dropOld, usePeerCycleMetadata,
            version, null, null);
    }

    /** CSV trié depuis la colonne {@code caids} ; vide/null = toutes (sémantique OSCam). */
    public static List<Integer> parseCaids(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            try {
                out.add(Integer.parseInt(s));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid caid entry: " + s);
            }
        }
        return out;
    }

    /** Représentation CSV stable pour la persistance. */
    public static String caidsToCsv(List<Integer> caids) {
        if (caids == null || caids.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        List<Integer> sorted = new ArrayList<>(caids);
        java.util.Collections.sort(sorted);
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(sorted.get(i));
        }
        return sb.toString();
    }

    /** Un CAID est-il concerné par le contrôle ? (liste vide = toutes) */
    public boolean appliesToCaid(int caid) {
        return caids.isEmpty() || caids.contains(caid);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CwCycleConfig that)) return false;
        // Identité de configuration (version/horodatage/acteur exclus)
        return enabled == that.enabled
            && Objects.equals(caids, that.caids)
            && maxList == that.maxList
            && keepTimeSec == that.keepTimeSec
            && onBad.equals(that.onBad)
            && dropOld == that.dropOld
            && usePeerCycleMetadata == that.usePeerCycleMetadata;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, caids, maxList, keepTimeSec, onBad, dropOld, usePeerCycleMetadata);
    }
}
