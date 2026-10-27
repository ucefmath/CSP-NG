-- =========================================================================
-- CSP-NG L5a : Schema de configuration PostgreSQL (source unique de verite)
-- Version V1 — migration Flyway initiale
-- =========================================================================
-- Conventions :
--   - Chaque table de config mutable a : version bigint, updated_at, updated_by
--   - Les secrets sont des *_ref (references env/file/vault), jamais en clair
--   - Trigger csp_config_notify sur tables config (payload VIDE)
--   - Trigger csp_user_changed sur csp_user (payload VIDE, separe)
--   - optimistic locking : WHERE version = ? (rowCount = 0 -> conflit)
--   - PAS de BEGIN/COMMIT : Flyway gere la transaction
-- =========================================================================

-- =========================================================================
-- 1. ROOT CONFIG (parametres globaux du proxy)
-- =========================================================================
CREATE TABLE csp_root_config (
    id                smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1), -- singleton
    proxy_name        text NOT NULL DEFAULT 'CSP-NG',
    proxy_origin_id   integer NOT NULL DEFAULT 0,
    version           bigint NOT NULL DEFAULT 1,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    updated_by        text
);

-- =========================================================================
-- 2. LOGGING CONFIG
-- =========================================================================
CREATE TABLE csp_logging_config (
    id                smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1), -- singleton
    log_level         text NOT NULL DEFAULT 'INFO',
    log_file          text,
    log_ecm           boolean NOT NULL DEFAULT true,
    log_emm           boolean NOT NULL DEFAULT true,
    log_zap           boolean NOT NULL DEFAULT true,
    log_failures      boolean NOT NULL DEFAULT true,
    silent            boolean NOT NULL DEFAULT false,
    debug             boolean NOT NULL DEFAULT false,
    hide_ip_addresses boolean NOT NULL DEFAULT false,
    log_dateformat    text NOT NULL DEFAULT 'yyMMdd HH:mm:ss.SSS',
    log_rotate_count  integer NOT NULL DEFAULT 0,
    log_rotate_limit  integer NOT NULL DEFAULT 0,
    -- warning-threshold
    wt_bad_flags      text NOT NULL DEFAULT 'YMNTSAOQGXWDHU-',
    wt_max_delay_ms   integer NOT NULL DEFAULT 5000,
    wt_include_file_events boolean NOT NULL DEFAULT true,
    -- event-threshold
    et_min_count      integer NOT NULL DEFAULT 1,
    version           bigint NOT NULL DEFAULT 1,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    updated_by        text
);

-- =========================================================================
-- 3. CA PROFILES GLOBAL (defaults herites par chaque profile)
-- =========================================================================
CREATE TABLE csp_ca_profiles_global (
    id                          smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    default_des_key_ref         text,   -- ref env: DEFAULT_PROFILE_DES_KEY (14 bytes hex)
    default_connector_des_key_ref text, -- ref env: DEFAULT_CONNECTOR_DES_KEY
    default_client_id_ref       text,   -- ref env: DEFAULT_CLIENT_ID
    default_connector_keepalive integer NOT NULL DEFAULT 15,
    default_connector_min_delay integer NOT NULL DEFAULT 10,
    default_connector_max_queue integer NOT NULL DEFAULT 200,
    block_caid_mismatch         boolean,
    version                     bigint NOT NULL DEFAULT 1,
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    updated_by                  text
);

-- =========================================================================
-- 4. PROFILES (CaProfile)
-- =========================================================================
CREATE TABLE csp_profile (
    name                text PRIMARY KEY,
    enabled             boolean NOT NULL DEFAULT true,
    debug               boolean NOT NULL DEFAULT false,
    cache_only          boolean NOT NULL DEFAULT false,
    ca_id               integer NOT NULL DEFAULT 0,
    network_id          integer NOT NULL DEFAULT 0,
    require_provider_match boolean,
    check_ecm_length    boolean,
    max_cw_wait_ms      bigint NOT NULL DEFAULT 5000,
    congestion_limit_ms  bigint,
    mismatched_cards    boolean NOT NULL DEFAULT false,
    service_conflicts   integer NOT NULL DEFAULT 0,
    services_file       text,
    provider_filter     text,
    services_file_format text,
    block_caid_mismatch boolean,
    version             bigint NOT NULL DEFAULT 1,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          text
);

-- Profile listeners (ports d'ecoute par protocole)
CREATE TABLE csp_profile_listener (
    id                  bigserial PRIMARY KEY,
    profile_name        text NOT NULL REFERENCES csp_profile(name) ON DELETE CASCADE,
    protocol            text NOT NULL,       -- newcamd, radegast, cccam, csp, ghttp
    port                integer NOT NULL CHECK (port BETWEEN 1 AND 65535),
    bind_address        text NOT NULL DEFAULT '0.0.0.0',
    enabled             boolean NOT NULL DEFAULT true,
    des_key_ref         text,               -- ref env/file: profile DES key pour newcamd
    allow_list          text,               -- IPs autorisees (csv)
    deny_list           text,               -- IPs bloquees (csv)
    version             bigint NOT NULL DEFAULT 1,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          text,
    UNIQUE (profile_name, protocol, port)
);

-- Profile cards (infos carte upstream par profile)
CREATE TABLE csp_profile_card (
    id                  bigserial PRIMARY KEY,
    profile_name        text NOT NULL REFERENCES csp_profile(name) ON DELETE CASCADE,
    card_id             text NOT NULL,
    card_label          text,
    ca_id               integer,
    node_id             text,
    version             bigint NOT NULL DEFAULT 1,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          text,
    UNIQUE (profile_name, card_id)
);

-- =========================================================================
-- 5. CONNECTION-MANAGER CONFIG
-- =========================================================================
CREATE TABLE csp_connection_manager (
    id                  smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    max_threads         integer NOT NULL DEFAULT 64,
    session_timeout_ms  integer NOT NULL DEFAULT 30000,
    newcamd_max_msg_size integer NOT NULL DEFAULT 2048,
    max_pending         integer NOT NULL DEFAULT 500,
    max_connections_ip  integer NOT NULL DEFAULT 5,
    session_keepalive_sec integer NOT NULL DEFAULT 30,
    session_keepalive_excluded text,   -- csv client ids
    version             bigint NOT NULL DEFAULT 1,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          text
);

-- =========================================================================
-- 6. SERVICE-MAP (mapping service -> connectors)
-- =========================================================================
CREATE TABLE csp_service_map (
    id                  bigserial PRIMARY KEY,
    profile_name        text NOT NULL REFERENCES csp_profile(name) ON DELETE CASCADE,
    service_id          integer NOT NULL,
    service_name        text,
    service_type        integer NOT NULL DEFAULT 0,
    provider_ident      integer,
    ecm_length          integer,
    custom_data         text,
    version             bigint NOT NULL DEFAULT 1,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          text
);
-- Expression-based UNIQUE index (COALESCE non autorise dans contrainte de table)
CREATE UNIQUE INDEX IF NOT EXISTS uq_service_map_key
    ON csp_service_map (profile_name, service_id, COALESCE(provider_ident, -1), COALESCE(ecm_length, -1));

-- =========================================================================
-- 7. CONNECTORS (upstream card servers)
-- =========================================================================
CREATE TABLE csp_connector (
    name                    text PRIMARY KEY,
    profile_name            text NOT NULL REFERENCES csp_profile(name) ON DELETE CASCADE,
    protocol                text NOT NULL,   -- newcamd, radegast, csp, chameleon, conax
    enabled                 boolean NOT NULL DEFAULT true,
    debug                   boolean NOT NULL DEFAULT false,
    -- Connection
    host                    text NOT NULL DEFAULT '127.0.0.1',
    port                    integer NOT NULL CHECK (port BETWEEN 1 AND 65535),
    -- Auth / credentials (references, never raw secrets)
    username                text,
    password_ref            text,           -- ref env/file
    des_key_ref             text,           -- ref env/file (14 bytes hex -> 3DES)
    -- Protocol-specific optional settings
    connect_timeout_ms      integer NOT NULL DEFAULT 5000,
    reconnect_delay_ms      integer NOT NULL DEFAULT 10000,
    keep_alive_sec          integer,
    min_delay_ms            integer,
    max_queue               integer,
    -- Newcamd specific
    newcamd_max_msg_size    integer,
    newcamd_client_id_ref   text,           -- ref env/file
    -- Radegast specific
    radegast_response_timeout_ms integer,
    -- CCcam / Cacheex
    cccam_enabled           boolean NOT NULL DEFAULT false,
    cccam_node_id           text,
    cccam_version           text,
    cacheex_mode            smallint CHECK (cacheex_mode IS NULL OR cacheex_mode IN (0, 2, 3)),
    cacheex_filter          text,           -- caid:provid pairs
    cacheex_max_send        integer,
    -- Chameleon (multi-profile)
    chameleon_profiles      text,           -- csv de noms de profils
    -- Conax / PCSC
    conax_reader_index      integer,
    -- Rating / load balancing
    priority                integer NOT NULL DEFAULT 50,
    max_capacity            integer NOT NULL DEFAULT 0,
    -- Audit
    version                 bigint NOT NULL DEFAULT 1,
    updated_at              timestamptz NOT NULL DEFAULT now(),
    updated_by              text
);

-- Connector services (explicitly mapped services per connector)
CREATE TABLE csp_connector_service (
    id              bigserial PRIMARY KEY,
    connector_name  text NOT NULL REFERENCES csp_connector(name) ON DELETE CASCADE,
    service_id      integer NOT NULL,
    service_name    text,
    version         bigint NOT NULL DEFAULT 1,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text,
    UNIQUE (connector_name, service_id)
);

-- =========================================================================
-- 8. CACHE CONFIG
-- =========================================================================
CREATE TABLE csp_cache_config (
    id                  smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    cw_max_age_ms       bigint NOT NULL DEFAULT 30000,
    maximum_size        bigint NOT NULL DEFAULT 100000,
    max_cache_wait_ms   bigint NOT NULL DEFAULT 500,
    max_cache_wait_percent integer NOT NULL DEFAULT -1,
    version             bigint NOT NULL DEFAULT 1,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          text
);

-- =========================================================================
-- 9. CLUSTER UDP CONFIG
-- =========================================================================
CREATE TABLE csp_cluster_udp_config (
    id                  smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled             boolean NOT NULL DEFAULT true,
    bind_port           integer NOT NULL DEFAULT 8099 CHECK (bind_port BETWEEN 1 AND 65535),
    bind_address        text NOT NULL DEFAULT '0.0.0.0',
    -- Peers statiques
    remote_hosts        text,               -- csv host:port pairs
    -- Multicast
    multicast_group     text,
    multicast_port      integer DEFAULT 8099,
    multicast_ttl       integer NOT NULL DEFAULT 1,
    -- Discovery
    auto_add_peers      boolean NOT NULL DEFAULT true,
    tracker_url_ref     text,               -- ref env (URL + blowfish key)
    -- Timing
    ping_interval_ms    bigint NOT NULL DEFAULT 4000,
    resend_throttle_ms  bigint NOT NULL DEFAULT 50,
    -- Security
    max_packets_per_sec integer NOT NULL DEFAULT 50,
    blacklist           text,               -- csv IPs
    sync_period_ms      bigint NOT NULL DEFAULT 100,
    version             bigint NOT NULL DEFAULT 1,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          text
);

-- =========================================================================
-- 10. WEB UI CONFIG
-- =========================================================================
CREATE TABLE csp_webui_config (
    id                  smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled             boolean NOT NULL DEFAULT true,
    listen_port         integer NOT NULL DEFAULT 8080 CHECK (listen_port BETWEEN 1 AND 65535),
    bind_address        text NOT NULL DEFAULT '0.0.0.0',
    -- TLS
    tls_enabled         boolean NOT NULL DEFAULT false,
    tls_cert_ref        text,               -- ref env/file
    tls_key_ref         text,               -- ref env/file
    -- Admin auth
    admin_user_ref      text,               -- ref env:WEBUI_ADMIN_USER
    admin_password_ref  text,               -- ref env:WEBUI_ADMIN_PASSWORD
    super_users         text,               -- csv noms utilisateurs
    -- IP filtering
    allow_list          text,
    -- Options
    welcome_file        text NOT NULL DEFAULT 'web/cs-status.html',
    session_timeout_min integer NOT NULL DEFAULT 30,
    version             bigint NOT NULL DEFAULT 1,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          text
);

-- =========================================================================
-- 11. PLUGINS CONFIG
-- =========================================================================
CREATE TABLE csp_plugin (
    name            text PRIMARY KEY,
    class_name      text NOT NULL,
    enabled         boolean NOT NULL DEFAULT true,
    description     text,
    config_json     jsonb,                  -- plugin-specific config (flexible)
    profiles        text,                   -- csv (empty = all)
    version         bigint NOT NULL DEFAULT 1,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text
);

-- =========================================================================
-- 12. AUDIT LOG
-- =========================================================================
CREATE TABLE csp_audit_log (
    id              bigserial PRIMARY KEY,
    event_type      text NOT NULL,          -- CONFIG_UPDATED, CONFIG_RELOADED, CONFIG_RELOAD_FAILED, ...
    entity_type     text,                   -- table name (csp_profile, csp_connector, ...)
    entity_name     text,                   -- primary key or name
    old_version     bigint,
    new_version     bigint,
    changed_by      text,
    summary         text,
    details_json    jsonb,
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- =========================================================================
-- 13. USERS (preparation L5b — UserDirectory / ACL)
-- =========================================================================
CREATE TABLE csp_user (
    name                text PRIMARY KEY,
    display_name        text,
    password_hash       text NOT NULL
        CHECK (password_hash <> '' AND (
            password_hash LIKE '$1$%' OR
            password_hash LIKE '$2a$%' OR
            password_hash LIKE '$2b$%' OR
            password_hash LIKE '$2y$%' OR
            password_hash LIKE '$argon2id$%'
        )),
    email               text,
    enabled             boolean NOT NULL DEFAULT true,
    admin               boolean NOT NULL DEFAULT false,
    debug               boolean NOT NULL DEFAULT false,
    map_excluded        boolean NOT NULL DEFAULT false,
    max_connections     integer NOT NULL DEFAULT 1,
    ip_mask             text,
    allowed_ecm_rate    integer NOT NULL DEFAULT -1,
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE csp_user_profile (
    user_name   text NOT NULL REFERENCES csp_user(name) ON DELETE CASCADE,
    profile     text NOT NULL,
    PRIMARY KEY (user_name, profile)
);

CREATE TABLE csp_user_service (
    user_name   text NOT NULL REFERENCES csp_user(name) ON DELETE CASCADE,
    profile     text NOT NULL,
    sid         integer NOT NULL,
    mode        smallint NOT NULL DEFAULT 0, -- 0=allow, 1=block
    PRIMARY KEY (user_name, profile, sid, mode)
);

CREATE TABLE csp_user_connector (
    user_name   text NOT NULL REFERENCES csp_user(name) ON DELETE CASCADE,
    connector   text NOT NULL,
    PRIMARY KEY (user_name, connector)
);

-- =========================================================================
-- 14. META — SID CACHE LINKER
-- =========================================================================
CREATE TABLE csp_sid_cache_link (
    id              bigserial PRIMARY KEY,
    profile_name    text NOT NULL REFERENCES csp_profile(name) ON DELETE CASCADE,
    sid_a           integer NOT NULL,
    sid_b           integer NOT NULL,
    UNIQUE (profile_name, sid_a, sid_b)
);

-- =========================================================================
-- 15. TRIGGERS : NOTIFY sur changement config / user
-- =========================================================================
-- Fonction generique pour toutes les tables de config
CREATE OR REPLACE FUNCTION csp_config_notify() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('csp_config_changed', '');
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Fonction specifique pour les changements utilisateur (cache L1 invalidation)
CREATE OR REPLACE FUNCTION csp_user_notify() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('csp_user_changed', '');
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Appliquer le trigger config sur chaque table de configuration
DO $$
DECLARE
    t text;
BEGIN
    FOR t IN
        SELECT unnest(ARRAY[
            'csp_root_config',
            'csp_logging_config',
            'csp_ca_profiles_global',
            'csp_profile',
            'csp_profile_listener',
            'csp_profile_card',
            'csp_connection_manager',
            'csp_service_map',
            'csp_connector',
            'csp_connector_service',
            'csp_cache_config',
            'csp_cluster_udp_config',
            'csp_webui_config',
            'csp_plugin',
            'csp_sid_cache_link'
        ])
    LOOP
        EXECUTE format(
            'CREATE TRIGGER csp_config_changed AFTER INSERT OR UPDATE OR DELETE ON %I
             FOR EACH STATEMENT EXECUTE FUNCTION csp_config_notify()', t);
    END LOOP;
END;
$$;

-- Trigger user separe (pas de payload sensible)
CREATE TRIGGER csp_user_changed AFTER INSERT OR UPDATE OR DELETE ON csp_user
    FOR EACH STATEMENT EXECUTE FUNCTION csp_user_notify();

-- =========================================================================
-- 16. SEED DEFAULTS (coherents avec CacheVerticleConfig.defaults()
--     et UdpClusterConfig.defaults() — aucun secret reel)
-- =========================================================================
INSERT INTO csp_root_config (id, proxy_name) VALUES (1, 'CSP-NG')
    ON CONFLICT (id) DO NOTHING;

INSERT INTO csp_logging_config (id) VALUES (1)
    ON CONFLICT (id) DO NOTHING;

INSERT INTO csp_ca_profiles_global (id) VALUES (1)
    ON CONFLICT (id) DO NOTHING;

INSERT INTO csp_connection_manager (id) VALUES (1)
    ON CONFLICT (id) DO NOTHING;

INSERT INTO csp_cache_config (id) VALUES (1)
    ON CONFLICT (id) DO NOTHING;

INSERT INTO csp_cluster_udp_config (id) VALUES (1)
    ON CONFLICT (id) DO NOTHING;

INSERT INTO csp_webui_config (id) VALUES (1)
    ON CONFLICT (id) DO NOTHING;

-- Profile "default" minimal (pour compatibilite legacy)
INSERT INTO csp_profile (name, ca_id, network_id) VALUES ('default', 0, 0)
    ON CONFLICT (name) DO NOTHING;

-- Profile listener newcamd par defaut (ref canonique sans syntaxe Flyway)
INSERT INTO csp_profile_listener (profile_name, protocol, port, des_key_ref)
    VALUES ('default', 'newcamd', 15000, 'env:CSP_PROFILE_DES_KEY')
    ON CONFLICT (profile_name, protocol, port) DO NOTHING;

-- =========================================================================
-- 17. INDEXES (performance)
-- =========================================================================
CREATE INDEX IF NOT EXISTS idx_connector_profile ON csp_connector(profile_name);
CREATE INDEX IF NOT EXISTS idx_service_map_profile ON csp_service_map(profile_name);
CREATE INDEX IF NOT EXISTS idx_plugin_enabled ON csp_plugin(enabled);
CREATE INDEX IF NOT EXISTS idx_audit_event_type ON csp_audit_log(event_type);
CREATE INDEX IF NOT EXISTS idx_audit_created_at ON csp_audit_log(created_at);
CREATE INDEX IF NOT EXISTS idx_user_enabled ON csp_user(enabled);
CREATE INDEX IF NOT EXISTS idx_user_profile_user ON csp_user_profile(user_name);
CREATE INDEX IF NOT EXISTS idx_user_service_user ON csp_user_service(user_name);
CREATE INDEX IF NOT EXISTS idx_user_connector_user ON csp_user_connector(user_name);
CREATE INDEX IF NOT EXISTS idx_sid_cache_link_profile ON csp_sid_cache_link(profile_name);
