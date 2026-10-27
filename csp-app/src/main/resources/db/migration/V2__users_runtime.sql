-- =========================================================================
-- CSP-NG L5b : evolution users runtime — version/validity + triggers ACL
-- =========================================================================
-- Ajoute version/updated_by/validity a csp_user, index lower(name),
-- et triggers statement-level sur les 4 tables users pour NOTIFY opaque.
-- =========================================================================

-- 1. Ajouter colonnes manquantes à csp_user si absentes
ALTER TABLE csp_user ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 1;
ALTER TABLE csp_user ADD COLUMN IF NOT EXISTS updated_by text;
ALTER TABLE csp_user ADD COLUMN IF NOT EXISTS valid_from timestamptz;
ALTER TABLE csp_user ADD COLUMN IF NOT EXISTS valid_until timestamptz;

-- 2. Index unique case-insensitive
CREATE UNIQUE INDEX IF NOT EXISTS uq_csp_user_lower_name ON csp_user (lower(name));

-- 4. Triggers sur les 4 tables users : NOTIFY csp_user_changed payload vide
-- V1 avait déjà csp_user_changed sur csp_user ; on le remplace proprement pour garantir statement-level
DROP TRIGGER IF EXISTS csp_user_changed ON csp_user;
CREATE TRIGGER csp_user_changed AFTER INSERT OR UPDATE OR DELETE ON csp_user
    FOR EACH STATEMENT EXECUTE FUNCTION csp_user_notify();

DROP TRIGGER IF EXISTS csp_user_profile_changed ON csp_user_profile;
CREATE TRIGGER csp_user_profile_changed AFTER INSERT OR UPDATE OR DELETE ON csp_user_profile
    FOR EACH STATEMENT EXECUTE FUNCTION csp_user_notify();

DROP TRIGGER IF EXISTS csp_user_service_changed ON csp_user_service;
CREATE TRIGGER csp_user_service_changed AFTER INSERT OR UPDATE OR DELETE ON csp_user_service
    FOR EACH STATEMENT EXECUTE FUNCTION csp_user_notify();

DROP TRIGGER IF EXISTS csp_user_connector_changed ON csp_user_connector;
CREATE TRIGGER csp_user_connector_changed AFTER INSERT OR UPDATE OR DELETE ON csp_user_connector
    FOR EACH STATEMENT EXECUTE FUNCTION csp_user_notify();
