-- =========================================================================
-- CSP-NG P4 étape P3 : AUTH EPOCH monotone transactionnelle (V7)
-- =========================================================================
-- CORRECTION du défaut MAX(version) (retour reviewer passage 4) : MAX(version)
-- n'est PAS une génération fiable — un UPDATE qui pose une version plus basse
-- qu'une autre ligne, ou un DELETE, laisse MAX inchangé => le cache d'auth
-- ne se rechargeait pas => révocation invisible (P4 exige révocation < 2s
-- même NOTIFY manqué / DB intermittente).
--
-- Contrat : l'epoch est un compteur MONOTONE bumpé DANS LA TRANSACTION de
-- chaque écriture de DROITS user (csp_user, csp_user_profile,
-- csp_user_service, csp_user_connector, csp_user_protocol_secret) :
--   * UPDATE csp_auth_epoch SET epoch = epoch + 1 — verrou ligne => chaque
--     txn consomme un epoch unique, ordre total avec le commit ;
--   * le poll (<= 1s) lit l'epoch ; différent => rechargement COMPLET des
--     snapshots ; la lease (<= 2s) garantit fail-closed si la DB est down ;
--   * le NOTIFY csp_user_changed reste un ACCÉLÉRATEUR, jamais la garantie
--     (epoch est l'unique source de vérité de fraîcheur) ;
--   * collision-free : pas de fingerprint de versions, juste le compteur.
-- =========================================================================

CREATE TABLE IF NOT EXISTS csp_auth_epoch (
    id      integer PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    epoch   bigint NOT NULL DEFAULT 0
);

INSERT INTO csp_auth_epoch (id, epoch) VALUES (1, 0) ON CONFLICT (id) DO NOTHING;

CREATE OR REPLACE FUNCTION csp_auth_epoch_bump() RETURNS trigger AS $$
BEGIN
    -- Bump in-transaction : le poll post-commit ne peut PAS manquer l'écriture
    -- (l'epoch lu est >= celui posé par toute txn commitée avant la lecture).
    UPDATE csp_auth_epoch SET epoch = epoch + 1 WHERE id = 1;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS csp_user_epoch ON csp_user;
CREATE TRIGGER csp_user_epoch
    AFTER INSERT OR UPDATE OR DELETE ON csp_user
    FOR EACH STATEMENT EXECUTE FUNCTION csp_auth_epoch_bump();

DROP TRIGGER IF EXISTS csp_user_profile_epoch ON csp_user_profile;
CREATE TRIGGER csp_user_profile_epoch
    AFTER INSERT OR UPDATE OR DELETE ON csp_user_profile
    FOR EACH STATEMENT EXECUTE FUNCTION csp_auth_epoch_bump();

DROP TRIGGER IF EXISTS csp_user_service_epoch ON csp_user_service;
CREATE TRIGGER csp_user_service_epoch
    AFTER INSERT OR UPDATE OR DELETE ON csp_user_service
    FOR EACH STATEMENT EXECUTE FUNCTION csp_auth_epoch_bump();

DROP TRIGGER IF EXISTS csp_user_connector_epoch ON csp_user_connector;
CREATE TRIGGER csp_user_connector_epoch
    AFTER INSERT OR UPDATE OR DELETE ON csp_user_connector
    FOR EACH STATEMENT EXECUTE FUNCTION csp_auth_epoch_bump();

DROP TRIGGER IF EXISTS csp_user_secret_epoch ON csp_user_protocol_secret;
CREATE TRIGGER csp_user_secret_epoch
    AFTER INSERT OR UPDATE OR DELETE ON csp_user_protocol_secret
    FOR EACH STATEMENT EXECUTE FUNCTION csp_auth_epoch_bump();
