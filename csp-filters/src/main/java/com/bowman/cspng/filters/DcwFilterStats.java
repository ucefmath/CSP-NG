package com.bowman.cspng.filters;

/**
 * Snapshot immuable des compteurs du filtre DCW.
 *
 * <p>Chaque champ expose un compteur monotone. Aucun raw CW ni secret n'est
 * présent dans ce record (ni dans {@link #toString()}).
 */
public record DcwFilterStats(
    long accepted,
    long filteredBadList,
    long badLength,
    long checksumFail,
    long zeroCount,
    long merged,
    long held,
    long verified,
    long linksDetected,
    long linksRemoved,
    long shadowDivergences,
    long evictions) {

  public static final DcwFilterStats ZERO = new DcwFilterStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

  /** Retourne un nouveau snapshot avec {@code accepted} incrémenté. */
  public DcwFilterStats withAccepted() { return new DcwFilterStats(accepted + 1, filteredBadList, badLength, checksumFail, zeroCount, merged, held, verified, linksDetected, linksRemoved, shadowDivergences, evictions); }

  /** Retourne un nouveau snapshot avec {@code filteredBadList} incrémenté. */
  public DcwFilterStats withFilteredBadList() { return new DcwFilterStats(accepted, filteredBadList + 1, badLength, checksumFail, zeroCount, merged, held, verified, linksDetected, linksRemoved, shadowDivergences, evictions); }

  /** Retourne un nouveau snapshot avec {@code badLength} incrémenté. */
  public DcwFilterStats withBadLength() { return new DcwFilterStats(accepted, filteredBadList, badLength + 1, checksumFail, zeroCount, merged, held, verified, linksDetected, linksRemoved, shadowDivergences, evictions); }

  /** Retourne un nouveau snapshot avec {@code checksumFail} incrémenté. */
  public DcwFilterStats withChecksumFail() { return new DcwFilterStats(accepted, filteredBadList, badLength, checksumFail + 1, zeroCount, merged, held, verified, linksDetected, linksRemoved, shadowDivergences, evictions); }

  /** Retourne un nouveau snapshot avec {@code zeroCount} incrémenté. */
  public DcwFilterStats withZeroCount() { return new DcwFilterStats(accepted, filteredBadList, badLength, checksumFail, zeroCount + 1, merged, held, verified, linksDetected, linksRemoved, shadowDivergences, evictions); }

  /** Retourne un nouveau snapshot avec {@code merged} incrémenté. */
  public DcwFilterStats withMerged() { return new DcwFilterStats(accepted, filteredBadList, badLength, checksumFail, zeroCount, merged + 1, held, verified, linksDetected, linksRemoved, shadowDivergences, evictions); }

  /** Retourne un nouveau snapshot avec {@code held} incrémenté. */
  public DcwFilterStats withHeld() { return new DcwFilterStats(accepted, filteredBadList, badLength, checksumFail, zeroCount, merged, held + 1, verified, linksDetected, linksRemoved, shadowDivergences, evictions); }

  /** Retourne un nouveau snapshot avec {@code verified} incrémenté. */
  public DcwFilterStats withVerified() { return new DcwFilterStats(accepted, filteredBadList, badLength, checksumFail, zeroCount, merged, held, verified + 1, linksDetected, linksRemoved, shadowDivergences, evictions); }

  /** Retourne un nouveau snapshot avec {@code linksDetected} incrémenté. */
  public DcwFilterStats withLinksDetected() { return new DcwFilterStats(accepted, filteredBadList, badLength, checksumFail, zeroCount, merged, held, verified, linksDetected + 1, linksRemoved, shadowDivergences, evictions); }

  /** Retourne un nouveau snapshot avec {@code linksRemoved} incrémenté. */
  public DcwFilterStats withLinksRemoved() { return new DcwFilterStats(accepted, filteredBadList, badLength, checksumFail, zeroCount, merged, held, verified, linksDetected, linksRemoved + 1, shadowDivergences, evictions); }

  /** Retourne un nouveau snapshot avec {@code shadowDivergences} incrémenté. */
  public DcwFilterStats withShadowDivergence() { return new DcwFilterStats(accepted, filteredBadList, badLength, checksumFail, zeroCount, merged, held, verified, linksDetected, linksRemoved, shadowDivergences + 1, evictions); }

  /** Retourne un nouveau snapshot avec {@code evictions} incrémenté. */
  public DcwFilterStats withEvictions() { return new DcwFilterStats(accepted, filteredBadList, badLength, checksumFail, zeroCount, merged, held, verified, linksDetected, linksRemoved, shadowDivergences, evictions + 1); }
}
