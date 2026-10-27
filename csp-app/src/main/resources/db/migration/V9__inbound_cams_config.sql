-- =========================================================================
-- CSP-NG P4 étapes P8/P9 : Configuration Listeners/Connecteurs V9
-- =========================================================================
-- MIGRATION ADDITIVE (plan P4 §P9 : nouvelles colonnes nullable/CHECK bornes).
--
-- Contrat (docs/INBOUND-CAMS-PLAN.md §P8/§P9) :
--  * csp_profile_listener :
--    - card_mode : NULL/'manual' (publication explicite requise) ou 'wildcard' (annonces automatiques)
--    - strict_policy : true obligatoire pour les protocoles entrants CCcam/CS378x (pas de bypass permissif)
--    - reshare_cap : 0..10 (défaut 0 = client only, bornes P4 0..10)
--    - max_cards : 1..4096 (défaut 512)
--    - max_inflight : 1..64 (défaut 32, bornes P4 max 64)
--    - max_per_ip : 1..100 (défaut 5)
--    - node_id : identifiant de serveur local optionnel (8 octets hex)
--    - idle_timeout_sec : délai d'inactivité (défaut 60s)
--  * csp_connector :
--    - card_discovery : découverte dynamique de cartes (défaut false)
--    - reshare_grant : grant explicite pour newcamd 0..10 (NULL = non accordé / 0)
--    - newcamd_dialect : 'legacy' (défaut) ou 'oscam_ncd525'
--    - reshare_cap : borne de reshare pour ce connecteur 0..10
-- =========================================================================

ALTER TABLE csp_profile_listener
    ADD COLUMN IF NOT EXISTS card_mode text CHECK (card_mode IS NULL OR card_mode IN ('manual', 'wildcard')),
    ADD COLUMN IF NOT EXISTS strict_policy boolean NOT NULL DEFAULT true CHECK (protocol NOT IN ('cccam', 'cs378x') OR strict_policy = true),
    ADD COLUMN IF NOT EXISTS reshare_cap integer CHECK (reshare_cap IS NULL OR (reshare_cap >= 0 AND reshare_cap <= 10)),
    ADD COLUMN IF NOT EXISTS max_cards integer NOT NULL DEFAULT 512 CHECK (max_cards >= 1 AND max_cards <= 4096),
    ADD COLUMN IF NOT EXISTS max_inflight integer NOT NULL DEFAULT 32 CHECK (max_inflight >= 1 AND max_inflight <= 64),
    ADD COLUMN IF NOT EXISTS max_per_ip integer NOT NULL DEFAULT 5 CHECK (max_per_ip >= 1 AND max_per_ip <= 100),
    ADD COLUMN IF NOT EXISTS node_id text,
    ADD COLUMN IF NOT EXISTS idle_timeout_sec integer NOT NULL DEFAULT 60 CHECK (idle_timeout_sec >= 5 AND idle_timeout_sec <= 3600);

ALTER TABLE csp_connector
    ADD COLUMN IF NOT EXISTS card_discovery boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS reshare_grant integer CHECK (reshare_grant IS NULL OR (reshare_grant >= 0 AND reshare_grant <= 10)),
    ADD COLUMN IF NOT EXISTS newcamd_dialect text CHECK (newcamd_dialect IS NULL OR newcamd_dialect IN ('legacy', 'oscam_ncd525')) DEFAULT 'legacy',
    ADD COLUMN IF NOT EXISTS reshare_cap integer CHECK (reshare_cap IS NULL OR (reshare_cap >= 0 AND reshare_cap <= 10));
