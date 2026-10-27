package com.bowman.cspng.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Snapshot immuable de l'integralite de la configuration CSP-NG.
 *
 * <p>Construit par {@link ConfigRepository#loadAll()} depuis PostgreSQL,
 * stocke dans {@link ConfigHolder} via {@code AtomicReference}.
 * Toutes les collections sont defensivement copiees a la construction.
 *
 * <p>Les champs optionnels/sous-configurations sont vides, jamais null.
 */
public record ConfigSnapshot(
    RootConfig root,
    LoggingConfig logging,
    CaProfilesGlobalConfig caProfilesGlobal,
    List<ProfileConfig> profiles,
    List<ProfileListenerConfig> profileListeners,
    List<ProfileCardConfig> profileCards,
    ConnectionManagerConfig connectionManager,
    List<ServiceMapEntryConfig> serviceMap,
    List<ConnectorConfig> connectors,
    List<ConnectorServiceConfig> connectorServices,
    CacheConfig cache,
    ClusterUdpConfig clusterUdp,
    WebUiConfig webui,
    List<PluginConfig> plugins,
    List<SidCacheLinkConfig> sidCacheLinks,
    ExtendedNewcamdConfig extendedNewcamd,
    List<ConnectorCaidProfileConfig> connectorCaidProfiles,
    List<ProfileProviderIdentConfig> profileProviderIdents,
    List<NetworkProxyConfig> proxies,
    List<RelayNodeConfig> relayNodes,
    List<RelayCacheConfig> relayCaches,
    List<ListenerCacheLinkConfig> listenerCacheLinks,
    List<RelayNodeListenerAssignment> nodeListeners,
    List<RelayNodeConnectorAssignment> nodeConnectors,
    List<RelayUdpPeerConfig> udpPeers,
    DcwPolicyConfig dcwPolicy,
    CwCycleConfig cwCycle,
    PeerQuarantineConfig quarantine,
    CacheExQuorumConfig quorum,
    long loadedAtMs
) {

    public ConfigSnapshot {
        profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
        profileListeners = List.copyOf(Objects.requireNonNull(profileListeners, "profileListeners"));
        profileCards = List.copyOf(Objects.requireNonNull(profileCards, "profileCards"));
        serviceMap = List.copyOf(Objects.requireNonNull(serviceMap, "serviceMap"));
        connectors = List.copyOf(Objects.requireNonNull(connectors, "connectors"));
        connectorServices = List.copyOf(Objects.requireNonNull(connectorServices, "connectorServices"));
        plugins = List.copyOf(Objects.requireNonNull(plugins, "plugins"));
        sidCacheLinks = List.copyOf(Objects.requireNonNull(sidCacheLinks, "sidCacheLinks"));
        extendedNewcamd = Objects.requireNonNull(extendedNewcamd, "extendedNewcamd");
        connectorCaidProfiles = List.copyOf(Objects.requireNonNull(connectorCaidProfiles, "connectorCaidProfiles"));
        profileProviderIdents = List.copyOf(Objects.requireNonNull(profileProviderIdents, "profileProviderIdents"));
        proxies = List.copyOf(Objects.requireNonNull(proxies, "proxies"));
        relayNodes = List.copyOf(Objects.requireNonNull(relayNodes, "relayNodes"));
        relayCaches = List.copyOf(Objects.requireNonNull(relayCaches, "relayCaches"));
        listenerCacheLinks = List.copyOf(Objects.requireNonNull(listenerCacheLinks, "listenerCacheLinks"));
        nodeListeners = List.copyOf(Objects.requireNonNull(nodeListeners, "nodeListeners"));
        nodeConnectors = List.copyOf(Objects.requireNonNull(nodeConnectors, "nodeConnectors"));
        udpPeers = List.copyOf(Objects.requireNonNull(udpPeers, "udpPeers"));
        dcwPolicy = dcwPolicy != null ? dcwPolicy : DcwPolicyConfig.DEFAULT;
        cwCycle = cwCycle != null ? cwCycle : CwCycleConfig.DEFAULT;
        quarantine = quarantine != null ? quarantine : PeerQuarantineConfig.DEFAULT;
        quorum = quorum != null ? quorum : CacheExQuorumConfig.DEFAULT;
    }

    /** Compat constructor with cwCycle defaulting to V15 default (pre-V15 snapshots). */
    public ConfigSnapshot(
        RootConfig root,
        LoggingConfig logging,
        CaProfilesGlobalConfig caProfilesGlobal,
        List<ProfileConfig> profiles,
        List<ProfileListenerConfig> profileListeners,
        List<ProfileCardConfig> profileCards,
        ConnectionManagerConfig connectionManager,
        List<ServiceMapEntryConfig> serviceMap,
        List<ConnectorConfig> connectors,
        List<ConnectorServiceConfig> connectorServices,
        CacheConfig cache,
        ClusterUdpConfig clusterUdp,
        WebUiConfig webui,
        List<PluginConfig> plugins,
        List<SidCacheLinkConfig> sidCacheLinks,
        ExtendedNewcamdConfig extendedNewcamd,
        List<ConnectorCaidProfileConfig> connectorCaidProfiles,
        List<ProfileProviderIdentConfig> profileProviderIdents,
        List<NetworkProxyConfig> proxies,
        List<RelayNodeConfig> relayNodes,
        List<RelayCacheConfig> relayCaches,
        List<ListenerCacheLinkConfig> listenerCacheLinks,
        List<RelayNodeListenerAssignment> nodeListeners,
        List<RelayNodeConnectorAssignment> nodeConnectors,
        List<RelayUdpPeerConfig> udpPeers,
        DcwPolicyConfig dcwPolicy,
        long loadedAtMs
    ) {
        this(root, logging, caProfilesGlobal, profiles, profileListeners, profileCards,
            connectionManager, serviceMap, connectors, connectorServices, cache, clusterUdp, webui,
            plugins, sidCacheLinks, extendedNewcamd, connectorCaidProfiles, profileProviderIdents,
            proxies, relayNodes, relayCaches, listenerCacheLinks, nodeListeners, nodeConnectors, udpPeers,
            dcwPolicy, CwCycleConfig.DEFAULT, PeerQuarantineConfig.DEFAULT, CacheExQuorumConfig.DEFAULT,
            loadedAtMs);
    }

    /** Compat constructor without dcwPolicy (pre-V13 snapshots). */
    public ConfigSnapshot(
        RootConfig root,
        LoggingConfig logging,
        CaProfilesGlobalConfig caProfilesGlobal,
        List<ProfileConfig> profiles,
        List<ProfileListenerConfig> profileListeners,
        List<ProfileCardConfig> profileCards,
        ConnectionManagerConfig connectionManager,
        List<ServiceMapEntryConfig> serviceMap,
        List<ConnectorConfig> connectors,
        List<ConnectorServiceConfig> connectorServices,
        CacheConfig cache,
        ClusterUdpConfig clusterUdp,
        WebUiConfig webui,
        List<PluginConfig> plugins,
        List<SidCacheLinkConfig> sidCacheLinks,
        ExtendedNewcamdConfig extendedNewcamd,
        List<ConnectorCaidProfileConfig> connectorCaidProfiles,
        List<ProfileProviderIdentConfig> profileProviderIdents,
        List<NetworkProxyConfig> proxies,
        List<RelayNodeConfig> relayNodes,
        List<RelayCacheConfig> relayCaches,
        List<ListenerCacheLinkConfig> listenerCacheLinks,
        List<RelayNodeListenerAssignment> nodeListeners,
        List<RelayNodeConnectorAssignment> nodeConnectors,
        List<RelayUdpPeerConfig> udpPeers,
        long loadedAtMs
    ) {
        this(root, logging, caProfilesGlobal, profiles, profileListeners, profileCards,
            connectionManager, serviceMap, connectors, connectorServices, cache, clusterUdp, webui,
            plugins, sidCacheLinks, extendedNewcamd, connectorCaidProfiles, profileProviderIdents,
            proxies, relayNodes, relayCaches, listenerCacheLinks, nodeListeners, nodeConnectors, udpPeers,
            DcwPolicyConfig.DEFAULT, loadedAtMs);
    }

    /** Compat constructor with relays defaulting to empty (pre-V10 snapshots). */
    public ConfigSnapshot(
        RootConfig root,
        LoggingConfig logging,
        CaProfilesGlobalConfig caProfilesGlobal,
        List<ProfileConfig> profiles,
        List<ProfileListenerConfig> profileListeners,
        List<ProfileCardConfig> profileCards,
        ConnectionManagerConfig connectionManager,
        List<ServiceMapEntryConfig> serviceMap,
        List<ConnectorConfig> connectors,
        List<ConnectorServiceConfig> connectorServices,
        CacheConfig cache,
        ClusterUdpConfig clusterUdp,
        WebUiConfig webui,
        List<PluginConfig> plugins,
        List<SidCacheLinkConfig> sidCacheLinks,
        ExtendedNewcamdConfig extendedNewcamd,
        List<ConnectorCaidProfileConfig> connectorCaidProfiles,
        List<ProfileProviderIdentConfig> profileProviderIdents,
        List<NetworkProxyConfig> proxies,
        long loadedAtMs
    ) {
        this(root, logging, caProfilesGlobal, profiles, profileListeners, profileCards,
            connectionManager, serviceMap, connectors, connectorServices, cache, clusterUdp, webui,
            plugins, sidCacheLinks, extendedNewcamd, connectorCaidProfiles, profileProviderIdents,
            proxies, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), DcwPolicyConfig.DEFAULT, loadedAtMs);
    }

    /** Compat constructor without proxies (V5) — delegates with proxies=empty. */
    public ConfigSnapshot(
        RootConfig root,
        LoggingConfig logging,
        CaProfilesGlobalConfig caProfilesGlobal,
        List<ProfileConfig> profiles,
        List<ProfileListenerConfig> profileListeners,
        List<ProfileCardConfig> profileCards,
        ConnectionManagerConfig connectionManager,
        List<ServiceMapEntryConfig> serviceMap,
        List<ConnectorConfig> connectors,
        List<ConnectorServiceConfig> connectorServices,
        CacheConfig cache,
        ClusterUdpConfig clusterUdp,
        WebUiConfig webui,
        List<PluginConfig> plugins,
        List<SidCacheLinkConfig> sidCacheLinks,
        ExtendedNewcamdConfig extendedNewcamd,
        List<ConnectorCaidProfileConfig> connectorCaidProfiles,
        List<ProfileProviderIdentConfig> profileProviderIdents,
        long loadedAtMs
    ) {
        this(root, logging, caProfilesGlobal, profiles, profileListeners, profileCards,
            connectionManager, serviceMap, connectors, connectorServices, cache, clusterUdp, webui,
            plugins, sidCacheLinks, extendedNewcamd, connectorCaidProfiles, profileProviderIdents,
            Collections.emptyList(), loadedAtMs);
    }

    /** Compat constructor without V3 fields (delegates to full with defaults). */
    public ConfigSnapshot(
        RootConfig root,
        LoggingConfig logging,
        CaProfilesGlobalConfig caProfilesGlobal,
        List<ProfileConfig> profiles,
        List<ProfileListenerConfig> profileListeners,
        List<ProfileCardConfig> profileCards,
        ConnectionManagerConfig connectionManager,
        List<ServiceMapEntryConfig> serviceMap,
        List<ConnectorConfig> connectors,
        List<ConnectorServiceConfig> connectorServices,
        CacheConfig cache,
        ClusterUdpConfig clusterUdp,
        WebUiConfig webui,
        List<PluginConfig> plugins,
        List<SidCacheLinkConfig> sidCacheLinks,
        long loadedAtMs
    ) {
        this(root, logging, caProfilesGlobal, profiles, profileListeners, profileCards,
            connectionManager, serviceMap, connectors, connectorServices, cache, clusterUdp, webui,
            plugins, sidCacheLinks, ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(),
            Collections.emptyList(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), DcwPolicyConfig.DEFAULT, loadedAtMs);
    }

    /** Empty snapshot with safe defaults (used before first DB load). */
    public static ConfigSnapshot empty() {
        return new ConfigSnapshot(
            RootConfig.DEFAULT,
            LoggingConfig.DEFAULT,
            CaProfilesGlobalConfig.DEFAULT,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            ConnectionManagerConfig.DEFAULT,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            CacheConfig.DEFAULT,
            ClusterUdpConfig.DEFAULT,
            WebUiConfig.DEFAULT,
            Collections.emptyList(),
            Collections.emptyList(),
            ExtendedNewcamdConfig.DEFAULT,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            DcwPolicyConfig.DEFAULT,
            System.currentTimeMillis()
        );
    }

    // -- Helpers d'indexation (derives, pas stockes) --

    public Map<String, ProfileConfig> profileByName() {
        return profiles.stream().collect(Collectors.toMap(
            ProfileConfig::name, Function.identity(), (a, b) -> a));
    }

    public Map<String, ConnectorConfig> connectorByName() {
        return connectors.stream().collect(Collectors.toMap(
            ConnectorConfig::name, Function.identity(), (a, b) -> a));
    }

    public Map<String, PluginConfig> pluginByName() {
        return plugins.stream().collect(Collectors.toMap(
            PluginConfig::name, Function.identity(), (a, b) -> a));
    }

    /** Lookup proxy par nom (O(n) — construire un Map si usage fréquent). */
    public Map<String, NetworkProxyConfig> proxyByName() {
        return proxies.stream().collect(Collectors.toMap(
            NetworkProxyConfig::name, Function.identity(), (a, b) -> a));
    }

    public List<ProfileListenerConfig> listenersForProfile(String profileName) {
        return profileListeners.stream()
            .filter(l -> Objects.equals(l.profileName(), profileName))
            .toList();
    }

    public List<ConnectorConfig> connectorsForProfile(String profileName) {
        if (profileName == null) return List.of();
        return connectors.stream()
            .filter(c -> Objects.equals(c.profileName(), profileName))
            .toList();
    }

    /** Connectors without fixed profile (dynamic, profileName null). */
    public List<ConnectorConfig> connectorsWithoutFixedProfile() {
        return connectors.stream()
            .filter(c -> !c.hasFixedProfile())
            .toList();
    }

    /**
     * CAID -> profile map for a connector, ordered by ordinal (LinkedHashMap).
     */
    public LinkedHashMap<Integer, String> caidProfileMapFor(String connectorName) {
        LinkedHashMap<Integer, String> map = new LinkedHashMap<>();
        connectorCaidProfiles.stream()
            .filter(e -> Objects.equals(e.connectorName(), connectorName))
            .sorted(java.util.Comparator.comparingInt(ConnectorCaidProfileConfig::ordinal))
            .forEach(e -> map.put(e.caId(), e.profileName()));
        return map;
    }

    /** All CAID profile entries for a connector, ordered by ordinal. */
    public List<ConnectorCaidProfileConfig> caidProfilesForConnector(String connectorName) {
        return connectorCaidProfiles.stream()
            .filter(e -> Objects.equals(e.connectorName(), connectorName))
            .sorted(java.util.Comparator.comparingInt(ConnectorCaidProfileConfig::ordinal))
            .toList();
    }

    /** Provider idents for a profile. */
    public List<ProfileProviderIdentConfig> providerIdentsFor(String profileName) {
        return profileProviderIdents.stream()
            .filter(e -> Objects.equals(e.profileName(), profileName))
            .toList();
    }

    public Map<String, RelayNodeConfig> relayNodeByName() {
        return relayNodes.stream().collect(Collectors.toMap(
            RelayNodeConfig::nodeName, Function.identity(), (a, b) -> a));
    }

    public Map<String, RelayCacheConfig> relayCacheByName() {
        return relayCaches.stream().collect(Collectors.toMap(
            RelayCacheConfig::name, Function.identity(), (a, b) -> a));
    }

    public List<ListenerCacheLinkConfig> linksForListener(long listenerId) {
        return listenerCacheLinks.stream()
            .filter(l -> l.listenerId() == listenerId)
            .toList();
    }

    /** Listeners explicitement affectes et actives pour un worker. */
    public List<Long> assignedListenerIds(String nodeName) {
        return nodeListeners.stream()
            .filter(a -> Objects.equals(a.nodeName(), nodeName) && a.enabled())
            .map(RelayNodeListenerAssignment::listenerId)
            .toList();
    }

    /** Connecteurs amont explicitement affectes et actifs pour un worker. */
    public List<String> assignedConnectorNames(String nodeName) {
        return nodeConnectors.stream()
            .filter(a -> Objects.equals(a.nodeName(), nodeName) && a.enabled())
            .map(RelayNodeConnectorAssignment::connectorName)
            .toList();
    }

    /** Copie avec peers UDP remplaces (tests/validation). */
    public ConfigSnapshot withUdpPeers(List<RelayUdpPeerConfig> peers) {
        return new ConfigSnapshot(root, logging, caProfilesGlobal, profiles,
            profileListeners, profileCards, connectionManager, serviceMap,
            connectors, connectorServices, cache, clusterUdp, webui, plugins,
            sidCacheLinks, extendedNewcamd, connectorCaidProfiles,
            profileProviderIdents, proxies, relayNodes, relayCaches,
            listenerCacheLinks, nodeListeners, nodeConnectors, peers,
            dcwPolicy, cwCycle, quarantine, quorum,
            System.currentTimeMillis());
    }

    /** Copie avec profils remplaces (tests/validation). */
    public ConfigSnapshot withProfiles(List<ProfileConfig> newProfiles) {
        return new ConfigSnapshot(root, logging, caProfilesGlobal, newProfiles != null ? newProfiles : List.of(),
            profileListeners, profileCards, connectionManager, serviceMap,
            connectors, connectorServices, cache, clusterUdp, webui, plugins,
            sidCacheLinks, extendedNewcamd, connectorCaidProfiles,
            profileProviderIdents, proxies, relayNodes, relayCaches,
            listenerCacheLinks, nodeListeners, nodeConnectors, udpPeers,
            dcwPolicy, cwCycle, quarantine, quorum,
            System.currentTimeMillis());
    }

    /** Copie avec politique DCW remplacee (tests/validation). */
    public ConfigSnapshot withDcwPolicy(DcwPolicyConfig policy) {
        return new ConfigSnapshot(root, logging, caProfilesGlobal, profiles,
            profileListeners, profileCards, connectionManager, serviceMap,
            connectors, connectorServices, cache, clusterUdp, webui, plugins,
            sidCacheLinks, extendedNewcamd, connectorCaidProfiles,
            profileProviderIdents, proxies, relayNodes, relayCaches,
            listenerCacheLinks, nodeListeners, nodeConnectors, udpPeers,
            policy, cwCycle, quarantine, quorum,
            System.currentTimeMillis());
    }

    /** Copie avec config CW-cycle remplacee (tests/validation). */
    public ConfigSnapshot withCwCycle(CwCycleConfig cfg) {
        return new ConfigSnapshot(root, logging, caProfilesGlobal, profiles,
            profileListeners, profileCards, connectionManager, serviceMap,
            connectors, connectorServices, cache, clusterUdp, webui, plugins,
            sidCacheLinks, extendedNewcamd, connectorCaidProfiles,
            profileProviderIdents, proxies, relayNodes, relayCaches,
            listenerCacheLinks, nodeListeners, nodeConnectors, udpPeers,
            dcwPolicy, cfg != null ? cfg : CwCycleConfig.DEFAULT, quarantine, quorum,
            System.currentTimeMillis());
    }

    /** Copie avec config quarantaine remplacee (tests/validation). */
    public ConfigSnapshot withQuarantine(PeerQuarantineConfig cfg) {
        return new ConfigSnapshot(root, logging, caProfilesGlobal, profiles,
            profileListeners, profileCards, connectionManager, serviceMap,
            connectors, connectorServices, cache, clusterUdp, webui, plugins,
            sidCacheLinks, extendedNewcamd, connectorCaidProfiles,
            profileProviderIdents, proxies, relayNodes, relayCaches,
            listenerCacheLinks, nodeListeners, nodeConnectors, udpPeers,
            dcwPolicy, cwCycle, cfg != null ? cfg : PeerQuarantineConfig.DEFAULT, quorum,
            System.currentTimeMillis());
    }

    /** Copie avec config quorum remplacee (tests/validation). */
    public ConfigSnapshot withQuorum(CacheExQuorumConfig cfg) {
        return new ConfigSnapshot(root, logging, caProfilesGlobal, profiles,
            profileListeners, profileCards, connectionManager, serviceMap,
            connectors, connectorServices, cache, clusterUdp, webui, plugins,
            sidCacheLinks, extendedNewcamd, connectorCaidProfiles,
            profileProviderIdents, proxies, relayNodes, relayCaches,
            listenerCacheLinks, nodeListeners, nodeConnectors, udpPeers,
            dcwPolicy, cwCycle, quarantine, cfg != null ? cfg : CacheExQuorumConfig.DEFAULT,
            System.currentTimeMillis());
    }

    /** Pairs UDP actives d'un cache (allowlist). */
    public List<RelayUdpPeerConfig> udpPeersForCache(String cacheName) {
        return udpPeers.stream()
            .filter(p -> Objects.equals(p.cacheName(), cacheName) && p.enabled())
            .toList();
    }
}
