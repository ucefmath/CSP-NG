-- =========================================================================
-- CSP-NG Relay workers v1 (ADR 0002, socle central W0-W3)
-- =========================================================================
-- MIGRATION STRICTEMENT ADDITIVE : CREATE TABLE IF NOT EXISTS, ADD COLUMN
-- IF NOT EXISTS, triggers statement-level payload vide, aucun seed actif,
-- aucun ALTER destructif. Workers SANS acces PG (canal HTTPS W4).
-- Caches distants DEBRANCHES par defaut, aucun pair inconnu auto-active.
-- =========================================================================

-- 1. Nodes relais (identite = SAN mTLS urn:csp:node:<node_name>, jamais id seul)
CREATE TABLE IF NOT EXISTS csp_relay_node (
    node_name       text PRIMARY KEY CHECK (node_name ~ '^[a-z0-9][a-z0-9._-]{0,62}$'),
    display_name    text,
    enabled         boolean NOT NULL DEFAULT false,
    authorized      boolean NOT NULL DEFAULT false,
    revoked         boolean NOT NULL DEFAULT false,
    desired_version bigint NOT NULL DEFAULT 1 CHECK (desired_version >= 1),
    applied_version bigint CHECK (applied_version IS NULL OR applied_version >= 1),
    applied_at      timestamptz,
    config_expires_at timestamptz,
    last_seen_at    timestamptz,
    version         bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text,
    CONSTRAINT chk_relay_node_applied CHECK (
        applied_version IS NULL OR applied_version <= desired_version
    )
);

-- 2. Caches (kind explicite : local | node (CSPv2) | udp (export legacy seul))
CREATE TABLE IF NOT EXISTS csp_relay_cache (
    name            text PRIMARY KEY CHECK (name ~ '^[A-Za-z0-9_.-]{1,64}$'),
    kind            text NOT NULL CHECK (kind IN ('local', 'node', 'udp')),
    node_name       text REFERENCES csp_relay_node(node_name) ON DELETE SET NULL,
    enabled         boolean NOT NULL DEFAULT false,
    max_age_ms      bigint NOT NULL DEFAULT 8000 CHECK (max_age_ms BETWEEN 500 AND 60000),
    max_entries     integer NOT NULL DEFAULT 10000 CHECK (max_entries BETWEEN 1 AND 1000000),
    max_inflight    integer NOT NULL DEFAULT 512 CHECK (max_inflight BETWEEN 1 AND 8192),
    description     text,
    version         bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text,
    CONSTRAINT chk_relay_cache_node CHECK (
        (kind = 'node' AND node_name IS NOT NULL)
        OR (kind IN ('local', 'udp'))
    )
);

-- 3. Matrice listener x cache (detachee par defaut)
CREATE TABLE IF NOT EXISTS csp_listener_cache_link (
    listener_id     bigint NOT NULL REFERENCES csp_profile_listener(id) ON DELETE CASCADE,
    cache_name      text NOT NULL REFERENCES csp_relay_cache(name) ON DELETE CASCADE,
    can_consult     boolean NOT NULL DEFAULT true,
    can_feed        boolean NOT NULL DEFAULT false,
    can_export      boolean NOT NULL DEFAULT false,
    enabled         boolean NOT NULL DEFAULT false,
    version         bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text,
    PRIMARY KEY (listener_id, cache_name),
    CONSTRAINT chk_link_role CHECK (can_consult OR can_feed OR can_export)
);

-- 4. Triggers NOTIFY config (payload vide, comme V1)
DO $$
DECLARE
    t text;
BEGIN
    FOR t IN SELECT unnest(ARRAY['csp_relay_node', 'csp_relay_cache', 'csp_listener_cache_link'])
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'csp_config_changed' AND tgrelid = t::regclass) THEN
            EXECUTE format(
                'CREATE TRIGGER csp_config_changed AFTER INSERT OR UPDATE OR DELETE ON %I
                 FOR EACH STATEMENT EXECUTE FUNCTION csp_config_notify()', t);
        END IF;
    END LOOP;
END;
$$;

-- 5. Index
CREATE INDEX IF NOT EXISTS idx_relay_cache_node ON csp_relay_cache(node_name);
CREATE INDEX IF NOT EXISTS idx_relay_cache_kind ON csp_relay_cache(kind);
CREATE INDEX IF NOT EXISTS idx_link_listener ON csp_listener_cache_link(listener_id);
CREATE INDEX IF NOT EXISTS idx_link_cache ON csp_listener_cache_link(cache_name);
CREATE INDEX IF NOT EXISTS idx_relay_node_enabled ON csp_relay_node(enabled) WHERE enabled;
