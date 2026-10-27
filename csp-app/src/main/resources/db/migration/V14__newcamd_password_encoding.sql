-- =========================================================================
-- CSP-NG : Encodage mot de passe Newcamd (plaintext | md5crypt)
-- =========================================================================

ALTER TABLE csp_connector
    ADD COLUMN IF NOT EXISTS newcamd_password_encoding text CHECK (newcamd_password_encoding IS NULL OR newcamd_password_encoding IN ('plaintext', 'md5crypt')) DEFAULT 'plaintext';
