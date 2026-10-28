-- =========================================================================
-- CSP-NG V17 : Mappings CAID / Provider & Service Mapping étendus
-- Support des mappings de couples (target_caid, target_provider) et SID wildcard (0).
-- =========================================================================

-- 1. Nettoyage des anciennes contraintes SID sur csp_dcw_service_mapping
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'csp_dcw_service_mapping'::regclass
          AND contype IN ('c', 'u')
          AND (
              conname IN ('ck_dcw_mapping_distinct_sid', 'uq_dcw_service_mapping')
              OR pg_get_constraintdef(oid) LIKE '%source_sid%'
              OR pg_get_constraintdef(oid) LIKE '%target_sid%'
          )
    LOOP
        EXECUTE 'ALTER TABLE csp_dcw_service_mapping DROP CONSTRAINT ' || quote_ident(r.conname);
    END LOOP;
END;
$$;

-- 2. Ajout des colonnes target_caid et target_provider avec backfill et NOT NULL
ALTER TABLE csp_dcw_service_mapping ADD COLUMN IF NOT EXISTS target_caid integer, ADD COLUMN IF NOT EXISTS target_provider integer;
UPDATE csp_dcw_service_mapping SET target_caid = caid WHERE target_caid IS NULL;
UPDATE csp_dcw_service_mapping SET target_provider = provider WHERE target_provider IS NULL;
ALTER TABLE csp_dcw_service_mapping ALTER COLUMN target_caid SET NOT NULL, ALTER COLUMN target_provider SET NOT NULL;

-- 3. Rétrocompatibilité : trigger par défaut si target_caid ou target_provider non fournis
CREATE OR REPLACE FUNCTION csp_dcw_service_mapping_defaults()
RETURNS trigger AS $$
BEGIN
    IF NEW.target_caid IS NULL THEN
        NEW.target_caid := NEW.caid;
    END IF;
    IF NEW.target_provider IS NULL THEN
        NEW.target_provider := NEW.provider;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_dcw_service_mapping_defaults ON csp_dcw_service_mapping;
CREATE TRIGGER trg_dcw_service_mapping_defaults
    BEFORE INSERT OR UPDATE ON csp_dcw_service_mapping
    FOR EACH ROW
    EXECUTE FUNCTION csp_dcw_service_mapping_defaults();

-- 4. Ajout des nouvelles contraintes
ALTER TABLE csp_dcw_service_mapping
    ADD CONSTRAINT ck_dcw_mapping_target_caid CHECK (target_caid BETWEEN 1 AND 65535),
    ADD CONSTRAINT ck_dcw_mapping_target_provider CHECK (target_provider BETWEEN 0 AND 16777215),
    ADD CONSTRAINT ck_dcw_mapping_source_sid CHECK (source_sid BETWEEN 0 AND 65535),
    ADD CONSTRAINT ck_dcw_mapping_target_sid CHECK (target_sid BETWEEN 0 AND 65535),
    ADD CONSTRAINT ck_dcw_mapping_sid_pair CHECK ((source_sid = 0 AND target_sid = 0) OR (source_sid BETWEEN 1 AND 65535 AND target_sid BETWEEN 1 AND 65535)),
    ADD CONSTRAINT ck_dcw_mapping_not_identity CHECK (NOT (caid = target_caid AND provider = target_provider AND source_sid = target_sid)),
    ADD CONSTRAINT uq_dcw_service_mapping UNIQUE (profile, caid, provider, target_caid, target_provider, source_sid, target_sid);

-- 5. Recréation de l'index de lookup
DROP INDEX IF EXISTS idx_dcw_service_map_lookup;
CREATE INDEX idx_dcw_service_map_lookup ON csp_dcw_service_mapping(profile, caid, provider, target_caid, target_provider, source_sid, target_sid);
