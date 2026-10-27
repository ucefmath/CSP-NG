-- =========================================================================
-- CSP-NG V5 : table csp_proxy (proxy SOCKS5 nommé, entité de premier ordre)
-- + colonne proxy_name sur csp_connector (FK nullable ON DELETE RESTRICT)
-- + trigger csp_config_notify sur csp_proxy
-- =========================================================================
-- Conventions alignées sur V1 :
--   - PK name TEXT immuable, regex validée par CHECK
--   - type CHECK 'socks5' uniquement (extensible ultérieurement via ALTER)
--   - host : IP littérale ou nom DNS sans schéma ni userinfo, validé CHECK
--   - port : 1..65535
--   - username/password_ref : appariés nullable (tous deux NULL ou tous deux non-NULL)
--   - password_ref : référence env: ou file: uniquement, jamais de valeur en clair
--   - enabled/version/updated_at/updated_by : conventions standard
--   - description bornée à 500 caractères
--   - Aucun seed, aucune adresse hardcodée
-- =========================================================================

-- 1. Table csp_proxy

CREATE TABLE csp_proxy (
    name            text PRIMARY KEY
                        CHECK (name ~ '^[A-Za-z0-9_.\\-]{1,64}$'),
    enabled         boolean NOT NULL DEFAULT true,
    type            text NOT NULL DEFAULT 'socks5'
                        CHECK (type IN ('socks5')),
    -- host : IPv4 dotted, IPv6 brut (sans crochets dans la colonne),
    --        ou nom DNS sans URI/userinfo/caractères de contrôle.
    -- La validation applicative (ConfigValidator) réalise le contrôle strict.
    -- Le CHECK SQL rejette les cas les plus grossiers (URI-like et ctrl chars).
    host            text NOT NULL
                        CHECK (
                            host <> ''
                            AND host NOT LIKE '%://%'
                            AND host NOT LIKE '%@%'
                            AND host NOT LIKE '% %'
                            AND host NOT SIMILAR TO '%[[:cntrl:]]%'
                        ),
    port            integer NOT NULL CHECK (port BETWEEN 1 AND 65535),
    -- username et password_ref : appariés (tous deux NULL ou tous deux non-NULL)
    username        text,
    password_ref    text    -- ref env:VAR ou file:/path — JAMAIS de valeur en clair
                        CHECK (
                            password_ref IS NULL
                            OR (
                                password_ref <> ''
                                AND (password_ref LIKE 'env:%' OR password_ref LIKE 'file:%')
                            )
                        ),
    -- Cohérence du pair (username, password_ref) vérifiée par contrainte CHECK
    CONSTRAINT csp_proxy_auth_pair CHECK (
        (username IS NULL AND password_ref IS NULL)
        OR
        (username IS NOT NULL AND password_ref IS NOT NULL)
    ),
    -- PAS de champ timeout sur le proxy (Plan P2) : le budget T est celui du
    -- CONNECTEUR (csp_connector.connect_timeout_ms, borné applicativement
    -- 1000..300000 sur le chemin proxy) appliqué aux DEUX phases
    -- (TCP connect vers le proxy + handshake SOCKS5).
    description     text CHECK (description IS NULL OR char_length(description) <= 500),
    version         bigint NOT NULL DEFAULT 1,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text
);

COMMENT ON TABLE csp_proxy IS
  'Proxys SOCKS5 nommés. Entité de premier ordre. '
  'name immuable (PK). '
  'IPv6 stocké sans crochets (ex: 2001:db8::1). '
  'password_ref : env:VAR ou file:/path uniquement.';

-- 2. Colonne proxy_name sur csp_connector
--    FK nullable RESTRICT : un proxy référencé ne peut pas être supprimé.
--    Valeur NULL = connexion directe (comportement legacy préservé).

ALTER TABLE csp_connector
    ADD COLUMN proxy_name text
        REFERENCES csp_proxy(name) ON DELETE RESTRICT ON UPDATE CASCADE;

-- Index partiel sur proxy_name (performances lookup "quel proxy est utilisé ?")
CREATE INDEX idx_connector_proxy_name
    ON csp_connector(proxy_name)
    WHERE proxy_name IS NOT NULL;

-- 3. Trigger csp_config_notify sur csp_proxy
--    Fonction csp_config_notify() déjà définie en V1 (réutilisation).

CREATE TRIGGER csp_config_changed
    AFTER INSERT OR UPDATE OR DELETE ON csp_proxy
    FOR EACH STATEMENT EXECUTE FUNCTION csp_config_notify();

-- 4. Index supplémentaire : lookup par enabled (monitoring, admin)
CREATE INDEX idx_proxy_enabled ON csp_proxy(enabled);

-- =========================================================================
-- NOTE : aucun seed, aucune adresse hardcodée.
-- Exemple de valeur valide (à insérer manuellement ou via API admin) :
--   INSERT INTO csp_proxy (name, host, port, description)
--   VALUES ('my-socks5', '172.20.0.1', 1080, 'LAN SOCKS5 proxy');
-- =========================================================================
