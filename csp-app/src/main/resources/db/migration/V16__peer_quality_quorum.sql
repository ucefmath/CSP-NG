-- =========================================================================
-- CSP-NG V16 : qualite pairs (quarantaine) + quorum CacheEx.
-- STRICTEMENT ADDITIF. Quarantaine opt-in (off defaut, observe defaut),
-- quorum off defaut. Seule la CONFIGURATION est persistee ; les etats
-- runtime (fenetres, quarantaines actives, cles quorum) restent memoire.
-- =========================================================================

-- 1. Singleton quarantaine des pairs (opt-in, off par defaut).
CREATE TABLE IF NOT EXISTS csp_peer_quarantine_config (
    id                       smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled                  boolean NOT NULL DEFAULT false,
    -- 'observe' = comptabilise sans exclure ; 'enforce' = exclusion temporaire du routage
    mode                     text NOT NULL DEFAULT 'observe' CHECK (mode IN ('observe', 'enforce')),
    -- echantillons eligibles minimum (accepted+invalid) avant toute decision
    min_samples              integer NOT NULL DEFAULT 50 CHECK (min_samples BETWEEN 2 AND 10000),
    -- ratio invalide en points de base (0..10000)
    invalid_ratio_bps        integer NOT NULL DEFAULT 5000 CHECK (invalid_ratio_bps BETWEEN 0 AND 10000),
    window_ms                bigint NOT NULL DEFAULT 300000 CHECK (window_ms BETWEEN 5000 AND 3600000),
    duration_ms              bigint NOT NULL DEFAULT 600000 CHECK (duration_ms BETWEEN 10000 AND 86400000),
    max_peers                integer NOT NULL DEFAULT 256 CHECK (max_peers BETWEEN 16 AND 1024),
    version                  bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    updated_by               text
);

INSERT INTO csp_peer_quarantine_config (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

-- 2. Singleton quorum CacheEx cible (off par defaut).
CREATE TABLE IF NOT EXISTS csp_cacheex_quorum_config (
    id                       smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    -- 'off' = bypass ; 'observe' = injecte + comptabilise ; 'enforce' = n'injecte que sur quorum
    mode                     text NOT NULL DEFAULT 'off' CHECK (mode IN ('off', 'observe', 'enforce')),
    min_origins              integer NOT NULL DEFAULT 2 CHECK (min_origins BETWEEN 2 AND 8),
    window_ms                bigint NOT NULL DEFAULT 30000 CHECK (window_ms BETWEEN 1000 AND 300000),
    max_keys                 integer NOT NULL DEFAULT 1024 CHECK (max_keys BETWEEN 16 AND 4096),
    -- profils concernes, CSV (vide = tous) ; jamais de secret
    profiles                 text NOT NULL DEFAULT '',
    -- CAIDs concernes, CSV trie ("256,1280") ; vide = tous
    caids                    text NOT NULL DEFAULT '',
    version                  bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    updated_by               text
);

INSERT INTO csp_cacheex_quorum_config (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

-- 3. Triggers NOTIFY config (payload vide, pattern V10/V11/V12/V15)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'csp_config_changed'
                   AND tgrelid = 'csp_peer_quarantine_config'::regclass) THEN
        EXECUTE format(
            'CREATE TRIGGER csp_config_changed AFTER INSERT OR UPDATE OR DELETE ON csp_peer_quarantine_config
             FOR EACH STATEMENT EXECUTE FUNCTION csp_config_notify()');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'csp_config_changed'
                   AND tgrelid = 'csp_cacheex_quorum_config'::regclass) THEN
        EXECUTE format(
            'CREATE TRIGGER csp_config_changed AFTER INSERT OR UPDATE OR DELETE ON csp_cacheex_quorum_config
             FOR EACH STATEMENT EXECUTE FUNCTION csp_config_notify()');
    END IF;
END;
$$;
