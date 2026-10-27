package com.bowman.cspng.filters;

import com.bowman.cspng.cache.DcwValidator;
import com.bowman.cspng.domain.dcw.DcwDigest;
import com.bowman.cspng.domain.dcw.DcwRuleScope;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Observateur de liens candidats inter-CAID / inter-provider.
 *
 * <p>Règles d'observation strictes :
 * <ul>
 *   <li>Scope complet : profile, CAID, provider, SID (UNKNOWN_PROVIDER exclu)</li>
 *   <li>Seules les CW valides, complètes (non demi-zéro) et admises sont considérées</li>
 *   <li>L'accumulation de preuves exige des cycles ECM distincts et des parités alternées</li>
 *   <li>Les duplicats / retransmissions n'incrémentent jamais le compteur de preuves</li>
 *   <li>Statut d'observation uniquement : jamais de confirmation automatique ni de réutilisation cache</li>
 *   <li>Purge TTL et bornes cardinales concurrentes</li>
 * </ul>
 */
public class InterProviderLinkObserver {

  private static final long RECENT_REPLY_TTL_MS = 2_000L;
  private static final long CANDIDATE_LINK_TTL_MS = 600_000L; // 10 min

  public record ServiceScope(String profile, int caid, int provider, int sid) {
    public ServiceScope {
      profile = profile != null ? profile.trim() : "";
    }
    @Override
    public String toString() {
      return profile + ":0x" + Integer.toHexString(caid) + ":0x" + Integer.toHexString(provider) + ":0x" + Integer.toHexString(sid);
    }
  }

  public static final class CandidateLink {
    private final ServiceScope scopeA;
    private final ServiceScope scopeB;
    private int distinctEvidenceCount = 0;
    private long firstObservedMs;
    private long lastObservedMs;
    private int lastTagA = -1;
    private int lastHashA = 0;
    private int lastTagB = -1;
    private int lastHashB = 0;

    public CandidateLink(ServiceScope a, ServiceScope b, long now) {
      if (a.toString().compareTo(b.toString()) <= 0) {
        this.scopeA = a;
        this.scopeB = b;
      } else {
        this.scopeA = b;
        this.scopeB = a;
      }
      this.firstObservedMs = now;
      this.lastObservedMs = now;
    }

    public ServiceScope scopeA() { return scopeA; }
    public ServiceScope scopeB() { return scopeB; }
    public int distinctEvidenceCount() { return distinctEvidenceCount; }
    public long firstObservedMs() { return firstObservedMs; }
    public long lastObservedMs() { return lastObservedMs; }
    public String state() { return "OBSERVATION"; }
    public boolean isConfirmed() { return false; } // Strict invariant : jamais confirmé automatiquement

    public boolean isExpired(long now) {
      return (now - lastObservedMs) > CANDIDATE_LINK_TTL_MS;
    }

    public synchronized void recordMatch(int tagA, int hashA, int tagB, int hashB, long now) {
      if (distinctEvidenceCount == 0) {
        distinctEvidenceCount = 1;
        lastTagA = tagA;
        lastHashA = hashA;
        lastTagB = tagB;
        lastHashB = hashB;
      } else {
        boolean isDistinctA = (lastTagA != tagA) || (lastHashA != hashA);
        boolean isDistinctB = (lastTagB != tagB) || (lastHashB != hashB);
        if (isDistinctA && isDistinctB) {
          distinctEvidenceCount++;
          lastTagA = tagA;
          lastHashA = hashA;
          lastTagB = tagB;
          lastHashB = hashB;
        }
      }
      this.lastObservedMs = now;
    }
  }

  private record RecentReply(
      ServiceScope scope,
      int commandTag,
      int dataHash,
      String cwDigest,
      long timestamp
  ) {}

  private final int maxCandidates;
  private final java.util.function.Supplier<Long> clock;
  private final ConcurrentHashMap<String, CandidateLink> candidateMap = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, RecentReply> recentByDigest = new ConcurrentHashMap<>();

  public InterProviderLinkObserver(int maxCandidates) {
    this(maxCandidates, System::currentTimeMillis);
  }

  public InterProviderLinkObserver(int maxCandidates, java.util.function.Supplier<Long> clock) {
    this.maxCandidates = Math.max(50, maxCandidates);
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public void observeReply(
      String profile, int caid, int provider, int sid,
      int commandTag, int dataHash, byte[] cw, long timestamp
  ) {
    if (cw == null || cw.length != 16) return;
    // UNKNOWN_PROVIDER (-1) ne peut pas servir de preuve pour les liens inter-providers
    if (provider == DcwRuleScope.UNKNOWN_PROVIDER) return;
    // Les demi-zéros ne sont pas des preuves d'équivalence
    if (DcwValidator.hasZeroDcw(cw)) return;

    // Purge périodique
    if (recentByDigest.size() > 500) {
      recentByDigest.entrySet().removeIf(e -> (timestamp - e.getValue().timestamp()) > RECENT_REPLY_TTL_MS);
    }
    if (candidateMap.size() >= maxCandidates) {
      candidateMap.entrySet().removeIf(e -> e.getValue().isExpired(timestamp));
    }

    ServiceScope currentScope = new ServiceScope(profile, caid, provider, sid);
    String digest = DcwDigest.digestSha256Hex(cw);

    RecentReply other = recentByDigest.get(digest);
    if (other != null && !other.scope().equals(currentScope)
        && Objects.equals(other.scope().profile(), profile)
        && (timestamp - other.timestamp()) < RECENT_REPLY_TTL_MS) {

      String linkKey = buildLinkKey(currentScope, other.scope());
      CandidateLink link = candidateMap.computeIfAbsent(linkKey, k -> {
        if (candidateMap.size() >= maxCandidates) return null;
        return new CandidateLink(currentScope, other.scope(), timestamp);
      });

      if (link != null) {
        ServiceScope s1 = currentScope;
        int t1 = commandTag;
        int h1 = dataHash;

        ServiceScope s2 = other.scope();
        int t2 = other.commandTag();
        int h2 = other.dataHash();

        if (s1.toString().compareTo(s2.toString()) > 0) {
          int tmpT = t1; t1 = t2; t2 = tmpT;
          int tmpH = h1; h1 = h2; h2 = tmpH;
        }
        link.recordMatch(t1, h1, t2, h2, timestamp);
      }
    }

    recentByDigest.put(digest, new RecentReply(currentScope, commandTag, dataHash, digest, timestamp));
  }

  private static String buildLinkKey(ServiceScope a, ServiceScope b) {
    if (a.toString().compareTo(b.toString()) <= 0) {
      return a + "<->" + b;
    } else {
      return b + "<->" + a;
    }
  }

  public List<CandidateLink> candidates() {
    long now = clock.get();
    candidateMap.entrySet().removeIf(e -> e.getValue().isExpired(now));
    return List.copyOf(candidateMap.values());
  }

  public void clear() {
    candidateMap.clear();
    recentByDigest.clear();
  }
}
