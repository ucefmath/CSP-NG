-- =========================================================================
-- CSP-NG V4 : colonne dedup_digest pour import cccam.cfg
-- Nullable, PAS de contrainte UNIQUE : dedup applicative au Lot C.
-- L'IP résolue est volatile ; deux imports du même digest sont arbitrés
-- par l'utilisateur, pas rejetés par Postgres.
-- =========================================================================
ALTER TABLE csp_connector ADD COLUMN dedup_digest TEXT;

CREATE INDEX idx_connector_dedup_digest ON csp_connector(dedup_digest) WHERE dedup_digest IS NOT NULL;
