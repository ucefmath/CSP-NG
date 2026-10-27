-- =========================================================================
-- CSP-NG DCW Quality & Policy Schema (V13)
-- Règles manuelles persistées, digest SHA-256, ChecksumMode scopé, DcwRepairMode, ServiceMapping et politique immuable.
-- STRICTEMENT ADDITIF.
-- =========================================================================

-- 1. Politique globale de qualité DCW (ligne unique id=1)
CREATE TABLE IF NOT EXISTS csp_dcw_policy (
    id                          integer PRIMARY KEY CHECK (id = 1),
    enabled                     boolean NOT NULL DEFAULT true,
    adaptive_routing_enabled    boolean NOT NULL DEFAULT false,
    auto_observe_enabled        boolean NOT NULL DEFAULT false,
    zero_counting_enabled       boolean NOT NULL DEFAULT false,
    -- 'inherit' = garde historique de chaque appelant (Strict permissif ; manager/UDP/CSPv2 checksum exige)
    default_checksum_mode       text NOT NULL DEFAULT 'inherit' CHECK (default_checksum_mode IN ('require_valid', 'ignore', 'reject_valid', 'inherit')),
    repair_mode                 text NOT NULL DEFAULT 'off' CHECK (repair_mode IN ('off', 'observe', 'enforce')),
    allow_zero_half             boolean NOT NULL DEFAULT true,
    version                     bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    updated_by                  text
);

-- Insertion de la politique par défaut si absente
INSERT INTO csp_dcw_policy (
    id, enabled, adaptive_routing_enabled, auto_observe_enabled, default_checksum_mode, repair_mode, allow_zero_half, version, updated_at
) VALUES (
    1, true, false, false, 'inherit', 'off', true, 1, now()
) ON CONFLICT (id) DO NOTHING;

-- 2. Règles DCW manuelles (blacklist SHA-256, ChecksumMode scopé, observe, no_action)
CREATE TABLE IF NOT EXISTS csp_dcw_rule (
    id              bigserial PRIMARY KEY,
    profile         text REFERENCES csp_profile(name) ON UPDATE CASCADE ON DELETE RESTRICT CHECK (profile IS NULL OR (char_length(profile) BETWEEN 1 AND 64)),
    caid            integer NOT NULL DEFAULT 0 CHECK (caid BETWEEN 0 AND 65535),
    -- NULL = wildcard ; 0..16777215 = provider exact (0 legitime). -1 (inconnu) est un etat runtime, jamais persiste.
    provider        integer CHECK (provider IS NULL OR provider BETWEEN 0 AND 16777215),
    sid             integer NOT NULL DEFAULT 0 CHECK (sid BETWEEN 0 AND 65535),
    source_id       text CHECK (source_id IS NULL OR (char_length(source_id) BETWEEN 1 AND 64)),
    digest_sha256   text CHECK (digest_sha256 IS NULL OR (char_length(digest_sha256) = 64 AND digest_sha256 ~ '^[0-9a-fA-F]{64}$')),
    action          text NOT NULL DEFAULT 'none' CHECK (action IN ('block', 'observe', 'none')),
    checksum_mode   text NOT NULL DEFAULT 'inherit' CHECK (checksum_mode IN ('require_valid', 'ignore', 'reject_valid', 'inherit')),
    notes           text NOT NULL DEFAULT '' CHECK (char_length(notes) <= 255),
    enabled         boolean NOT NULL DEFAULT true,
    version         bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text,
    -- BLOCK/OBSERVE exigent un digest ; none = regle declarative checksumMode sans digest
    CONSTRAINT ck_dcw_rule_action_digest CHECK (
        (action IN ('block', 'observe') AND digest_sha256 IS NOT NULL)
        OR (action = 'none' AND digest_sha256 IS NULL AND checksum_mode <> 'inherit'))
);

-- 3. Mappings de service validés (même provider/profil) pour corroboration de réparation
CREATE TABLE IF NOT EXISTS csp_dcw_service_mapping (
    id              bigserial PRIMARY KEY,
    profile         text NOT NULL REFERENCES csp_profile(name) ON UPDATE CASCADE ON DELETE RESTRICT CHECK (char_length(profile) BETWEEN 1 AND 64),
    caid            integer NOT NULL CHECK (caid BETWEEN 1 AND 65535),
    provider        integer NOT NULL CHECK (provider BETWEEN 0 AND 16777215),
    source_sid      integer NOT NULL CHECK (source_sid BETWEEN 1 AND 65535),
    target_sid      integer NOT NULL CHECK (target_sid BETWEEN 1 AND 65535),
    approved        boolean NOT NULL DEFAULT false,
    enabled         boolean NOT NULL DEFAULT true,
    expires_at      timestamptz,
    version         bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text,
    CONSTRAINT ck_dcw_mapping_distinct_sid CHECK (source_sid <> target_sid),
    CONSTRAINT uq_dcw_service_mapping UNIQUE (profile, caid, provider, source_sid, target_sid)
);

-- 4. Triggers NOTIFY config (payload vide, comme V10/V11/V12)
DO $$
DECLARE
    t text;
BEGIN
    FOR t IN SELECT unnest(ARRAY['csp_dcw_policy', 'csp_dcw_rule', 'csp_dcw_service_mapping'])
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'csp_config_changed' AND tgrelid = t::regclass) THEN
            EXECUTE format(
                'CREATE TRIGGER csp_config_changed AFTER INSERT OR UPDATE OR DELETE ON %I
                 FOR EACH STATEMENT EXECUTE FUNCTION csp_config_notify()', t);
        END IF;
    END LOOP;
END;
$$;

CREATE INDEX IF NOT EXISTS idx_dcw_rule_digest ON csp_dcw_rule(digest_sha256);
CREATE INDEX IF NOT EXISTS idx_dcw_rule_scope ON csp_dcw_rule(profile, caid, provider, sid);
CREATE INDEX IF NOT EXISTS idx_dcw_service_map_lookup ON csp_dcw_service_mapping(profile, caid, provider, source_sid, target_sid);
