-- =========================================================================
-- CSP-NG L5e V3 : extended-newcamd + caid-profile-map + provider idents
-- =========================================================================
--  - csp_extended_newcamd singleton (enabled false seed)
--  - ALTER csp_connector.profile_name nullable
--  - csp_connector_caid_profile : CAID→profil par connecteur (ordinal affichage)
--  - csp_profile_provider_ident : provider idents par profil
--  - Helpers inet : csp_is_valid_inet, csp_is_wildcard, csp_bind_conflicts
--  - deferred constraint triggers :
--      * dynamic connector (profile NULL) requires >=1 map
--      * fixed connector (profile NOT NULL) requires 0 map
--      * port collision enabled listener vs extended ET listener↔listener sur même/wildcard bind (inet famille)
--      * disable profile referenced by map interdit
--      * caid map profile must be enabled
--  - statement-level NOTIFY triggers sur 3 nouvelles tables
-- =========================================================================

-- -------------------------------------------------------------------------
-- 0. Helpers inet (avant tables pour CHECK)
-- -------------------------------------------------------------------------
-- Politique: :: est considéré IPv6-only pour collision cross-family ; runtime ListenerManager devra appliquer IPV6_V6ONLY cohérent ou fail-fast.
CREATE OR REPLACE FUNCTION csp_is_valid_inet(addr text) RETURNS boolean AS $$
DECLARE ia inet;
BEGIN
  IF addr IS NULL OR btrim(addr) = '' THEN RETURN false; END IF;
  -- Rejeter CIDR/networks : host-only, exiger masklen 32 IPv4 ou 128 IPv6
  IF position('/' in addr) > 0 THEN RETURN false; END IF;
  -- Rejeter integer shorthand type "0" (pas de '.' ni ':')
  IF position(':' in addr) = 0 AND position('.' in addr) = 0 THEN RETURN false; END IF;
  BEGIN
    ia := addr::inet;
  EXCEPTION WHEN others THEN RETURN false;
  END;
  IF family(ia) = 4 AND masklen(ia) != 32 THEN RETURN false; END IF;
  IF family(ia) = 6 AND masklen(ia) != 128 THEN RETURN false; END IF;
  RETURN true;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE OR REPLACE FUNCTION csp_is_wildcard(addr text) RETURNS boolean AS $$
DECLARE ia inet;
BEGIN
  BEGIN
    ia := addr::inet;
  EXCEPTION WHEN others THEN RETURN false;
  END;
  RETURN ia = '0.0.0.0'::inet OR ia = '::'::inet;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE OR REPLACE FUNCTION csp_bind_conflicts(a text, b text) RETURNS boolean AS $$
DECLARE ia inet; ib inet;
BEGIN
  BEGIN
    ia := a::inet;
    ib := b::inet;
  EXCEPTION WHEN others THEN RETURN false;
  END;
  IF ia = ib THEN RETURN true; END IF;
  IF csp_is_wildcard(a) AND family(ia) = family(ib) THEN RETURN true; END IF;
  IF csp_is_wildcard(b) AND family(ib) = family(ia) THEN RETURN true; END IF;
  RETURN false;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- -------------------------------------------------------------------------
-- 1. Extended-newcamd singleton
-- -------------------------------------------------------------------------
CREATE TABLE csp_extended_newcamd (
    id              smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled         boolean NOT NULL DEFAULT false,
    listen_port     integer NOT NULL DEFAULT 2233 CHECK (listen_port BETWEEN 1 AND 65535),
    bind_address    text NOT NULL DEFAULT '0.0.0.0' CHECK (csp_is_valid_inet(bind_address)),
    catch_all       boolean NOT NULL DEFAULT false,
    debug           boolean NOT NULL DEFAULT false,
    des_key_ref     text,
    allow_list      text,
    deny_list       text,
    max_msg_size    integer NOT NULL DEFAULT 2048 CHECK (max_msg_size BETWEEN 128 AND 65536),
    version         bigint NOT NULL DEFAULT 1,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text
);

-- -------------------------------------------------------------------------
-- 1b. Enforce IP literal on existing listener table
-- -------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_listener_bind_address_nonblank') THEN
        ALTER TABLE csp_profile_listener ADD CONSTRAINT chk_listener_bind_address_nonblank CHECK (btrim(bind_address) <> '');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_listener_bind_address_inet') THEN
        ALTER TABLE csp_profile_listener ADD CONSTRAINT chk_listener_bind_address_inet CHECK (csp_is_valid_inet(bind_address));
    END IF;
END $$;

-- -------------------------------------------------------------------------
-- 2. ALTER connector profile nullability (dynamic connectors)
-- -------------------------------------------------------------------------
ALTER TABLE csp_connector ALTER COLUMN profile_name DROP NOT NULL;

-- -------------------------------------------------------------------------
-- 3. CAID profile map
-- -------------------------------------------------------------------------
CREATE TABLE csp_connector_caid_profile (
    id              bigserial PRIMARY KEY,
    connector_name  text NOT NULL REFERENCES csp_connector(name) ON DELETE CASCADE,
    ordinal         integer NOT NULL CHECK (ordinal >= 0),
    ca_id           integer NOT NULL CHECK (ca_id BETWEEN 0 AND 65535),
    profile_name    text NOT NULL REFERENCES csp_profile(name) ON DELETE RESTRICT,
    version         bigint NOT NULL DEFAULT 1,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text,
    UNIQUE (connector_name, ca_id),
    UNIQUE (connector_name, ordinal)
);
CREATE INDEX IF NOT EXISTS idx_caid_profile_connector ON csp_connector_caid_profile(connector_name);
CREATE INDEX IF NOT EXISTS idx_caid_profile_profile ON csp_connector_caid_profile(profile_name);

-- -------------------------------------------------------------------------
-- 4. Provider idents par profil
-- -------------------------------------------------------------------------
CREATE TABLE csp_profile_provider_ident (
    id              bigserial PRIMARY KEY,
    profile_name    text NOT NULL REFERENCES csp_profile(name) ON DELETE CASCADE,
    provider_ident  integer NOT NULL CHECK (provider_ident BETWEEN 0 AND 16777215),
    label           text,
    version         bigint NOT NULL DEFAULT 1,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text,
    UNIQUE (profile_name, provider_ident)
);
CREATE INDEX IF NOT EXISTS idx_provider_ident_profile ON csp_profile_provider_ident(profile_name);

-- -------------------------------------------------------------------------
-- 5. Deferred constraints : connector dynamic/fixed consistency
-- -------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION csp_connector_profile_check() RETURNS trigger AS $$
DECLARE
    cnt int;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RETURN NULL;
    END IF;
    SELECT count(*) INTO cnt FROM csp_connector_caid_profile WHERE connector_name = NEW.name;
    IF NEW.profile_name IS NOT NULL AND cnt > 0 THEN
        RAISE EXCEPTION 'Connector % has fixed profile % but has % caid-profile-map entries (must be 0)', NEW.name, NEW.profile_name, cnt;
    END IF;
    IF NEW.profile_name IS NULL AND cnt = 0 THEN
        RAISE EXCEPTION 'Connector % has NULL profile_name but requires >=1 caid-profile-map entries', NEW.name;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER csp_connector_profile_consistency
AFTER INSERT OR UPDATE ON csp_connector
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION csp_connector_profile_check();

CREATE OR REPLACE FUNCTION csp_caid_map_connector_check() RETURNS trigger AS $$
DECLARE
    cname text;
    has_profile boolean;
    cnt int;
BEGIN
    IF TG_OP = 'DELETE' THEN
        cname := OLD.connector_name;
    ELSE
        cname := NEW.connector_name;
    END IF;
    SELECT (profile_name IS NOT NULL) INTO has_profile FROM csp_connector WHERE name = cname;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    SELECT count(*) INTO cnt FROM csp_connector_caid_profile WHERE connector_name = cname;
    IF has_profile AND cnt > 0 THEN
        RAISE EXCEPTION 'Connector % has fixed profile but has % caid-profile-map entries', cname, cnt;
    END IF;
    IF NOT has_profile AND cnt = 0 THEN
        RAISE EXCEPTION 'Connector % is dynamic (NULL profile) but has no caid-profile-map entries', cname;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER csp_caid_map_consistency
AFTER INSERT OR UPDATE OR DELETE ON csp_connector_caid_profile
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION csp_caid_map_connector_check();

-- Ensure caid map profile is enabled (deferred)
CREATE OR REPLACE FUNCTION csp_caid_map_profile_enabled_check() RETURNS trigger AS $$
DECLARE
    enabled boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RETURN NULL;
    END IF;
    SELECT p.enabled INTO enabled FROM csp_profile p WHERE p.name = NEW.profile_name;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'CAID map profile not found: %', NEW.profile_name;
    ELSIF NOT enabled THEN
        RAISE EXCEPTION 'CAID map profile disabled: %', NEW.profile_name;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER csp_caid_map_profile_enabled
AFTER INSERT OR UPDATE OF profile_name ON csp_connector_caid_profile
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION csp_caid_map_profile_enabled_check();

-- Prevent disabling a profile referenced by caid map (deferred)
CREATE OR REPLACE FUNCTION csp_profile_disable_check() RETURNS trigger AS $$
DECLARE cnt int;
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.enabled = true AND NEW.enabled = false THEN
        SELECT count(*) INTO cnt FROM csp_connector_caid_profile WHERE profile_name = NEW.name;
        IF cnt > 0 THEN
            RAISE EXCEPTION 'Cannot disable profile % referenced by % caid-profile-map entries', NEW.name, cnt;
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS csp_profile_disable_check ON csp_profile;
CREATE CONSTRAINT TRIGGER csp_profile_disable_check
AFTER UPDATE OF enabled ON csp_profile
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION csp_profile_disable_check();

-- -------------------------------------------------------------------------
-- 6. Deferred constraints : port collisions (extended↔listener + listener↔listener) with inet famille
-- -------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION csp_check_port_collision() RETURNS trigger AS $$
DECLARE
    coll int;
BEGIN
    -- extended vs listener
    SELECT count(*) INTO coll
    FROM csp_extended_newcamd e
    JOIN csp_profile_listener l ON l.enabled AND e.enabled
        AND e.listen_port = l.port
        AND csp_bind_conflicts(e.bind_address, l.bind_address);
    IF coll > 0 THEN
        RAISE EXCEPTION 'Port collision between extended-newcamd and profile_listener on same/wildcard bind';
    END IF;
    -- listener vs listener (distinct ids)
    SELECT count(*) INTO coll
    FROM csp_profile_listener l1
    JOIN csp_profile_listener l2 ON l1.id < l2.id
        AND l1.enabled AND l2.enabled
        AND l1.port = l2.port
        AND csp_bind_conflicts(l1.bind_address, l2.bind_address);
    IF coll > 0 THEN
        RAISE EXCEPTION 'Port collision between profile_listeners on same/wildcard bind';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS csp_extended_port_collision ON csp_extended_newcamd;
CREATE CONSTRAINT TRIGGER csp_extended_port_collision
AFTER INSERT OR UPDATE OR DELETE ON csp_extended_newcamd
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION csp_check_port_collision();

DROP TRIGGER IF EXISTS csp_listener_port_collision ON csp_profile_listener;
CREATE CONSTRAINT TRIGGER csp_listener_port_collision
AFTER INSERT OR UPDATE OR DELETE ON csp_profile_listener
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION csp_check_port_collision();

-- -------------------------------------------------------------------------
-- 7. NOTIFY triggers statement-level sur 3 nouvelles tables (payload vide)
-- -------------------------------------------------------------------------
CREATE TRIGGER csp_extended_newcamd_changed AFTER INSERT OR UPDATE OR DELETE ON csp_extended_newcamd
    FOR EACH STATEMENT EXECUTE FUNCTION csp_config_notify();
CREATE TRIGGER csp_connector_caid_profile_changed AFTER INSERT OR UPDATE OR DELETE ON csp_connector_caid_profile
    FOR EACH STATEMENT EXECUTE FUNCTION csp_config_notify();
CREATE TRIGGER csp_profile_provider_ident_changed AFTER INSERT OR UPDATE OR DELETE ON csp_profile_provider_ident
    FOR EACH STATEMENT EXECUTE FUNCTION csp_config_notify();

-- -------------------------------------------------------------------------
-- 8. Seed extended singleton disabled
-- -------------------------------------------------------------------------
INSERT INTO csp_extended_newcamd (id, enabled, listen_port, bind_address, catch_all, debug, max_msg_size)
VALUES (1, false, 2233, '0.0.0.0', false, false, 2048)
ON CONFLICT (id) DO NOTHING;
