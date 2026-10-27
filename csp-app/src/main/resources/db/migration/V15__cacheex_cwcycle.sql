-- =========================================================================
-- CSP-NG V15 : CacheEx runtime + CW-Cycle check.
-- STRICTEMENT ADDITIF. Contrats docs/api-contracts-ui-refonte.md (v2).
--   1. csp_connector.cacheex_max_hop (explicite, fin du hardcodage défaut 10)
--   2. Politique cacheex par user protocole entrant (csp_user_protocol_secret)
--   3. Singleton csp_cw_cycle_config (vrai contrôle de cycle CW)
-- =========================================================================

-- 1. Hop max cacheex par connecteur (0..10, NULL = défaut runtime 10)
ALTER TABLE csp_connector
    ADD COLUMN IF NOT EXISTS cacheex_max_hop smallint
        CHECK (cacheex_max_hop IS NULL OR (cacheex_max_hop >= 0 AND cacheex_max_hop <= 10));

-- 2. Politique cacheex par (user, protocole) entrant.
--    Sémantique (vérifiée module-cacheex.c l.584-604/625-672/722-748) :
--      mode 2 = NOUS émettons vers le client (il reçoit) ; mode 3 = NOUS recevons (il émet).
--    NULL mode = cacheex désactivé pour ce compte.
ALTER TABLE csp_user_protocol_secret
    ADD COLUMN IF NOT EXISTS cacheex_mode smallint
        CHECK (cacheex_mode IS NULL OR cacheex_mode IN (2, 3)),
    ADD COLUMN IF NOT EXISTS cacheex_max_hop integer
        CHECK (cacheex_max_hop IS NULL OR (cacheex_max_hop >= 0 AND cacheex_max_hop <= 10)),
    ADD COLUMN IF NOT EXISTS cacheex_filter text;

-- 3. Singleton contrôle de cycle CW (miroir comportemental OSCam CW_CYCLE_CHECK, noyau)
CREATE TABLE IF NOT EXISTS csp_cw_cycle_config (
    id                       smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled                  boolean NOT NULL DEFAULT false,
    -- CAIDs concernés, CSV trié ("256,1280") ; vide = toutes
    caids                    text NOT NULL DEFAULT '',
    max_list                 integer NOT NULL DEFAULT 500
        CHECK (max_list BETWEEN 16 AND 4000),
    keep_time_sec            integer NOT NULL DEFAULT 15
        CHECK (keep_time_sec BETWEEN 5 AND 240),
    -- 'drop' = CW rejetée (onbadcycle=1) ; 'log' = compter/journaliser seulement
    on_bad                   text NOT NULL DEFAULT 'drop' CHECK (on_bad IN ('log', 'drop')),
    drop_old                 boolean NOT NULL DEFAULT true,
    -- Exploite le cycletime/flag next-cycle transporté par les push cacheex (CCcam/CS378x)
    use_peer_cycle_metadata  boolean NOT NULL DEFAULT false,
    version                  bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    updated_by               text
);

INSERT INTO csp_cw_cycle_config (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

-- 4. Triggers NOTIFY config (payload vide, pattern V10/V11/V12)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'csp_config_changed'
                   AND tgrelid = 'csp_cw_cycle_config'::regclass) THEN
        EXECUTE format(
            'CREATE TRIGGER csp_config_changed AFTER INSERT OR UPDATE OR DELETE ON csp_cw_cycle_config
             FOR EACH STATEMENT EXECUTE FUNCTION csp_config_notify()');
    END IF;
END;
$$;
