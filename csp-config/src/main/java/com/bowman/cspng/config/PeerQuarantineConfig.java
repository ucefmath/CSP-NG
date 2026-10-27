package com.bowman.cspng.config;

import java.util.Locale;
import java.util.Objects;

/**
 * Quarantaine temporaire des pairs (singleton, migration V16
 * {@code csp_peer_quarantine_config}).
 *
 * <p>Opt-in : {@code enabled=false} par défaut. Mode {@code observe} par défaut
 * (comptabilise sans exclure). La quarantaine est un état <b>mémoire
 * temporaire borné</b> ({@code PeerQuarantine}) : redémarrage = table vide.
 * Seule la configuration est persistée.
 *
 * <p>Validation stricte (fail-closed) : toute valeur hors bornes lève
 * {@link IllegalArgumentException} à la construction.
 */
public record PeerQuarantineConfig(
    boolean enabled,
    String mode,
    int minSamples,
    int invalidRatioBps,
    long windowMs,
    long durationMs,
    int maxPeers,
    long version,
    Long updatedAtMs,
    String updatedBy
) {

    public static final String MODE_OBSERVE = "observe";
    public static final String MODE_ENFORCE = "enforce";

    public static final PeerQuarantineConfig DEFAULT = new PeerQuarantineConfig(
        false, MODE_OBSERVE, 50, 5_000, 300_000L, 600_000L, 256, 1L, null, null);

    public PeerQuarantineConfig {
        if (mode == null) mode = MODE_OBSERVE;
        mode = mode.trim().toLowerCase(Locale.ROOT);
        if (!MODE_OBSERVE.equals(mode) && !MODE_ENFORCE.equals(mode)) {
            throw new IllegalArgumentException("mode must be observe or enforce");
        }
        if (minSamples < 2 || minSamples > 10_000) {
            throw new IllegalArgumentException("minSamples must be 2..10000");
        }
        if (invalidRatioBps < 0 || invalidRatioBps > 10_000) {
            throw new IllegalArgumentException("invalidRatioBps must be 0..10000");
        }
        if (windowMs < 5_000 || windowMs > 3_600_000) {
            throw new IllegalArgumentException("windowMs must be 5000..3600000");
        }
        if (durationMs < 10_000 || durationMs > 86_400_000) {
            throw new IllegalArgumentException("durationMs must be 10000..86400000");
        }
        if (maxPeers < 16 || maxPeers > 1_024) {
            throw new IllegalArgumentException("maxPeers must be 16..1024");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
    }

    /** Compat sans horodatage/acteur. */
    public PeerQuarantineConfig(boolean enabled, String mode, int minSamples, int invalidRatioBps,
                                long windowMs, long durationMs, int maxPeers, long version) {
        this(enabled, mode, minSamples, invalidRatioBps, windowMs, durationMs, maxPeers,
            version, null, null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PeerQuarantineConfig that)) return false;
        return enabled == that.enabled
            && Objects.equals(mode, that.mode)
            && minSamples == that.minSamples
            && invalidRatioBps == that.invalidRatioBps
            && windowMs == that.windowMs
            && durationMs == that.durationMs
            && maxPeers == that.maxPeers;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, mode, minSamples, invalidRatioBps, windowMs, durationMs, maxPeers);
    }
}
