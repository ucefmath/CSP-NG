-- =========================================================================
-- CSP-NG P4 étape P6 : Catalogue de cartes persistantes (V8)
-- =========================================================================
-- Contrat (docs/INBOUND-CAMS-PLAN.md §P6) :
--  * csp_card_capability : unicité (profile, caid, provider) ;
--    state IN ('discovered', 'published', 'blocked') ;
--    card_id uint32 unique PERSISTENT (1..4294967295) alloué UNE seule fois
--    depuis une séquence NO CYCLE, stable aux redémarrages.
--  * csp_card_source : relation n:n capability + connector + upstream card identity
--    (plusieurs cartes d'un même connecteur pour une même cap coexistent) ;
--    trust IN ('discovered', 'declared_unverified', 'manual') ;
--    hop 1..15, reshare 0..15.
--  * Triggers sur csp_auth_epoch pour notifier/invalider le routage et le cache.
-- =========================================================================

CREATE SEQUENCE IF NOT EXISTS csp_card_id_seq
    AS bigint
    START WITH 1
    MINVALUE 1
    MAXVALUE 4294967295
    NO CYCLE;

CREATE TABLE IF NOT EXISTS csp_card_capability (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    profile         text NOT NULL,
    caid            integer NOT NULL CHECK (caid >= 1 AND caid <= 65535),
    provider        integer NOT NULL CHECK (provider >= 0 AND provider <= 16777215),
    state           text NOT NULL CHECK (state IN ('discovered', 'published', 'blocked')) DEFAULT 'discovered',
    card_id         bigint NOT NULL UNIQUE CHECK (card_id >= 1 AND card_id <= 4294967295),
    version         bigint NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (profile, caid, provider)
);

CREATE TABLE IF NOT EXISTS csp_card_source (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    capability_id   bigint NOT NULL REFERENCES csp_card_capability(id) ON DELETE CASCADE,
    connector_name  text NOT NULL,
    origin_card_id  bigint,
    trust           text NOT NULL CHECK (trust IN ('discovered', 'declared_unverified', 'manual')) DEFAULT 'discovered',
    hop             integer NOT NULL DEFAULT 1 CHECK (hop >= 1 AND hop <= 15),
    reshare         integer NOT NULL DEFAULT 0 CHECK (reshare >= 0 AND reshare <= 15),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (capability_id, connector_name, origin_card_id)
);

CREATE INDEX IF NOT EXISTS idx_card_source_connector
    ON csp_card_source (connector_name);

CREATE INDEX IF NOT EXISTS idx_card_cap_profile_caid_prov
    ON csp_card_capability (profile, caid, provider);

DROP TRIGGER IF EXISTS csp_card_capability_epoch ON csp_card_capability;
CREATE TRIGGER csp_card_capability_epoch
    AFTER INSERT OR UPDATE OR DELETE ON csp_card_capability
    FOR EACH STATEMENT EXECUTE FUNCTION csp_auth_epoch_bump();

-- NOTE: Pas de trigger d'auth epoch sur csp_card_source pour éviter les NOTIFY storms
-- lors de la découverte dynamique des métadonnées de cartes (hops, reshares, origin IDs).
-- Les retraits et expirations runtime sont gérés immédiatement en mémoire par le routeur/session.
