package com.bowman.cspng.config;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Repository reactif pour charger et modifier la configuration depuis PostgreSQL.
 *
 * <p>Utilise {@code io.vertx.sqlclient.Pool} (client PG reactif),
 * prepared queries, et {@code Future} Vert.x (pas Uni).
 * Aucun SQL bloquant, aucun join()/get()/await() sur l'event-loop.
 */
public class ConfigRepository {

    private static final Logger LOG = Logger.getLogger(ConfigRepository.class.getName());

    private final Pool pool;

    public ConfigRepository(Pool pool) {
        this.pool = pool;
    }

    // -- loadAll --

    /**
     * Charge TOUTES les tables de configuration dans une transaction READ ONLY
     * et construit un {@link ConfigSnapshot} complet.
     * La transaction est explicitement mise en READ ONLY via SET TRANSACTION.
     */
    public Future<ConfigSnapshot> loadAll() {
        Promise<ConfigSnapshot> promise = Promise.promise();
        long start = System.currentTimeMillis();

        pool.withTransaction(client ->
            client.query("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY").execute()
                .compose(v -> loadRoot(client, new SnapshotParts()))
                .compose(p -> loadLogging(client, p))
                .compose(p -> loadCaProfilesGlobal(client, p))
                .compose(p -> loadProfiles(client, p))
                .compose(p -> loadListeners(client, p))
                .compose(p -> loadCards(client, p))
                .compose(p -> loadConnectionManager(client, p))
                .compose(p -> loadServiceMap(client, p))
                .compose(p -> loadConnectors(client, p))
                .compose(p -> loadConnectorServices(client, p))
                .compose(p -> loadCache(client, p))
                .compose(p -> loadClusterUdp(client, p))
                .compose(p -> loadWebUi(client, p))
                .compose(p -> loadPlugins(client, p))
                .compose(p -> loadSidLinks(client, p))
                .compose(p -> loadExtendedNewcamd(client, p))
                .compose(p -> loadCaidProfiles(client, p))
                .compose(p -> loadProviderIdents(client, p))
                .compose(p -> loadProxies(client, p))
                .compose(p -> loadRelayNodes(client, p))
                .compose(p -> loadRelayCaches(client, p))
                .compose(p -> loadListenerCacheLinks(client, p))
                .compose(p -> loadNodeListeners(client, p))
                .compose(p -> loadNodeConnectors(client, p))
                .compose(p -> loadUdpPeers(client, p))
                .compose(p -> loadDcwPolicy(client, p))
                .compose(p -> loadDcwRules(client, p))
                .compose(p -> loadDcwServiceMappings(client, p))
                .compose(p -> loadCwCycle(client, p))
                .compose(p -> loadPeerQuarantine(client, p))
                .compose(p -> loadCacheExQuorum(client, p))
                .map(SnapshotParts::assemble)
        ).onSuccess(snapshot -> {
            long elapsed = System.currentTimeMillis() - start;
            LOG.info(() -> "ConfigSnapshot loaded in " + elapsed + " ms: "
                + snapshot.profiles().size() + " profiles, "
                + snapshot.connectors().size() + " connectors, "
                + snapshot.plugins().size() + " plugins");
            promise.complete(snapshot);
        }).onFailure(err -> {
            LOG.log(Level.WARNING, "Failed to load ConfigSnapshot: " + err.getMessage());
            promise.fail(err);
        });

        return promise.future();
    }

    /**
     * Holder mutable des étapes de {@link #loadAll()} — une seule transaction,
     * un seul thread à la fois (chaque étape s'exécute après la précédente).
     * Permet de chaîner les requêtes en méthodes plates (indentation 1)
     * au lieu de compose imbriqués sur ~26 niveaux.
     */
    private static final class SnapshotParts {
        RootConfig root;
        LoggingConfig logging;
        CaProfilesGlobalConfig capg;
        List<ProfileConfig> profiles;
        List<ProfileListenerConfig> listeners;
        List<ProfileCardConfig> cards;
        ConnectionManagerConfig cm;
        List<ServiceMapEntryConfig> sm;
        List<ConnectorConfig> connectors;
        List<ConnectorServiceConfig> cs;
        CacheConfig cache;
        ClusterUdpConfig udp;
        WebUiConfig wui;
        List<PluginConfig> plugins;
        List<SidCacheLinkConfig> sidLinks;
        ExtendedNewcamdConfig ext;
        List<ConnectorCaidProfileConfig> caid;
        List<ProfileProviderIdentConfig> prov;
        List<NetworkProxyConfig> proxies;
        List<RelayNodeConfig> relayNodes = List.of();
        List<RelayCacheConfig> relayCaches = List.of();
        List<ListenerCacheLinkConfig> listenerCacheLinks = List.of();
        List<RelayNodeListenerAssignment> nodeListeners = List.of();
        List<RelayNodeConnectorAssignment> nodeConnectors = List.of();
        List<RelayUdpPeerConfig> udpPeers = List.of();
        DcwPolicyConfig dcwPolicy = DcwPolicyConfig.DEFAULT;
        List<DcwRuleConfig> dcwRules = List.of();
        List<DcwServiceMappingConfig> dcwMappings = List.of();
        CwCycleConfig cwCycle = CwCycleConfig.DEFAULT;
        PeerQuarantineConfig quarantine = PeerQuarantineConfig.DEFAULT;
        CacheExQuorumConfig quorum = CacheExQuorumConfig.DEFAULT;

        ConfigSnapshot assemble() {
            // Toutes les colonnes preservees (checksum/repair/zeroCounting/mappings) ; validation
            // stricte faite ensuite par ConfigValidator (snapshot refuse => dernier bon conserve).
            DcwPolicyConfig effectivePolicy = (dcwPolicy != null ? dcwPolicy : DcwPolicyConfig.DEFAULT)
                .withRulesAndMappings(dcwRules, dcwMappings);

            return new ConfigSnapshot(
                root, logging, capg,
                profiles, listeners, cards,
                cm, sm, connectors, cs,
                cache, udp, wui,
                plugins, sidLinks, ext,
                caid, prov, proxies,
                relayNodes, relayCaches, listenerCacheLinks,
                nodeListeners, nodeConnectors, udpPeers,
                effectivePolicy,
                cwCycle,
                quarantine != null ? quarantine : PeerQuarantineConfig.DEFAULT,
                quorum != null ? quorum : CacheExQuorumConfig.DEFAULT,
                System.currentTimeMillis()
            );
        }
    }

    private Future<SnapshotParts> loadRoot(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT proxy_name, proxy_origin_id, version FROM csp_root_config WHERE id = 1")
            .execute()
            .map(rows -> {
                Row r = firstOrNull(rows);
                p.root = r != null ? mapRoot(r) : RootConfig.DEFAULT;
                return p;
            });
    }

    private Future<SnapshotParts> loadLogging(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT log_level, log_file, log_ecm, log_emm, log_zap, log_failures,"
                + " silent, debug, hide_ip_addresses, log_dateformat, log_rotate_count, log_rotate_limit,"
                + " wt_bad_flags, wt_max_delay_ms, wt_include_file_events, et_min_count, version"
                + " FROM csp_logging_config WHERE id = 1")
            .execute()
            .map(rows -> {
                Row r = firstOrNull(rows);
                p.logging = r != null ? mapLogging(r) : LoggingConfig.DEFAULT;
                return p;
            });
    }

    private Future<SnapshotParts> loadCaProfilesGlobal(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT default_des_key_ref, default_connector_des_key_ref,"
                + " default_client_id_ref, default_connector_keepalive, default_connector_min_delay,"
                + " default_connector_max_queue, block_caid_mismatch, version"
                + " FROM csp_ca_profiles_global WHERE id = 1")
            .execute()
            .map(rows -> {
                Row r = firstOrNull(rows);
                p.capg = r != null ? mapCaProfilesGlobal(r) : CaProfilesGlobalConfig.DEFAULT;
                return p;
            });
    }

    private Future<SnapshotParts> loadProfiles(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT name, enabled, debug, cache_only, ca_id, network_id,"
                + " require_provider_match, check_ecm_length, max_cw_wait_ms, congestion_limit_ms,"
                + " mismatched_cards, service_conflicts, services_file, provider_filter, services_file_format,"
                + " block_caid_mismatch, version FROM csp_profile ORDER BY name")
            .execute()
            .map(rows -> {
                p.profiles = mapProfiles(rows);
                return p;
            });
    }

    private Future<SnapshotParts> loadListeners(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT id, profile_name, protocol, port, bind_address, enabled,"
                + " des_key_ref, allow_list, deny_list, version, card_mode, strict_policy, reshare_cap,"
                + " max_cards, max_inflight, max_per_ip, node_id, idle_timeout_sec"
                + " FROM csp_profile_listener ORDER BY id")
            .execute()
            .map(rows -> {
                p.listeners = mapListeners(rows);
                return p;
            });
    }

    private Future<SnapshotParts> loadCards(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT id, profile_name, card_id, card_label, ca_id,"
                + " node_id, version FROM csp_profile_card ORDER BY id")
            .execute()
            .map(rows -> {
                p.cards = mapCards(rows);
                return p;
            });
    }

    private Future<SnapshotParts> loadConnectionManager(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT max_threads, session_timeout_ms,"
                + " newcamd_max_msg_size, max_pending, max_connections_ip,"
                + " session_keepalive_sec, session_keepalive_excluded, version"
                + " FROM csp_connection_manager WHERE id = 1")
            .execute()
            .map(rows -> {
                Row r = firstOrNull(rows);
                p.cm = r != null ? mapConnectionManager(r) : ConnectionManagerConfig.DEFAULT;
                return p;
            });
    }

    private Future<SnapshotParts> loadServiceMap(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT id, profile_name, service_id,"
                + " service_name, service_type, provider_ident, ecm_length,"
                + " custom_data, version FROM csp_service_map ORDER BY id")
            .execute()
            .map(rows -> {
                p.sm = mapServiceMap(rows);
                return p;
            });
    }

    private Future<SnapshotParts> loadConnectors(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT name, profile_name,"
                + " protocol, enabled, debug, host, port, username,"
                + " password_ref, des_key_ref, connect_timeout_ms,"
                + " reconnect_delay_ms, keep_alive_sec, min_delay_ms,"
                + " max_queue, newcamd_max_msg_size, newcamd_client_id_ref,"
                + " radegast_response_timeout_ms, cccam_enabled, cccam_node_id,"
                + " cccam_version, cacheex_mode, cacheex_filter, cacheex_max_send, cacheex_max_hop,"
                + " chameleon_profiles, conax_reader_index, priority,"
                + " max_capacity, version, proxy_name, card_discovery,"
                + " reshare_grant, newcamd_dialect, reshare_cap, newcamd_password_encoding"
                + " FROM csp_connector ORDER BY name")
            .execute()
            .map(rows -> {
                p.connectors = mapConnectors(rows);
                return p;
            });
    }

    private Future<SnapshotParts> loadConnectorServices(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT id, connector_name,"
                + " service_id, service_name, version"
                + " FROM csp_connector_service ORDER BY id")
            .execute()
            .map(rows -> {
                p.cs = mapConnectorServices(rows);
                return p;
            });
    }

    private Future<SnapshotParts> loadCache(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT cw_max_age_ms,"
                + " maximum_size, max_cache_wait_ms,"
                + " max_cache_wait_percent, version"
                + " FROM csp_cache_config WHERE id = 1")
            .execute()
            .map(rows -> {
                Row r = firstOrNull(rows);
                p.cache = r != null ? mapCache(r) : CacheConfig.DEFAULT;
                return p;
            });
    }

    private Future<SnapshotParts> loadClusterUdp(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT enabled,"
                + " bind_port, bind_address, remote_hosts,"
                + " multicast_group, multicast_port,"
                + " multicast_ttl, auto_add_peers,"
                + " tracker_url_ref, ping_interval_ms,"
                + " resend_throttle_ms, max_packets_per_sec,"
                + " blacklist, sync_period_ms, version"
                + " FROM csp_cluster_udp_config WHERE id = 1")
            .execute()
            .map(rows -> {
                Row r = firstOrNull(rows);
                p.udp = r != null ? mapClusterUdp(r) : ClusterUdpConfig.DEFAULT;
                return p;
            });
    }

    private Future<SnapshotParts> loadWebUi(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT enabled,"
                + " listen_port, bind_address,"
                + " tls_enabled, tls_cert_ref,"
                + " tls_key_ref, admin_user_ref,"
                + " admin_password_ref, super_users,"
                + " allow_list, welcome_file,"
                + " session_timeout_min, version"
                + " FROM csp_webui_config WHERE id = 1")
            .execute()
            .map(rows -> {
                Row r = firstOrNull(rows);
                p.wui = r != null ? mapWebUi(r) : WebUiConfig.DEFAULT;
                return p;
            });
    }

    private Future<SnapshotParts> loadPlugins(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT name,"
                + " class_name, enabled, description,"
                + " config_json::text AS config_json, profiles, version"
                + " FROM csp_plugin ORDER BY name")
            .execute()
            .map(rows -> {
                p.plugins = mapPlugins(rows);
                return p;
            });
    }

    private Future<SnapshotParts> loadSidLinks(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT id,"
                + " profile_name, sid_a, sid_b"
                + " FROM csp_sid_cache_link ORDER BY id")
            .execute()
            .map(rows -> {
                p.sidLinks = mapSidLinks(rows);
                return p;
            });
    }

    private Future<SnapshotParts> loadExtendedNewcamd(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT enabled, listen_port, bind_address, catch_all, debug, des_key_ref, allow_list, deny_list, max_msg_size, version FROM csp_extended_newcamd WHERE id = 1")
            .execute()
            .map(rows -> {
                Row r = firstOrNull(rows);
                p.ext = r != null ? mapExtended(r) : ExtendedNewcamdConfig.DEFAULT;
                return p;
            });
    }

    private Future<SnapshotParts> loadCaidProfiles(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT id, connector_name, ordinal, ca_id, profile_name, version FROM csp_connector_caid_profile ORDER BY connector_name, ordinal")
            .execute()
            .map(rows -> {
                p.caid = mapCaidProfiles(rows);
                return p;
            });
    }

    private Future<SnapshotParts> loadProviderIdents(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT id, profile_name, provider_ident, label, version FROM csp_profile_provider_ident ORDER BY profile_name, provider_ident")
            .execute()
            .map(rows -> {
                p.prov = mapProviderIdents(rows);
                return p;
            });
    }

    /**
     * Charge les proxys SOCKS5 (csp_proxy) — requête extraite de loadAll
     * (Plan P2 : PAS de champ timeout sur le proxy, le budget T est porté
     * par le connecteur).
     */
    private Future<SnapshotParts> loadProxies(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT name, enabled, type, host, port, username, password_ref, description, version FROM csp_proxy ORDER BY name")
            .execute()
            .map(rows -> {
                p.proxies = mapProxies(rows);
                return p;
            });
    }

    /**
     * FIX Sonnet #2 : tolerance strictement limitee a la table absente pre-V10
     * (SQLSTATE 42P01 undefined_table). Toute autre panne PG est propagee :
     * jamais de fallback permissif sur panne.
     */
    static boolean isUndefinedTable(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            String msg = String.valueOf(t.getMessage());
            if (msg.contains("42P01") || msg.contains("undefined_table")
                || msg.contains("does not exist")) {
                // Verifie qu'il s'agit bien d'une table relais (evite de masquer
                // une autre table manquante).
                if (msg.contains("csp_relay_node") || msg.contains("csp_relay_cache")
                    || msg.contains("csp_listener_cache_link") || msg.contains("csp_relay_udp_peer")
                    || msg.contains("csp_relay_node_stats") || msg.contains("csp_host")
                    || msg.contains("csp_port") || msg.contains("42P01")) {
                    return true;
                }
            }
            // Vert.x PgException expose getSqlState() sans dependance dure :
            try {
                var m = t.getClass().getMethod("getSqlState");
                Object st = m.invoke(t);
                if ("42P01".equals(String.valueOf(st))) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** Charge les nodes relais (V10). Echec autre que 42P01 => propage. */
    private Future<SnapshotParts> loadNodeListeners(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT node_name, listener_id, enabled, version"
                + " FROM csp_relay_node_listener ORDER BY node_name, listener_id")
            .execute()
            .map(rows -> {
                List<RelayNodeListenerAssignment> list = new ArrayList<>();
                for (Row r : rows) {
                    try {
                        list.add(new RelayNodeListenerAssignment(
                            r.getString("node_name"),
                            r.getLong("listener_id") != null ? r.getLong("listener_id") : -1L,
                            Boolean.TRUE.equals(r.getBoolean("enabled")),
                            r.getLong("version") != null ? r.getLong("version") : 1L));
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Skipping malformed node-listener row: " + e.getMessage());
                    }
                }
                p.nodeListeners = list;
                return p;
            })
            .recover(err -> {
                if (isUndefinedTable(err)) {
                    p.nodeListeners = List.of();
                    return Future.succeededFuture(p);
                }
                return Future.failedFuture(err);
            });
    }

    private Future<SnapshotParts> loadNodeConnectors(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT node_name, connector_name, enabled, version"
                + " FROM csp_relay_node_connector ORDER BY node_name, connector_name")
            .execute()
            .map(rows -> {
                List<RelayNodeConnectorAssignment> list = new ArrayList<>();
                for (Row r : rows) {
                    try {
                        list.add(new RelayNodeConnectorAssignment(
                            r.getString("node_name"),
                            r.getString("connector_name"),
                            Boolean.TRUE.equals(r.getBoolean("enabled")),
                            r.getLong("version") != null ? r.getLong("version") : 1L));
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Skipping malformed node-connector row: " + e.getMessage());
                    }
                }
                p.nodeConnectors = list;
                return p;
            })
            .recover(err -> {
                if (isUndefinedTable(err)) {
                    p.nodeConnectors = List.of();
                    return Future.succeededFuture(p);
                }
                return Future.failedFuture(err);
            });
    }

    private Future<SnapshotParts> loadUdpPeers(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT id, cache_name, host, port, enabled, version"
                + " FROM csp_relay_udp_peer ORDER BY cache_name, host, port")
            .execute()
            .map(rows -> {
                List<RelayUdpPeerConfig> list = new ArrayList<>();
                for (Row r : rows) {
                    try {
                        list.add(new RelayUdpPeerConfig(
                            r.getLong("id") != null ? r.getLong("id") : -1L,
                            r.getString("cache_name"),
                            r.getString("host"),
                            r.getInteger("port") != null ? r.getInteger("port") : -1,
                            Boolean.TRUE.equals(r.getBoolean("enabled")),
                            r.getLong("version") != null ? r.getLong("version") : 1L));
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Skipping malformed udp peer row: " + e.getMessage());
                    }
                }
                p.udpPeers = list;
                return p;
            })
            .recover(err -> {
                if (isUndefinedTable(err)) {
                    LOG.fine("csp_relay_udp_peer unavailable (pre-V12) — empty");
                    p.udpPeers = List.of();
                    return Future.succeededFuture(p);
                }
                return Future.failedFuture(err);
            });
    }

    private Future<SnapshotParts> loadRelayNodes(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT node_name, display_name, enabled, authorized, revoked,"
                + " desired_version, applied_version, version, last_seen_at, csp_host, csp_port"
                + " FROM csp_relay_node ORDER BY node_name")
            .execute()
            .map(rows -> {
                p.relayNodes = mapRelayNodes(rows);
                return p;
            })
            .recover(err -> {
                if (isUndefinedTable(err)) {
                    LOG.fine("csp_relay_node unavailable (pre-V10) — empty relay nodes");
                    p.relayNodes = List.of();
                    return Future.succeededFuture(p);
                }
                return Future.failedFuture(err);
            });
    }

    private Future<SnapshotParts> loadRelayCaches(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT name, kind, node_name, enabled, max_age_ms,"
                + " max_entries, max_inflight, version FROM csp_relay_cache ORDER BY name")
            .execute()
            .map(rows -> {
                p.relayCaches = mapRelayCaches(rows);
                return p;
            })
            .recover(err -> {
                if (isUndefinedTable(err)) {
                    LOG.fine("csp_relay_cache unavailable (pre-V10) — empty");
                    p.relayCaches = List.of();
                    return Future.succeededFuture(p);
                }
                return Future.failedFuture(err);
            });
    }

    private Future<SnapshotParts> loadListenerCacheLinks(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT listener_id, cache_name, can_consult, can_feed,"
                + " can_export, enabled, version FROM csp_listener_cache_link ORDER BY listener_id, cache_name")
            .execute()
            .map(rows -> {
                p.listenerCacheLinks = mapListenerCacheLinks(rows);
                return p;
            })
            .recover(err -> {
                if (isUndefinedTable(err)) {
                    LOG.fine("csp_listener_cache_link unavailable (pre-V10) — empty");
                    p.listenerCacheLinks = List.of();
                    return Future.succeededFuture(p);
                }
                return Future.failedFuture(err);
            });
    }

    // -- Versioned update operations (for later lots: L5c admin API) --
    // Toutes les mutations ecrivent csp_audit_log DANS LA MEME transaction.
    // Un echec audit rollback la mutation. Actor passe explicitement, overload
    // sans actor => "system".

    public Future<Long> updateRoot(String proxyName, long expectedVersion) {
        return updateRoot(proxyName, expectedVersion, "system");
    }

    public Future<Long> updateRoot(String proxyName, long expectedVersion, String actor) {
        return pool.withTransaction(client ->
            client.preparedQuery(
                "UPDATE csp_root_config SET proxy_name = $1, version = version + 1,"
                    + " updated_at = now() WHERE id = 1 AND version = $2")
                .execute(Tuple.of(proxyName, expectedVersion))
                .compose(rows -> {
                    checkOptimisticLock(rows, "csp_root_config", expectedVersion);
                    long newVersion = expectedVersion + 1;
                    return insertAudit(client, "CONFIG_UPDATED", "csp_root_config", "1",
                        expectedVersion, newVersion, actor, "proxy_name=" + proxyName)
                        .map(v -> newVersion);
                }));
    }

    public Future<Long> updateCache(long cwMaxAgeMs, long maximumSize,
                                     long maxCacheWaitMs, int maxCacheWaitPercent,
                                     long expectedVersion) {
        return updateCache(cwMaxAgeMs, maximumSize, maxCacheWaitMs, maxCacheWaitPercent, expectedVersion, "system");
    }

    public Future<Long> updateCache(long cwMaxAgeMs, long maximumSize,
                                     long maxCacheWaitMs, int maxCacheWaitPercent,
                                     long expectedVersion, String actor) {
        return pool.withTransaction(client ->
            client.preparedQuery(
                "UPDATE csp_cache_config SET cw_max_age_ms = $1, maximum_size = $2,"
                    + " max_cache_wait_ms = $3, max_cache_wait_percent = $4,"
                    + " version = version + 1, updated_at = now()"
                    + " WHERE id = 1 AND version = $5")
                .execute(Tuple.of(cwMaxAgeMs, maximumSize, maxCacheWaitMs, maxCacheWaitPercent, expectedVersion))
                .compose(rows -> {
                    checkOptimisticLock(rows, "csp_cache_config", expectedVersion);
                    long newVersion = expectedVersion + 1;
                    return insertAudit(client, "CONFIG_UPDATED", "csp_cache_config", "1",
                        expectedVersion, newVersion, actor, "cache updated")
                        .map(v -> newVersion);
                }));
    }

    public Future<Long> updateClusterUdp(ClusterUdpConfig cfg) {
        return updateClusterUdp(cfg, "system");
    }

    public Future<Long> updateClusterUdp(ClusterUdpConfig cfg, String actor) {
        return pool.withTransaction(client ->
            client.preparedQuery(
                "UPDATE csp_cluster_udp_config SET enabled = $1, bind_port = $2, bind_address = $3,"
                    + " remote_hosts = $4, multicast_group = $5, multicast_port = $6,"
                    + " multicast_ttl = $7, auto_add_peers = $8, tracker_url_ref = $9,"
                    + " ping_interval_ms = $10, resend_throttle_ms = $11, max_packets_per_sec = $12,"
                    + " blacklist = $13, sync_period_ms = $14, version = version + 1, updated_at = now()"
                    + " WHERE id = 1 AND version = $15")
                .execute(Tuple.of(cfg.enabled(), cfg.bindPort(), cfg.bindAddress(),
                    cfg.remoteHosts(), cfg.multicastGroup(), cfg.multicastPort(),
                    cfg.multicastTtl(), cfg.autoAddPeers(), cfg.trackerUrlRef(),
                    cfg.pingIntervalMs(), cfg.resendThrottleMs(), cfg.maxPacketsPerSec(),
                    cfg.blacklist(), cfg.syncPeriodMs(), cfg.version()))
                .compose(rows -> {
                    checkOptimisticLock(rows, "csp_cluster_udp_config", cfg.version());
                    long newVersion = cfg.version() + 1;
                    return insertAudit(client, "CONFIG_UPDATED", "csp_cluster_udp_config", "1",
                        cfg.version(), newVersion, actor, "cluster udp updated")
                        .map(v -> newVersion);
                }));
    }

    public Future<Long> updateExtendedNewcamd(ExtendedNewcamdConfig cfg, String actor) {
        return pool.withTransaction(client ->
            client.preparedQuery(
                "UPDATE csp_extended_newcamd SET enabled=$1, listen_port=$2, bind_address=$3, catch_all=$4, debug=$5, des_key_ref=$6, allow_list=$7, deny_list=$8, max_msg_size=$9, version=version+1, updated_at=now(), updated_by=$11 WHERE id=1 AND version=$10")
                .execute(Tuple.of(cfg.enabled(), cfg.listenPort(), cfg.bindAddress(), cfg.catchAll(), cfg.debug(), cfg.desKeyRef(), cfg.allowList(), cfg.denyList(), cfg.maxMsgSize(), cfg.version(), actor))
                .compose(rows -> {
                    checkOptimisticLock(rows, "csp_extended_newcamd", cfg.version());
                    long newVersion = cfg.version() + 1;
                    return insertAudit(client, "CONFIG_UPDATED", "csp_extended_newcamd", "1",
                        cfg.version(), newVersion, actor, "extended-newcamd updated")
                        .map(v -> newVersion);
                }));
    }

    public Future<Long> updateExtendedNewcamd(ExtendedNewcamdConfig cfg) {
        return updateExtendedNewcamd(cfg, "system");
    }

    public Future<Long> replaceCaidProfileMap(String connectorName, List<ConnectorCaidProfileConfig> entries, long expectedConnectorVersion, String actor) {
        if (entries == null || entries.isEmpty()) {
            return Future.failedFuture(new ConfigValidationException("CAID map must be non-empty for dynamic connector"));
        }
        // validate ordinals contiguous before SQL
        try { validateCaidMap(entries); } catch (IllegalArgumentException e) { return Future.failedFuture(e); }
        if (!isValidName(connectorName)) {
            return Future.failedFuture(new ConfigValidationException("Invalid connector name"));
        }
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT version, profile_name FROM csp_connector WHERE name=$1 FOR UPDATE")
                .execute(Tuple.of(connectorName))
                .compose(selRows -> {
                    Row row = firstOrNull(selRows);
                    if (row == null) {
                        return Future.failedFuture(new ConfigNotFoundException("csp_connector", connectorName));
                    }
                    String profileName = row.getString("profile_name");
                    if (profileName != null) {
                        return Future.failedFuture(new ConfigValidationException("Fixed connector cannot have CAID map"));
                    }
                    long currentVersion = row.getLong("version");
                    if (currentVersion != expectedConnectorVersion) {
                        return Future.failedFuture(new OptimisticLockException("csp_connector", connectorName, expectedConnectorVersion));
                    }
                    return validateMapProfilesEnabled(client, entries)
                        .compose(v -> client.preparedQuery("DELETE FROM csp_connector_caid_profile WHERE connector_name=$1")
                            .execute(Tuple.of(connectorName)))
                        .compose(del -> {
                            Future<Void> chain = Future.succeededFuture();
                            for (ConnectorCaidProfileConfig e : entries) {
                                ConnectorCaidProfileConfig entry = e;
                                chain = chain.compose(v -> client.preparedQuery("INSERT INTO csp_connector_caid_profile (connector_name, ordinal, ca_id, profile_name, version, updated_at, updated_by) VALUES ($1,$2,$3,$4,1,now(),$5)")
                                    .execute(Tuple.of(connectorName, entry.ordinal(), entry.caId(), entry.profileName(), actor))
                                    .mapEmpty());
                            }
                            return chain;
                        })
                        .compose(v -> client.preparedQuery("UPDATE csp_connector SET version=version+1, updated_at=now(), updated_by=$2 WHERE name=$1 AND version=$3 RETURNING version")
                            .execute(Tuple.of(connectorName, actor, expectedConnectorVersion)))
                        .compose(upRows -> {
                            if (upRows.rowCount() == 0) {
                                return Future.failedFuture(new OptimisticLockException("csp_connector", connectorName, expectedConnectorVersion));
                            }
                            Row r = firstOrNull(upRows);
                            long newVersion = r != null && r.getLong("version") != null ? r.getLong("version") : expectedConnectorVersion + 1;
                            return insertAudit(client, "CONFIG_UPDATED", "csp_connector", connectorName,
                                expectedConnectorVersion, newVersion, actor, "replace caid-profile-map for " + connectorName)
                                .map(v -> newVersion);
                        });
                }));
    }

    public Future<Long> replaceProviderIdents(String profileName, List<ProfileProviderIdentConfig> idents, long expectedProfileVersion, String actor) {
        final List<ProfileProviderIdentConfig> norm = idents == null ? List.of() : idents;
        if (!isValidName(profileName)) {
            return Future.failedFuture(new ConfigValidationException("Invalid profile name"));
        }
        if (norm.size() > 256) {
            return Future.failedFuture(new ConfigValidationException("provider idents limit 256"));
        }
        // duplicate check before SQL
        {
            long distinct = norm.stream().map(ProfileProviderIdentConfig::providerIdent).distinct().count();
            if (distinct != norm.size()) return Future.failedFuture(new ConfigValidationException("duplicate providerIdent"));
        }
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT version, enabled FROM csp_profile WHERE name=$1 FOR UPDATE")
                .execute(Tuple.of(profileName))
                .compose(selRows -> {
                    Row row = firstOrNull(selRows);
                    if (row == null) {
                        return Future.failedFuture(new ConfigNotFoundException("csp_profile", profileName));
                    }
                    Boolean enabled = row.getBoolean("enabled");
                    if (Boolean.FALSE.equals(enabled)) {
                        return Future.failedFuture(new ConfigValidationException("Profile disabled: " + profileName));
                    }
                    long currentVersion = row.getLong("version");
                    if (currentVersion != expectedProfileVersion) {
                        return Future.failedFuture(new OptimisticLockException("csp_profile", profileName, expectedProfileVersion));
                    }
                    return client.preparedQuery("DELETE FROM csp_profile_provider_ident WHERE profile_name=$1")
                        .execute(Tuple.of(profileName))
                        .compose(del -> {
                            Future<Void> chain = Future.succeededFuture();
                            for (ProfileProviderIdentConfig e : norm) {
                                ProfileProviderIdentConfig entry = e;
                                chain = chain.compose(v -> client.preparedQuery("INSERT INTO csp_profile_provider_ident (profile_name, provider_ident, label, version, updated_at, updated_by) VALUES ($1,$2,$3,1,now(),$4)")
                                    .execute(Tuple.of(profileName, entry.providerIdent(), entry.label(), actor))
                                    .mapEmpty());
                            }
                            return chain;
                        })
                        .compose(v -> client.preparedQuery("UPDATE csp_profile SET version=version+1, updated_at=now(), updated_by=$2 WHERE name=$1 AND version=$3 RETURNING version")
                            .execute(Tuple.of(profileName, actor, expectedProfileVersion)))
                        .compose(upRows -> {
                            if (upRows.rowCount() == 0) {
                                return Future.failedFuture(new OptimisticLockException("csp_profile", profileName, expectedProfileVersion));
                            }
                            Row r = firstOrNull(upRows);
                            long newVersion = r != null && r.getLong("version") != null ? r.getLong("version") : expectedProfileVersion + 1;
                            return insertAudit(client, "CONFIG_UPDATED", "csp_profile", profileName,
                                expectedProfileVersion, newVersion, actor, "replace provider-idents for " + profileName)
                                .map(v -> newVersion);
                        });
                }));
    }

    public Future<Long> upsertConnectorWithCaidMap(ConnectorConfig conn, List<ConnectorCaidProfileConfig> map, String actor) {
        if (conn.profileName() != null) {
            return Future.failedFuture(new ConfigValidationException("Dynamic connector must have NULL profileName"));
        }
        if (map == null || map.isEmpty()) {
            return Future.failedFuture(new ConfigValidationException("Dynamic connector requires nonempty CAID map"));
        }
        try { validateCaidMap(map); } catch (IllegalArgumentException e) { return Future.failedFuture(e); }
        if (!isValidName(conn.name())) {
            return Future.failedFuture(new ConfigValidationException("Invalid connector name"));
        }
        long expectedVersion = conn.version();
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT version, profile_name FROM csp_connector WHERE name=$1 FOR UPDATE")
                .execute(Tuple.of(conn.name()))
                .compose(selRows -> {
                    Row existing = firstOrNull(selRows);
                    if (existing == null) {
                        if (expectedVersion != 1) {
                            return Future.failedFuture(new ConfigNotFoundException("csp_connector", conn.name()));
                        }
                        return validateMapProfilesEnabled(client, map)
                            .compose(v -> {
                                String insertSql = "INSERT INTO csp_connector (name, profile_name, protocol, enabled, debug, host, port,"
                                    + " username, password_ref, des_key_ref, connect_timeout_ms, reconnect_delay_ms,"
                                    + " keep_alive_sec, min_delay_ms, max_queue, newcamd_max_msg_size, newcamd_client_id_ref,"
                                    + " radegast_response_timeout_ms, cccam_enabled, cccam_node_id, cccam_version,"
                                    + " cacheex_mode, cacheex_filter, cacheex_max_send, cacheex_max_hop, chameleon_profiles, conax_reader_index,"
                                    + " priority, max_capacity, proxy_name, card_discovery, reshare_grant, newcamd_dialect, reshare_cap, newcamd_password_encoding,"
                                    + " version, updated_at, updated_by) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23,$24,$25,$26,$27,$28,$29,$30,$31,$32,$33,$34,$35,1,now(),$36) ON CONFLICT (name) DO NOTHING RETURNING version";
                                return client.preparedQuery(insertSql)
                                    .execute(Tuple.of(
                                        conn.name(), null, conn.protocol(), conn.enabled(), conn.debug(),
                                        conn.host(), conn.port(), conn.username(), conn.passwordRef(), conn.desKeyRef(),
                                        conn.connectTimeoutMs(), conn.reconnectDelayMs(), conn.keepAliveSec(), conn.minDelayMs(),
                                        conn.maxQueue(), conn.newcamdMaxMsgSize(), conn.newcamdClientIdRef(),
                                        conn.radegastResponseTimeoutMs(), conn.cccamEnabled(), conn.cccamNodeId(),
                                        conn.cccamVersion(), conn.cacheexMode(), conn.cacheexFilter(), conn.cacheexMaxSend(),
                                        conn.cacheexMaxHop(),
                                        conn.chameleonProfiles(), conn.conaxReaderIndex(), conn.priority(), conn.maxCapacity(),
                                        conn.proxyName(), conn.cardDiscovery(), conn.reshareGrant(), conn.newcamdDialect(),
                                        conn.reshareCap(), conn.newcamdPasswordEncoding(), actor));
                            })
                            .compose(insRows -> {
                                if (insRows.rowCount() == 0) {
                                    return Future.failedFuture(new OptimisticLockException("csp_connector", conn.name(), expectedVersion));
                                }
                                Future<Void> chain = Future.succeededFuture();
                                for (ConnectorCaidProfileConfig e : map) {
                                    ConnectorCaidProfileConfig entry = e;
                                    chain = chain.compose(v2 -> client.preparedQuery("INSERT INTO csp_connector_caid_profile (connector_name, ordinal, ca_id, profile_name, version, updated_at, updated_by) VALUES ($1,$2,$3,$4,1,now(),$5)")
                                        .execute(Tuple.of(conn.name(), entry.ordinal(), entry.caId(), entry.profileName(), actor))
                                        .mapEmpty());
                                }
                                return chain.compose(v2 -> {
                                    Row r = firstOrNull(insRows);
                                    long newVersion = r != null && r.getLong("version") != null ? r.getLong("version") : 1L;
                                    return insertAudit(client, "CONFIG_UPSERTED", "csp_connector", conn.name(),
                                        null, newVersion, actor, "upsert dynamic connector")
                                        .map(v3 -> newVersion);
                                });
                            });
                    } else {
                        long currentVersion = existing.getLong("version");
                        if (currentVersion != expectedVersion) {
                            return Future.failedFuture(new OptimisticLockException("csp_connector", conn.name(), expectedVersion));
                        }
                        return validateMapProfilesEnabled(client, map)
                            .compose(v -> client.preparedQuery("DELETE FROM csp_connector_caid_profile WHERE connector_name=$1")
                                .execute(Tuple.of(conn.name())))
                            .compose(del -> client.preparedQuery("UPDATE csp_connector SET profile_name=NULL, protocol=$2, enabled=$3, debug=$4, host=$5, port=$6,"
                                + " username=$7, password_ref=$8, des_key_ref=$9, connect_timeout_ms=$10, reconnect_delay_ms=$11,"
                                + " keep_alive_sec=$12, min_delay_ms=$13, max_queue=$14, newcamd_max_msg_size=$15, newcamd_client_id_ref=$16,"
                                + " radegast_response_timeout_ms=$17, cccam_enabled=$18, cccam_node_id=$19, cccam_version=$20,"
                                + " cacheex_mode=$21, cacheex_filter=$22, cacheex_max_send=$23, cacheex_max_hop=$24, chameleon_profiles=$25, conax_reader_index=$26,"
                                + " priority=$27, max_capacity=$28, proxy_name=$29, card_discovery=$30, reshare_grant=$31, newcamd_dialect=$32, reshare_cap=$33, newcamd_password_encoding=$34,"
                                + " version=version+1, updated_at=now(), updated_by=$35 WHERE name=$1 AND version=$36 RETURNING version")
                                .execute(Tuple.of(
                                    conn.name(), conn.protocol(), conn.enabled(), conn.debug(),
                                    conn.host(), conn.port(), conn.username(), conn.passwordRef(), conn.desKeyRef(),
                                    conn.connectTimeoutMs(), conn.reconnectDelayMs(), conn.keepAliveSec(), conn.minDelayMs(),
                                    conn.maxQueue(), conn.newcamdMaxMsgSize(), conn.newcamdClientIdRef(),
                                    conn.radegastResponseTimeoutMs(), conn.cccamEnabled(), conn.cccamNodeId(),
                                    conn.cccamVersion(), conn.cacheexMode(), conn.cacheexFilter(), conn.cacheexMaxSend(),
                                    conn.cacheexMaxHop(),
                                    conn.chameleonProfiles(), conn.conaxReaderIndex(), conn.priority(), conn.maxCapacity(),
                                    conn.proxyName(), conn.cardDiscovery(), conn.reshareGrant(), conn.newcamdDialect(),
                                    conn.reshareCap(), conn.newcamdPasswordEncoding(), actor, expectedVersion)))
                            .compose(upRows -> {
                                if (upRows.rowCount() == 0) {
                                    return Future.failedFuture(new OptimisticLockException("csp_connector", conn.name(), expectedVersion));
                                }
                                Future<Void> chain = Future.succeededFuture();
                                for (ConnectorCaidProfileConfig e : map) {
                                    ConnectorCaidProfileConfig entry = e;
                                    chain = chain.compose(v2 -> client.preparedQuery("INSERT INTO csp_connector_caid_profile (connector_name, ordinal, ca_id, profile_name, version, updated_at, updated_by) VALUES ($1,$2,$3,$4,1,now(),$5)")
                                        .execute(Tuple.of(conn.name(), entry.ordinal(), entry.caId(), entry.profileName(), actor))
                                        .mapEmpty());
                                }
                                return chain.compose(v2 -> {
                                    Row r = firstOrNull(upRows);
                                    long newVersion = r != null && r.getLong("version") != null ? r.getLong("version") : expectedVersion + 1;
                                    return insertAudit(client, "CONFIG_UPSERTED", "csp_connector", conn.name(),
                                        expectedVersion, newVersion, actor, "upsert dynamic connector")
                                        .map(v3 -> newVersion);
                                });
                            });
                    }
                }));
    }

    public Future<Long> upsertProfile(ProfileConfig profile) {
        return upsertProfile(profile, "system");
    }

    public Future<Long> upsertProfile(ProfileConfig profile, String actor) {
        long expectedVersion = profile.version();
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT version FROM csp_profile WHERE name=$1 FOR UPDATE")
                .execute(Tuple.of(profile.name()))
                .compose(selRows -> {
                    Row existing = firstOrNull(selRows);
                    if (existing == null) {
                        // Absent: create only if expectedVersion is 0 or 1 (convention create)
                        if (expectedVersion != 1) {
                            throw new ConfigNotFoundException("csp_profile", profile.name());
                        }
                        String insertSql = "INSERT INTO csp_profile (name, enabled, debug, cache_only, ca_id, network_id,"
                            + " require_provider_match, check_ecm_length, max_cw_wait_ms, congestion_limit_ms,"
                            + " mismatched_cards, service_conflicts, services_file, provider_filter, services_file_format,"
                            + " block_caid_mismatch, version, updated_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,1,now()) ON CONFLICT (name) DO NOTHING RETURNING version";
                        return client.preparedQuery(insertSql)
                            .execute(Tuple.of(
                                profile.name(), profile.enabled(), profile.debug(), profile.cacheOnly(),
                                profile.caId(), profile.networkId(),
                                profile.requireProviderMatch(), profile.checkEcmLength(),
                                profile.maxCwWaitMs(), profile.congestionLimitMs(),
                                profile.mismatchedCards(), profile.serviceConflicts(),
                                profile.servicesFile(), profile.providerFilter(), profile.servicesFileFormat(),
                                profile.blockCaidMismatch()))
                            .compose(insRows -> {
                                if (insRows.rowCount() == 0) {
                                    // concurrent insert won race
                                    throw new OptimisticLockException("csp_profile", profile.name(), expectedVersion);
                                }
                                Row r = firstOrNull(insRows);
                                long newVersion = r != null && r.getLong("version") != null ? r.getLong("version") : 1L;
                                return insertAudit(client, "CONFIG_UPSERTED", "csp_profile", profile.name(),
                                    null, newVersion, actor, "upsert profile " + profile.name())
                                    .map(v -> newVersion);
                            });
                    } else {
                        long currentVersion = existing.getLong("version");
                        if (currentVersion != expectedVersion) {
                            throw new OptimisticLockException("csp_profile", profile.name(), expectedVersion);
                        }
                        String updateSql = "UPDATE csp_profile SET enabled=$2, debug=$3, cache_only=$4, ca_id=$5, network_id=$6,"
                            + " require_provider_match=$7, check_ecm_length=$8, max_cw_wait_ms=$9, congestion_limit_ms=$10,"
                            + " mismatched_cards=$11, service_conflicts=$12, services_file=$13, provider_filter=$14, services_file_format=$15,"
                            + " block_caid_mismatch=$16, version=version+1, updated_at=now() WHERE name=$1 AND version=$17 RETURNING version";
                        return client.preparedQuery(updateSql)
                            .execute(Tuple.of(
                                profile.name(), profile.enabled(), profile.debug(), profile.cacheOnly(),
                                profile.caId(), profile.networkId(),
                                profile.requireProviderMatch(), profile.checkEcmLength(),
                                profile.maxCwWaitMs(), profile.congestionLimitMs(),
                                profile.mismatchedCards(), profile.serviceConflicts(),
                                profile.servicesFile(), profile.providerFilter(), profile.servicesFileFormat(),
                                profile.blockCaidMismatch(), expectedVersion))
                            .compose(upRows -> {
                                if (upRows.rowCount() == 0) {
                                    throw new OptimisticLockException("csp_profile", profile.name(), expectedVersion);
                                }
                                Row r = firstOrNull(upRows);
                                long newVersion = r != null && r.getLong("version") != null ? r.getLong("version") : expectedVersion + 1;
                                return insertAudit(client, "CONFIG_UPSERTED", "csp_profile", profile.name(),
                                    expectedVersion, newVersion, actor, "upsert profile " + profile.name())
                                    .map(v -> newVersion);
                            });
                    }
                }));
    }

    public Future<Long> upsertConnector(ConnectorConfig conn) {
        return upsertConnector(conn, "system");
    }

    public Future<Long> upsertConnector(ConnectorConfig conn, String actor) {
        long expectedVersion = conn.version();
        boolean isFixed = conn.hasFixedProfile();
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT version, profile_name FROM csp_connector WHERE name=$1 FOR UPDATE")
                .execute(Tuple.of(conn.name()))
                .compose(selRows -> {
                    Row existing = firstOrNull(selRows);
                    if (existing == null) {
                        if (expectedVersion != 1) {
                            throw new ConfigNotFoundException("csp_connector", conn.name());
                        }
                        if (!isFixed) {
                            throw new IllegalArgumentException("Dynamic connector creation requires upsertConnectorWithCaidMap");
                        }
                        String insertSql = "INSERT INTO csp_connector (name, profile_name, protocol, enabled, debug, host, port,"
                            + " username, password_ref, des_key_ref, connect_timeout_ms, reconnect_delay_ms,"
                            + " keep_alive_sec, min_delay_ms, max_queue, newcamd_max_msg_size, newcamd_client_id_ref,"
                            + " radegast_response_timeout_ms, cccam_enabled, cccam_node_id, cccam_version,"
                            + " cacheex_mode, cacheex_filter, cacheex_max_send, cacheex_max_hop, chameleon_profiles, conax_reader_index,"
                            + " priority, max_capacity, proxy_name, card_discovery, reshare_grant, newcamd_dialect, reshare_cap, newcamd_password_encoding,"
                            + " version, updated_at, updated_by) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23,$24,$25,$26,$27,$28,$29,$30,$31,$32,$33,$34,$35,1,now(),$36) ON CONFLICT (name) DO NOTHING RETURNING version";
                        return client.preparedQuery(insertSql)
                            .execute(Tuple.of(
                                conn.name(), conn.profileName(), conn.protocol(), conn.enabled(), conn.debug(),
                                conn.host(), conn.port(), conn.username(), conn.passwordRef(), conn.desKeyRef(),
                                conn.connectTimeoutMs(), conn.reconnectDelayMs(), conn.keepAliveSec(), conn.minDelayMs(),
                                conn.maxQueue(), conn.newcamdMaxMsgSize(), conn.newcamdClientIdRef(),
                                conn.radegastResponseTimeoutMs(), conn.cccamEnabled(), conn.cccamNodeId(),
                                conn.cccamVersion(), conn.cacheexMode(), conn.cacheexFilter(), conn.cacheexMaxSend(),
                                conn.cacheexMaxHop(),
                                conn.chameleonProfiles(), conn.conaxReaderIndex(), conn.priority(), conn.maxCapacity(),
                                conn.proxyName(), conn.cardDiscovery(), conn.reshareGrant(), conn.newcamdDialect(),
                                conn.reshareCap(), conn.newcamdPasswordEncoding(), actor))
                            .compose(insRows -> {
                                if (insRows.rowCount() == 0) {
                                    throw new OptimisticLockException("csp_connector", conn.name(), expectedVersion);
                                }
                                Row r = firstOrNull(insRows);
                                long newVersion = r != null && r.getLong("version") != null ? r.getLong("version") : 1L;
                                return insertAudit(client, "CONFIG_UPSERTED", "csp_connector", conn.name(),
                                    null, newVersion, actor, "upsert connector " + conn.name())
                                    .map(v -> newVersion);
                            });
                    } else {
                        long currentVersion = existing.getLong("version");
                        if (currentVersion != expectedVersion) {
                            throw new OptimisticLockException("csp_connector", conn.name(), expectedVersion);
                        }
                        if (!isFixed) {
                            throw new IllegalArgumentException("Dynamic connector update requires upsertConnectorWithCaidMap");
                        }
                        // Fixed connector update: ensure any existing CAID map is deleted atomically
                        return client.preparedQuery("DELETE FROM csp_connector_caid_profile WHERE connector_name=$1")
                            .execute(Tuple.of(conn.name()))
                            .compose(del -> client.preparedQuery("UPDATE csp_connector SET profile_name=$2, protocol=$3, enabled=$4, debug=$5, host=$6, port=$7,"
                                + " username=$8, password_ref=$9, des_key_ref=$10, connect_timeout_ms=$11, reconnect_delay_ms=$12,"
                                + " keep_alive_sec=$13, min_delay_ms=$14, max_queue=$15, newcamd_max_msg_size=$16, newcamd_client_id_ref=$17,"
                                + " radegast_response_timeout_ms=$18, cccam_enabled=$19, cccam_node_id=$20, cccam_version=$21,"
                                + " cacheex_mode=$22, cacheex_filter=$23, cacheex_max_send=$24, cacheex_max_hop=$25, chameleon_profiles=$26, conax_reader_index=$27,"
                                + " priority=$28, max_capacity=$29, proxy_name=$30, card_discovery=$31, reshare_grant=$32, newcamd_dialect=$33, reshare_cap=$34, newcamd_password_encoding=$35,"
                                + " version=version+1, updated_at=now(), updated_by=$36 WHERE name=$1 AND version=$37 RETURNING version")
                                .execute(Tuple.of(
                                    conn.name(), conn.profileName(), conn.protocol(), conn.enabled(), conn.debug(),
                                    conn.host(), conn.port(), conn.username(), conn.passwordRef(), conn.desKeyRef(),
                                    conn.connectTimeoutMs(), conn.reconnectDelayMs(), conn.keepAliveSec(), conn.minDelayMs(),
                                    conn.maxQueue(), conn.newcamdMaxMsgSize(), conn.newcamdClientIdRef(),
                                    conn.radegastResponseTimeoutMs(), conn.cccamEnabled(), conn.cccamNodeId(),
                                    conn.cccamVersion(), conn.cacheexMode(), conn.cacheexFilter(), conn.cacheexMaxSend(),
                                    conn.cacheexMaxHop(),
                                    conn.chameleonProfiles(), conn.conaxReaderIndex(), conn.priority(), conn.maxCapacity(),
                                    conn.proxyName(), conn.cardDiscovery(), conn.reshareGrant(), conn.newcamdDialect(),
                                    conn.reshareCap(), conn.newcamdPasswordEncoding(), actor, expectedVersion)))
                            .compose(upRows -> {
                                if (upRows.rowCount() == 0) {
                                    throw new OptimisticLockException("csp_connector", conn.name(), expectedVersion);
                                }
                                Row r = firstOrNull(upRows);
                                long newVersion = r != null && r.getLong("version") != null ? r.getLong("version") : expectedVersion + 1;
                                return insertAudit(client, "CONFIG_UPSERTED", "csp_connector", conn.name(),
                                    expectedVersion, newVersion, actor, "upsert connector " + conn.name())
                                    .map(v -> newVersion);
                            });
                    }
                }));
    }

    public Future<Void> deleteConnector(String name, long expectedVersion, String actor) {
        if (expectedVersion < 1) return Future.failedFuture(new ConfigValidationException("version must be >=1"));
        if (!isValidName(name)) return Future.failedFuture(new ConfigValidationException("Invalid name"));
        return pool.withTransaction(client ->
            client.preparedQuery("DELETE FROM csp_connector WHERE name = $1 AND version = $2 RETURNING version")
                .execute(Tuple.of(name, expectedVersion))
                .compose(delRows -> {
                    if (delRows.rowCount() > 0) {
                        Row r = firstOrNull(delRows);
                        long v = r != null ? r.getLong("version") : expectedVersion;
                        return insertAudit(client, "CONFIG_DELETED", "csp_connector", name,
                            v, null, actor, "delete connector " + name).mapEmpty();
                    }
                    // zero rows: distinguish 404 vs 409
                    return client.preparedQuery("SELECT version FROM csp_connector WHERE name = $1")
                        .execute(Tuple.of(name))
                        .compose(selRows -> {
                            Row selRow = firstOrNull(selRows);
                            if (selRow == null) {
                                return Future.failedFuture(new ConfigNotFoundException("csp_connector", name));
                            }
                            throw new OptimisticLockException("csp_connector", name, expectedVersion);
                        });
                })
        );
    }

    public Future<Void> deleteProfile(String name, long expectedVersion, String actor) {
        if (expectedVersion < 1) return Future.failedFuture(new ConfigValidationException("version must be >=1"));
        if (!isValidName(name)) return Future.failedFuture(new ConfigValidationException("Invalid name"));
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT 1 FROM csp_dcw_rule WHERE profile = $1 UNION SELECT 1 FROM csp_dcw_service_mapping WHERE profile = $1 LIMIT 1")
                .execute(Tuple.of(name))
                .recover(err -> isUndefinedTable(err) ? Future.succeededFuture(null) : Future.failedFuture(err))
                .compose(dcwRows -> {
                    if (dcwRows != null && dcwRows.rowCount() > 0) {
                        return Future.failedFuture(new ConfigValidationException("Cannot delete profile '" + name + "': referenced by DCW rules or service mappings"));
                    }
                    return client.preparedQuery("DELETE FROM csp_profile WHERE name = $1 AND version = $2 RETURNING version")
                        .execute(Tuple.of(name, expectedVersion))
                        .compose(delRows -> {
                            if (delRows.rowCount() > 0) {
                                Row r = firstOrNull(delRows);
                                long v = r != null ? r.getLong("version") : expectedVersion;
                                return insertAudit(client, "CONFIG_DELETED", "csp_profile", name,
                                    v, null, actor, "delete profile " + name).mapEmpty();
                            }
                            return client.preparedQuery("SELECT version FROM csp_profile WHERE name = $1")
                                .execute(Tuple.of(name))
                                .compose(selRows -> {
                                    Row selRow = firstOrNull(selRows);
                                    if (selRow == null) {
                                        return Future.failedFuture(new ConfigNotFoundException("csp_profile", name));
                                    }
                                    throw new OptimisticLockException("csp_profile", name, expectedVersion);
                                });
                        });
                })
        );
    }

    // -- Import dedup + audit (Lot C) --

    /**
     * Retourne le sous-ensemble des digests candidats deja presents en base.
     * Requete bulk unique (ANY) pour tout le lot.
     */
    public Future<java.util.Set<String>> findExistingDedupDigests(java.util.Set<String> candidateDigests) {
        if (candidateDigests == null || candidateDigests.isEmpty()) {
            return Future.succeededFuture(java.util.Collections.emptySet());
        }
        String[] arr = candidateDigests.toArray(new String[0]);
        return pool.preparedQuery("SELECT dedup_digest FROM csp_connector WHERE dedup_digest = ANY($1)")
            .execute(Tuple.of((Object) arr))
            .map(rows -> {
                java.util.Set<String> found = new java.util.HashSet<>();
                for (Row r : rows) {
                    String d = r.getString("dedup_digest");
                    if (d != null) found.add(d);
                }
                return found;
            });
    }

    /**
     * Met a jour le digest de dedup pour un connecteur existant.
     * Best-effort : l'appelant decide de la gestion d'erreur.
     */
    public Future<Void> setDedupDigest(String connectorName, String digest, String actor) {
        if (connectorName == null || connectorName.isBlank()) {
            return Future.failedFuture(new ConfigValidationException("connectorName required"));
        }
        if (digest == null || digest.isBlank()) {
            return Future.failedFuture(new ConfigValidationException("digest required"));
        }
        return pool.preparedQuery(
                "UPDATE csp_connector SET dedup_digest = $1, updated_at = now(), updated_by = $3 WHERE name = $2")
            .execute(Tuple.of(digest, connectorName, actor))
            .mapEmpty();
    }

    /**
     * Insere une entree d'audit pour un import de connecteurs.
     * details_json contient uniquement des agregats (pas de secrets ni host/IP individuels).
     */
    public Future<Void> insertImportAudit(String actor, String targetProfile,
                                          int total, int imported, int duplicate,
                                          int dnsFailed, int probeFailed, int persistFailed, int parseError) {
        String summary = "import profile=" + targetProfile
            + " imported " + imported + ", duplicate " + duplicate
            + ", dnsFailed " + dnsFailed + ", probeFailed " + probeFailed
            + ", persistFailed " + persistFailed + ", parseError " + parseError;
        // details_json compact : profile + counts (jamais de secret/host)
        String detailsJson = "{\"profile\":\"" + escapeJson(targetProfile) + "\""
            + ",\"total\":" + total
            + ",\"imported\":" + imported
            + ",\"duplicate\":" + duplicate
            + ",\"dnsFailed\":" + dnsFailed
            + ",\"probeFailed\":" + probeFailed
            + ",\"persistFailed\":" + persistFailed
            + ",\"parseError\":" + parseError + "}";
        return pool.preparedQuery(
                "INSERT INTO csp_audit_log (event_type, entity_type, entity_name, old_version, new_version, changed_by, summary, details_json)"
                    + " VALUES ($1,$2,$3,$4,$5,$6,$7,$8::jsonb)")
            .execute(Tuple.of("CONNECTOR_IMPORT", "csp_connector", null, null, null, actor, summary, detailsJson))
            .mapEmpty();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Future<Void> insertAudit(io.vertx.sqlclient.SqlClient client,
                                     String eventType, String entityType, String entityName,
                                     Long oldVersion, Long newVersion,
                                     String actor, String summary) {
        return client.preparedQuery(
                "INSERT INTO csp_audit_log (event_type, entity_type, entity_name, old_version, new_version, changed_by, summary)"
                    + " VALUES ($1,$2,$3,$4,$5,$6,$7)")
            .execute(Tuple.of(eventType, entityType, entityName, oldVersion, newVersion, actor, summary))
            .mapEmpty();
    }

    /**
     * Audit hors-transaction (best-effort) : événement sans versions de ligne
     * (ex. SECRET_MATERIALIZED — purpose/owner/ref uniquement, JAMAIS de valeur).
     * L'échec d'audit ne doit jamais faire échouer l'opération auditée.
     */
    public Future<Void> auditEvent(String eventType, String entityType, String entityName,
                                   String actor, String summary) {
        return pool.preparedQuery(
                "INSERT INTO csp_audit_log (event_type, entity_type, entity_name, changed_by, summary)"
                    + " VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(eventType, entityType, entityName, actor, summary))
            .mapEmpty();
    }

    // -- optimistic lock helper --

    private long checkOptimisticLock(RowSet<Row> rows, String table, long expectedVersion) {
        if (rows.rowCount() == 0) {
            throw new OptimisticLockException(table, "singleton", expectedVersion);
        }
        return rows.rowCount();
    }

    // -- Row mappers --

    private RootConfig mapRoot(Row r) {
        return new RootConfig(
            r.getString("proxy_name"),
            r.getInteger("proxy_origin_id"),
            r.getLong("version")
        );
    }

    private LoggingConfig mapLogging(Row r) {
        return new LoggingConfig(
            r.getString("log_level"), r.getString("log_file"),
            r.getBoolean("log_ecm"), r.getBoolean("log_emm"), r.getBoolean("log_zap"), r.getBoolean("log_failures"),
            r.getBoolean("silent"), r.getBoolean("debug"), r.getBoolean("hide_ip_addresses"),
            r.getString("log_dateformat"), r.getInteger("log_rotate_count"), r.getInteger("log_rotate_limit"),
            r.getString("wt_bad_flags"), r.getInteger("wt_max_delay_ms"), r.getBoolean("wt_include_file_events"),
            r.getInteger("et_min_count"), r.getLong("version")
        );
    }

    private CaProfilesGlobalConfig mapCaProfilesGlobal(Row r) {
        return new CaProfilesGlobalConfig(
            r.getString("default_des_key_ref"), r.getString("default_connector_des_key_ref"),
            r.getString("default_client_id_ref"), r.getInteger("default_connector_keepalive"),
            r.getInteger("default_connector_min_delay"), r.getInteger("default_connector_max_queue"),
            r.getBoolean("block_caid_mismatch"), r.getLong("version")
        );
    }

    private List<ProfileConfig> mapProfiles(RowSet<Row> rows) {
        List<ProfileConfig> list = new ArrayList<>();
        for (Row r : rows) {
            list.add(new ProfileConfig(
                r.getString("name"), r.getBoolean("enabled"), r.getBoolean("debug"), r.getBoolean("cache_only"),
                r.getInteger("ca_id"), r.getInteger("network_id"),
                r.getBoolean("require_provider_match"), r.getBoolean("check_ecm_length"),
                r.getLong("max_cw_wait_ms"), r.getLong("congestion_limit_ms"),
                r.getBoolean("mismatched_cards"), r.getInteger("service_conflicts"),
                r.getString("services_file"), r.getString("provider_filter"), r.getString("services_file_format"),
                r.getBoolean("block_caid_mismatch"), r.getLong("version")
            ));
        }
        return list;
    }

    private List<ProfileListenerConfig> mapListeners(RowSet<Row> rows) {
        List<ProfileListenerConfig> list = new ArrayList<>();
        for (Row r : rows) {
            String cardMode = r.getString("card_mode");
            Boolean strictPolicy = r.getBoolean("strict_policy");
            Integer reshareCap = r.getInteger("reshare_cap");
            Integer maxCards = r.getInteger("max_cards");
            Integer maxInflight = r.getInteger("max_inflight");
            Integer maxPerIp = r.getInteger("max_per_ip");
            String nodeId = r.getString("node_id");
            Integer idleTimeoutSec = r.getInteger("idle_timeout_sec");

            list.add(new ProfileListenerConfig(
                r.getLong("id"), r.getString("profile_name"), r.getString("protocol"),
                r.getInteger("port"), r.getString("bind_address"), r.getBoolean("enabled"),
                r.getString("des_key_ref"), r.getString("allow_list"), r.getString("deny_list"),
                r.getLong("version"),
                cardMode != null ? cardMode : "manual",
                strictPolicy != null ? strictPolicy : true,
                reshareCap != null ? reshareCap : 0,
                maxCards != null ? maxCards : 512,
                maxInflight != null ? maxInflight : 50,
                maxPerIp != null ? maxPerIp : 5,
                nodeId,
                idleTimeoutSec != null ? idleTimeoutSec : 60
            ));
        }
        return list;
    }

    private List<ProfileCardConfig> mapCards(RowSet<Row> rows) {
        List<ProfileCardConfig> list = new ArrayList<>();
        for (Row r : rows) {
            list.add(new ProfileCardConfig(
                r.getLong("id"), r.getString("profile_name"), r.getString("card_id"),
                r.getString("card_label"), r.getInteger("ca_id"), r.getString("node_id"),
                r.getLong("version")
            ));
        }
        return list;
    }

    private ConnectionManagerConfig mapConnectionManager(Row r) {
        return new ConnectionManagerConfig(
            r.getInteger("max_threads"), r.getInteger("session_timeout_ms"),
            r.getInteger("newcamd_max_msg_size"), r.getInteger("max_pending"),
            r.getInteger("max_connections_ip"), r.getInteger("session_keepalive_sec"),
            r.getString("session_keepalive_excluded"), r.getLong("version")
        );
    }

    private List<ServiceMapEntryConfig> mapServiceMap(RowSet<Row> rows) {
        List<ServiceMapEntryConfig> list = new ArrayList<>();
        for (Row r : rows) {
            list.add(new ServiceMapEntryConfig(
                r.getLong("id"), r.getString("profile_name"), r.getInteger("service_id"),
                r.getString("service_name"), r.getInteger("service_type"),
                r.getInteger("provider_ident"), r.getInteger("ecm_length"),
                r.getString("custom_data"), r.getLong("version")
            ));
        }
        return list;
    }

    private List<ConnectorConfig> mapConnectors(RowSet<Row> rows) {
        List<ConnectorConfig> list = new ArrayList<>();
        for (Row r : rows) {
            Boolean cardDiscovery = r.getBoolean("card_discovery");
            Integer reshareGrant = r.getInteger("reshare_grant");
            String newcamdDialect = r.getString("newcamd_dialect");
            Integer reshareCap = r.getInteger("reshare_cap");
            String newcamdPasswordEncoding = r.getString("newcamd_password_encoding");

            list.add(new ConnectorConfig(
                r.getString("name"), r.getString("profile_name"), r.getString("protocol"),
                r.getBoolean("enabled"), r.getBoolean("debug"), r.getString("host"), r.getInteger("port"),
                r.getString("username"), r.getString("password_ref"), r.getString("des_key_ref"),
                r.getInteger("connect_timeout_ms"), r.getInteger("reconnect_delay_ms"),
                r.getInteger("keep_alive_sec"), r.getInteger("min_delay_ms"), r.getInteger("max_queue"),
                r.getInteger("newcamd_max_msg_size"), r.getString("newcamd_client_id_ref"),
                r.getInteger("radegast_response_timeout_ms"), r.getBoolean("cccam_enabled"),
                r.getString("cccam_node_id"), r.getString("cccam_version"),
                r.getInteger("cacheex_mode"), r.getString("cacheex_filter"), r.getInteger("cacheex_max_send"),
                r.getInteger("cacheex_max_hop"),
                r.getString("chameleon_profiles"), r.getInteger("conax_reader_index"),
                r.getInteger("priority"), r.getInteger("max_capacity"), r.getLong("version"),
                r.getString("proxy_name"),
                cardDiscovery != null ? cardDiscovery : false,
                reshareGrant,
                newcamdDialect != null ? newcamdDialect : "legacy",
                reshareCap,
                newcamdPasswordEncoding != null ? newcamdPasswordEncoding : "plaintext"
            ));
        }
        return list;
    }

    private List<ConnectorServiceConfig> mapConnectorServices(RowSet<Row> rows) {
        List<ConnectorServiceConfig> list = new ArrayList<>();
        for (Row r : rows) {
            list.add(new ConnectorServiceConfig(
                r.getLong("id"), r.getString("connector_name"), r.getInteger("service_id"),
                r.getString("service_name"), r.getLong("version")
            ));
        }
        return list;
    }

    private CacheConfig mapCache(Row r) {
        return new CacheConfig(
            r.getLong("cw_max_age_ms"), r.getLong("maximum_size"),
            r.getLong("max_cache_wait_ms"), r.getInteger("max_cache_wait_percent"),
            r.getLong("version")
        );
    }

    private ClusterUdpConfig mapClusterUdp(Row r) {
        return new ClusterUdpConfig(
            r.getBoolean("enabled"), r.getInteger("bind_port"), r.getString("bind_address"),
            r.getString("remote_hosts"), r.getString("multicast_group"), r.getInteger("multicast_port"),
            r.getInteger("multicast_ttl"), r.getBoolean("auto_add_peers"), r.getString("tracker_url_ref"),
            r.getLong("ping_interval_ms"), r.getLong("resend_throttle_ms"),
            r.getInteger("max_packets_per_sec"), r.getString("blacklist"),
            r.getLong("sync_period_ms"), r.getLong("version")
        );
    }

    private WebUiConfig mapWebUi(Row r) {
        return new WebUiConfig(
            r.getBoolean("enabled"), r.getInteger("listen_port"), r.getString("bind_address"),
            r.getBoolean("tls_enabled"), r.getString("tls_cert_ref"), r.getString("tls_key_ref"),
            r.getString("admin_user_ref"), r.getString("admin_password_ref"),
            r.getString("super_users"), r.getString("allow_list"),
            r.getString("welcome_file"), r.getInteger("session_timeout_min"), r.getLong("version")
        );
    }

    static Row firstOrNull(RowSet<Row> rows) {
        if (rows == null) return null;
        var it = rows.iterator();
        return it.hasNext() ? it.next() : null;
    }

    private List<PluginConfig> mapPlugins(RowSet<Row> rows) {
        List<PluginConfig> list = new ArrayList<>();
        for (Row r : rows) {
            list.add(new PluginConfig(
                r.getString("name"), r.getString("class_name"), r.getBoolean("enabled"),
                r.getString("description"), r.getString("config_json"),
                r.getString("profiles"), r.getLong("version")
            ));
        }
        return list;
    }

    private List<SidCacheLinkConfig> mapSidLinks(RowSet<Row> rows) {
        List<SidCacheLinkConfig> list = new ArrayList<>();
        for (Row r : rows) {
            list.add(new SidCacheLinkConfig(
                r.getLong("id"), r.getString("profile_name"),
                r.getInteger("sid_a"), r.getInteger("sid_b")
            ));
        }
        return list;
    }

    private ExtendedNewcamdConfig mapExtended(Row r) {
        return new ExtendedNewcamdConfig(
            r.getBoolean("enabled"), r.getInteger("listen_port"), r.getString("bind_address"),
            r.getBoolean("catch_all"), r.getBoolean("debug"), r.getString("des_key_ref"),
            r.getString("allow_list"), r.getString("deny_list"), r.getInteger("max_msg_size"),
            r.getLong("version")
        );
    }

    private List<ConnectorCaidProfileConfig> mapCaidProfiles(RowSet<Row> rows) {
        List<ConnectorCaidProfileConfig> list = new ArrayList<>();
        for (Row r : rows) {
            list.add(new ConnectorCaidProfileConfig(
                r.getLong("id"), r.getString("connector_name"), r.getInteger("ordinal"),
                r.getInteger("ca_id"), r.getString("profile_name"), r.getLong("version")
            ));
        }
        return list;
    }

    private List<ProfileProviderIdentConfig> mapProviderIdents(RowSet<Row> rows) {
        List<ProfileProviderIdentConfig> list = new ArrayList<>();
        for (Row r : rows) {
            list.add(new ProfileProviderIdentConfig(
                r.getLong("id"), r.getString("profile_name"), r.getInteger("provider_ident"),
                r.getString("label"), r.getLong("version")
            ));
        }
        return list;
    }

    // -- validation helpers --

    private static boolean isValidName(String n) {
        return n != null && !n.isBlank() && n.length() <= 128 && n.matches("^[A-Za-z0-9_.-]+$");
    }

    // =========================================================================
    // PROXY CRUD (csp_proxy)
    // =========================================================================

    /**
     * Crée ou met à jour un proxy SOCKS5 dans csp_proxy.
     * Convention : expectedVersion=1 → création (absent requis), sinon mise à jour.
     * 404 si absent et expectedVersion≠1 ; 409 si version mismatch.
     * Les secrets (passwordRef) ne sont jamais loggés.
     */
    public Future<Long> upsertProxy(NetworkProxyConfig proxy, String actor) {
        long expectedVersion = proxy.version();
        String proxyName = proxy.name();
        if (!isValidProxyName(proxyName)) {
            return Future.failedFuture(new ConfigValidationException(
                "Invalid proxy name (must match [A-Za-z0-9_.-]{1,64}): " + proxyName));
        }
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT version FROM csp_proxy WHERE name=$1 FOR UPDATE")
                .execute(Tuple.of(proxyName))
                .compose(selRows -> {
                    Row existing = firstOrNull(selRows);
                    if (existing == null) {
                        if (expectedVersion != 1) {
                            return Future.failedFuture(new ConfigNotFoundException("csp_proxy", proxyName));
                        }
                        String insertSql = "INSERT INTO csp_proxy"
                            + " (name, enabled, type, host, port, username, password_ref,"
                            + "  description, version, updated_at, updated_by)"
                            + " VALUES ($1,$2,$3,$4,$5,$6,$7,$8,1,now(),$9)"
                            + " ON CONFLICT (name) DO NOTHING RETURNING version";
                        return client.preparedQuery(insertSql)
                            .execute(Tuple.of(
                                proxy.name(), proxy.enabled(), proxy.type(),
                                proxy.host(), proxy.port(), proxy.username(), proxy.passwordRef(),
                                proxy.description(), actor))
                            .compose(insRows -> {
                                if (insRows.rowCount() == 0) {
                                    return Future.failedFuture(
                                        new OptimisticLockException("csp_proxy", proxyName, expectedVersion));
                                }
                                Row r = firstOrNull(insRows);
                                long newVersion = r != null && r.getLong("version") != null
                                    ? r.getLong("version") : 1L;
                                return insertAudit(client, "CONFIG_UPSERTED", "csp_proxy", proxyName,
                                    null, newVersion, actor, "create proxy " + proxyName)
                                    .map(v -> newVersion);
                            });
                    } else {
                        long currentVersion = existing.getLong("version");
                        if (currentVersion != expectedVersion) {
                            return Future.failedFuture(
                                new OptimisticLockException("csp_proxy", proxyName, expectedVersion));
                        }
                        String updateSql = "UPDATE csp_proxy"
                            + " SET enabled=$2, type=$3, host=$4, port=$5,"
                            + "     username=$6, password_ref=$7,"
                            + "     description=$8, version=version+1, updated_at=now(), updated_by=$9"
                            + " WHERE name=$1 AND version=$10 RETURNING version";
                        return client.preparedQuery(updateSql)
                            .execute(Tuple.of(
                                proxy.name(), proxy.enabled(), proxy.type(),
                                proxy.host(), proxy.port(), proxy.username(), proxy.passwordRef(),
                                proxy.description(), actor, expectedVersion))
                            .compose(upRows -> {
                                if (upRows.rowCount() == 0) {
                                    return Future.failedFuture(
                                        new OptimisticLockException("csp_proxy", proxyName, expectedVersion));
                                }
                                Row r = firstOrNull(upRows);
                                long newVersion = r != null && r.getLong("version") != null
                                    ? r.getLong("version") : expectedVersion + 1;
                                return insertAudit(client, "CONFIG_UPSERTED", "csp_proxy", proxyName,
                                    expectedVersion, newVersion, actor, "update proxy " + proxyName)
                                    .map(v -> newVersion);
                            });
                    }
                }));
    }

    /**
     * Supprime un proxy SOCKS5.
     * 409 PROXY_IN_USE si proxy référencé par au moins un connecteur (FK RESTRICT filet de sécurité).
     * 404 si absent. 409 si version mismatch.
     */
    public Future<Void> deleteProxy(String name, long expectedVersion, String actor) {
        if (expectedVersion < 1) {
            return Future.failedFuture(new ConfigValidationException("version must be >=1"));
        }
        if (!isValidProxyName(name)) {
            return Future.failedFuture(new ConfigValidationException("Invalid proxy name"));
        }
        return pool.withTransaction(client ->
            // Vérifier qu'aucun connecteur n'utilise ce proxy (usage count)
            client.preparedQuery("SELECT COUNT(*) AS cnt FROM csp_connector WHERE proxy_name=$1")
                .execute(Tuple.of(name))
                .compose(cntRows -> {
                    Row cntRow = firstOrNull(cntRows);
                    long usageCount = cntRow != null ? cntRow.getLong("cnt") : 0L;
                    if (usageCount > 0) {
                        return Future.failedFuture(new ConfigValidationException(
                            "PROXY_IN_USE: proxy '" + name + "' is referenced by "
                                + usageCount + " connector(s)"));
                    }
                    return client.preparedQuery(
                            "DELETE FROM csp_proxy WHERE name=$1 AND version=$2 RETURNING version")
                        .execute(Tuple.of(name, expectedVersion))
                        .compose(delRows -> {
                            if (delRows.rowCount() > 0) {
                                Row r = firstOrNull(delRows);
                                long v = r != null ? r.getLong("version") : expectedVersion;
                                return insertAudit(client, "CONFIG_DELETED", "csp_proxy", name,
                                    v, null, actor, "delete proxy " + name).mapEmpty();
                            }
                            // zero rows: 404 vs 409
                            return client.preparedQuery("SELECT version FROM csp_proxy WHERE name=$1")
                                .execute(Tuple.of(name))
                                .compose(selRows -> {
                                    Row sel = firstOrNull(selRows);
                                    if (sel == null) {
                                        return Future.failedFuture(
                                            new ConfigNotFoundException("csp_proxy", name));
                                    }
                                    throw new OptimisticLockException("csp_proxy", name, expectedVersion);
                                });
                        });
                }));
    }

    /**
     * Charge tous les proxies (lecture seule, hors transaction principale).
     */
    public Future<List<NetworkProxyConfig>> loadAllProxies() {
        return pool.preparedQuery(
                "SELECT name, enabled, type, host, port, username, password_ref,"
                + " description, version FROM csp_proxy ORDER BY name")
            .execute()
            .map(this::mapProxies);
    }

    /**
     * Retourne le nombre de connecteurs utilisant ce proxy.
     */
    public Future<Long> countProxyUsage(String proxyName) {
        return pool.preparedQuery("SELECT COUNT(*) AS cnt FROM csp_connector WHERE proxy_name=$1")
            .execute(Tuple.of(proxyName))
            .map(rows -> {
                Row r = firstOrNull(rows);
                return r != null ? r.getLong("cnt") : 0L;
            });
    }

    private static boolean isValidProxyName(String n) {
        return n != null && n.matches("^[A-Za-z0-9_.\\-]{1,64}$");
    }

    private List<NetworkProxyConfig> mapProxies(RowSet<Row> rows) {
        List<NetworkProxyConfig> list = new ArrayList<>();
        for (Row r : rows) {
            try {
                list.add(new NetworkProxyConfig(
                    r.getString("name"),
                    Boolean.TRUE.equals(r.getBoolean("enabled")),
                    r.getString("type") != null ? r.getString("type") : "socks5",
                    r.getString("host"),
                    r.getInteger("port"),
                    r.getString("username"),
                    r.getString("password_ref"),
                    r.getString("description"),
                    r.getLong("version")
                ));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Skipping malformed proxy row: " + e.getMessage());
            }
        }
        return list;
    }

    private static void validateCaidMap(List<ConnectorCaidProfileConfig> entries) {
        if (entries == null || entries.isEmpty()) throw new ConfigValidationException("CAID map must be non-empty");
        if (entries.size() > 256) throw new ConfigValidationException("CAID map limit 256");
        java.util.Set<Integer> caIds = new java.util.HashSet<>();
        java.util.Set<Integer> ords = new java.util.HashSet<>();
        for (ConnectorCaidProfileConfig e : entries) {
            if (e.caId() < 0 || e.caId() > 65535) throw new ConfigValidationException("caId out of range");
            if (e.ordinal() < 0) throw new ConfigValidationException("ordinal must be >=0");
            if (!caIds.add(e.caId())) throw new ConfigValidationException("duplicate caId");
            if (!ords.add(e.ordinal())) throw new ConfigValidationException("duplicate ordinal");
        }
        int max = ords.stream().mapToInt(Integer::intValue).max().orElse(-1);
        if (ords.size() != max + 1) throw new ConfigValidationException("ordinal must be contiguous 0..n-1");
    }

    private Future<Void> validateMapProfilesEnabled(io.vertx.sqlclient.SqlClient client, List<ConnectorCaidProfileConfig> map) {
        Future<Void> f = Future.succeededFuture();
        for (ConnectorCaidProfileConfig e : map) {
            String profileName = e.profileName();
            f = f.compose(v -> client.preparedQuery("SELECT enabled FROM csp_profile WHERE name=$1")
                .execute(Tuple.of(profileName))
                .compose(rows -> {
                    Row r = firstOrNull(rows);
                    if (r == null) return Future.failedFuture(new ConfigValidationException("Unknown profile in map: " + profileName));
                    if (!Boolean.TRUE.equals(r.getBoolean("enabled"))) return Future.failedFuture(new ConfigValidationException("Disabled profile in map: " + profileName));
                    return Future.succeededFuture();
                }));
        }
        return f;
    }

    // =========================================================================
    // PROFILE LISTENER CRUD (csp_profile_listener)
    // =========================================================================

    public Future<Long> upsertListener(ProfileListenerConfig l, String actor) {
        long expectedVersion = l.version();
        return pool.withTransaction(client -> {
            if (l.id() <= 0) {
                // Création / insertion
                String insertSql = "INSERT INTO csp_profile_listener (profile_name, protocol, port, bind_address,"
                    + " enabled, des_key_ref, allow_list, deny_list, card_mode, strict_policy, reshare_cap,"
                    + " max_cards, max_inflight, max_per_ip, node_id, idle_timeout_sec, version, updated_at, updated_by)"
                    + " VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, 1, now(), $17)"
                    + " ON CONFLICT (profile_name, protocol, port) DO NOTHING RETURNING id, version";
                return client.preparedQuery(insertSql)
                    .execute(Tuple.of(
                        l.profileName(), l.protocol(), l.port(), l.bindAddress(), l.enabled(),
                        l.desKeyRef(), l.allowList(), l.denyList(), l.cardMode(), l.strictPolicy(),
                        l.reshareCap(), l.maxCards(), l.maxInflight(), l.maxPerIp(), l.nodeId(),
                        l.idleTimeoutSec(), actor))
                    .compose(insRows -> {
                        if (insRows.rowCount() == 0) {
                            return Future.failedFuture(new ConfigValidationException(
                                "Listener on " + l.profileName() + "/" + l.protocol() + ":" + l.port() + " already exists"));
                        }
                        Row r = firstOrNull(insRows);
                        long newVersion = r != null && r.getLong("version") != null ? r.getLong("version") : 1L;
                        String entityId = r != null ? String.valueOf(r.getLong("id")) : l.profileName() + ":" + l.port();
                        return insertAudit(client, "CONFIG_UPSERTED", "csp_profile_listener", entityId,
                            null, newVersion, actor, "create listener " + l.profileName() + ":" + l.port())
                            .map(v -> newVersion);
                    });
            } else {
                // Mise à jour d'un listener existant (id > 0) avec verrou optimiste
                String updateSql = "UPDATE csp_profile_listener SET protocol=$2, port=$3, bind_address=$4,"
                    + " enabled=$5, des_key_ref=$6, allow_list=$7, deny_list=$8, card_mode=$9, strict_policy=$10,"
                    + " reshare_cap=$11, max_cards=$12, max_inflight=$13, max_per_ip=$14, node_id=$15,"
                    + " idle_timeout_sec=$16, version=version+1, updated_at=now(), updated_by=$17"
                    + " WHERE id=$1 AND version=$18 RETURNING version";
                return client.preparedQuery(updateSql)
                    .execute(Tuple.of(
                        l.id(), l.protocol(), l.port(), l.bindAddress(), l.enabled(),
                        l.desKeyRef(), l.allowList(), l.denyList(), l.cardMode(), l.strictPolicy(),
                        l.reshareCap(), l.maxCards(), l.maxInflight(), l.maxPerIp(), l.nodeId(),
                        l.idleTimeoutSec(), actor, expectedVersion))
                    .compose(upRows -> {
                        if (upRows.rowCount() == 0) {
                            return client.preparedQuery("SELECT version FROM csp_profile_listener WHERE id=$1")
                                .execute(Tuple.of(l.id()))
                                .compose(chkRows -> {
                                    if (chkRows.rowCount() == 0) {
                                        return Future.failedFuture(new java.util.NoSuchElementException(
                                            "Listener not found: " + l.id()));
                                    }
                                    return Future.failedFuture(new OptimisticLockException(
                                        "csp_profile_listener", String.valueOf(l.id()), expectedVersion));
                                });
                        }
                        Row r = firstOrNull(upRows);
                        long newVersion = r != null && r.getLong("version") != null ? r.getLong("version") : expectedVersion + 1;
                        return insertAudit(client, "CONFIG_UPDATED", "csp_profile_listener", String.valueOf(l.id()),
                            expectedVersion, newVersion, actor, "update listener " + l.id())
                            .map(v -> newVersion);
                    });
            }
        });
    }

    public Future<Void> deleteListener(long id, long expectedVersion, String actor) {
        return pool.withTransaction(client ->
            client.preparedQuery("DELETE FROM csp_profile_listener WHERE id=$1 AND version=$2")
                .execute(Tuple.of(id, expectedVersion))
                .compose(delRows -> {
                    if (delRows.rowCount() == 0) {
                        return Future.failedFuture(new OptimisticLockException(
                            "csp_profile_listener", String.valueOf(id), expectedVersion));
                    }
                    return insertAudit(client, "CONFIG_DELETED", "csp_profile_listener", String.valueOf(id),
                        expectedVersion, null, actor, "delete listener " + id)
                        .mapEmpty();
                }));
    }

    // =========================================================================
    // RELAY v1 (ADR 0002) : nodes / caches / links + desired/applied
    // =========================================================================

    private List<RelayNodeConfig> mapRelayNodes(RowSet<Row> rows) {
        List<RelayNodeConfig> list = new ArrayList<>();
        for (Row r : rows) {
            try {
                Long applied = r.getLong("applied_version");
                java.time.OffsetDateTime seen = null;
                try {
                    seen = r.getOffsetDateTime("last_seen_at");
                } catch (Exception ignored) {
                }
                String cspHost = null;
                Integer cspPort = null;
                try {
                    cspHost = r.getString("csp_host");
                    cspPort = r.getInteger("csp_port");
                } catch (Exception ignored) {
                }
                list.add(new RelayNodeConfig(
                    r.getString("node_name"),
                    r.getString("display_name"),
                    Boolean.TRUE.equals(r.getBoolean("enabled")),
                    Boolean.TRUE.equals(r.getBoolean("authorized")),
                    Boolean.TRUE.equals(r.getBoolean("revoked")),
                    r.getLong("desired_version") != null ? r.getLong("desired_version") : 1L,
                    applied,
                    r.getLong("version") != null ? r.getLong("version") : 1L,
                    seen != null ? seen.toInstant() : null,
                    cspHost, cspPort
                ));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Skipping malformed relay node row: " + e.getMessage());
            }
        }
        return list;
    }

    private List<RelayCacheConfig> mapRelayCaches(RowSet<Row> rows) {
        List<RelayCacheConfig> list = new ArrayList<>();
        for (Row r : rows) {
            try {
                list.add(new RelayCacheConfig(
                    r.getString("name"),
                    r.getString("kind"),
                    r.getString("node_name"),
                    Boolean.TRUE.equals(r.getBoolean("enabled")),
                    r.getLong("max_age_ms") != null ? r.getLong("max_age_ms") : 8000L,
                    r.getInteger("max_entries") != null ? r.getInteger("max_entries") : 10000,
                    r.getInteger("max_inflight") != null ? r.getInteger("max_inflight") : 512,
                    r.getLong("version") != null ? r.getLong("version") : 1L
                ));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Skipping malformed relay cache row: " + e.getMessage());
            }
        }
        return list;
    }

    private List<ListenerCacheLinkConfig> mapListenerCacheLinks(RowSet<Row> rows) {
        List<ListenerCacheLinkConfig> list = new ArrayList<>();
        for (Row r : rows) {
            try {
                list.add(new ListenerCacheLinkConfig(
                    r.getLong("listener_id") != null ? r.getLong("listener_id") : -1L,
                    r.getString("cache_name"),
                    Boolean.TRUE.equals(r.getBoolean("can_consult")),
                    Boolean.TRUE.equals(r.getBoolean("can_feed")),
                    Boolean.TRUE.equals(r.getBoolean("can_export")),
                    Boolean.TRUE.equals(r.getBoolean("enabled")),
                    r.getLong("version") != null ? r.getLong("version") : 1L
                ));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Skipping malformed listener-cache link row: " + e.getMessage());
            }
        }
        return list;
    }

    /**
     * Gemini FIX : {@code desired_version} est READONLY (genere serveur, monotone).
     * La valeur cliente est ignoree : creation desired=1, mise a jour
     * desired=desired+1 avec renouvellement d'expiry, en une seule instruction
     * (pas de double increment trigger/app — aucun trigger ne touche desired).
     * applied_version n'est JAMAIS ecrit ici (ack worker seul).
     */
    public Future<Long> upsertRelayNode(RelayNodeConfig n, String actor) {
        return upsertRelayNode(n, actor, RelayWorkerContract.TTL_DEFAULT_MS);
    }

    public Future<Long> upsertRelayNode(RelayNodeConfig n, String actor, long ttlMs) {
        long ttl = RelayWorkerContract.normalizeTtl(ttlMs);
        long expectedVersion = n.version();
        if (!n.nodeName().matches("^[a-z0-9][a-z0-9._-]{0,62}$"))
            return Future.failedFuture(new ConfigValidationException("Invalid node name"));
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT version FROM csp_relay_node WHERE node_name=$1 FOR UPDATE")
                .execute(Tuple.of(n.nodeName()))
                .compose(sel -> {
                    Row existing = firstOrNull(sel);
                    if (existing == null) {
                        if (expectedVersion != 1)
                            return Future.failedFuture(new ConfigNotFoundException("csp_relay_node", n.nodeName()));
                        return client.preparedQuery("INSERT INTO csp_relay_node"
                                + " (node_name, display_name, enabled, authorized, revoked,"
                                + " desired_version, applied_version, version, updated_at, updated_by,"
                                + " config_expires_at, csp_host, csp_port)"
                                + " VALUES ($1,$2,$3,$4,$5,1,NULL,1,now(),$6,"
                                + " now() + make_interval(secs => $7),$8,$9)"
                                + " ON CONFLICT (node_name) DO NOTHING RETURNING version")
                            .execute(Tuple.of(n.nodeName(), n.displayName(), n.enabled(),
                                n.authorized(), n.revoked(), actor, (double) (ttl / 1000),
                                n.cspHost(), n.cspPort()))
                            .compose(ins -> {
                                if (ins.rowCount() == 0)
                                    return Future.failedFuture(new OptimisticLockException(
                                        "csp_relay_node", n.nodeName(), expectedVersion));
                                Row r = firstOrNull(ins);
                                long nv = r != null && r.getLong("version") != null ? r.getLong("version") : 1L;
                                return insertAudit(client, "CONFIG_UPSERTED", "csp_relay_node",
                                    n.nodeName(), null, nv, actor, "upsert relay node " + n.nodeName())
                                    .map(v -> nv);
                            });
                    }
                    long cur = existing.getLong("version");
                    if (cur != expectedVersion)
                        return Future.failedFuture(new OptimisticLockException(
                            "csp_relay_node", n.nodeName(), expectedVersion));
                    // desired_version serveur : +1 monotone, expiry renouvelee.
                    return client.preparedQuery("UPDATE csp_relay_node SET display_name=$2,"
                            + " enabled=$3, authorized=$4, revoked=$5,"
                            + " desired_version=desired_version+1,"
                            + " config_expires_at=now() + make_interval(secs => $6),"
                            + " csp_host=$9, csp_port=$10,"
                            + " version=version+1, updated_at=now(), updated_by=$7"
                            + " WHERE node_name=$1 AND version=$8 RETURNING version, desired_version")
                        .execute(Tuple.of(n.nodeName(), n.displayName(), n.enabled(),
                            n.authorized(), n.revoked(), (double) (ttl / 1000), actor, expectedVersion,
                            n.cspHost(), n.cspPort()))
                        .compose(up -> {
                            if (up.rowCount() == 0)
                                return Future.failedFuture(new OptimisticLockException(
                                    "csp_relay_node", n.nodeName(), expectedVersion));
                            Row r = firstOrNull(up);
                            long nv = r != null && r.getLong("version") != null ? r.getLong("version") : expectedVersion + 1;
                            return insertAudit(client, "CONFIG_UPDATED", "csp_relay_node",
                                n.nodeName(), expectedVersion, nv, actor, "update relay node " + n.nodeName())
                                .map(v -> nv);
                        });
                }));
    }

    public Future<Long> upsertRelayCache(RelayCacheConfig c, String actor) {
        long expectedVersion = c.version();
        try {
            new RelayCacheConfig(c.name(), c.kind(), c.nodeName(), c.enabled(),
                c.maxAgeMs(), c.maxEntries(), c.maxInflight(), 1L);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(new ConfigValidationException(e.getMessage()));
        }
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT version FROM csp_relay_cache WHERE name=$1 FOR UPDATE")
                .execute(Tuple.of(c.name()))
                .compose(sel -> {
                    Row existing = firstOrNull(sel);
                    if (existing == null) {
                        if (expectedVersion != 1)
                            return Future.failedFuture(new ConfigNotFoundException("csp_relay_cache", c.name()));
                        return client.preparedQuery("INSERT INTO csp_relay_cache"
                                + " (name, kind, node_name, enabled, max_age_ms, max_entries,"
                                + " max_inflight, version, updated_at, updated_by)"
                                + " VALUES ($1,$2,$3,$4,$5,$6,$7,1,now(),$8)"
                                + " ON CONFLICT (name) DO NOTHING RETURNING version")
                            .execute(Tuple.of(c.name(), c.kind(), c.nodeName(), c.enabled(),
                                c.maxAgeMs(), c.maxEntries(), c.maxInflight(), actor))
                            .compose(ins -> {
                                if (ins.rowCount() == 0)
                                    return Future.failedFuture(new OptimisticLockException(
                                        "csp_relay_cache", c.name(), expectedVersion));
                                Row r = firstOrNull(ins);
                                long nv = r != null && r.getLong("version") != null ? r.getLong("version") : 1L;
                                return insertAudit(client, "CONFIG_UPSERTED", "csp_relay_cache",
                                    c.name(), null, nv, actor, "upsert relay cache " + c.name())
                                    .compose(v -> bumpDesiredForCacheTx(client, c.name(),
                                        RelayWorkerContract.TTL_DEFAULT_MS, actor).map(vv -> nv));
                            });
                    }
                    long cur = existing.getLong("version");
                    if (cur != expectedVersion)
                        return Future.failedFuture(new OptimisticLockException(
                            "csp_relay_cache", c.name(), expectedVersion));
                    return client.preparedQuery("UPDATE csp_relay_cache SET kind=$2, node_name=$3,"
                            + " enabled=$4, max_age_ms=$5, max_entries=$6, max_inflight=$7,"
                            + " version=version+1, updated_at=now(), updated_by=$8"
                            + " WHERE name=$1 AND version=$9 RETURNING version")
                        .execute(Tuple.of(c.name(), c.kind(), c.nodeName(), c.enabled(),
                            c.maxAgeMs(), c.maxEntries(), c.maxInflight(), actor, expectedVersion))
                        .compose(up -> {
                            if (up.rowCount() == 0)
                                return Future.failedFuture(new OptimisticLockException(
                                    "csp_relay_cache", c.name(), expectedVersion));
                            Row r = firstOrNull(up);
                            long nv = r != null && r.getLong("version") != null ? r.getLong("version") : expectedVersion + 1;
                            return insertAudit(client, "CONFIG_UPDATED", "csp_relay_cache",
                                c.name(), expectedVersion, nv, actor, "update relay cache " + c.name())
                                .compose(v -> bumpDesiredForCacheTx(client, c.name(),
                                    RelayWorkerContract.TTL_DEFAULT_MS, actor).map(vv -> nv));
                        });
                }));
    }

    public Future<Long> upsertListenerCacheLink(ListenerCacheLinkConfig l, String actor) {
        long expectedVersion = l.version();
        try {
            new ListenerCacheLinkConfig(l.listenerId(), l.cacheName(), l.canConsult(),
                l.canFeed(), l.canExport(), l.enabled(), 1L);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(new ConfigValidationException(e.getMessage()));
        }
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT version FROM csp_listener_cache_link"
                    + " WHERE listener_id=$1 AND cache_name=$2 FOR UPDATE")
                .execute(Tuple.of(l.listenerId(), l.cacheName()))
                .compose(sel -> {
                    Row existing = firstOrNull(sel);
                    if (existing == null) {
                        if (expectedVersion != 1)
                            return Future.failedFuture(new ConfigNotFoundException(
                                "csp_listener_cache_link", l.listenerId() + ":" + l.cacheName()));
                        // FK listener/cache : echec SQL => remonte tel quel (fail-closed)
                        return client.preparedQuery("INSERT INTO csp_listener_cache_link"
                                + " (listener_id, cache_name, can_consult, can_feed, can_export,"
                                + " enabled, version, updated_at, updated_by)"
                                + " VALUES ($1,$2,$3,$4,$5,$6,1,now(),$7)"
                                + " ON CONFLICT (listener_id, cache_name) DO NOTHING RETURNING version")
                            .execute(Tuple.of(l.listenerId(), l.cacheName(), l.canConsult(),
                                l.canFeed(), l.canExport(), l.enabled(), actor))
                            .compose(ins -> {
                                if (ins.rowCount() == 0)
                                    return Future.failedFuture(new OptimisticLockException(
                                        "csp_listener_cache_link", l.listenerId() + ":" + l.cacheName(), expectedVersion));
                                Row r = firstOrNull(ins);
                                long nv = r != null && r.getLong("version") != null ? r.getLong("version") : 1L;
                                return insertAudit(client, "CONFIG_UPSERTED", "csp_listener_cache_link",
                                    l.listenerId() + ":" + l.cacheName(), null, nv, actor, "link listener-cache")
                                    .compose(v -> bumpDesiredForCacheTx(client, l.cacheName(),
                                        RelayWorkerContract.TTL_DEFAULT_MS, actor).map(vv -> nv));
                            });
                    }
                    long cur = existing.getLong("version");
                    if (cur != expectedVersion)
                        return Future.failedFuture(new OptimisticLockException(
                            "csp_listener_cache_link", l.listenerId() + ":" + l.cacheName(), expectedVersion));
                    return client.preparedQuery("UPDATE csp_listener_cache_link"
                            + " SET can_consult=$3, can_feed=$4, can_export=$5, enabled=$6,"
                            + " version=version+1, updated_at=now(), updated_by=$7"
                            + " WHERE listener_id=$1 AND cache_name=$2 AND version=$8 RETURNING version")
                        .execute(Tuple.of(l.listenerId(), l.cacheName(), l.canConsult(),
                            l.canFeed(), l.canExport(), l.enabled(), actor, expectedVersion))
                        .compose(up -> {
                            if (up.rowCount() == 0)
                                return Future.failedFuture(new OptimisticLockException(
                                    "csp_listener_cache_link", l.listenerId() + ":" + l.cacheName(), expectedVersion));
                            Row r = firstOrNull(up);
                            long nv = r != null && r.getLong("version") != null ? r.getLong("version") : expectedVersion + 1;
                            return insertAudit(client, "CONFIG_UPDATED", "csp_listener_cache_link",
                                l.listenerId() + ":" + l.cacheName(), expectedVersion, nv, actor, "update link")
                                .compose(v -> bumpDesiredForCacheTx(client, l.cacheName(),
                                    RelayWorkerContract.TTL_DEFAULT_MS, actor).map(vv -> nv));
                        });
                }));
    }

    public Future<Void> deleteListenerCacheLink(long listenerId, String cacheName, long expectedVersion, String actor) {
        return deleteListenerCacheLink(listenerId, cacheName, expectedVersion, actor, 30000L);
    }

    public Future<Void> deleteListenerCacheLink(long listenerId, String cacheName, long expectedVersion, String actor, long ttlMs) {
        long ttl = RelayWorkerContract.normalizeTtl(ttlMs);
        return pool.withTransaction(client ->
            client.preparedQuery("DELETE FROM csp_listener_cache_link WHERE listener_id=$1 AND cache_name=$2 AND version=$3")
                .execute(Tuple.of(listenerId, cacheName, expectedVersion))
                .compose(del -> {
                    if (del.rowCount() == 0)
                        return Future.failedFuture(new OptimisticLockException(
                            "csp_listener_cache_link", listenerId + ":" + cacheName, expectedVersion));
                    return insertAudit(client, "CONFIG_DELETED", "csp_listener_cache_link",
                        listenerId + ":" + cacheName, expectedVersion, null, actor, "delete link")
                        .compose(v -> bumpDesiredForCacheTx(client, cacheName, ttl, actor))
                        .mapEmpty();
                }));
    }

    // =========================================================================
    // FIX Sonnet #4 : ack persistant + bump desired versionne/expire monotone
    // =========================================================================

    /**
     * Ack worker exact : ecrit applied_version/applied_at/last_seen_at + renouvelle
     * config_expires_at, en transaction avec audit. Echec : stale => 409
     * (OptimisticLock), revoque/expire => validation (403/410 mappes endpoint).
     * L'authentification SAN est verifiee par l'appelant (endpoint mTLS).
     */
    /** Borne payload stats worker->central (JSON, jamais de secret dedans). */
    public static final int MAX_STATS_BYTES = 8192;

    public Future<Long> recordWorkerAck(String nodeName, long claimedDesiredVersion, String actor, long ttlMs) {
        return recordWorkerAck(nodeName, claimedDesiredVersion, actor, ttlMs, null);
    }

    public Future<Long> recordWorkerAck(String nodeName, long claimedDesiredVersion, String actor, long ttlMs,
                                        String statsJson) {
        if (statsJson != null && statsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_STATS_BYTES)
            return Future.failedFuture(new ConfigValidationException("stats payload exceeds 8KB"));
        long ttl = RelayWorkerContract.normalizeTtl(ttlMs);
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT desired_version, enabled, authorized, revoked,"
                    + " config_expires_at FROM csp_relay_node WHERE node_name=$1 FOR UPDATE")
                .execute(Tuple.of(nodeName))
                .compose(sel -> {
                    Row r = firstOrNull(sel);
                    if (r == null)
                        return Future.failedFuture(new ConfigNotFoundException("csp_relay_node", nodeName));
                    boolean enabled = Boolean.TRUE.equals(r.getBoolean("enabled"));
                    boolean authorized = Boolean.TRUE.equals(r.getBoolean("authorized"));
                    boolean revoked = Boolean.TRUE.equals(r.getBoolean("revoked"));
                    Long desired = r.getLong("desired_version");
                    if (!enabled || !authorized || revoked)
                        return Future.failedFuture(new WorkerRevokedException(
                            "Worker revoked or not authorized: " + nodeName));
                    if (desired == null || claimedDesiredVersion != desired.longValue())
                        return Future.failedFuture(new OptimisticLockException(
                            "csp_relay_node", nodeName, claimedDesiredVersion));
                    java.time.OffsetDateTime expires = r.getOffsetDateTime("config_expires_at");
                    if (expires != null && expires.toInstant().isBefore(java.time.Instant.now()))
                        return Future.failedFuture(new WorkerExpiredException(
                            "Worker config expired for: " + nodeName));
                    return client.preparedQuery("UPDATE csp_relay_node SET applied_version=$2,"
                            + " applied_at=now(), last_seen_at=now(),"
                            + " config_expires_at=now() + make_interval(secs => $3),"
                            + " updated_at=now(), updated_by=$4"
                            + " WHERE node_name=$1 RETURNING applied_version")
                        .execute(Tuple.of(nodeName, desired, (double) (ttl / 1000), actor))
                        .compose(up -> {
                            if (up.rowCount() == 0)
                                return Future.failedFuture(new OptimisticLockException(
                                    "csp_relay_node", nodeName, claimedDesiredVersion));
                            Row ur = firstOrNull(up);
                            long applied = ur != null && ur.getLong("applied_version") != null
                                ? ur.getLong("applied_version") : desired;
                            return insertAudit(client, "WORKER_ACK", "csp_relay_node", nodeName,
                                claimedDesiredVersion, applied, actor,
                                "worker ack exact config " + applied)
                                .compose(v -> {
                                    if (statsJson == null) return Future.succeededFuture(applied);
                                    return client.preparedQuery("INSERT INTO csp_relay_node_stats"
                                            + " (node_name, updated_at, payload) VALUES ($1, now(), $2::jsonb)"
                                            + " ON CONFLICT (node_name) DO UPDATE"
                                            + " SET updated_at=now(), payload=EXCLUDED.payload")
                                        .execute(Tuple.of(nodeName, statsJson))
                                        .map(x -> applied);
                                });
                        });
                }));
    }

    /** Stats brutes par node (payload JSON), tolere pre-V12. */
    public Future<java.util.Map<String, String>> loadNodeStats() {
        return pool.preparedQuery("SELECT node_name, payload FROM csp_relay_node_stats")
            .execute()
            .map(rows -> {
                java.util.Map<String, String> out = new java.util.HashMap<>();
                for (Row r : rows) {
                    if (r.getString("node_name") == null) continue;
                    Object v = r.getValue("payload");
                    String text = null;
                    if (v instanceof io.vertx.core.json.JsonObject jo) text = jo.encode();
                    else if (v instanceof String s) text = s;
                    else if (v != null) text = String.valueOf(v);
                    // Deballe un niveau si double-encode (pilote JDBC texte).
                    if (text != null && text.length() >= 2 && text.startsWith("\"")
                        && text.endsWith("\"")) {
                        String inner = text.substring(1, text.length() - 1)
                            .replace("\\\"", "\"").replace("\\\\", "\\");
                        if (inner.startsWith("{")) text = inner;
                    }
                    if (text != null) out.put(r.getString("node_name"), text);
                }
                return out;
            })
            .recover(err -> {
                if (isUndefinedTable(err)) return Future.succeededFuture(java.util.Map.of());
                return Future.failedFuture(err);
            });
    }

    /** Ajout pair UDP allowlist sur cache udp (+bump desired, +audit). */
    public Future<Long> upsertUdpPeer(String cacheName, String host, int port, boolean enabled, String actor) {
        try {
            new RelayUdpPeerConfig(1L, cacheName, host, port, enabled, 1L);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(new ConfigValidationException(e.getMessage()));
        }
        return pool.withTransaction(client ->
            client.preparedQuery("INSERT INTO csp_relay_udp_peer"
                    + " (cache_name, host, port, enabled, version, updated_at, updated_by)"
                    + " VALUES ($1,$2,$3,$4,1,now(),$5)"
                    + " ON CONFLICT (cache_name, host, port) DO UPDATE"
                    + " SET enabled=EXCLUDED.enabled, version=csp_relay_udp_peer.version+1,"
                    + " updated_at=now(), updated_by=EXCLUDED.updated_by"
                    + " RETURNING id, version")
                .execute(Tuple.of(cacheName, host.trim(), port, enabled, actor))
                .compose(ins -> {
                    Row r = firstOrNull(ins);
                    long id = r != null && r.getLong("id") != null ? r.getLong("id") : -1L;
                    long nv = r != null && r.getLong("version") != null ? r.getLong("version") : 1L;
                    return insertAudit(client, "CONFIG_UPSERTED", "csp_relay_udp_peer",
                        cacheName + ":" + host + ":" + port, null, nv, actor, "upsert udp peer")
                        .compose(v -> bumpDesiredForCacheTx(client, cacheName,
                            RelayWorkerContract.TTL_DEFAULT_MS, actor).map(x -> id));
                }));
    }

    /** Retrait pair UDP (+bump desired, +audit). */
    public Future<Void> deleteUdpPeer(long id, String actor) {
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT cache_name FROM csp_relay_udp_peer WHERE id=$1")
                .execute(Tuple.of(id))
                .compose(sel -> {
                    Row r = firstOrNull(sel);
                    if (r == null)
                        return Future.failedFuture(new ConfigNotFoundException("csp_relay_udp_peer", String.valueOf(id)));
                    String cacheName = r.getString("cache_name");
                    return client.preparedQuery("DELETE FROM csp_relay_udp_peer WHERE id=$1")
                        .execute(Tuple.of(id))
                        .compose(del -> insertAudit(client, "CONFIG_DELETED", "csp_relay_udp_peer",
                            String.valueOf(id), null, null, actor, "delete udp peer")
                            .compose(v -> bumpDesiredForCacheTx(client, cacheName,
                                RelayWorkerContract.TTL_DEFAULT_MS, actor).mapEmpty()));
                }));
    }

    /**
     * ACL utilisateurs sanitisee pour worker (FIX W4) : jamais de password_hash
     * ni secret en valeur, uniquement noms/profils. Hors transaction snapshot.
     */
    public Future<List<RelayWorkerContract.UserAclView>> loadWorkerUserAcls() {
        return pool.preparedQuery("SELECT u.name AS uname, u.enabled AS uen,"
                + " COALESCE(array_agg(DISTINCT up.profile) FILTER (WHERE up.profile IS NOT NULL), '{}') AS profs"
                + " FROM csp_user u LEFT JOIN csp_user_profile up ON up.user_name=u.name"
                + " GROUP BY u.name, u.enabled ORDER BY u.name")
            .execute()
            .map(rows -> {
                List<RelayWorkerContract.UserAclView> out = new ArrayList<>();
                for (Row r : rows) {
                    String uname = r.getString("uname");
                    boolean en = Boolean.TRUE.equals(r.getBoolean("uen"));
                    List<String> profs = new ArrayList<>();
                    try {
                        Object v = r.getValue("profs");
                        if (v instanceof String[] arr && arr != null) profs.addAll(List.of(arr));
                        else if (v instanceof java.util.Collection<?> coll) {
                            for (Object o : coll) if (o != null) profs.add(String.valueOf(o));
                        }
                    } catch (Exception ignored) {
                    }
                    if (uname != null) out.add(new RelayWorkerContract.UserAclView(uname, en, List.copyOf(profs)));
                }
                return out;
            })
            .recover(err -> {
                if (isUndefinedTable(err)) return Future.succeededFuture(List.of());
                return Future.failedFuture(err);
            });
    }

    /**
     * Observation transport (desired pull) et auto-guerison conditionnelle du bail (ADR 0002).
     * <p>Si le node est usable (enabled=true AND authorized=true AND revoked=false) ET que le bail
     * est expire (< now()) ou non initialise (IS NULL), regrante config_expires_at=now()+ttl et insere
     * un evenement d'audit WORKER_LEASE_RENEWED.
     * <p>En regime sain (bail valide), met uniquement a jour last_seen_at sans modifier config_expires_at
     * ni inserer de ligne d'audit.
     */
    public Future<Boolean> touchWorkerSeenAndHeal(String nodeName, long ttlMs, String actor) {
        long ttl = RelayWorkerContract.normalizeTtl(ttlMs);
        return pool.preparedQuery(
                "WITH node_before AS ("
                + "    SELECT node_name, desired_version,"
                + "           (enabled = true AND authorized = true AND revoked = false"
                + "            AND (config_expires_at IS NULL OR config_expires_at < now())) AS needs_heal"
                + "    FROM csp_relay_node"
                + "    WHERE node_name = $1"
                + "    FOR UPDATE"
                + "),"
                + "upd AS ("
                + "    UPDATE csp_relay_node n"
                + "    SET last_seen_at = now(),"
                + "        config_expires_at = CASE"
                + "            WHEN b.needs_heal THEN now() + make_interval(secs => $2)"
                + "            ELSE n.config_expires_at"
                + "        END,"
                + "        updated_at = CASE"
                + "            WHEN b.needs_heal THEN now()"
                + "            ELSE n.updated_at"
                + "        END,"
                + "        updated_by = CASE"
                + "            WHEN b.needs_heal THEN $3"
                + "            ELSE n.updated_by"
                + "        END"
                + "    FROM node_before b"
                + "    WHERE n.node_name = b.node_name"
                + "    RETURNING n.node_name, b.desired_version, b.needs_heal"
                + "),"
                + "ins_audit AS ("
                + "    INSERT INTO csp_audit_log (event_type, entity_type, entity_name, old_version, new_version, changed_by, summary)"
                + "    SELECT 'WORKER_LEASE_RENEWED', 'csp_relay_node', u.node_name, u.desired_version, u.desired_version, $3,"
                + "           'auto-heal expired worker lease on desired pull'"
                + "    FROM upd u"
                + "    WHERE u.needs_heal = true"
                + ")"
                + "SELECT needs_heal FROM upd")
            .execute(Tuple.of(nodeName, (double) (ttl / 1000), actor))
            .map(rows -> {
                Row r = firstOrNull(rows);
                return r != null && Boolean.TRUE.equals(r.getBoolean("needs_heal"));
            });
    }

    /** Observation transport (desired pull) : compatibilite deleguee. */
    public Future<Void> touchWorkerSeen(String nodeName) {
        return touchWorkerSeenAndHeal(nodeName, RelayWorkerContract.TTL_DEFAULT_MS, "worker:" + nodeName).mapEmpty();
    }

    /** Bump desired monotone + expiry pour un node (transaction appelante). */
    private Future<Void> bumpDesiredTx(io.vertx.sqlclient.SqlClient client, String nodeName, long ttlMs, String actor) {
        long ttl = RelayWorkerContract.normalizeTtl(ttlMs);
        return client.preparedQuery("UPDATE csp_relay_node SET desired_version=desired_version+1,"
                + " config_expires_at=now() + make_interval(secs => $2),"
                + " updated_at=now(), updated_by=$3 WHERE node_name=$1")
            .execute(Tuple.of(nodeName, (double) (ttl / 1000), actor))
            .compose(up -> insertAudit(client, "CONFIG_UPDATED", "csp_relay_node", nodeName,
                null, null, actor, "bump desired after relay dependency change").mapEmpty());
    }

    private Future<Void> bumpDesiredForCacheTx(io.vertx.sqlclient.SqlClient client, String cacheName, long ttlMs, String actor) {
        return client.preparedQuery("SELECT kind, node_name FROM csp_relay_cache WHERE name=$1")
            .execute(Tuple.of(cacheName))
            .compose(sel -> {
                Row r = firstOrNull(sel);
                if (r == null) return Future.succeededFuture();
                String kind = r.getString("kind");
                String node = r.getString("node_name");
                if ("node".equals(kind) && node != null) {
                    return bumpDesiredTx(client, node, ttlMs, actor);
                }
                // Cache local/udp partage : bump monotone de tous les nodes autorises.
                return client.preparedQuery("SELECT node_name FROM csp_relay_node WHERE authorized=true AND revoked=false")
                    .execute()
                    .compose(all -> {
                        Future<Void> chain = Future.succeededFuture();
                        for (Row row : all) {
                            String n = row.getString("node_name");
                            chain = chain.compose(v -> bumpDesiredTx(client, n, ttlMs, actor));
                        }
                        return chain;
                    });
            });
    }

    // =========================================================================
    // Affectations explicites par worker (Gemini FIX) + catalogue/ACL completes
    // =========================================================================

    public Future<Long> upsertNodeListener(String nodeName, long listenerId, boolean enabled,
                                           long expectedVersion, String actor) {
        if (expectedVersion < 1) return Future.failedFuture(new ConfigValidationException("version must be >=1"));
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT version FROM csp_relay_node_listener"
                    + " WHERE node_name=$1 AND listener_id=$2 FOR UPDATE")
                .execute(Tuple.of(nodeName, listenerId))
                .compose(sel -> {
                    Row existing = firstOrNull(sel);
                    Future<Long> op;
                    if (existing == null) {
                        if (expectedVersion != 1)
                            return Future.<Long>failedFuture(new ConfigNotFoundException(
                                "csp_relay_node_listener", nodeName + ":" + listenerId));
                        op = client.preparedQuery("INSERT INTO csp_relay_node_listener"
                                + " (node_name, listener_id, enabled, version, updated_at, updated_by)"
                                + " VALUES ($1,$2,$3,1,now(),$4)"
                                + " ON CONFLICT (node_name, listener_id) DO NOTHING RETURNING version")
                            .execute(Tuple.of(nodeName, listenerId, enabled, actor))
                            .compose(ins -> {
                                if (ins.rowCount() == 0)
                                    return Future.failedFuture(new OptimisticLockException(
                                        "csp_relay_node_listener", nodeName + ":" + listenerId, expectedVersion));
                                return insertAudit(client, "CONFIG_UPSERTED", "csp_relay_node_listener",
                                    nodeName + ":" + listenerId, null, 1L, actor, "assign listener")
                                    .map(v -> 1L);
                            });
                    } else {
                        long cur = existing.getLong("version");
                        if (cur != expectedVersion)
                            return Future.failedFuture(new OptimisticLockException(
                                "csp_relay_node_listener", nodeName + ":" + listenerId, expectedVersion));
                        op = client.preparedQuery("UPDATE csp_relay_node_listener SET enabled=$3,"
                                + " version=version+1, updated_at=now(), updated_by=$4"
                                + " WHERE node_name=$1 AND listener_id=$2 AND version=$5 RETURNING version")
                            .execute(Tuple.of(nodeName, listenerId, enabled, actor, expectedVersion))
                            .compose(up -> {
                                if (up.rowCount() == 0)
                                    return Future.failedFuture(new OptimisticLockException(
                                        "csp_relay_node_listener", nodeName + ":" + listenerId, expectedVersion));
                                Row r = firstOrNull(up);
                                long nv = r != null && r.getLong("version") != null ? r.getLong("version") : expectedVersion + 1;
                                return insertAudit(client, "CONFIG_UPDATED", "csp_relay_node_listener",
                                    nodeName + ":" + listenerId, expectedVersion, nv, actor, "assign listener")
                                    .map(v -> nv);
                            });
                    }
                    return op.compose(nv ->
                        bumpDesiredTx(client, nodeName, RelayWorkerContract.TTL_DEFAULT_MS, actor).map(v -> nv));
                }));
    }

    public Future<Void> deleteNodeListener(String nodeName, long listenerId, long expectedVersion, String actor) {
        return pool.withTransaction(client ->
            client.preparedQuery("DELETE FROM csp_relay_node_listener WHERE node_name=$1 AND listener_id=$2 AND version=$3")
                .execute(Tuple.of(nodeName, listenerId, expectedVersion))
                .compose(del -> {
                    if (del.rowCount() == 0)
                        return Future.failedFuture(new OptimisticLockException(
                            "csp_relay_node_listener", nodeName + ":" + listenerId, expectedVersion));
                    return insertAudit(client, "CONFIG_DELETED", "csp_relay_node_listener",
                        nodeName + ":" + listenerId, expectedVersion, null, actor, "unassign listener")
                        .compose(v -> bumpDesiredTx(client, nodeName, RelayWorkerContract.TTL_DEFAULT_MS, actor))
                        .mapEmpty();
                }));
    }

    public Future<Long> upsertNodeConnector(String nodeName, String connectorName, boolean enabled,
                                            long expectedVersion, String actor) {
        if (expectedVersion < 1) return Future.failedFuture(new ConfigValidationException("version must be >=1"));
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT version FROM csp_relay_node_connector"
                    + " WHERE node_name=$1 AND connector_name=$2 FOR UPDATE")
                .execute(Tuple.of(nodeName, connectorName))
                .compose(sel -> {
                    Row existing = firstOrNull(sel);
                    Future<Long> op;
                    if (existing == null) {
                        if (expectedVersion != 1)
                            return Future.<Long>failedFuture(new ConfigNotFoundException(
                                "csp_relay_node_connector", nodeName + ":" + connectorName));
                        op = client.preparedQuery("INSERT INTO csp_relay_node_connector"
                                + " (node_name, connector_name, enabled, version, updated_at, updated_by)"
                                + " VALUES ($1,$2,$3,1,now(),$4)"
                                + " ON CONFLICT (node_name, connector_name) DO NOTHING RETURNING version")
                            .execute(Tuple.of(nodeName, connectorName, enabled, actor))
                            .compose(ins -> {
                                if (ins.rowCount() == 0)
                                    return Future.failedFuture(new OptimisticLockException(
                                        "csp_relay_node_connector", nodeName + ":" + connectorName, expectedVersion));
                                return insertAudit(client, "CONFIG_UPSERTED", "csp_relay_node_connector",
                                    nodeName + ":" + connectorName, null, 1L, actor, "assign connector")
                                    .map(v -> 1L);
                            });
                    } else {
                        long cur = existing.getLong("version");
                        if (cur != expectedVersion)
                            return Future.failedFuture(new OptimisticLockException(
                                "csp_relay_node_connector", nodeName + ":" + connectorName, expectedVersion));
                        op = client.preparedQuery("UPDATE csp_relay_node_connector SET enabled=$3,"
                                + " version=version+1, updated_at=now(), updated_by=$4"
                                + " WHERE node_name=$1 AND connector_name=$2 AND version=$5 RETURNING version")
                            .execute(Tuple.of(nodeName, connectorName, enabled, actor, expectedVersion))
                            .compose(up -> {
                                if (up.rowCount() == 0)
                                    return Future.failedFuture(new OptimisticLockException(
                                        "csp_relay_node_connector", nodeName + ":" + connectorName, expectedVersion));
                                Row r = firstOrNull(up);
                                long nv = r != null && r.getLong("version") != null ? r.getLong("version") : expectedVersion + 1;
                                return insertAudit(client, "CONFIG_UPDATED", "csp_relay_node_connector",
                                    nodeName + ":" + connectorName, expectedVersion, nv, actor, "assign connector")
                                    .map(v -> nv);
                            });
                    }
                    return op.compose(nv ->
                        bumpDesiredTx(client, nodeName, RelayWorkerContract.TTL_DEFAULT_MS, actor).map(v -> nv));
                }));
    }

    public Future<Void> deleteNodeConnector(String nodeName, String connectorName, long expectedVersion, String actor) {
        return pool.withTransaction(client ->
            client.preparedQuery("DELETE FROM csp_relay_node_connector WHERE node_name=$1 AND connector_name=$2 AND version=$3")
                .execute(Tuple.of(nodeName, connectorName, expectedVersion))
                .compose(del -> {
                    if (del.rowCount() == 0)
                        return Future.failedFuture(new OptimisticLockException(
                            "csp_relay_node_connector", nodeName + ":" + connectorName, expectedVersion));
                    return insertAudit(client, "CONFIG_DELETED", "csp_relay_node_connector",
                        nodeName + ":" + connectorName, expectedVersion, null, actor, "unassign connector")
                        .compose(v -> bumpDesiredTx(client, nodeName, RelayWorkerContract.TTL_DEFAULT_MS, actor))
                        .mapEmpty();
                }));
    }

    /** ACL completes sanitisees (profils + connecteurs + services allow/block). */
    public Future<List<RelayWorkerContract.UserAclView>> loadWorkerUserAclsFull() {
        return pool.preparedQuery("SELECT u.name AS uname, u.enabled AS uen,"
                + " COALESCE(array_agg(DISTINCT up.profile) FILTER (WHERE up.profile IS NOT NULL), '{}') AS profs,"
                + " COALESCE(array_agg(DISTINCT uc.connector) FILTER (WHERE uc.connector IS NOT NULL), '{}') AS conns,"
                + " COALESCE(array_agg(DISTINCT us.profile || ':' || us.sid) FILTER (WHERE us.mode=0), '{}') AS allow,"
                + " COALESCE(array_agg(DISTINCT us.profile || ':' || us.sid) FILTER (WHERE us.mode=1), '{}') AS block"
                + " FROM csp_user u LEFT JOIN csp_user_profile up ON up.user_name=u.name"
                + " LEFT JOIN csp_user_connector uc ON uc.user_name=u.name"
                + " LEFT JOIN csp_user_service us ON us.user_name=u.name"
                + " GROUP BY u.name, u.enabled ORDER BY u.name")
            .execute()
            .map(rows -> {
                List<RelayWorkerContract.UserAclView> out = new ArrayList<>();
                for (Row r : rows) {
                    String uname = r.getString("uname");
                    if (uname == null) continue;
                    boolean en = Boolean.TRUE.equals(r.getBoolean("uen"));
                    List<String> profs = strList(r.getValue("profs"));
                    List<String> conns = strList(r.getValue("conns"));
                    java.util.Map<String, List<Integer>> allow = sidMap(strList(r.getValue("allow")));
                    java.util.Map<String, List<Integer>> block = sidMap(strList(r.getValue("block")));
                    out.add(new RelayWorkerContract.UserAclView(uname, en, profs, conns, allow, block));
                }
                return out;
            })
            .recover(err -> {
                if (isUndefinedTable(err)) return Future.succeededFuture(List.of());
                return Future.failedFuture(err);
            });
    }

    private static List<String> strList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof String[] arr && arr != null) out.addAll(List.of(arr));
        else if (v instanceof java.util.Collection<?> coll) {
            for (Object o : coll) if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    private static java.util.Map<String, List<Integer>> sidMap(List<String> pairs) {
        java.util.Map<String, List<Integer>> m = new java.util.HashMap<>();
        for (String p : pairs) {
            int i = p.lastIndexOf(':');
            if (i < 0) continue;
            try {
                m.computeIfAbsent(p.substring(0, i), k -> new ArrayList<>())
                    .add(Integer.parseInt(p.substring(i + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
        java.util.Map<String, List<Integer>> ro = new java.util.HashMap<>();
        m.forEach((k, v) -> ro.put(k, List.copyOf(v)));
        return ro;
    }

    /** Secrets protocoles : references seules, jamais de valeurs (+ politique cacheex V15). */
    public Future<List<RelayWorkerContract.UserSecretView>> loadWorkerProtocolSecrets() {
        return pool.preparedQuery("SELECT user_name, protocol, secret_ref, secret_version, enabled,"
                + " cacheex_mode, cacheex_max_hop, cacheex_filter"
                + " FROM csp_user_protocol_secret WHERE enabled=true ORDER BY user_name, protocol")
            .execute()
            .map(rows -> {
                List<RelayWorkerContract.UserSecretView> out = new ArrayList<>();
                for (Row r : rows) {
                    try {
                        Integer cxMode = null;
                        Integer cxHop = null;
                        String cxFilter = null;
                        try {
                            cxMode = r.getInteger("cacheex_mode");
                            cxHop = r.getInteger("cacheex_max_hop");
                            cxFilter = r.getString("cacheex_filter");
                        } catch (Exception preV15) {
                            // colonnes absentes (pre-V15) — policy off
                        }
                        out.add(new RelayWorkerContract.UserSecretView(
                            r.getString("user_name"), r.getString("protocol"),
                            r.getString("secret_ref"),
                            r.getLong("secret_version") != null ? r.getLong("secret_version") : 1L,
                            Boolean.TRUE.equals(r.getBoolean("enabled")),
                            cxMode, cxHop, cxFilter));
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Skipping malformed protocol secret row: " + e.getMessage());
                    }
                }
                return out;
            })
            .recover(err -> {
                if (isUndefinedTable(err)) return Future.succeededFuture(List.of());
                return Future.failedFuture(err);
            });
    }

    /** Catalogue publie (sans serial/secret carte). */
    public Future<List<RelayWorkerContract.CatalogView>> loadWorkerCatalog() {
        return pool.preparedQuery("SELECT profile, caid, provider, state, card_id"
                + " FROM csp_card_capability WHERE state='published' ORDER BY profile, caid, provider")
            .execute()
            .map(rows -> {
                List<RelayWorkerContract.CatalogView> out = new ArrayList<>();
                for (Row r : rows) {
                    try {
                        out.add(new RelayWorkerContract.CatalogView(
                            r.getString("profile"),
                            r.getInteger("caid") != null ? r.getInteger("caid") : 0,
                            r.getInteger("provider") != null ? r.getInteger("provider") : 0,
                            r.getString("state"),
                            r.getLong("card_id") != null ? r.getLong("card_id") : 0L));
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Skipping malformed catalog row: " + e.getMessage());
                    }
                }
                return out;
            })
            .recover(err -> {
                if (isUndefinedTable(err)) return Future.succeededFuture(List.of());
                return Future.failedFuture(err);
            });
    }

    // =====================================================================
    // DCW quality policy (V13) — lecture snapshot + CRUD admin transactionnel
    // (validation stricte avant ecriture, audit, bump desired workers ; NOTIFY
    // via triggers csp_config_changed).
    // =====================================================================

    private static final String DCW_POLICY_COLS = "enabled, adaptive_routing_enabled, auto_observe_enabled,"
        + " zero_counting_enabled, default_checksum_mode, repair_mode, allow_zero_half, version, updated_at";
    private static final String DCW_RULE_COLS = "id, profile, caid, provider, sid, source_id, digest_sha256,"
        + " action, checksum_mode, notes, enabled, version, updated_at";
    private static final String DCW_MAP_COLS = "id, profile, caid, provider, target_caid, target_provider, source_sid, target_sid, approved,"
        + " enabled, expires_at, version, updated_at";

    private static long tsMs(Row r, String col) {
        var t = r.getOffsetDateTime(col);
        return t != null ? t.toInstant().toEpochMilli() : 0L;
    }

    private static boolean boolOr(Row r, String col, boolean def) {
        Boolean b = r.getBoolean(col);
        return b != null ? b : def;
    }

    private static DcwPolicyConfig mapDcwPolicy(Row r) {
        return new DcwPolicyConfig(boolOr(r, "enabled", true), boolOr(r, "adaptive_routing_enabled", false),
            boolOr(r, "auto_observe_enabled", false), boolOr(r, "zero_counting_enabled", false),
            r.getString("default_checksum_mode"), r.getString("repair_mode"), boolOr(r, "allow_zero_half", true),
            List.of(), List.of(), r.getLong("version") != null ? r.getLong("version") : 1L, tsMs(r, "updated_at"));
    }

    private static DcwRuleConfig mapDcwRule(Row r) {
        return new DcwRuleConfig(r.getLong("id"), r.getString("profile"),
            r.getInteger("caid") != null ? r.getInteger("caid") : 0,
            r.getInteger("provider"),
            r.getInteger("sid") != null ? r.getInteger("sid") : 0,
            r.getString("source_id"), r.getString("digest_sha256"), r.getString("action"),
            r.getString("checksum_mode"), r.getString("notes"), boolOr(r, "enabled", true),
            r.getLong("version") != null ? r.getLong("version") : 1L, tsMs(r, "updated_at"));
    }

    private static DcwServiceMappingConfig mapDcwMapping(Row r) {
        int caid = r.getInteger("caid") != null ? r.getInteger("caid") : 0;
        int provider = r.getInteger("provider") != null ? r.getInteger("provider") : 0;
        int targetCaid = r.getInteger("target_caid") != null ? r.getInteger("target_caid") : caid;
        int targetProvider = r.getInteger("target_provider") != null ? r.getInteger("target_provider") : provider;
        return new DcwServiceMappingConfig(r.getLong("id"), r.getString("profile"),
            caid, provider, targetCaid, targetProvider,
            r.getInteger("source_sid") != null ? r.getInteger("source_sid") : 0,
            r.getInteger("target_sid") != null ? r.getInteger("target_sid") : 0,
            boolOr(r, "approved", false), boolOr(r, "enabled", true), tsMs(r, "expires_at"),
            r.getLong("version") != null ? r.getLong("version") : 1L, tsMs(r, "updated_at"));
    }

    private Future<SnapshotParts> loadDcwPolicy(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT " + DCW_POLICY_COLS + " FROM csp_dcw_policy WHERE id = 1")
            .execute()
            .map(rows -> {
                Row r = firstOrNull(rows);
                p.dcwPolicy = r != null ? mapDcwPolicy(r) : DcwPolicyConfig.DEFAULT;
                return p;
            })
            .recover(err -> {
                if (isUndefinedTable(err)) { p.dcwPolicy = DcwPolicyConfig.DEFAULT; return Future.succeededFuture(p); }
                return Future.failedFuture(err);
            });
    }

    /** Singleton CW-cycle (V15) — tolère un déploiement pre-V15 (défaut). */
    private Future<SnapshotParts> loadCwCycle(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT enabled, caids, max_list, keep_time_sec, on_bad, drop_old,"
                + " use_peer_cycle_metadata, version, updated_at, updated_by"
                + " FROM csp_cw_cycle_config WHERE id = 1")
            .execute()
            .map(rows -> {
                Row r = firstOrNull(rows);
                if (r == null) {
                    p.cwCycle = CwCycleConfig.DEFAULT;
                } else {
                    p.cwCycle = new CwCycleConfig(
                        r.getBoolean("enabled"),
                        CwCycleConfig.parseCaids(r.getString("caids")),
                        r.getInteger("max_list"),
                        r.getInteger("keep_time_sec"),
                        r.getString("on_bad"),
                        r.getBoolean("drop_old"),
                        r.getBoolean("use_peer_cycle_metadata"),
                        r.getLong("version"),
                        r.getOffsetDateTime("updated_at") != null
                            ? r.getOffsetDateTime("updated_at").toInstant().toEpochMilli() : null,
                        r.getString("updated_by"));
                }
                return p;
            })
            .recover(err -> {
                if (isUndefinedTable(err)) { p.cwCycle = CwCycleConfig.DEFAULT; return Future.succeededFuture(p); }
                return Future.failedFuture(err);
            });
    }

    /**
     * Mise à jour du singleton CW-cycle (remplacement complet, optimistic lock par ligne).
     * Audit transactionnel ; la validation stricte est faite par {@link CwCycleConfig}.
     */
    public Future<Long> updateCwCycle(CwCycleConfig cfg, String actor) {
        if (cfg == null) {
            return Future.failedFuture(new ConfigValidationException("cw-cycle config required"));
        }
        long expectedVersion = cfg.version();
        String caidsCsv = CwCycleConfig.caidsToCsv(cfg.caids());
        return pool.withTransaction(client ->
            client.preparedQuery(
                "UPDATE csp_cw_cycle_config SET enabled=$1, caids=$2, max_list=$3, keep_time_sec=$4,"
                    + " on_bad=$5, drop_old=$6, use_peer_cycle_metadata=$7,"
                    + " version=version+1, updated_at=now(), updated_by=$8"
                    + " WHERE id=1 AND version=$9 RETURNING version")
                .execute(Tuple.of(cfg.enabled(), caidsCsv, cfg.maxList(), cfg.keepTimeSec(),
                    cfg.onBad(), cfg.dropOld(), cfg.usePeerCycleMetadata(),
                    actor, expectedVersion))
                .compose(rows -> {
                    if (rows.rowCount() == 0) {
                        throw new OptimisticLockException("csp_cw_cycle_config", "1", expectedVersion);
                    }
                    long newVersion = expectedVersion + 1;
                    return insertAudit(client, "CONFIG_UPDATED", "csp_cw_cycle_config", "1",
                        expectedVersion, newVersion, actor,
                        "cw-cycle enabled=" + cfg.enabled() + " caids=" + caidsCsv
                            + " onBad=" + cfg.onBad())
                        .map(v -> newVersion);
                }));
    }

    /** Singleton quarantaine pairs (V16) — tolère un déploiement pre-V16 (défaut off). */
    private Future<SnapshotParts> loadPeerQuarantine(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT enabled, mode, min_samples, invalid_ratio_bps, window_ms,"
                + " duration_ms, max_peers, version, updated_at, updated_by"
                + " FROM csp_peer_quarantine_config WHERE id = 1")
            .execute()
            .map(rows -> {
                Row r = firstOrNull(rows);
                if (r == null) {
                    p.quarantine = PeerQuarantineConfig.DEFAULT;
                } else {
                    p.quarantine = new PeerQuarantineConfig(
                        r.getBoolean("enabled"),
                        r.getString("mode"),
                        r.getInteger("min_samples"),
                        r.getInteger("invalid_ratio_bps"),
                        r.getLong("window_ms"),
                        r.getLong("duration_ms"),
                        r.getInteger("max_peers"),
                        r.getLong("version"),
                        r.getOffsetDateTime("updated_at") != null
                            ? r.getOffsetDateTime("updated_at").toInstant().toEpochMilli() : null,
                        r.getString("updated_by"));
                }
                return p;
            })
            .recover(err -> {
                if (isUndefinedTable(err)) { p.quarantine = PeerQuarantineConfig.DEFAULT; return Future.succeededFuture(p); }
                return Future.failedFuture(err);
            });
    }

    /** Singleton quorum CacheEx (V16) — tolère un déploiement pre-V16 (défaut off). */
    private Future<SnapshotParts> loadCacheExQuorum(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT mode, min_origins, window_ms, max_keys, profiles, caids,"
                + " version, updated_at, updated_by"
                + " FROM csp_cacheex_quorum_config WHERE id = 1")
            .execute()
            .map(rows -> {
                Row r = firstOrNull(rows);
                if (r == null) {
                    p.quorum = CacheExQuorumConfig.DEFAULT;
                } else {
                    p.quorum = new CacheExQuorumConfig(
                        r.getString("mode"),
                        r.getInteger("min_origins"),
                        r.getLong("window_ms"),
                        r.getInteger("max_keys"),
                        parseCsvStrings(r.getString("profiles")),
                        CwCycleConfig.parseCaids(r.getString("caids")),
                        r.getLong("version"),
                        r.getOffsetDateTime("updated_at") != null
                            ? r.getOffsetDateTime("updated_at").toInstant().toEpochMilli() : null,
                        r.getString("updated_by"));
                }
                return p;
            })
            .recover(err -> {
                if (isUndefinedTable(err)) { p.quorum = CacheExQuorumConfig.DEFAULT; return Future.succeededFuture(p); }
                return Future.failedFuture(err);
            });
    }

    /** CSV simple de profils (vide/null = tous) ; valeurs bornées par le record. */
    static java.util.List<String> parseCsvStrings(String csv) {
        if (csv == null || csv.isBlank()) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : csv.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    static String toCsvStrings(java.util.List<String> values) {
        if (values == null || values.isEmpty()) return "";
        return String.join(",", values);
    }

    /**
     * Enregistre l'audit de levée de quarantaine centrale (sans secret, R6).
     * Échoue si la transaction BD échoue (pas de swallow silencieux).
     */
    public Future<Void> recordQuarantineReleaseAudit(String peerId, String actor) {
        if (peerId == null || peerId.isBlank()) return Future.succeededFuture();
        return pool.withTransaction(client ->
            insertAudit(client, "QUARANTINE_RELEASED", "csp_peer_quarantine", peerId.trim(),
                null, null, actor != null ? actor : "admin", "quarantine release: " + peerId.trim())
        );
    }

    /**
     * Mise à jour du singleton quarantaine (remplacement complet, optimistic lock par ligne).
     * Audit transactionnel ; la validation stricte est faite par {@link PeerQuarantineConfig}.
     */
    public Future<Long> updatePeerQuarantine(PeerQuarantineConfig cfg, String actor) {
        if (cfg == null) {
            return Future.failedFuture(new ConfigValidationException("peer quarantine config required"));
        }
        long expectedVersion = cfg.version();
        return pool.withTransaction(client ->
            client.preparedQuery(
                "UPDATE csp_peer_quarantine_config SET enabled=$1, mode=$2, min_samples=$3,"
                    + " invalid_ratio_bps=$4, window_ms=$5, duration_ms=$6, max_peers=$7,"
                    + " version=version+1, updated_at=now(), updated_by=$8"
                    + " WHERE id=1 AND version=$9 RETURNING version")
                .execute(Tuple.of(cfg.enabled(), cfg.mode(), cfg.minSamples(), cfg.invalidRatioBps(),
                    cfg.windowMs(), cfg.durationMs(), cfg.maxPeers(),
                    actor, expectedVersion))
                .compose(rows -> {
                    if (rows.rowCount() == 0) {
                        throw new OptimisticLockException("csp_peer_quarantine_config", "1", expectedVersion);
                    }
                    long newVersion = expectedVersion + 1;
                    return insertAudit(client, "CONFIG_UPDATED", "csp_peer_quarantine_config", "1",
                        expectedVersion, newVersion, actor,
                        "peer-quarantine enabled=" + cfg.enabled() + " mode=" + cfg.mode())
                        .compose(v -> bumpAllWorkersTx(client, actor))
                        .map(v -> newVersion);
                }));
    }

    /**
     * Mise à jour du singleton quorum CacheEx (remplacement complet, optimistic lock par ligne).
     * Audit transactionnel ; la validation stricte est faite par {@link CacheExQuorumConfig}.
     */
    public Future<Long> updateCacheExQuorum(CacheExQuorumConfig cfg, String actor) {
        if (cfg == null) {
            return Future.failedFuture(new ConfigValidationException("cacheex quorum config required"));
        }
        long expectedVersion = cfg.version();
        return pool.withTransaction(client ->
            client.preparedQuery(
                "UPDATE csp_cacheex_quorum_config SET mode=$1, min_origins=$2, window_ms=$3,"
                    + " max_keys=$4, profiles=$5, caids=$6,"
                    + " version=version+1, updated_at=now(), updated_by=$7"
                    + " WHERE id=1 AND version=$8 RETURNING version")
                .execute(Tuple.of(cfg.mode(), cfg.minOrigins(), cfg.windowMs(), cfg.maxKeys(),
                    toCsvStrings(cfg.profiles()), CwCycleConfig.caidsToCsv(cfg.caids()),
                    actor, expectedVersion))
                .compose(rows -> {
                    if (rows.rowCount() == 0) {
                        throw new OptimisticLockException("csp_cacheex_quorum_config", "1", expectedVersion);
                    }
                    long newVersion = expectedVersion + 1;
                    return insertAudit(client, "CONFIG_UPDATED", "csp_cacheex_quorum_config", "1",
                        expectedVersion, newVersion, actor,
                        "cacheex-quorum mode=" + cfg.mode() + " minOrigins=" + cfg.minOrigins())
                        .compose(v -> bumpAllWorkersTx(client, actor))
                        .map(v -> newVersion);
                }));
    }

    /** Lecture directe du singleton quarantaine (V16) ; pre-V16 => défaut off. */
    public Future<PeerQuarantineConfig> getPeerQuarantine() {
        return pool.preparedQuery("SELECT enabled, mode, min_samples, invalid_ratio_bps, window_ms,"
                + " duration_ms, max_peers, version, updated_at, updated_by"
                + " FROM csp_peer_quarantine_config WHERE id = 1")
            .execute()
            .map(rows -> {
                Row r = firstOrNull(rows);
                if (r == null) return PeerQuarantineConfig.DEFAULT;
                return new PeerQuarantineConfig(
                    r.getBoolean("enabled"), r.getString("mode"),
                    r.getInteger("min_samples"), r.getInteger("invalid_ratio_bps"),
                    r.getLong("window_ms"), r.getLong("duration_ms"), r.getInteger("max_peers"),
                    r.getLong("version"),
                    r.getOffsetDateTime("updated_at") != null
                        ? r.getOffsetDateTime("updated_at").toInstant().toEpochMilli() : null,
                    r.getString("updated_by"));
            })
            .recover(err -> isUndefinedTable(err)
                ? Future.succeededFuture(PeerQuarantineConfig.DEFAULT) : Future.failedFuture(err));
    }

    /** Lecture directe du singleton quorum CacheEx (V16) ; pre-V16 => défaut off. */
    public Future<CacheExQuorumConfig> getCacheExQuorum() {
        return pool.preparedQuery("SELECT mode, min_origins, window_ms, max_keys, profiles, caids,"
                + " version, updated_at, updated_by"
                + " FROM csp_cacheex_quorum_config WHERE id = 1")
            .execute()
            .map(rows -> {
                Row r = firstOrNull(rows);
                if (r == null) return CacheExQuorumConfig.DEFAULT;
                return new CacheExQuorumConfig(
                    r.getString("mode"), r.getInteger("min_origins"),
                    r.getLong("window_ms"), r.getInteger("max_keys"),
                    parseCsvStrings(r.getString("profiles")),
                    CwCycleConfig.parseCaids(r.getString("caids")),
                    r.getLong("version"),
                    r.getOffsetDateTime("updated_at") != null
                        ? r.getOffsetDateTime("updated_at").toInstant().toEpochMilli() : null,
                    r.getString("updated_by"));
            })
            .recover(err -> isUndefinedTable(err)
                ? Future.succeededFuture(CacheExQuorumConfig.DEFAULT) : Future.failedFuture(err));
    }

    // ============================================================
    // Bulk updates (contrat v2 §2) — versions PAR LIGNE, ordre croissant id,
    // UNE transaction, rollback total sur conflit/invalide/inconnu.
    // ============================================================

    /** Bornes bulk (contrat v2) : 1..100 items uniques + patch présent non vide. */
    static void validateBulkItems(java.util.List<BulkPatches.BulkItem> items,
                                  java.util.Set<String> presentFields) {
        if (items == null || items.isEmpty()) {
            throw new ConfigValidationException("items must contain at least 1 element");
        }
        if (items.size() > 100) {
            throw new ConfigValidationException("items limit 100");
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (BulkPatches.BulkItem it : items) {
            if (!seen.add(it.id())) {
                throw new ConfigValidationException("duplicate item id: " + it.id());
            }
        }
        if (presentFields == null || presentFields.isEmpty()) {
            throw new ConfigValidationException("patch must contain at least 1 field");
        }
    }

    /** Normalise une chaîne blank en null (ex. proxyName "" = connexion directe). */
    private static String blankToNull(String v) {
        return v != null && v.isBlank() ? null : v;
    }

    /** Tri déterministe : ids croissants (lexicographique). */
    static java.util.List<BulkPatches.BulkItem> sortedBulkItems(java.util.List<BulkPatches.BulkItem> items) {
        java.util.List<BulkPatches.BulkItem> copy = new java.util.ArrayList<>(items);
        copy.sort(java.util.Comparator.comparing(BulkPatches.BulkItem::id));
        return copy;
    }

    /**
     * Bulk connecteurs : patch partiel des options communes (aucun secret/identité/nodeId).
     * Tout-ou-rien : un item inconnu ({@link BulkUnknownItemsException}), une version en
     * désaccord ({@link OptimisticLockException}) ou une valeur invalide
     * ({@link ConfigValidationException}) roulle back TOUT.
     */
    public Future<List<BulkPatches.BulkItemResult>> bulkUpdateConnectors(
        List<BulkPatches.BulkItem> items, BulkPatches.ConnectorPatch patch,
        java.util.Set<String> present, String actor) {
        validateBulkItems(items, present);
        List<BulkPatches.BulkItem> sorted = sortedBulkItems(items);
        List<String> names = sorted.stream().map(BulkPatches.BulkItem::id).toList();
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT name, version FROM csp_connector WHERE name = ANY($1) FOR UPDATE")
                .execute(Tuple.of((Object) names.toArray(new String[0])))
                .compose(selRows -> {
                    java.util.Map<String, Long> current = new java.util.HashMap<>();
                    for (Row r : selRows) current.put(r.getString("name"), r.getLong("version"));
                    List<String> unknown = names.stream().filter(n -> !current.containsKey(n)).toList();
                    if (!unknown.isEmpty()) throw new BulkUnknownItemsException(unknown);
                    for (BulkPatches.BulkItem it : sorted) {
                        if (current.get(it.id()) != it.version()) {
                            throw new OptimisticLockException("csp_connector", it.id(), it.version());
                        }
                    }
                    StringBuilder sql = new StringBuilder("UPDATE csp_connector SET ");
                    List<Object> params = new ArrayList<>();
                    // (champ, valeur, présent, nullable, reset-défaut-unitaire)
                    appendBulkSet(sql, params, "enabled", patch.enabled(), present.contains("enabled"), false, null);
                    appendBulkSet(sql, params, "debug", patch.debug(), present.contains("debug"), false, null);
                    appendBulkSet(sql, params, "connect_timeout_ms", patch.connectTimeoutMs(), present.contains("connectTimeoutMs"), false, null);
                    appendBulkSet(sql, params, "reconnect_delay_ms", patch.reconnectDelayMs(), present.contains("reconnectDelayMs"), false, null);
                    appendBulkSet(sql, params, "keep_alive_sec", patch.keepAliveSec(), present.contains("keepAliveSec"), true, null);
                    appendBulkSet(sql, params, "min_delay_ms", patch.minDelayMs(), present.contains("minDelayMs"), true, null);
                    appendBulkSet(sql, params, "max_queue", patch.maxQueue(), present.contains("maxQueue"), true, null);
                    appendBulkSet(sql, params, "newcamd_max_msg_size", patch.newcamdMaxMsgSize(), present.contains("newcamdMaxMsgSize"), true, null);
                    appendBulkSet(sql, params, "radegast_response_timeout_ms", patch.radegastResponseTimeoutMs(), present.contains("radegastResponseTimeoutMs"), true, null);
                    appendBulkSet(sql, params, "cccam_enabled", patch.cccamEnabled(), present.contains("cccamEnabled"), false, null);
                    appendBulkSet(sql, params, "cccam_version", patch.cccamVersion(), present.contains("cccamVersion"), true, null);
                    appendBulkSet(sql, params, "priority", patch.priority(), present.contains("priority"), false, null);
                    appendBulkSet(sql, params, "max_capacity", patch.maxCapacity(), present.contains("maxCapacity"), false, null);
                    appendBulkSet(sql, params, "proxy_name", blankToNull(patch.proxyName()), present.contains("proxyName"), true, null);
                    appendBulkSet(sql, params, "card_discovery", patch.cardDiscovery(), present.contains("cardDiscovery"), false, Boolean.FALSE);
                    appendBulkSet(sql, params, "reshare_grant", patch.reshareGrant(), present.contains("reshareGrant"), true, null);
                    appendBulkSet(sql, params, "newcamd_dialect", patch.newcamdDialect(), present.contains("newcamdDialect"), true, "legacy");
                    appendBulkSet(sql, params, "reshare_cap", patch.reshareCap(), present.contains("reshareCap"), true, 0);
                    appendBulkSet(sql, params, "newcamd_password_encoding", patch.newcamdPasswordEncoding(), present.contains("newcamdPasswordEncoding"), true, "plaintext");
                    appendBulkSet(sql, params, "cacheex_mode", patch.cacheexMode(), present.contains("cacheexMode"), true, null);
                    appendBulkSet(sql, params, "cacheex_filter", patch.cacheexFilter(), present.contains("cacheexFilter"), true, null);
                    appendBulkSet(sql, params, "cacheex_max_send", patch.cacheexMaxSend(), present.contains("cacheexMaxSend"), true, null);
                    appendBulkSet(sql, params, "cacheex_max_hop", patch.cacheexMaxHop(), present.contains("cacheexMaxHop"), true, null);
                    // version=version+1 retiré du SET dynamique : ajouté ci-dessous
                    sql.append(", version = version + 1, updated_at = now(), updated_by = $")
                       .append(params.size() + 1);
                    params.add(actor);
                    sql.append(" WHERE name = ANY($").append(params.size() + 1).append(") RETURNING name, version");
                    params.add(names.toArray(new String[0]));
                    Tuple tuple = Tuple.tuple();
                    for (Object p : params) tuple.addValue(p);
                    return client.preparedQuery(sql.toString()).execute(tuple)
                        .compose(upRows -> {
                            java.util.Map<String, Long> newVersions = new java.util.HashMap<>();
                            for (Row r : upRows) newVersions.put(r.getString("name"), r.getLong("version"));
                            Future<Void> chain = Future.succeededFuture();
                            for (BulkPatches.BulkItem it : sorted) {
                                chain = chain.compose(v -> insertAudit(client, "BULK_UPDATE", "csp_connector",
                                    it.id(), it.version(), newVersions.get(it.id()), actor,
                                    "bulk connector patch"));
                            }
                            List<BulkPatches.BulkItemResult> out = sorted.stream()
                                .map(it -> new BulkPatches.BulkItemResult(it.id(), newVersions.get(it.id())))
                                .toList();
                            return chain.map(v -> out);
                        });
                }));
    }

    /** Bulk profils : patch partiel des options communes. */
    public Future<List<BulkPatches.BulkItemResult>> bulkUpdateProfiles(
        List<BulkPatches.BulkItem> items, BulkPatches.ProfilePatch patch,
        java.util.Set<String> present, String actor) {
        validateBulkItems(items, present);
        List<BulkPatches.BulkItem> sorted = sortedBulkItems(items);
        List<String> names = sorted.stream().map(BulkPatches.BulkItem::id).toList();
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT name, version FROM csp_profile WHERE name = ANY($1) FOR UPDATE")
                .execute(Tuple.of((Object) names.toArray(new String[0])))
                .compose(selRows -> {
                    java.util.Map<String, Long> current = new java.util.HashMap<>();
                    for (Row r : selRows) current.put(r.getString("name"), r.getLong("version"));
                    List<String> unknown = names.stream().filter(n -> !current.containsKey(n)).toList();
                    if (!unknown.isEmpty()) throw new BulkUnknownItemsException(unknown);
                    for (BulkPatches.BulkItem it : sorted) {
                        if (current.get(it.id()) != it.version()) {
                            throw new OptimisticLockException("csp_profile", it.id(), it.version());
                        }
                    }
                    StringBuilder sql = new StringBuilder("UPDATE csp_profile SET ");
                    List<Object> params = new ArrayList<>();
                    appendBulkSet(sql, params, "enabled", patch.enabled(), present.contains("enabled"), false, null);
                    appendBulkSet(sql, params, "debug", patch.debug(), present.contains("debug"), false, null);
                    appendBulkSet(sql, params, "cache_only", patch.cacheOnly(), present.contains("cacheOnly"), false, null);
                    appendBulkSet(sql, params, "require_provider_match", patch.requireProviderMatch(), present.contains("requireProviderMatch"), true, null);
                    appendBulkSet(sql, params, "check_ecm_length", patch.checkEcmLength(), present.contains("checkEcmLength"), true, null);
                    appendBulkSet(sql, params, "max_cw_wait_ms", patch.maxCwWaitMs(), present.contains("maxCwWaitMs"), false, null);
                    appendBulkSet(sql, params, "congestion_limit_ms", patch.congestionLimitMs(), present.contains("congestionLimitMs"), true, null);
                    appendBulkSet(sql, params, "mismatched_cards", patch.mismatchedCards(), present.contains("mismatchedCards"), false, null);
                    appendBulkSet(sql, params, "service_conflicts", patch.serviceConflicts(), present.contains("serviceConflicts"), false, null);
                    appendBulkSet(sql, params, "block_caid_mismatch", patch.blockCaidMismatch(), present.contains("blockCaidMismatch"), true, null);
                    appendBulkSet(sql, params, "provider_filter", patch.providerFilter(), present.contains("providerFilter"), true, null);
                    appendBulkSet(sql, params, "services_file", patch.servicesFile(), present.contains("servicesFile"), true, null);
                    appendBulkSet(sql, params, "services_file_format", patch.servicesFileFormat(), present.contains("servicesFileFormat"), true, null);
                    sql.append(", version = version + 1, updated_at = now(), updated_by = $")
                       .append(params.size() + 1);
                    params.add(actor);
                    sql.append(" WHERE name = ANY($").append(params.size() + 1).append(") RETURNING name, version");
                    params.add(names.toArray(new String[0]));
                    Tuple tuple = Tuple.tuple();
                    for (Object p : params) tuple.addValue(p);
                    return client.preparedQuery(sql.toString()).execute(tuple)
                        .compose(upRows -> {
                            java.util.Map<String, Long> newVersions = new java.util.HashMap<>();
                            for (Row r : upRows) newVersions.put(r.getString("name"), r.getLong("version"));
                            Future<Void> chain = Future.succeededFuture();
                            for (BulkPatches.BulkItem it : sorted) {
                                chain = chain.compose(v -> insertAudit(client, "BULK_UPDATE", "csp_profile",
                                    it.id(), it.version(), newVersions.get(it.id()), actor,
                                    "bulk profile patch"));
                            }
                            List<BulkPatches.BulkItemResult> out = sorted.stream()
                                .map(it -> new BulkPatches.BulkItemResult(it.id(), newVersions.get(it.id())))
                                .toList();
                            return chain.map(v -> out);
                        });
                }));
    }

    /** Bulk listeners (items = ids numériques en chaîne) : patch partiel des options communes. */
    public Future<List<BulkPatches.BulkItemResult>> bulkUpdateListeners(
        List<BulkPatches.BulkItem> items, BulkPatches.ListenerPatch patch,
        java.util.Set<String> present, String actor) {
        validateBulkItems(items, present);
        // Tri numérique déterministe
        List<BulkPatches.BulkItem> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> {
            try {
                return Long.compare(Long.parseLong(a.id()), Long.parseLong(b.id()));
            } catch (NumberFormatException e) {
                throw new ConfigValidationException("listener ids must be numeric");
            }
        });
        List<Long> ids;
        try {
            ids = sorted.stream().map(it -> Long.parseLong(it.id())).toList();
        } catch (NumberFormatException e) {
            return Future.failedFuture(new ConfigValidationException("listener ids must be numeric"));
        }
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT id, version FROM csp_profile_listener WHERE id = ANY($1) FOR UPDATE")
                .execute(Tuple.of((Object) ids.toArray(new Long[0])))
                .compose(selRows -> {
                    java.util.Map<Long, Long> current = new java.util.HashMap<>();
                    for (Row r : selRows) current.put(r.getLong("id"), r.getLong("version"));
                    List<String> unknown = sorted.stream()
                        .map(BulkPatches.BulkItem::id)
                        .filter(idStr -> !current.containsKey(Long.parseLong(idStr)))
                        .toList();
                    if (!unknown.isEmpty()) throw new BulkUnknownItemsException(unknown);
                    for (BulkPatches.BulkItem it : sorted) {
                        if (current.get(Long.parseLong(it.id())) != it.version()) {
                            throw new OptimisticLockException("csp_profile_listener", it.id(), it.version());
                        }
                    }
                    StringBuilder sql = new StringBuilder("UPDATE csp_profile_listener SET ");
                    List<Object> params = new ArrayList<>();
                    appendBulkSet(sql, params, "enabled", patch.enabled(), present.contains("enabled"), false, null);
                    appendBulkSet(sql, params, "card_mode", patch.cardMode(), present.contains("cardMode"), true, "manual");
                    appendBulkSet(sql, params, "strict_policy", patch.strictPolicy(), present.contains("strictPolicy"), false, null);
                    appendBulkSet(sql, params, "reshare_cap", patch.reshareCap(), present.contains("reshareCap"), true, 0);
                    appendBulkSet(sql, params, "max_cards", patch.maxCards(), present.contains("maxCards"), false, null);
                    appendBulkSet(sql, params, "max_inflight", patch.maxInflight(), present.contains("maxInflight"), false, null);
                    appendBulkSet(sql, params, "max_per_ip", patch.maxPerIp(), present.contains("maxPerIp"), false, null);
                    appendBulkSet(sql, params, "idle_timeout_sec", patch.idleTimeoutSec(), present.contains("idleTimeoutSec"), false, null);
                    appendBulkSet(sql, params, "allow_list", patch.allowList(), present.contains("allowList"), true, null);
                    appendBulkSet(sql, params, "deny_list", patch.denyList(), present.contains("denyList"), true, null);
                    sql.append(", version = version + 1, updated_at = now(), updated_by = $")
                       .append(params.size() + 1);
                    params.add(actor);
                    sql.append(" WHERE id = ANY($").append(params.size() + 1).append(") RETURNING id, version");
                    params.add(ids.toArray(new Long[0]));
                    Tuple tuple = Tuple.tuple();
                    for (Object p : params) tuple.addValue(p);
                    return client.preparedQuery(sql.toString()).execute(tuple)
                        .compose(upRows -> {
                            java.util.Map<Long, Long> newVersions = new java.util.HashMap<>();
                            for (Row r : upRows) newVersions.put(r.getLong("id"), r.getLong("version"));
                            Future<Void> chain = Future.succeededFuture();
                            for (BulkPatches.BulkItem it : sorted) {
                                chain = chain.compose(v -> insertAudit(client, "BULK_UPDATE", "csp_profile_listener",
                                    it.id(), it.version(), newVersions.get(Long.parseLong(it.id())), actor,
                                    "bulk listener patch"));
                            }
                            List<BulkPatches.BulkItemResult> out = sorted.stream()
                                .map(it -> new BulkPatches.BulkItemResult(it.id(),
                                    newVersions.get(Long.parseLong(it.id()))))
                                .toList();
                            return chain.map(v -> out);
                        });
                }));
    }

    /**
     * Ajoute une colonne au SET dynamique bulk avec sémantique de PRÉSENCE (contrat v2 §2) :
     * <ul>
     *   <li>champ ABSENT du patch ⇒ {@code col = col} (inchangé) ;</li>
     *   <li>champ présent non-null ⇒ {@code col = $n} (valeur déjà validée par le record) ;</li>
     *   <li>champ présent null + colonne NULLABLE ⇒ reset ({@code col = NULL}, ou
     *       {@code resetDefault} quand le PUT unitaire applique un défaut : p.ex.
     *       {@code reshareCap→0}, {@code cardMode→'manual'}, {@code proxyName blank→NULL}) ;</li>
     *   <li>champ présent null + colonne NOT NULL ⇒ {@link ConfigValidationException}
     *       (400 — jamais de succès silencieux sans modification).</li>
     * </ul>
     */
    private static void appendBulkSet(StringBuilder sql, List<Object> params, String col,
                                      Object value, boolean present, boolean nullable,
                                      Object resetDefault) {
        if (sql.indexOf("=") >= 0) {
            sql.append(", ");
        }
        if (!present) {
            sql.append(col).append(" = ").append(col);
            return;
        }
        if (value != null) {
            sql.append(col).append(" = $").append(params.size() + 1);
            params.add(value);
            return;
        }
        if (!nullable) {
            throw new ConfigValidationException(
                "bulk patch field '" + col + "' does not accept null (omit it to leave unchanged)");
        }
        if (resetDefault != null) {
            sql.append(col).append(" = $").append(params.size() + 1);
            params.add(resetDefault);
        } else {
            sql.append(col).append(" = NULL");
        }
    }

    /** Aucun enregistrement ignore silencieusement : une ligne invalide fait echouer la validation du snapshot. */
    private Future<SnapshotParts> loadDcwRules(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT " + DCW_RULE_COLS + " FROM csp_dcw_rule ORDER BY id ASC")
            .execute()
            .map(rows -> {
                List<DcwRuleConfig> out = new ArrayList<>();
                for (Row r : rows) out.add(mapDcwRule(r));
                p.dcwRules = out;
                return p;
            })
            .recover(err -> {
                if (isUndefinedTable(err)) { p.dcwRules = List.of(); return Future.succeededFuture(p); }
                return Future.failedFuture(err);
            });
    }

    private Future<SnapshotParts> loadDcwServiceMappings(io.vertx.sqlclient.SqlClient client, SnapshotParts p) {
        return client.preparedQuery("SELECT " + DCW_MAP_COLS + " FROM csp_dcw_service_mapping ORDER BY id ASC")
            .execute()
            .map(rows -> {
                List<DcwServiceMappingConfig> out = new ArrayList<>();
                for (Row r : rows) out.add(mapDcwMapping(r));
                p.dcwMappings = out;
                return p;
            })
            .recover(err -> {
                if (isUndefinedTable(err)) { p.dcwMappings = List.of(); return Future.succeededFuture(p); }
                return Future.failedFuture(err);
            });
    }

    /** Charge la policy complete (en-tete + regles + mappings) avec le client donne (tx possible). */
    private Future<DcwPolicyConfig> loadDcwPolicyFull(io.vertx.sqlclient.SqlClient client, boolean lock) {
        SnapshotParts parts = new SnapshotParts();
        Future<SnapshotParts> head = lock
            ? client.preparedQuery("SELECT " + DCW_POLICY_COLS + " FROM csp_dcw_policy WHERE id = 1 FOR UPDATE")
                .execute().map(rows -> {
                    Row r = firstOrNull(rows);
                    parts.dcwPolicy = r != null ? mapDcwPolicy(r) : DcwPolicyConfig.DEFAULT;
                    return parts;
                })
            : loadDcwPolicy(client, parts);
        return head.compose(p -> loadDcwRules(client, p))
            .compose(p -> loadDcwServiceMappings(client, p))
            .map(p -> p.dcwPolicy.withRulesAndMappings(p.dcwRules, p.dcwMappings));
    }

    public Future<DcwPolicyConfig> getDcwPolicy() {
        return loadDcwPolicyFull(pool, false);
    }

    public Future<List<DcwRuleConfig>> listDcwRules() {
        return getDcwPolicy().map(DcwPolicyConfig::rules);
    }

    public Future<List<DcwServiceMappingConfig>> listDcwServiceMappings() {
        return getDcwPolicy().map(DcwPolicyConfig::mappings);
    }

    private static boolean sameEndpoint(int caid1, int prov1, int sid1, int caid2, int prov2, int sid2) {
        return caid1 == caid2 && prov1 == prov2 && sid1 == sid2;
    }

    private static boolean sameProfile(String p1, String p2) {
        if (p1 == null && p2 == null) return true;
        if (p1 == null || p2 == null) return false;
        return p1.equalsIgnoreCase(p2);
    }

    private static void checkNoDuplicateMappingsForId(List<DcwServiceMappingConfig> mappings, long targetId) {
        if (mappings == null || mappings.size() < 2) return;
        DcwServiceMappingConfig target = null;
        for (DcwServiceMappingConfig m : mappings) {
            if (m.id() == targetId) {
                target = m;
                break;
            }
        }
        if (target == null) return;
        for (DcwServiceMappingConfig other : mappings) {
            if (other.id() == targetId) continue;
            if (sameProfile(target.profile(), other.profile())) {
                boolean direct = sameEndpoint(target.caid(), target.provider(), target.sourceSid(), other.caid(), other.provider(), other.sourceSid())
                    && sameEndpoint(target.targetCaid(), target.targetProvider(), target.targetSid(), other.targetCaid(), other.targetProvider(), other.targetSid());
                boolean inverse = sameEndpoint(target.caid(), target.provider(), target.sourceSid(), other.targetCaid(), other.targetProvider(), other.targetSid())
                    && sameEndpoint(target.targetCaid(), target.targetProvider(), target.targetSid(), other.caid(), other.provider(), other.sourceSid());
                if (direct || inverse) {
                    throw new ConfigValidationException("Duplicate or inverse service mapping conflict in profile '"
                        + target.profile() + "'");
                }
            }
        }
    }

    /** Valide la policy candidate complete (bornes, conflits, profils connus, compilation) ; echec => ConfigValidationException (400). */
    private static Future<Void> checkCandidate(DcwPolicyConfig candidate, java.util.Set<String> knownProfiles) {
        List<String> errors = DcwConfigRules.validatePolicy(candidate, knownProfiles);
        if (!errors.isEmpty()) {
            return Future.failedFuture(new ConfigValidationException(String.join("; ", errors)));
        }
        return Future.succeededFuture();
    }

    /**
     * Mutation DCW transactionnelle : verrou ligne policy, calcul du candidat, validation stricte
     * avec verification des profils connus dans la MEME transaction AVANT ecriture, ecriture, audit,
     * bump desired de tous les workers autorises.
     */
    private <T> Future<T> dcwMutation(String actor, String entity, String entityName, String summary,
                                      java.util.function.Function<DcwPolicyConfig, DcwPolicyConfig> candidateOf,
                                      java.util.function.BiFunction<io.vertx.sqlclient.SqlClient, DcwPolicyConfig, Future<T>> write) {
        return pool.withTransaction(client ->
            client.preparedQuery("INSERT INTO csp_dcw_policy (id) VALUES (1) ON CONFLICT (id) DO NOTHING").execute()
                .compose(x -> loadDcwPolicyFull(client, true))
                .compose(current ->
                    client.preparedQuery("SELECT name FROM csp_profile").execute()
                        .map(rows -> {
                            java.util.Set<String> set = new java.util.HashSet<>();
                            for (Row r : rows) {
                                String n = r.getString("name");
                                if (n != null) set.add(n);
                            }
                            return set;
                        })
                        .compose(knownProfiles -> {
                            DcwPolicyConfig candidate = candidateOf.apply(current);
                            return checkCandidate(candidate, knownProfiles)
                                .compose(v -> write.apply(client, current))
                                .compose(res -> insertAudit(client, "CONFIG_UPDATED", entity, entityName, null, null, actor, summary)
                                    .compose(v -> bumpAllWorkersTx(client, actor))
                                    .map(v -> res));
                        })
                ));
    }

    private Future<Void> bumpAllWorkersTx(io.vertx.sqlclient.SqlClient client, String actor) {
        return client.preparedQuery("SELECT node_name FROM csp_relay_node WHERE authorized = true AND revoked = false")
            .execute()
            .compose(all -> {
                Future<Void> chain = Future.succeededFuture();
                for (Row row : all) {
                    String n = row.getString("node_name");
                    chain = chain.compose(v -> bumpDesiredTx(client, n, RelayWorkerContract.TTL_DEFAULT_MS, actor));
                }
                return chain;
            })
            .recover(err -> isUndefinedTable(err) ? Future.succeededFuture() : Future.failedFuture(err));
    }

    private static <E> List<E> replaceById(List<E> list, java.util.function.ToLongFunction<E> id, long target, E repl) {
        List<E> out = new ArrayList<>();
        boolean found = false;
        for (E e : list) {
            if (id.applyAsLong(e) == target) { found = true; if (repl != null) out.add(repl); }
            else out.add(e);
        }
        if (!found && repl != null) out.add(repl);
        return out;
    }

    public Future<Void> saveDcwPolicy(boolean enabled, boolean adaptiveRoutingEnabled, boolean autoObserveEnabled,
                                      String defaultChecksumMode, String repairMode, boolean allowZeroHalf,
                                      long version, String user) {
        return saveDcwPolicy(enabled, adaptiveRoutingEnabled, autoObserveEnabled, false, defaultChecksumMode,
            repairMode, allowZeroHalf, version, user);
    }

    public Future<Void> saveDcwPolicy(boolean enabled, boolean adaptiveRoutingEnabled, boolean autoObserveEnabled,
                                      boolean zeroCountingEnabled, String defaultChecksumMode, String repairMode,
                                      boolean allowZeroHalf, long version, String user) {
        return dcwMutation(user, "csp_dcw_policy", "1",
            "dcw policy updated checksum=" + defaultChecksumMode + " repair=" + repairMode,
            cur -> new DcwPolicyConfig(enabled, adaptiveRoutingEnabled, autoObserveEnabled, zeroCountingEnabled,
                defaultChecksumMode, repairMode, allowZeroHalf, cur.rules(), cur.mappings(), version, 0L),
            (client, cur) -> client.preparedQuery("UPDATE csp_dcw_policy SET enabled=$1, adaptive_routing_enabled=$2,"
                    + " auto_observe_enabled=$3, zero_counting_enabled=$4, default_checksum_mode=$5, repair_mode=$6,"
                    + " allow_zero_half=$7, version=version+1, updated_at=now(), updated_by=$8 WHERE id=1 AND version=$9")
                .execute(Tuple.from(new Object[]{enabled, adaptiveRoutingEnabled, autoObserveEnabled, zeroCountingEnabled,
                    defaultChecksumMode, repairMode, allowZeroHalf, user, version}))
                .compose(rows -> rows.rowCount() == 0
                    ? Future.failedFuture(new OptimisticLockException("csp_dcw_policy", "1", version))
                    : Future.<Void>succeededFuture()));
    }

    public Future<Long> createDcwRule(DcwRuleConfig rule, String user) {
        return dcwMutation(user, "csp_dcw_rule", "new", "dcw rule created action=" + rule.action(),
            cur -> cur.withRulesAndMappings(replaceById(cur.rules(), DcwRuleConfig::id, Long.MIN_VALUE,
                new DcwRuleConfig(Long.MIN_VALUE, rule.profile(), rule.caid(), rule.provider(), rule.sid(), rule.sourceId(),
                    rule.digestSha256(), rule.action(), rule.checksumMode(), rule.notes(), rule.enabled(), 1L, 0L)), cur.mappings()),
            (client, cur) -> client.preparedQuery("INSERT INTO csp_dcw_rule (profile, caid, provider, sid, source_id,"
                    + " digest_sha256, action, checksum_mode, notes, enabled, version, updated_at, updated_by)"
                    + " VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,1,now(),$11) RETURNING id")
                .execute(Tuple.from(new Object[]{rule.profile(), rule.caid(), rule.provider(), rule.sid(), rule.sourceId(),
                    rule.digestSha256(), rule.action(), rule.checksumMode(), rule.notes(), rule.enabled(), user}))
                .map(rows -> firstOrNull(rows).getLong("id")));
    }

    public Future<Void> updateDcwRule(long id, DcwRuleConfig rule, long version, String user) {
        return dcwMutation(user, "csp_dcw_rule", String.valueOf(id), "dcw rule updated action=" + rule.action(),
            cur -> {
                if (cur.rules().stream().noneMatch(r -> r.id() == id)) throw new ConfigNotFoundException("csp_dcw_rule", String.valueOf(id));
                return cur.withRulesAndMappings(replaceById(cur.rules(), DcwRuleConfig::id, id,
                    new DcwRuleConfig(id, rule.profile(), rule.caid(), rule.provider(), rule.sid(), rule.sourceId(),
                        rule.digestSha256(), rule.action(), rule.checksumMode(), rule.notes(), rule.enabled(), version, 0L)), cur.mappings());
            },
            (client, cur) -> client.preparedQuery("UPDATE csp_dcw_rule SET profile=$1, caid=$2, provider=$3, sid=$4,"
                    + " source_id=$5, digest_sha256=$6, action=$7, checksum_mode=$8, notes=$9, enabled=$10,"
                    + " version=version+1, updated_at=now(), updated_by=$11 WHERE id=$12 AND version=$13")
                .execute(Tuple.from(new Object[]{rule.profile(), rule.caid(), rule.provider(), rule.sid(), rule.sourceId(),
                    rule.digestSha256(), rule.action(), rule.checksumMode(), rule.notes(), rule.enabled(), user, id, version}))
                .compose(rows -> rows.rowCount() == 0
                    ? Future.failedFuture(new OptimisticLockException("csp_dcw_rule", String.valueOf(id), version))
                    : Future.<Void>succeededFuture()));
    }

    public Future<Void> deleteDcwRule(long id, String user) {
        return dcwMutation(user, "csp_dcw_rule", String.valueOf(id), "dcw rule deleted",
            cur -> cur.withRulesAndMappings(replaceById(cur.rules(), DcwRuleConfig::id, id, null), cur.mappings()),
            (client, cur) -> client.preparedQuery("DELETE FROM csp_dcw_rule WHERE id=$1").execute(Tuple.of(id))
                .compose(rows -> rows.rowCount() == 0
                    ? Future.failedFuture(new ConfigNotFoundException("csp_dcw_rule", String.valueOf(id)))
                    : Future.<Void>succeededFuture()));
    }

    private static java.time.OffsetDateTime expiresTs(long ms) {
        return ms <= 0 ? null : java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneOffset.UTC);
    }

    public Future<Long> createDcwServiceMapping(DcwServiceMappingConfig m, String user) {
        return dcwMutation(user, "csp_dcw_service_mapping", "new", "dcw mapping created approved=" + m.approved(),
            cur -> {
                DcwPolicyConfig cand = cur.withRulesAndMappings(cur.rules(), replaceById(cur.mappings(), DcwServiceMappingConfig::id, Long.MIN_VALUE,
                    new DcwServiceMappingConfig(Long.MIN_VALUE, m.profile(), m.caid(), m.provider(), m.targetCaid(), m.targetProvider(),
                        m.sourceSid(), m.targetSid(), m.approved(), m.enabled(), m.expiresAtMs(), 1L, 0L)));
                checkNoDuplicateMappingsForId(cand.mappings(), Long.MIN_VALUE);
                return cand;
            },
            (client, cur) -> client.preparedQuery("INSERT INTO csp_dcw_service_mapping (profile, caid, provider, target_caid, target_provider, source_sid,"
                    + " target_sid, approved, enabled, expires_at, version, updated_at, updated_by)"
                    + " VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,1,now(),$11) RETURNING id")
                .execute(Tuple.from(new Object[]{m.profile(), m.caid(), m.provider(), m.targetCaid(), m.targetProvider(),
                    m.sourceSid(), m.targetSid(), m.approved(), m.enabled(), expiresTs(m.expiresAtMs()), user}))
                .map(rows -> firstOrNull(rows).getLong("id")));
    }

    public Future<Void> updateDcwServiceMapping(long id, DcwServiceMappingConfig m, long version, String user) {
        return dcwMutation(user, "csp_dcw_service_mapping", String.valueOf(id), "dcw mapping updated approved=" + m.approved(),
            cur -> {
                if (cur.mappings().stream().noneMatch(x -> x.id() == id)) throw new ConfigNotFoundException("csp_dcw_service_mapping", String.valueOf(id));
                DcwPolicyConfig cand = cur.withRulesAndMappings(cur.rules(), replaceById(cur.mappings(), DcwServiceMappingConfig::id, id,
                    new DcwServiceMappingConfig(id, m.profile(), m.caid(), m.provider(), m.targetCaid(), m.targetProvider(),
                        m.sourceSid(), m.targetSid(), m.approved(), m.enabled(), m.expiresAtMs(), version, 0L)));
                checkNoDuplicateMappingsForId(cand.mappings(), id);
                return cand;
            },
            (client, cur) -> client.preparedQuery("UPDATE csp_dcw_service_mapping SET profile=$1, caid=$2, provider=$3,"
                    + " target_caid=$4, target_provider=$5, source_sid=$6, target_sid=$7, approved=$8, enabled=$9, expires_at=$10, version=version+1,"
                    + " updated_at=now(), updated_by=$11 WHERE id=$12 AND version=$13")
                .execute(Tuple.from(new Object[]{m.profile(), m.caid(), m.provider(), m.targetCaid(), m.targetProvider(),
                    m.sourceSid(), m.targetSid(), m.approved(), m.enabled(), expiresTs(m.expiresAtMs()), user, id, version}))
                .compose(rows -> rows.rowCount() == 0
                    ? Future.failedFuture(new OptimisticLockException("csp_dcw_service_mapping", String.valueOf(id), version))
                    : Future.<Void>succeededFuture()));
    }

    public Future<Void> deleteDcwServiceMapping(long id, String user) {
        return dcwMutation(user, "csp_dcw_service_mapping", String.valueOf(id), "dcw mapping deleted",
            cur -> cur.withRulesAndMappings(cur.rules(), replaceById(cur.mappings(), DcwServiceMappingConfig::id, id, null)),
            (client, cur) -> client.preparedQuery("DELETE FROM csp_dcw_service_mapping WHERE id=$1").execute(Tuple.of(id))
                .compose(rows -> rows.rowCount() == 0
                    ? Future.failedFuture(new ConfigNotFoundException("csp_dcw_service_mapping", String.valueOf(id)))
                    : Future.<Void>succeededFuture()));
    }
}
