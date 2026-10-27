-- =========================================================================
-- CSP-NG Relay : pairs UDP allowlist par cache + stats worker->central +
-- endpoint CSPv2 distant par node. STRICTEMENT ADDITIF.
-- =========================================================================

-- 1. Pairs UDP assignes par cache (allowlist ; jamais decouverts cote worker)
CREATE TABLE IF NOT EXISTS csp_relay_udp_peer (
    id              bigserial PRIMARY KEY,
    cache_name      text NOT NULL REFERENCES csp_relay_cache(name) ON DELETE CASCADE,
    host            text NOT NULL CHECK (char_length(host) BETWEEN 1 AND 253),
    port            integer NOT NULL CHECK (port BETWEEN 1 AND 65535),
    enabled         boolean NOT NULL DEFAULT true,
    version         bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text,
    CONSTRAINT uq_relay_udp_peer UNIQUE (cache_name, host, port)
);

-- 2. Endpoint CSPv2 distant par node (dial sortant ; NULL = accept-only)
ALTER TABLE csp_relay_node
    ADD COLUMN IF NOT EXISTS csp_host text,
    ADD COLUMN IF NOT EXISTS csp_port integer CHECK (csp_port IS NULL OR (csp_port BETWEEN 1 AND 65535));

-- 3. Stats remontees worker->central (payload JSON borne, ecrit par ack)
CREATE TABLE IF NOT EXISTS csp_relay_node_stats (
    node_name       text PRIMARY KEY REFERENCES csp_relay_node(node_name) ON DELETE CASCADE,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    payload         jsonb NOT NULL
);

-- 4. Triggers NOTIFY config (payload vide, comme V10/V11)
DO $$
DECLARE
    t text;
BEGIN
    FOR t IN SELECT unnest(ARRAY['csp_relay_udp_peer'])
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'csp_config_changed' AND tgrelid = t::regclass) THEN
            EXECUTE format(
                'CREATE TRIGGER csp_config_changed AFTER INSERT OR UPDATE OR DELETE ON %I
                 FOR EACH STATEMENT EXECUTE FUNCTION csp_config_notify()', t);
        END IF;
    END LOOP;
END;
$$;

CREATE INDEX IF NOT EXISTS idx_relay_udp_peer_cache ON csp_relay_udp_peer(cache_name);
