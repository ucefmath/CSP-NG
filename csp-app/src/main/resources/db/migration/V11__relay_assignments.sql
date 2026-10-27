-- =========================================================================
-- CSP-NG Relay workers : affectations explicites par worker (Gemini FIX)
-- =========================================================================
-- MIGRATION STRICTEMENT ADDITIVE : CREATE TABLE IF NOT EXISTS, aucun seed,
-- aucun ALTER destructif. Sans affectation, le worker ne recoit ni listener,
-- ni connecteur, ni secret, ni catalogue (fail-closed).
-- desired_version reste genere SERVEUR (bump applicatif) : aucun trigger ne
-- l'incremente (coherence optimistic version, pas de double increment).
-- =========================================================================

CREATE TABLE IF NOT EXISTS csp_relay_node_listener (
    node_name       text NOT NULL REFERENCES csp_relay_node(node_name) ON DELETE CASCADE,
    listener_id     bigint NOT NULL REFERENCES csp_profile_listener(id) ON DELETE CASCADE,
    enabled         boolean NOT NULL DEFAULT true,
    version         bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text,
    PRIMARY KEY (node_name, listener_id)
);

CREATE TABLE IF NOT EXISTS csp_relay_node_connector (
    node_name       text NOT NULL REFERENCES csp_relay_node(node_name) ON DELETE CASCADE,
    connector_name  text NOT NULL REFERENCES csp_connector(name) ON DELETE CASCADE,
    enabled         boolean NOT NULL DEFAULT true,
    version         bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text,
    PRIMARY KEY (node_name, connector_name)
);

DO $$
DECLARE
    t text;
BEGIN
    FOR t IN SELECT unnest(ARRAY['csp_relay_node_listener', 'csp_relay_node_connector'])
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'csp_config_changed' AND tgrelid = t::regclass) THEN
            EXECUTE format(
                'CREATE TRIGGER csp_config_changed AFTER INSERT OR UPDATE OR DELETE ON %I
                 FOR EACH STATEMENT EXECUTE FUNCTION csp_config_notify()', t);
        END IF;
    END LOOP;
END;
$$;

CREATE INDEX IF NOT EXISTS idx_relay_nl_node ON csp_relay_node_listener(node_name);
CREATE INDEX IF NOT EXISTS idx_relay_nl_listener ON csp_relay_node_listener(listener_id);
CREATE INDEX IF NOT EXISTS idx_relay_nc_node ON csp_relay_node_connector(node_name);
CREATE INDEX IF NOT EXISTS idx_relay_nc_connector ON csp_relay_node_connector(connector_name);
