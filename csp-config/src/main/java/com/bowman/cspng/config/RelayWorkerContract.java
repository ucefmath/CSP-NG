package com.bowman.cspng.config;

import java.util.List;

/**
 * Contrat canal controle worker HTTPS (ADR 0002 §5, implémentation W4).
 * Worker SANS acces PG. TTL configurable bornee, jamais lease 2s en dur.
 */
public final class RelayWorkerContract {

  public static final long TTL_MIN_MS = 5_000L;
  public static final long TTL_MAX_MS = 300_000L;
  public static final long TTL_DEFAULT_MS = 30_000L;

  private RelayWorkerContract() {}

  public static long normalizeTtl(long ttlMs) {
    if (ttlMs < TTL_MIN_MS || ttlMs > TTL_MAX_MS)
      throw new IllegalArgumentException("worker-config-ttl must be 5000..300000");
    return ttlMs;
  }

  /** Pair d'echange (UDP ou CSPv2/TCP) : endpoint assigne explicitement, jamais decouvert. */
  public record PeerView(String host, int port) {
    public PeerView {
      if (host == null || host.isBlank() || host.length() > 253)
        throw new IllegalArgumentException("peer host required (<=253)");
      if (port < 1 || port > 65535) throw new IllegalArgumentException("peer port 1..65535");
    }
  }

  /** Visibilite d'un node distant pour la policy worker (utilisabilite vue centrale). */
  public record NodeView(String nodeName, boolean enabled, boolean authorized, boolean revoked) {
    public NodeView {
      if (nodeName == null || !nodeName.matches("^[a-z0-9][a-z0-9._-]{0,62}$"))
        throw new IllegalArgumentException("nodeName invalid");
    }
  }

  public record CacheView(String name, String kind, boolean enabled, long maxAgeMs, int maxEntries, int maxInflight,
                          String nodeName, List<PeerView> peers) {
    public CacheView {
      if (name == null || !name.matches("^[A-Za-z0-9_.-]{1,64}$"))
        throw new IllegalArgumentException("cache name invalid");
      if (!"local".equals(kind) && !"node".equals(kind) && !"udp".equals(kind))
        throw new IllegalArgumentException("kind must be local|node|udp");
      if ("node".equals(kind) && (nodeName == null || nodeName.isBlank()))
        throw new IllegalArgumentException("node cache requires REMOTE nodeName (never self)");
      peers = List.copyOf(peers == null ? List.of() : peers);
      if (peers.size() > 16) throw new IllegalArgumentException("peers bounded to 16");
    }
    /** Compat sans node/peers (tests, caches locaux). */
    public CacheView(String name, String kind, boolean enabled, long maxAgeMs, int maxEntries, int maxInflight) {
      this(name, kind, enabled, maxAgeMs, maxEntries, maxInflight, null, List.of());
    }
  }

  public record LinkView(long listenerId, String cacheName, boolean canConsult, boolean canFeed, boolean canExport, boolean enabled) {}

  /**
   * Listener a servir : parametres session搬运 depuis la ligne centrale
   * (le worker n'a aucun acces DB). Protocoles servis v1 : cccam, cs378x.
   */
  public record ListenerView(long listenerId, String profile, String protocol, int port,
                             String bindAddress, String cardMode, int reshareCap,
                             int maxCards, int maxInflight, int maxPerIp, int idleTimeoutSec,
                             String allowList, String denyList) {
    public ListenerView {
      if (listenerId < 1) throw new IllegalArgumentException("listenerId invalid");
      if (profile == null || profile.isBlank()) throw new IllegalArgumentException("profile required");
      if (protocol == null || protocol.isBlank()) throw new IllegalArgumentException("protocol required");
      if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range");
      if (bindAddress == null || bindAddress.isBlank()) bindAddress = "0.0.0.0";
      if (cardMode == null || cardMode.isBlank()) cardMode = "manual";
    }

    /** Compat sans parametres session (defauts centraux). */
    public ListenerView(long listenerId, String profile, String protocol, int port) {
      this(listenerId, profile, protocol, port, "0.0.0.0", "manual", 0, 512, 32, 5, 60, null, null);
    }

    /** Compat sans ACL IP. */
    public ListenerView(long listenerId, String profile, String protocol, int port,
                        String bindAddress, String cardMode, int reshareCap,
                        int maxCards, int maxInflight, int maxPerIp, int idleTimeoutSec) {
      this(listenerId, profile, protocol, port, bindAddress, cardMode, reshareCap,
          maxCards, maxInflight, maxPerIp, idleTimeoutSec, null, null);
    }
  }

  /**
   * Connecteur autorise pour worker : jamais de secret en valeur, uniquement
   * des references {@code env:/file:} + hotes/ports non sensibles. Le worker
   * resout ses secrets localement (fail-closed si irresoluble).
   */
  /**
   * Connecteur amont assigne : parametres fideles (proxy/timeouts/versions/reshare),
   * secrets en refs seules. Tout connecteur non liste est interdit au worker.
   */
  public record ConnectorView(String name, String protocol, String profile, boolean enabled,
                              String host, int port, String username, String passwordRef,
                              String desKeyRef, String proxyName, int connectTimeoutMs,
                              String cccamVersion, Integer reshareCap, Boolean cardDiscovery,
                              Integer reshareGrant, String newcamdDialect, Integer cacheexMode,
                              String newcamdPasswordEncoding,
                              String cacheexFilter, Integer cacheexMaxSend, Integer cacheexMaxHop) {
    /** Compat avec l'ancienne signature (V15 sans champs cacheex avancés). */
    public ConnectorView(String name, String protocol, String profile, boolean enabled,
                         String host, int port, String username, String passwordRef,
                         String desKeyRef, String proxyName, int connectTimeoutMs,
                         String cccamVersion, Integer reshareCap, Boolean cardDiscovery,
                         Integer reshareGrant, String newcamdDialect, Integer cacheexMode,
                         String newcamdPasswordEncoding) {
      this(name, protocol, profile, enabled, host, port, username, passwordRef, desKeyRef,
          proxyName, connectTimeoutMs, cccamVersion, reshareCap, cardDiscovery, reshareGrant,
          newcamdDialect, cacheexMode, newcamdPasswordEncoding, null, null, null);
    }
    /** Compat sans newcamdPasswordEncoding (défaut plaintext). */
    public ConnectorView(String name, String protocol, String profile, boolean enabled,
                         String host, int port, String username, String passwordRef,
                         String desKeyRef, String proxyName, int connectTimeoutMs,
                         String cccamVersion, Integer reshareCap, Boolean cardDiscovery,
                         Integer reshareGrant, String newcamdDialect, Integer cacheexMode) {
      this(name, protocol, profile, enabled, host, port, username, passwordRef, desKeyRef,
          proxyName, connectTimeoutMs, cccamVersion, reshareCap, cardDiscovery, reshareGrant,
          newcamdDialect, cacheexMode, "plaintext");
    }

    /** Compat sans parametres avances. */
    public ConnectorView(String name, String protocol, String profile, boolean enabled,
                         String host, int port, String username, String passwordRef) {
      this(name, protocol, profile, enabled, host, port, username, passwordRef, null,
          null, 5000, null, null, null, null, null, null, "plaintext");
    }

    /** Compat sans proxy/comportement. */
    public ConnectorView(String name, String protocol, String profile, boolean enabled,
                         String host, int port, String username, String passwordRef,
                         String desKeyRef) {
      this(name, protocol, profile, enabled, host, port, username, passwordRef, desKeyRef,
          null, 5000, null, null, null, null, null, null, "plaintext");
    }
  }

  /** Proxy SOCKS5 : adresse + auth par ref seule (jamais de valeur). */
  public record ProxyView(String name, boolean enabled, String type, String host, int port,
                          String username, String passwordRef) {}

  /**
   * ACL utilisateur sanitisee : jamais de password_hash ni secret en valeur.
   * Profils/connecteurs/services restreints aux affectations du worker.
   */
  public record UserAclView(String userName, boolean enabled, List<String> profiles,
                            List<String> allowedConnectors,
                            java.util.Map<String, List<Integer>> allowedServices,
                            java.util.Map<String, List<Integer>> blockedServices) {
    public UserAclView {
      if (userName == null || userName.isBlank()) throw new IllegalArgumentException("userName required");
      profiles = List.copyOf(profiles == null ? List.of() : profiles);
      allowedConnectors = List.copyOf(allowedConnectors == null ? List.of() : allowedConnectors);
      allowedServices = allowedServices == null ? java.util.Map.of() : java.util.Map.copyOf(allowedServices);
      blockedServices = blockedServices == null ? java.util.Map.of() : java.util.Map.copyOf(blockedServices);
    }

    /** Compat profils seuls (anciens tests). */
    public UserAclView(String userName, boolean enabled, List<String> profiles) {
      this(userName, enabled, profiles, List.of(), java.util.Map.of(), java.util.Map.of());
    }
  }

  /** Secret protocole : reference {@code env:/file:} seule, jamais la valeur. */
  public record UserSecretView(String userName, String protocol, String secretRef,
                               long secretVersion, boolean enabled,
                               Integer cacheexMode, Integer cacheexMaxHop, String cacheexFilter) {
    public UserSecretView {
      if (userName == null || userName.isBlank()) throw new IllegalArgumentException("userName required");
      if (!"cccam".equals(protocol) && !"cs378x".equals(protocol))
        throw new IllegalArgumentException("protocol must be cccam|cs378x");
      if (secretRef == null || !(secretRef.startsWith("env:") || secretRef.startsWith("file:")))
        throw new IllegalArgumentException("secretRef must be env:/file: (ref only, never value)");
      if (cacheexMode != null && cacheexMode != 0 && cacheexMode != 2 && cacheexMode != 3)
        throw new IllegalArgumentException("cacheexMode must be 0, 2 or 3");
      if (cacheexMaxHop != null && (cacheexMaxHop < 0 || cacheexMaxHop > 10))
        throw new IllegalArgumentException("cacheexMaxHop must be 0..10");
    }

    /** Compat sans politique cacheex (pre-V15 — off). */
    public UserSecretView(String userName, String protocol, String secretRef,
                          long secretVersion, boolean enabled) {
      this(userName, protocol, secretRef, secretVersion, enabled, null, null, null);
    }
  }

  /** Capacite catalogue publiee (sans serial/secret carte). */
  public record CatalogView(String profile, int caid, int provider, String state, long cardId) {
    public CatalogView {
      if (profile == null || profile.isBlank()) throw new IllegalArgumentException("profile required");
      if (caid < 1 || caid > 65535) throw new IllegalArgumentException("caid out of range");
      if (provider < 0 || provider > 16777215) throw new IllegalArgumentException("provider out of range");
      if (!"published".equals(state) && !"discovered".equals(state) && !"blocked".equals(state))
        throw new IllegalArgumentException("state must be published|discovered|blocked");
    }
  }

  /** Règle DCW transportée au worker (provider null = wildcard, 0 légitime). */
  public record DcwRuleView(long id, String profile, int caid, Integer provider, int sid,
                            String sourceId, String digestSha256, String action,
                            String checksumMode, String notes, boolean enabled) {
    public DcwRuleConfig toConfig() {
      return new DcwRuleConfig(id, profile, caid, provider, sid, sourceId, digestSha256, action, checksumMode, notes, enabled, 1L, 0L);
    }

    public com.bowman.cspng.domain.dcw.DcwRule toDomain() {
      return toConfig().toDomain();
    }
  }

  /** Mapping de service approuvé transporté au worker ({@code expiresAtMs = 0} : sans expiration). */
  public record DcwMappingView(long id, String profile, int caid, int provider, int sourceSid, int targetSid,
                               boolean approved, boolean enabled, long expiresAtMs) {
    public DcwServiceMappingConfig toConfig() {
      return new DcwServiceMappingConfig(id, profile, caid, provider, sourceSid, targetSid, approved, enabled, expiresAtMs, 1L, 0L);
    }
  }

  /**
   * Politique DCW transportée au worker. Absente (central ancien) => {@link #DEFAULT} (gardes
   * historiques). {@link #toDomain()} valide strictement (IAE si invalide) : le worker refuse
   * alors le desired entier (fail-closed, policy précédente conservée).
   */
  public record DcwPolicyView(boolean enabled, boolean adaptiveRoutingEnabled, boolean autoObserveEnabled,
                              boolean zeroCountingEnabled, String defaultChecksumMode, String repairMode,
                              boolean allowZeroHalf, List<DcwRuleView> rules, List<DcwMappingView> mappings) {
    public static final DcwPolicyView DEFAULT =
        new DcwPolicyView(true, false, false, false, "inherit", "off", true, List.of(), List.of());

    public DcwPolicyView {
      // Une policy entière absente dans un ancien desired utilise DEFAULT ; une policy
      // présente mais incomplète reste invalide (ne jamais vider silencieusement les règles).
      if (rules != null) rules = List.copyOf(rules);
      if (mappings != null) mappings = List.copyOf(mappings);
    }

    public DcwPolicyConfig toConfig() {
      if (defaultChecksumMode == null || repairMode == null || rules == null || mappings == null) {
        throw new IllegalArgumentException("DCW policy fields required when present");
      }
      return new DcwPolicyConfig(enabled, adaptiveRoutingEnabled, autoObserveEnabled, zeroCountingEnabled,
          defaultChecksumMode, repairMode, allowZeroHalf,
          rules.stream().map(DcwRuleView::toConfig).toList(),
          mappings.stream().map(DcwMappingView::toConfig).toList(), 1L, 0L);
    }

    public com.bowman.cspng.domain.dcw.DcwQualityPolicy toDomain() {
      DcwPolicyConfig cfg = toConfig();
      List<String> errors = DcwConfigRules.validatePolicy(cfg, null);
      if (!errors.isEmpty()) {
        throw new IllegalArgumentException("invalid dcw policy (" + errors.size() + " error(s))");
      }
      return cfg.toDomain();
    }

    public static DcwPolicyView from(DcwPolicyConfig p, java.util.function.Predicate<String> profileFilter) {
      List<DcwRuleView> rv = new java.util.ArrayList<>();
      for (var r : p.rules()) {
        if (!r.enabled()) continue;
        if (r.profile() != null && profileFilter != null && !profileFilter.test(r.profile())) continue;
        rv.add(new DcwRuleView(r.id(), r.profile(), r.caid(), r.provider(), r.sid(), r.sourceId(),
            r.digestSha256(), r.action(), r.checksumMode(), r.notes(), r.enabled()));
      }
      List<DcwMappingView> mv = new java.util.ArrayList<>();
      for (var m : p.mappings()) {
        if (!m.enabled() || !m.approved()) continue;
        if (profileFilter != null && !profileFilter.test(m.profile())) continue;
        mv.add(new DcwMappingView(m.id(), m.profile(), m.caid(), m.provider(), m.sourceSid(), m.targetSid(),
            m.approved(), m.enabled(), m.expiresAtMs()));
      }
      return new DcwPolicyView(p.enabled(), p.adaptiveRoutingEnabled(), p.autoObserveEnabled(), p.zeroCountingEnabled(),
          p.defaultChecksumMode(), p.repairMode(), p.allowZeroHalf(), rv, mv);
    }
  }

  public record DesiredWorkerConfig(
      String nodeId,
      long desiredVersion,
      long expiresAtMs,
      long ttlMs,
      List<ListenerView> listeners,
      List<CacheView> caches,
      List<LinkView> links,
      List<ConnectorView> connectors,
      List<UserAclView> users,
      List<UserSecretView> userSecrets,
      List<CatalogView> catalog,
      List<ProxyView> proxies,
      List<NodeView> nodes,
      DcwPolicyView dcwPolicy,
      CwCycleView cwCycle,
      PeerQuarantineView quarantine,
      CacheExQuorumView quorum
  ) {
    public DesiredWorkerConfig {
      if (nodeId == null || !nodeId.matches("^[a-z0-9][a-z0-9._-]{0,62}$"))
        throw new IllegalArgumentException("nodeId invalid");
      normalizeTtl(ttlMs);
      listeners = List.copyOf(listeners == null ? List.of() : listeners);
      caches = List.copyOf(caches == null ? List.of() : caches);
      links = List.copyOf(links == null ? List.of() : links);
      connectors = List.copyOf(connectors == null ? List.of() : connectors);
      users = List.copyOf(users == null ? List.of() : users);
      userSecrets = List.copyOf(userSecrets == null ? List.of() : userSecrets);
      catalog = List.copyOf(catalog == null ? List.of() : catalog);
      proxies = List.copyOf(proxies == null ? List.of() : proxies);
      nodes = List.copyOf(nodes == null ? List.of() : nodes);
      if (nodes.size() > 64) throw new IllegalArgumentException("nodes bounded to 64");
      dcwPolicy = dcwPolicy != null ? dcwPolicy : DcwPolicyView.DEFAULT;
      cwCycle = cwCycle != null ? cwCycle : CwCycleView.DEFAULT;
      quarantine = quarantine != null ? quarantine : PeerQuarantineView.DEFAULT;
      quorum = quorum != null ? quorum : CacheExQuorumView.DEFAULT;
    }

    /** Compat constructor with 15 fields (pre-quarantine/quorum V16). */
    public DesiredWorkerConfig(
        String nodeId, long desiredVersion, long expiresAtMs, long ttlMs,
        List<ListenerView> listeners, List<CacheView> caches,
        List<LinkView> links, List<ConnectorView> connectors,
        List<UserAclView> users, List<UserSecretView> userSecrets,
        List<CatalogView> catalog, List<ProxyView> proxies,
        List<NodeView> nodes, DcwPolicyView dcwPolicy, CwCycleView cwCycle) {
      this(nodeId, desiredVersion, expiresAtMs, ttlMs, listeners, caches, links, connectors,
          users, userSecrets, catalog, proxies, nodes, dcwPolicy, cwCycle,
          PeerQuarantineView.DEFAULT, CacheExQuorumView.DEFAULT);
    }

    /** Compat constructor with 14 fields (pre-cwCycle). */
    public DesiredWorkerConfig(
        String nodeId, long desiredVersion, long expiresAtMs, long ttlMs,
        List<ListenerView> listeners, List<CacheView> caches,
        List<LinkView> links, List<ConnectorView> connectors,
        List<UserAclView> users, List<UserSecretView> userSecrets,
        List<CatalogView> catalog, List<ProxyView> proxies,
        List<NodeView> nodes, DcwPolicyView dcwPolicy) {
      this(nodeId, desiredVersion, expiresAtMs, ttlMs, listeners, caches, links, connectors,
          users, userSecrets, catalog, proxies, nodes, dcwPolicy, CwCycleView.DEFAULT,
          PeerQuarantineView.DEFAULT, CacheExQuorumView.DEFAULT);
    }

    /** Compat constructor with 13 fields (pre-dcwPolicy). */
    public DesiredWorkerConfig(
        String nodeId, long desiredVersion, long expiresAtMs, long ttlMs,
        List<ListenerView> listeners, List<CacheView> caches,
        List<LinkView> links, List<ConnectorView> connectors,
        List<UserAclView> users, List<UserSecretView> userSecrets,
        List<CatalogView> catalog, List<ProxyView> proxies,
        List<NodeView> nodes) {
      this(nodeId, desiredVersion, expiresAtMs, ttlMs, listeners, caches, links, connectors,
          users, userSecrets, catalog, proxies, nodes, DcwPolicyView.DEFAULT);
    }

    /** Compat sans users (anciens tests). */
    public DesiredWorkerConfig(String nodeId, long desiredVersion, long expiresAtMs, long ttlMs,
                               List<ListenerView> listeners, List<CacheView> caches,
                               List<LinkView> links, List<ConnectorView> connectors) {
      this(nodeId, desiredVersion, expiresAtMs, ttlMs, listeners, caches, links, connectors,
          List.of(), List.of(), List.of(), List.of(), List.of(), DcwPolicyView.DEFAULT);
    }

    /** Compat sans secrets/catalogue/proxies/nodes. */
    public DesiredWorkerConfig(String nodeId, long desiredVersion, long expiresAtMs, long ttlMs,
                               List<ListenerView> listeners, List<CacheView> caches,
                               List<LinkView> links, List<ConnectorView> connectors,
                               List<UserAclView> users) {
      this(nodeId, desiredVersion, expiresAtMs, ttlMs, listeners, caches, links, connectors,
          users, List.of(), List.of(), List.of(), List.of(), DcwPolicyView.DEFAULT);
    }

    /** Compat sans proxies/nodes. */
    public DesiredWorkerConfig(String nodeId, long desiredVersion, long expiresAtMs, long ttlMs,
                               List<ListenerView> listeners, List<CacheView> caches,
                               List<LinkView> links, List<ConnectorView> connectors,
                               List<UserAclView> users, List<UserSecretView> userSecrets,
                               List<CatalogView> catalog) {
      this(nodeId, desiredVersion, expiresAtMs, ttlMs, listeners, caches, links, connectors,
          users, userSecrets, catalog, List.of(), List.of(), DcwPolicyView.DEFAULT);
    }

    /** Compat sans nodes. */
    public DesiredWorkerConfig(String nodeId, long desiredVersion, long expiresAtMs, long ttlMs,
                               List<ListenerView> listeners, List<CacheView> caches,
                               List<LinkView> links, List<ConnectorView> connectors,
                               List<UserAclView> users, List<UserSecretView> userSecrets,
                               List<CatalogView> catalog, List<ProxyView> proxies) {
      this(nodeId, desiredVersion, expiresAtMs, ttlMs, listeners, caches, links, connectors,
          users, userSecrets, catalog, proxies, List.of(), DcwPolicyView.DEFAULT);
    }
  }

  /**
   * Stats connecteur worker : compteurs dispatch reels (ok/echec/timeout) +
   * latence moyenne mesuree (nullable si aucune mesure). Absence d'entree =
   * aucun trafic, jamais de zero fictif cumule.
   */
  public record ConnectorStatsView(String name, boolean online, long ecmOk, long ecmFail,
                                   long timeouts, Long avgLatencyMs) {
    public ConnectorStatsView {
      if (name == null || name.isBlank()) throw new IllegalArgumentException("connector name required");
      if (ecmOk < 0 || ecmFail < 0 || timeouts < 0)
        throw new IllegalArgumentException("connector counters >= 0");
      if (avgLatencyMs != null && avgLatencyMs < 0) throw new IllegalArgumentException("avgLatencyMs >= 0");
    }
    /** Compat sans echecs/timeouts (anciens tests). */
    public ConnectorStatsView(String name, boolean online, long ecmOk, Long avgLatencyMs) {
      this(name, online, ecmOk, 0L, 0L, avgLatencyMs);
    }
  }

  /** Stats echange UDP worker : compteurs transport + RTT moyen mesure (nullable si aucun echange). */
  public record UdpStatsView(long sentReq, long sentRepl, long recvReq, long recvRepl,
                             long rejected, long rateLimited, Long avgRttMs) {
    public UdpStatsView {
      if (sentReq < 0 || sentRepl < 0 || recvReq < 0 || recvRepl < 0 || rejected < 0 || rateLimited < 0)
        throw new IllegalArgumentException("udp counters >= 0");
    }
  }

  /** Stats cache worker : hits/mises reels par cache. */
  public record CacheStatsView(String name, long consultHits, long feedPuts) {
    public CacheStatsView {
      if (name == null || name.isBlank()) throw new IllegalArgumentException("cache name required");
      if (consultHits < 0 || feedPuts < 0) throw new IllegalArgumentException("cache counters >= 0");
    }
  }

  /** Stats node distant vu worker : utilisabilite + echanges CSPv2 mesures. */
  public record NodeStatsView(String nodeName, boolean usable, long consultOk, Long avgRttMs) {
    public NodeStatsView {
      if (nodeName == null || nodeName.isBlank()) throw new IllegalArgumentException("nodeName required");
      if (consultOk < 0) throw new IllegalArgumentException("consultOk >= 0");
    }
  }

  /** Stats cacheex par pair remontées par le worker (même forme que le central, C1). */
  public record CacheExPairStatsView(
      String peerId, String direction, String protocol, int mode, String profile,
      boolean online, String state, int maxHop, int maxSendQueue, boolean filterActive,
      long pushedCw, long receivedCw, long droppedCw,
      java.util.Map<String, Long> dropReasons, Long lastPushAtMs, Long lastReceiveAtMs) {
    public CacheExPairStatsView {
      if (peerId == null || peerId.isBlank()) throw new IllegalArgumentException("peerId required");
      dropReasons = dropReasons != null ? java.util.Map.copyOf(dropReasons) : java.util.Map.of();
    }
  }

  /** Vue CW-cycle transportée au worker (B1) : additive, jamais null en pratique (DEFAULT si absent). */
  public record CwCycleView(boolean enabled, List<Integer> caids, int maxList, int keepTimeSec,
                            String onBad, boolean dropOld, boolean usePeerCycleMetadata) {
    public static final CwCycleView DEFAULT =
        new CwCycleView(false, List.of(), 500, 15, "drop", true, false);

    public CwCycleView {
      if (caids == null) caids = List.of();
      List<Integer> normalized = new java.util.ArrayList<>(caids);
      java.util.Collections.sort(normalized);
      for (int i = 0; i < normalized.size(); i++) {
        int c = normalized.get(i);
        if (c < 1 || c > 65535) throw new IllegalArgumentException("caid out of range 1..65535: " + c);
        if (i > 0 && normalized.get(i - 1).intValue() == c)
          throw new IllegalArgumentException("duplicate caid: " + c);
      }
      if (normalized.size() > 32) throw new IllegalArgumentException("caids limit 32");
      caids = List.copyOf(normalized);
      if (maxList < 16 || maxList > 4000)
        throw new IllegalArgumentException("maxList must be 16..4000");
      if (keepTimeSec < 5 || keepTimeSec > 240)
        throw new IllegalArgumentException("keepTimeSec must be 5..240");
      if (onBad == null) onBad = "drop";
      onBad = onBad.trim().toLowerCase(java.util.Locale.ROOT);
      if (!"log".equals(onBad) && !"drop".equals(onBad))
        throw new IllegalArgumentException("onBad must be log or drop");
    }

    public static CwCycleView from(CwCycleConfig cfg) {
      if (cfg == null) return DEFAULT;
      return new CwCycleView(cfg.enabled(), cfg.caids(), cfg.maxList(), cfg.keepTimeSec(),
          cfg.onBad(), cfg.dropOld(), cfg.usePeerCycleMetadata());
    }

    public CwCycleConfig toConfig() {
      return new CwCycleConfig(enabled, caids, maxList, keepTimeSec, onBad, dropOld,
          usePeerCycleMetadata, 1L, null, null);
    }
  }

  /** Compteurs CW-cycle réels d'un worker (B1) : null si jamais configuré (honnête). */
  public record CwCycleStatsView(boolean enabled, int trackedEntries, long checks, long badCycles,
                                 long dropped, long logged, long bypassed) {
    /** Compat sans bypass (pre-compat OSCam oddEven=0). */
    public CwCycleStatsView(boolean enabled, int trackedEntries, long checks, long badCycles,
                            long dropped, long logged) {
      this(enabled, trackedEntries, checks, badCycles, dropped, logged, 0L);
    }
  }

  /** Vue quarantaine pairs transportée au worker (V16) : additive, DEFAULT si absente. */
  public record PeerQuarantineView(boolean enabled, String mode, int minSamples, int invalidRatioBps,
                                   long windowMs, long durationMs, int maxPeers) {
    public static final PeerQuarantineView DEFAULT =
        new PeerQuarantineView(false, "observe", 50, 5_000, 300_000L, 600_000L, 256);

    public PeerQuarantineView {
      if (mode == null) mode = "observe";
      mode = mode.trim().toLowerCase(java.util.Locale.ROOT);
      if (!"observe".equals(mode) && !"enforce".equals(mode))
        throw new IllegalArgumentException("mode must be observe or enforce");
    }

    public static PeerQuarantineView from(PeerQuarantineConfig cfg) {
      if (cfg == null) return DEFAULT;
      return new PeerQuarantineView(cfg.enabled(), cfg.mode(), cfg.minSamples(), cfg.invalidRatioBps(),
          cfg.windowMs(), cfg.durationMs(), cfg.maxPeers());
    }

    public PeerQuarantineConfig toConfig() {
      return new PeerQuarantineConfig(enabled, mode, minSamples, invalidRatioBps,
          windowMs, durationMs, maxPeers, 1L);
    }
  }

  /** Vue quorum CacheEx transportée au worker (V16) : additive, DEFAULT (off) si absente. */
  public record CacheExQuorumView(String mode, int minOrigins, long windowMs, int maxKeys,
                                  List<String> profiles, List<Integer> caids) {
    public static final CacheExQuorumView DEFAULT =
        new CacheExQuorumView("off", 2, 30_000L, 1_024, List.of(), List.of());

    public CacheExQuorumView {
      if (mode == null) mode = "off";
      mode = mode.trim().toLowerCase(java.util.Locale.ROOT);
      if (!"off".equals(mode) && !"observe".equals(mode) && !"enforce".equals(mode))
        throw new IllegalArgumentException("mode must be off, observe or enforce");
      profiles = List.copyOf(profiles == null ? List.of() : profiles);
      caids = List.copyOf(caids == null ? List.of() : caids);
    }

    public static CacheExQuorumView from(CacheExQuorumConfig cfg) {
      if (cfg == null) return DEFAULT;
      return new CacheExQuorumView(cfg.mode(), cfg.minOrigins(), cfg.windowMs(), cfg.maxKeys(),
          cfg.profiles(), cfg.caids());
    }

    public CacheExQuorumConfig toConfig() {
      return new CacheExQuorumConfig(mode, minOrigins, windowMs, maxKeys, profiles, caids, 1L);
    }
  }

  /** Stats qualité fenêtrée remontées par un worker (B5) : liste bornée à 64 entrées. */
  public record PeerQualityStatView(String peerId, long accepted, long invalid, long total,
                                    int invalidRatioBps, long windowMs, boolean quarantined) {
    public PeerQualityStatView {
      if (peerId == null || peerId.isBlank()) throw new IllegalArgumentException("peerId required");
    }
  }

  /** Stats hits servis remontées par un worker (B5) : liste bornée à 64 entrées. */
  public record CacheHitOriginStatView(String origin, long hits, Long lastHitAtMs) {
    public CacheHitOriginStatView {
      if (origin == null || origin.isBlank()) throw new IllegalArgumentException("origin required");
    }
  }

  /** Stats quarantaine active remontées par un worker (B5) : liste bornée à 64 entrées. */
  public record PeerQuarantineStatView(String peerId, String reason, long quarantinedAtMs,
                                       long expiresAtMs, boolean observedOnly) {
    public PeerQuarantineStatView {
      if (peerId == null || peerId.isBlank()) throw new IllegalArgumentException("peerId required");
    }
  }

  /** Compteurs quorum remontés par un worker (B5) : null si quorum off/vide. */
  public record QuorumStatsView(int keys, long observed, long quorum, long pending,
                                long mismatch, long expired) {}

  /** Payload stats worker→central : borne (64 entrees max par liste), authentifie via mTLS SAN. */
  public record WorkerStatsView(List<ConnectorStatsView> connectors, UdpStatsView udp,
                                List<CacheStatsView> caches, List<NodeStatsView> nodes,
                                List<CacheExPairStatsView> cacheex,
                                CwCycleStatsView cwCycle,
                                List<PeerQualityStatView> peerQuality,
                                List<CacheHitOriginStatView> cacheHits,
                                List<PeerQuarantineStatView> quarantine,
                                QuorumStatsView quorum,
                                long collectedAtMs) {
    public WorkerStatsView {
      connectors = List.copyOf(connectors == null ? List.of() : connectors);
      caches = List.copyOf(caches == null ? List.of() : caches);
      nodes = List.copyOf(nodes == null ? List.of() : nodes);
      cacheex = List.copyOf(cacheex == null ? List.of() : cacheex);
      peerQuality = List.copyOf(peerQuality == null ? List.of() : peerQuality);
      cacheHits = List.copyOf(cacheHits == null ? List.of() : cacheHits);
      quarantine = List.copyOf(quarantine == null ? List.of() : quarantine);
      if (connectors.size() > 64 || caches.size() > 64 || nodes.size() > 64 || cacheex.size() > 64
          || peerQuality.size() > 64 || cacheHits.size() > 64 || quarantine.size() > 64)
        throw new IllegalArgumentException("stats lists bounded to 64 entries");
    }

    /** Compat constructor (pre-B5). */
    public WorkerStatsView(List<ConnectorStatsView> connectors, UdpStatsView udp,
                           List<CacheStatsView> caches, List<NodeStatsView> nodes,
                           List<CacheExPairStatsView> cacheex,
                           CwCycleStatsView cwCycle,
                           long collectedAtMs) {
      this(connectors, udp, caches, nodes, cacheex, cwCycle, List.of(), List.of(), List.of(), null, collectedAtMs);
    }

    /** Compat constructor sans stats CW-cycle (pre-B1). */
    public WorkerStatsView(List<ConnectorStatsView> connectors, UdpStatsView udp,
                           List<CacheStatsView> caches, List<NodeStatsView> nodes,
                           List<CacheExPairStatsView> cacheex,
                           long collectedAtMs) {
      this(connectors, udp, caches, nodes, cacheex, null, List.of(), List.of(), List.of(), null, collectedAtMs);
    }

    /** Compat sans paires cacheex (pre-V15). */
    public WorkerStatsView(List<ConnectorStatsView> connectors, UdpStatsView udp,
                           List<CacheStatsView> caches, List<NodeStatsView> nodes,
                           long collectedAtMs) {
      this(connectors, udp, caches, nodes, List.of(), null, List.of(), List.of(), List.of(), null, collectedAtMs);
    }
  }

  /** Entrée de log émise par un worker relais. */
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public record WorkerLogEntry(long seq, long timestamp, String level, String logger, String message) {
    public WorkerLogEntry {
      level = level != null ? (level.length() > 16 ? level.substring(0, 16) : level) : "INFO";
      logger = logger != null ? (logger.length() > 128 ? logger.substring(0, 128) : logger) : "app";
      message = message != null ? (message.length() > 2048 ? message.substring(0, 2048) : message) : "";
    }
  }

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public record WorkerAck(
      String nodeId,
      long appliedVersion,
      boolean ok,
      String error,
      WorkerStatsView stats,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
      List<WorkerLogEntry> logs) {
    public WorkerAck {
      if (nodeId == null || !nodeId.matches("^[a-z0-9][a-z0-9._-]{0,62}$"))
        throw new IllegalArgumentException("nodeId invalid");
      if (appliedVersion < 1) throw new IllegalArgumentException("appliedVersion must be >= 1");
      logs = logs == null ? List.of() : List.copyOf(logs);
      if (logs.size() > 200) {
        throw new IllegalArgumentException("logs bounded to 200 entries max");
      }
    }
    /** Compat sans logs. */
    public WorkerAck(String nodeId, long appliedVersion, boolean ok, String error, WorkerStatsView stats) {
      this(nodeId, appliedVersion, ok, error, stats, List.of());
    }
    /** Compat sans stats ni logs. */
    public WorkerAck(String nodeId, long appliedVersion, boolean ok, String error) {
      this(nodeId, appliedVersion, ok, error, null, List.of());
    }
  }

  public record WorkerAckResult(long appliedVersion, boolean stale, boolean expired) {}
}
