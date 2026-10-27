package com.bowman.cspng.filters;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration immuable du filtre DCW avancé.
 *
 * <p>Tous les champs ont des valeurs par défaut sûres ; le tout-zéro est
 * systématiquement ajouté à la bad-list. Aucun secret ni CW brut dans
 * {@link #toString()}.
 *
 * <h3>Modes de continuité</h3>
 * <ul>
 *   <li>{@link ContinuityMode#COMPATIBILITY} — compatible legacy exact
 *       (prédicat {@code data[0]+data[1]+data[3]==0 || data[9]+data[10]+data[11]==0},
 *       arithmétique byte Java signée)</li>
 *   <li>{@link ContinuityMode#STRICT} — via {@code DcwValidator.hasZeroDcw}
 *       (demi-CW entièrement nulle)</li>
 * </ul>
 */
public record DcwFilterConfig(
    boolean enabled,
    boolean detectLinks,
    boolean verifyReplies,
    boolean forceContinuity,
    boolean zeroCounting,
    boolean shadowMode,
    ContinuityMode continuityMode,
    Set<String> profiles,
    Set<byte[]> badDcws,
    int maxEntries) {

  /** CW tout-zéro utilisé systématiquement dans la bad-list. */
  static final byte[] ALL_ZERO = new byte[16];

  public DcwFilterConfig {
    if (maxEntries < 100) throw new IllegalArgumentException("maxEntries must be >= 100");
    if (maxEntries > 1_000_000) throw new IllegalArgumentException("maxEntries must be <= 1_000_000");
    Objects.requireNonNull(continuityMode, "continuityMode");
    // defensive copy of sets
    profiles = Set.copyOf(profiles != null ? profiles : Set.of());
    badDcws = buildBadDcws(badDcws);
  }

  /**
   * Construit la bad-list défensive : copie chaque tableau + tout-zéro systématique.
   */
  private static Set<byte[]> buildBadDcws(Set<byte[]> raw) {
    var bld = new java.util.LinkedHashSet<byte[]>();
    bld.add(ALL_ZERO);
    if (raw != null) {
      for (byte[] b : raw) {
        if (b != null && b.length == 16) bld.add(b.clone());
      }
    }
    return Set.copyOf(bld);
  }

  /** Constructeur avec toutes les valeurs par défaut. */
  public DcwFilterConfig() {
    this(true, false, false, false, true, false,
        ContinuityMode.COMPATIBILITY, Set.of(), null, 10_000);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DcwFilterConfig other)) return false;
    return enabled == other.enabled
        && detectLinks == other.detectLinks
        && verifyReplies == other.verifyReplies
        && forceContinuity == other.forceContinuity
        && zeroCounting == other.zeroCounting
        && shadowMode == other.shadowMode
        && continuityMode == other.continuityMode
        && profiles.equals(other.profiles)
        && badDcwsEqual(other.badDcws)
        && maxEntries == other.maxEntries;
  }

  @Override
  public int hashCode() {
    // omit badDcws content for stable hashing
    return Objects.hash(enabled, detectLinks, verifyReplies, forceContinuity,
        zeroCounting, shadowMode, continuityMode, profiles, maxEntries);
  }

  private boolean badDcwsEqual(Set<byte[]> other) {
    if (badDcws.size() != other.size()) return false;
    for (byte[] a : badDcws) {
      boolean found = false;
      for (byte[] b : other) {
        if (Arrays.equals(a, b)) { found = true; break; }
      }
      if (!found) return false;
    }
    return true;
  }

  @Override
  public String toString() {
    return "DcwFilterConfig[enabled=" + enabled
        + ",detectLinks=" + detectLinks
        + ",verifyReplies=" + verifyReplies
        + ",forceContinuity=" + forceContinuity
        + ",zeroCounting=" + zeroCounting
        + ",shadowMode=" + shadowMode
        + ",continuityMode=" + continuityMode
        + ",profiles=" + profiles.size()
        + ",badDcws=" + badDcws.size()
        + ",maxEntries=" + maxEntries + "]";
  }

  /** Retourne les digests hex des bad DCW pour le logging (jamais raw). */
  public Set<String> badDcwDigests() {
    var set = new java.util.LinkedHashSet<String>();
    var hf = HexFormat.of();
    for (byte[] b : badDcws) {
      set.add(hf.formatHex(sha256(b)));
    }
    return Set.copyOf(set);
  }

  static byte[] sha256(byte[] data) {
    try {
      var md = java.security.MessageDigest.getInstance("SHA-256");
      return md.digest(data);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 not available", e);
    }
  }

  /** Mode de continuité forcée. */
  public enum ContinuityMode { COMPATIBILITY, STRICT }
}
