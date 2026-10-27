-- =========================================================================
-- CSP-NG P4 étape P3 : secrets protocoles entrants CCcam/CS378x (V6)
-- =========================================================================
-- MIGRATION ADDITIVE. Révision du passage 4 (retour reviewer, documentée dans
-- docs/INBOUND-CAMS-PROGRESS.md) : PAS de colonne protocol_name — le login
-- protocole est EXACTEMENT csp_user.name (usernames P4 exacts, pas d'alias
-- supplémentaire non validé). L'ucrc camd35 = CRC32(MD5(csp_user.name)) est
-- porté ICI (et non sur csp_user) : il est PROTOCOLE-dépendant (cs378x
-- seulement) et dérivé de l'identité canonique csp_user.name — appliqué dans
-- la MÊME transaction que l'upsert du secret, la contrainte UNIQUE garantit
-- l'absence de collision inter-comptes (l'unicité de csp_user.name fait le
-- reste : deux comptes ≠ ne peuvent pas produire le même ucrc, et un même
-- compte ne peut pas l'usurper — txn-lock implicite par la PK user+protocol).
--
-- Contrat (docs/INBOUND-CAMS-PLAN.md §P3) :
--  * bcrypt HTTP non récupérable => les protocoles entrants ont leur PROPRE
--    secret référencé (env:/file: uniquement) — la VALEUR n'est JAMAIS stockée
--    ni retournée par l'API (refs seulement).
--  * PK (user_name, protocol) ; FK csp_user ON DELETE CASCADE.
--  * protocol CHECK IN ('cccam','cs378x') — legacy INTERDIT ici (inchangé).
--  * ucrc : CRC32(MD5(csp_user.name)) uint32 (module-camd35.c l.142/866),
--    NULL pour cccam, UNIQUE — collision = rejet 409 à l'activation,
--    jamais de fusion.
--  * enabled/valid_until du secret : SUPPLÉMENTS protocole, jamais un
--    substitut des flags/dates ACL de csp_user — la snapshot d'auth (P4/P5)
--    combine les DEUX (user éligible ET secret éligible).
--  * NOTIFY csp_user_notify() : accélérateur — la GARANTIE de fraîcheur
--    vient de l'epoch monotone V7 (csp_auth_epoch), poll <= 1s / lease <= 2s.
-- =========================================================================

CREATE TABLE IF NOT EXISTS csp_user_protocol_secret (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_name       text NOT NULL REFERENCES csp_user(name) ON DELETE CASCADE,
    protocol        text NOT NULL CHECK (protocol IN ('cccam', 'cs378x')),
    secret_ref      text NOT NULL
        CHECK (secret_ref LIKE 'env:%' OR secret_ref LIKE 'file:%'),
    secret_version  bigint NOT NULL CHECK (secret_version >= 1) DEFAULT 1,
    ucrc            bigint CHECK (ucrc IS NULL OR (ucrc >= 0 AND ucrc <= 4294967295)),
    enabled         boolean NOT NULL DEFAULT true,
    valid_until     timestamptz,
    version         bigint NOT NULL DEFAULT 1,
    created_by      text,
    updated_by      text,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_name, protocol),
    UNIQUE (ucrc)
);

DROP TRIGGER IF EXISTS csp_user_secret_changed ON csp_user_protocol_secret;
CREATE TRIGGER csp_user_secret_changed
    AFTER INSERT OR UPDATE OR DELETE ON csp_user_protocol_secret
    FOR EACH STATEMENT EXECUTE FUNCTION csp_user_notify();
