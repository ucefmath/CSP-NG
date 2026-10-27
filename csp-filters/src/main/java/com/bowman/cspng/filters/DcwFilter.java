package com.bowman.cspng.filters;

import com.bowman.cspng.cache.DcwValidator;
import com.bowman.cspng.domain.CwReply;

import java.util.*;

/**
 * Filtre DCW avance stateful (single-writer).
 *
 * <p>Pas de {@code synchronized}, lock, wait. Tous les TTL metier sont
 * implementes via {@link FilterContext.FilterScheduler}. Toutes les
 * collections sont bornees avec eviction annulant les taches planifiees.
 *
 * <h3>Shadow mode</h3>
 * En mode shadow, le filtre evalue/compte mais ne DROP/HOLD/REWRITE
 * jamais : retourne toujours {@code Accept} de la reponse ORIGINALE.
 * Les methodes internes retournent toujours la decision production ;
 * {@code evaluateReply} intercepte et transforme en shadow.
 */
public class DcwFilter implements CwFilter {

  private static final HexFormat HF = HexFormat.of();

  private final DcwFilterConfig config;
  private final ServiceDirectory serviceDirectory;
  private final Set<String> badDcwDigests;

  // --- mutable state (single-writer) ---
  private final BMap<String, ContinuityEntry> zeroedReplyMap;
  private final BMap<String, VerifyEntry> verifyStateMap;
  private final BMap<String, ReplyEntry> replyMap;
  private final BMap<String, Set<String>> sidLinksMap;
  private final BMap<String, MonitoredEntry> monitoredSidsMap;
  private final BMap<String, StrikeEntry> invalidLinksMap;

  private volatile DcwFilterStats stats = DcwFilterStats.ZERO;

  /** Listener appele quand les groupes de liens changent (detect/remove/restore).
   *  Jamais appele a chaque reply. Toujours depuis le meme contexte single-writer. */
  private volatile Runnable linkChangeListener;

  public DcwFilter(DcwFilterConfig config, ServiceDirectory serviceDirectory) {
    this.config = config;
    if (config.detectLinks() && serviceDirectory == null) {
      throw new IllegalArgumentException("ServiceDirectory required when detectLinks enabled");
    }
    this.serviceDirectory = serviceDirectory;
    this.badDcwDigests = config.badDcwDigests();
    int cap = Math.max(100, config.maxEntries());
    int smallCap = Math.max(10, cap / 10);
    this.zeroedReplyMap = boundedLinkedMap(smallCap);
    this.verifyStateMap = boundedLinkedMap(cap);
    this.replyMap = boundedLinkedMap(cap);
    this.sidLinksMap = boundedLinkedMap(cap);
    this.monitoredSidsMap = boundedLinkedMap(cap);
    this.invalidLinksMap = boundedLinkedMap(cap);
  }

  @Override public String name() { return "dcw"; }
  @Override public int order() { return 0; }

  @Override
  public boolean requiresMultipleReplies() {
    return config.verifyReplies();
  }

  public DcwFilterConfig config() { return config; }
  public DcwFilterStats stats() { return stats; }

  /**
   * Enregistre un listener appele (meme contexte single-writer) quand les
   * groupes de liens changent (detection, suppression, restoration).
   * Jamais appele a chaque reply. Null pour desactiver.
   *
   * @param listener callback execute dans le meme contexte (peut etre null)
   */
  public void setLinkChangeListener(Runnable listener) {
    this.linkChangeListener = listener;
  }

  // ==================== evaluateReply ====================

  @Override
  public FilterDecision evaluateReply(FilterContext context, CwReply reply) {
    if (!config.enabled()) return new FilterDecision.Accept(reply);

    if (!config.profiles().isEmpty()
        && !config.profiles().contains(context.profile())) {
      return new FilterDecision.Accept(reply);
    }

    byte[] cw = reply.cw();
    int sid = reply.key() != null ? reply.key().serviceId() : 0;
    String profile = context.profile();
    CwReply originalReply = reply;

    // --- 1. longueur ---
    if (cw.length != 16) {
      stats = stats.withBadLength();
      return shadowWrap(FilterDecision.Drop.INSTANCE, originalReply);
    }

    // --- 2. checksum ---
    if (!DcwValidator.checksumDcw(cw)) {
      stats = stats.withChecksumFail();
      return shadowWrap(FilterDecision.Drop.INSTANCE, originalReply);
    }

    // --- 3. bad-dcw list ---
    if (isBadDcw(cw)) {
      stats = stats.withFilteredBadList();
      return shadowWrap(FilterDecision.Drop.INSTANCE, originalReply);
    }

    // --- 4. detectLinks (avant forceContinuity comme legacy) ---
    if (config.detectLinks() && sid != 0) {
      detectLink(context, reply, sid, profile);
    }

    // --- 5. forceContinuity ---
    CwReply mergedReply = null;
    if (config.forceContinuity() && sid != 0) {
      mergedReply = forceContinuity(context, reply, sid, profile);
    }

    // --- 6. update replyMap with ORIGINAL digest (detectLinks order avant continuity) ---
    if (config.detectLinks() || config.verifyReplies()) {
      trackReply(context, reply, sid, profile);
    }

    CwReply effectiveReply = mergedReply != null ? mergedReply : reply;

    // --- 7. verifyReplies ---
    if (config.verifyReplies()) {
      FilterDecision vd = handleVerifyReply(context, effectiveReply, profile);
      if (vd instanceof FilterDecision.Hold
          || vd instanceof FilterDecision.ReleasePrevious) {
        return shadowWrap(vd, originalReply);
      }
      // Accept (impossible now since handleVerifyReply never returns Accept)
      return vd;
    }

    // --- accept / rewrite ---
    if (mergedReply != null) {
      return shadowWrap(new FilterDecision.Rewrite(mergedReply), originalReply);
    }

    stats = stats.withAccepted();
    return new FilterDecision.Accept(originalReply);
  }

  // ==================== shadow wrap ====================

  /**
   * En mode production, retourne la decision. En mode shadow, retourne
   * toujours {@code Accept} de la reponse originale avec divergence comptee.
   */
  private FilterDecision shadowWrap(FilterDecision decision, CwReply original) {
    if (config.shadowMode()) {
      stats = stats.withShadowDivergence();
      stats = stats.withAccepted();
      return new FilterDecision.Accept(original);
    }
    // production
    if (decision instanceof FilterDecision.Drop) return decision;
    if (decision instanceof FilterDecision.Hold) { stats = stats.withHeld(); return decision; }
    if (decision instanceof FilterDecision.ReleasePrevious) { stats = stats.withVerified(); return decision; }
    if (decision instanceof FilterDecision.Rewrite) { stats = stats.withAccepted(); return decision; }
    if (decision instanceof FilterDecision.Accept) { stats = stats.withAccepted(); return decision; }
    return decision;
  }

  // ==================== base ====================

  private boolean isBadDcw(byte[] cw) {
    String digest = hexDigest(cw);
    if (badDcwDigests.contains(digest)) return true;
    if (config.zeroCounting() && DcwValidator.hasFiveZeroes(cw)) {
      stats = stats.withZeroCount();
      return true;
    }
    return false;
  }

  // ==================== forceContinuity ====================

  private CwReply forceContinuity(FilterContext context,
                                   CwReply msg, int sid, String profile) {
    byte[] cw = msg.cw();
    if (!hasZeroDcwCompat(cw)) return null;

    String key = (msg.commandTag() == 0x81 ? "81" : "80") + ":" + sid + ":" + profile;
    String prevKey = (msg.commandTag() == 0x81 ? "80" : "81") + ":" + sid + ":" + profile;

    ContinuityEntry oldEntry = zeroedReplyMap.get(key);
    if (oldEntry != null && oldEntry.cleanup != null) oldEntry.cleanup.cancel();

    long now = context.timestamp();
    var cleanupTask = context.scheduler().schedule(12_000,
        () -> removeIfSame(key, zeroedReplyMap.get(key)));
    zeroedReplyMap.put(key, new ContinuityEntry(cw.clone(), now, cleanupTask));

    ContinuityEntry prev = zeroedReplyMap.get(prevKey);
    if (prev != null) {
      long age = now - prev.timestamp;
      if (age < 12_000) {
        byte[] merged = cw.clone();
        mergeZeroed(merged, prev.rawCw);
        stats = stats.withMerged();
        return new CwReply(msg.key(), msg.commandTag(), merged,
            msg.connectorName(), msg.originAddress(), msg.timestamp());
      }
    }
    return null;
  }

  private boolean hasZeroDcwCompat(byte[] data) {
    if (config.continuityMode() == DcwFilterConfig.ContinuityMode.STRICT) {
      return DcwValidator.hasZeroDcw(data);
    }
    return data[0] + data[1] + data[3] == 0 || data[9] + data[10] + data[11] == 0;
  }

  private static void mergeZeroed(byte[] curr, byte[] prev) {
    for (int i = 0; i < curr.length; i++) curr[i] |= prev[i];
  }

  // ==================== verifyReplies ====================

  /** Toujours retourne la decision production (HOLD, ReleasePrevious). */
  private FilterDecision handleVerifyReply(FilterContext context,
                                            CwReply reply, String profile) {
    byte[] cw = reply.cw();
    String cwDigest = hexDigest(cw);
    int dataHash = context.request().key().dataHash();
    String key = profile + ":" + dataHash + ":" + cwDigest;

    VerifyEntry existing = verifyStateMap.get(key);
    if (existing == null) {
      var timer = context.scheduler().schedule(20_000,
          () -> removeIfSame(key, verifyStateMap.get(key)));
      verifyStateMap.put(key, new VerifyEntry(reply, context.connectorName(),
          context.timestamp(), timer));
      return FilterDecision.Hold.INSTANCE;
    }

    if (context.connectorName().equals(existing.connectorRef)) {
      return FilterDecision.Hold.INSTANCE;
    }

    // different connector -> ReleasePrevious
    existing.cleanup.cancel();
    verifyStateMap.remove(key);
    return new FilterDecision.ReleasePrevious(existing.reply, existing.connectorRef);
  }

  // ==================== detectLinks ====================

  private void detectLink(FilterContext context, CwReply msg,
                           int sid, String profile) {
    String msgId = sidKey(sid, profile);
    String cwDigest = hexDigest(msg.cw());

    if (sidLinksMap.containsKey(msgId)) {
      checkLinkInvalidation(context, msgId, cwDigest, sid, profile);
    }

    String replyKey = replyMapKey(profile, cwDigest);
    ReplyEntry prevEntry = replyMap.get(replyKey);
    if (prevEntry != null && prevEntry.sid != sid
        && profile.equals(prevEntry.profile)) {

      if (!isTvService(profile, sid) || !isTvService(profile, prevEntry.sid)) {
        return;
      }

      String prevId = sidKey(prevEntry.sid, prevEntry.profile);
      Set<String> links = sidLinksMap.get(prevId);
      if (links == null) links = sidLinksMap.get(msgId);
      if (links == null) links = new LinkedHashSet<>();
      int before = links.size();
      links.add(prevId);
      links.add(msgId);
      sidLinksMap.put(prevId, links);
      sidLinksMap.put(msgId, links);
      if (links.size() > before) {
        stats = stats.withLinksDetected();
        notifyLinkChange();
      }
    }
  }

  private void checkLinkInvalidation(FilterContext context, String msgId,
                                      String cwDigest, int sid, String profile) {
    MonitoredEntry oldMon = monitoredSidsMap.get(msgId);
    if (oldMon != null && oldMon.cleanup != null) oldMon.cleanup.cancel();

    var monCleanup = context.scheduler().schedule(20_000,
        () -> monitoredSidsMap.remove(msgId));
    monitoredSidsMap.put(msgId, new MonitoredEntry(sid, profile,
        cwDigest, context.timestamp(), monCleanup));

    Set<String> linkedIds = sidLinksMap.get(msgId);
    if (linkedIds == null || linkedIds.size() <= 1) return;

    var copy = List.copyOf(linkedIds);
    for (String testId : copy) {
      if (testId.equals(msgId)) continue;
      MonitoredEntry otherMon = monitoredSidsMap.get(testId);
      if (otherMon == null) continue;
      long delta = context.timestamp() - otherMon.timestamp;
      if (delta < 2000) {
        if (!cwDigest.equals(otherMon.cwDigest)) {
          processLinkMismatch(context, msgId, testId, linkedIds,
              profile, sid, otherMon);
        }
      }
    }
  }

  private void processLinkMismatch(FilterContext context, String msgId,
                                    String testId, Set<String> linkedIds,
                                    String profile, int sid, MonitoredEntry other) {
    String link = buildLinkString(profile, sid, other.profile, other.sid);

    StrikeEntry existing = invalidLinksMap.get(link);
    if (existing != null && (context.timestamp() - existing.timestamp) < 20_000) {
      // second strike -> remove link
      existing.cleanup.cancel();
      invalidLinksMap.remove(link);

      // Remove testId from the shared group set AND from sidLinksMap
      linkedIds.remove(testId);
      sidLinksMap.remove(testId); // always remove — avoids orphan key

      if (linkedIds.size() <= 1) {
        // Group collapsed: remove all remaining key references
        for (String id : List.copyOf(linkedIds)) {
          sidLinksMap.remove(id);
        }
      } else {
        // Group of 3+: reassign the shared set to remaining members
        for (String id : linkedIds) {
          sidLinksMap.put(id, linkedIds);
        }
      }
      stats = stats.withLinksRemoved();
      notifyLinkChange();
    } else {
      // first strike (or expired strike)
      if (existing != null) existing.cleanup.cancel();
      var strikeCleanup = context.scheduler().schedule(20_000,
          () -> invalidLinksMap.remove(link));
      invalidLinksMap.put(link, new StrikeEntry(context.timestamp(), strikeCleanup));
    }
  }

  private static String buildLinkString(String p1, int s1, String p2, int s2) {
    var sj = new StringJoiner("<->");
    if (p1.compareTo(p2) < 0 || (p1.equals(p2) && s1 < s2)) {
      sj.add(Integer.toHexString(s1) + ":" + p1);
      sj.add(Integer.toHexString(s2) + ":" + p2);
    } else {
      sj.add(Integer.toHexString(s2) + ":" + p2);
      sj.add(Integer.toHexString(s1) + ":" + p1);
    }
    return sj.toString();
  }

  /** Stocke le digest du CW ORIGINAL (avant forceContinuity). */
  private void trackReply(FilterContext context, CwReply msg,
                           int sid, String profile) {
    String cwDigest = hexDigest(msg.cw());
    String key = replyMapKey(profile, cwDigest);
    ReplyEntry old = replyMap.get(key);
    if (old != null && old.cleanup != null) old.cleanup.cancel();
    var cleanup = context.scheduler().schedule(20_000,
        () -> removeIfSame(key, replyMap.get(key)));
    replyMap.put(key, new ReplyEntry(sid, profile, cwDigest,
        context.timestamp(), cleanup));
  }

  private <V> void removeIfSame(String key, V entry) {
    if (entry == null) return;
    if (entry instanceof ContinuityEntry) {
      zeroedReplyMap.remove(key, entry);
    } else if (entry instanceof VerifyEntry) {
      verifyStateMap.remove(key, entry);
    } else if (entry instanceof ReplyEntry) {
      replyMap.remove(key, entry);
    }
  }

  private boolean isTvService(String profile, int sid) {
    return serviceDirectory.lookup(profile, sid)
        .map(ServiceDirectory.ServiceDescriptor::tv)
        .orElse(false);
  }

  // ==================== snapshot / restore ====================

  /**
   * Snapshot immuable des groupes de liens (service refs).
   *
   * <p><b>Doit etre appele via le contexte single-writer
   * ({@code CwsConnectorManager.runOnFilterContext}).</b>
   * L'appel direct depuis un autre thread/contexte produit un snapshot
   * potentiellement incoherent.
   *
   * @return snapshot immuable des groupes (jamais null)
   */
  public Set<Set<String>> linkGroupsSnapshot() {
    Set<Set<String>> result = new LinkedHashSet<>();
    Set<Set<String>> seen = new HashSet<>();
    for (Set<String> group : sidLinksMap.values()) {
      if (group.size() >= 2 && seen.add(group)) {
        result.add(Set.copyOf(group));
      }
    }
    return Collections.unmodifiableSet(result);
  }

  /**
   * Restaure les groupes de liens depuis un snapshot externe (ex. persistance).
   *
   * <p>Appelé seulement depuis le contexte single-writer (via
   * {@code CwsConnectorManager.runOnFilterContext}). Valide chaque ref
   * via {@link ServiceDirectory} (service TV, même profil), ignore
   * silencieusement les refs invalides et les groupes de taille &lt; 2.
   * Les groupes résultants sont immuables et bornés.
   *
   * <p>Si {@code detectLinks} n'est pas activé, ne fait rien.
   *
   * @param groups groupes à restaurer (non-null)
   * @throws IllegalStateException si {@code detectLinks} n'est pas activé
   *         (la restauration n'a pas de sens sans la fonctionnalité)
   */
  public void restoreLinkGroups(Set<Set<String>> groups) {
    if (!config.detectLinks()) {
      throw new IllegalStateException(
          "Cannot restore link groups: detectLinks is not enabled");
    }
    Objects.requireNonNull(groups, "groups must not be null");

    // nettoyage de l'état existant
    sidLinksMap.clear();
    invalidLinksMap.clear();
    monitoredSidsMap.clear();

    int restored = 0;
    int maxGroups = Math.max(10, config.maxEntries() / 10);

    for (Set<String> group : groups) {
      if (restored >= maxGroups) break;
      if (group == null || group.size() < 2) continue;

      // valider les refs : format sidHex:profile, service TV, même profil
      String commonProfile = null;
      Set<String> validRefs = new LinkedHashSet<>();
      boolean groupValid = true;

      for (String ref : group) {
        if (ref == null || ref.isBlank()) { groupValid = false; break; }
        int colon = ref.indexOf(':');
        if (colon <= 0 || colon >= ref.length() - 1) { groupValid = false; break; }

        String sidHex = ref.substring(0, colon);
        String profile = ref.substring(colon + 1);

        int sid;
        try {
          sid = Integer.parseInt(sidHex, 16);
        } catch (NumberFormatException e) {
          groupValid = false; break;
        }

        if (commonProfile == null) {
          commonProfile = profile;
        } else if (!commonProfile.equals(profile)) {
          groupValid = false; break;
        }

        if (!isTvService(profile, sid)) {
          groupValid = false; break;
        }

        validRefs.add(ref);
      }

      if (!groupValid || validRefs.size() < 2) continue;

      // insérer dans sidLinksMap — utiliser un LinkedHashSet MUTABLE
      // partagé par toutes les clés du groupe (cohérent avec detectLinks
      // qui mute le même set via linkedIds.remove/add). Le snapshot
      // linkGroupsSnapshot() encapsule l'immuabilité à la lecture.
      for (String ref : validRefs) {
        sidLinksMap.put(ref, validRefs);
      }
      restored++;
    }

    if (restored > 0) {
      for (int i = 0; i < restored; i++) {
        stats = stats.withLinksDetected();
      }
      notifyLinkChange();
    }
  }

  // ==================== link change notification ====================

  private void notifyLinkChange() {
    Runnable l = linkChangeListener;
    if (l != null) {
      try {
        l.run();
      } catch (Exception e) {
        // ne jamais casser le datapath
      }
    }
  }

  // ==================== keys ====================

  static String replyMapKey(String profile, String cwDigest) {
    return profile + ":" + cwDigest;
  }

  static String sidKey(int sid, String profile) {
    return Integer.toHexString(sid) + ":" + profile;
  }

  // ==================== bounded maps ====================

  private <V> BMap<String, V> boundedLinkedMap(int max) {
    return new BMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
        if (size() > max) {
          cancelEntryTask(eldest.getValue());
          stats = stats.withEvictions();
          return true;
        }
        return false;
      }
      private void cancelEntryTask(Object value) {
        if (value instanceof ContinuityEntry e && e.cleanup != null) e.cleanup.cancel();
        else if (value instanceof VerifyEntry e && e.cleanup != null) e.cleanup.cancel();
        else if (value instanceof ReplyEntry e && e.cleanup != null) e.cleanup.cancel();
        else if (value instanceof MonitoredEntry e && e.cleanup != null) e.cleanup.cancel();
        else if (value instanceof StrikeEntry e && e.cleanup != null) e.cleanup.cancel();
        else if (value instanceof Set<?> set) {
          for (Object member : set) {
            if (member instanceof String s) {
              MonitoredEntry me = monitoredSidsMap.get(s);
              if (me != null && me.cleanup != null) me.cleanup.cancel();
              monitoredSidsMap.remove(s);
            }
          }
        }
      }
    };
  }

  static class BMap<K, V> extends LinkedHashMap<K, V> {
    BMap(int initialCapacity, float loadFactor, boolean accessOrder) {
      super(initialCapacity, loadFactor, accessOrder);
    }
  }

  // ==================== digest ====================

  static String hexDigest(byte[] data) {
    byte[] digest = DcwFilterConfig.sha256(data);
    return HF.formatHex(digest);
  }

  // ==================== entry types ====================

  record ContinuityEntry(byte[] rawCw, long timestamp,
                         FilterContext.FilterScheduler.ScheduledTask cleanup) {
    ContinuityEntry { rawCw = rawCw.clone(); }
  }

  record VerifyEntry(CwReply reply, String connectorRef, long timestamp,
                     FilterContext.FilterScheduler.ScheduledTask cleanup) {}

  record ReplyEntry(int sid, String profile, String cwDigest, long timestamp,
                    FilterContext.FilterScheduler.ScheduledTask cleanup) {}

  record MonitoredEntry(int sid, String profile, String cwDigest, long timestamp,
                        FilterContext.FilterScheduler.ScheduledTask cleanup) {}

  record StrikeEntry(long timestamp,
                     FilterContext.FilterScheduler.ScheduledTask cleanup) {}
}
