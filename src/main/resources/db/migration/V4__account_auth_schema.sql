-- V4: 계정·인증·온보딩·인가 스키마 (001 테이블 수정 없음, 추가만)

-- ================================================================
-- accounts
-- ================================================================
CREATE TABLE accounts (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email                VARCHAR(255) UNIQUE,
    password_hash        VARCHAR(255),
    role                 VARCHAR(20)  NOT NULL DEFAULT 'USER',
    status               VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    email_verified       BOOLEAN      NOT NULL DEFAULT FALSE,
    onboarding_completed BOOLEAN      NOT NULL DEFAULT FALSE,
    signup_type          VARCHAR(20)  NOT NULL,
    failed_login_count   INTEGER      NOT NULL DEFAULT 0,
    locked_until         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_accounts_email ON accounts(LOWER(email));

CREATE TRIGGER trg_accounts_updated_at
    BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ================================================================
-- social_connections
-- ================================================================
CREATE TABLE social_connections (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       UUID         NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    provider         VARCHAR(20)  NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    connected_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (provider, provider_user_id)
);

CREATE INDEX idx_social_connections_account ON social_connections(account_id);

-- ================================================================
-- terms_versions
-- ================================================================
CREATE TABLE terms_versions (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    type           VARCHAR(30) NOT NULL,
    version        VARCHAR(50) NOT NULL,
    effective_date DATE        NOT NULL,
    is_required    BOOLEAN     NOT NULL DEFAULT TRUE,
    is_active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (type, version)
);

INSERT INTO terms_versions (type, version, effective_date, is_required, is_active) VALUES
    ('SERVICE',   '1.0', '2026-06-01', TRUE,  TRUE),
    ('PRIVACY',   '1.0', '2026-06-01', TRUE,  TRUE),
    ('MARKETING', '1.0', '2026-06-01', FALSE, TRUE);

-- ================================================================
-- consent_records
-- ================================================================
CREATE TABLE consent_records (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       UUID        NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    terms_version_id UUID        NOT NULL REFERENCES terms_versions(id),
    agreed           BOOLEAN     NOT NULL,
    agreed_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (account_id, terms_version_id)
);

CREATE INDEX idx_consent_records_account ON consent_records(account_id);

-- ================================================================
-- refresh_tokens
-- ================================================================
CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID        NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    family_id   UUID        NOT NULL,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    device_id   VARCHAR(255),
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    is_revoked  BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_refresh_tokens_account ON refresh_tokens(account_id);
CREATE INDEX idx_refresh_tokens_family  ON refresh_tokens(family_id);
CREATE INDEX idx_refresh_tokens_hash    ON refresh_tokens(token_hash);

-- ================================================================
-- verification_codes
-- ================================================================
CREATE TABLE verification_codes (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    UUID        NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    purpose       VARCHAR(30) NOT NULL,
    code_hash     VARCHAR(64) NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER     NOT NULL DEFAULT 0,
    hourly_count  INTEGER     NOT NULL DEFAULT 0,
    window_start  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_used       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_verification_codes_account_purpose
    ON verification_codes(account_id, purpose);

-- ================================================================
-- user_profiles
-- ================================================================
CREATE TABLE user_profiles (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID        NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
    nickname   VARCHAR(50),
    age_group  VARCHAR(20),
    occupation VARCHAR(100),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TRIGGER trg_user_profiles_updated_at
    BEFORE UPDATE ON user_profiles
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ================================================================
-- user_interests
-- ================================================================
CREATE TABLE user_interests (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID        NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    category   VARCHAR(50) NOT NULL,
    UNIQUE (account_id, category)
);

CREATE INDEX idx_user_interests_account ON user_interests(account_id);

-- ================================================================
-- follow_keywords
-- ================================================================
CREATE TABLE follow_keywords (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID         NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    keyword    VARCHAR(100) NOT NULL,
    type       VARCHAR(20)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_follow_keywords_account ON follow_keywords(account_id);

-- ================================================================
-- reading_preferences
-- ================================================================
CREATE TABLE reading_preferences (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    UUID        NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
    summary_depth VARCHAR(20) NOT NULL DEFAULT 'BALANCED',
    consume_mode  VARCHAR(20) NOT NULL DEFAULT 'READ',
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TRIGGER trg_reading_preferences_updated_at
    BEFORE UPDATE ON reading_preferences
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ================================================================
-- briefing_settings
-- ================================================================
CREATE TABLE briefing_settings (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       UUID        NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
    briefing_time    TIME        NOT NULL DEFAULT '08:00',
    timezone_offset  SMALLINT    NOT NULL DEFAULT 540,
    voice_enabled    BOOLEAN     NOT NULL DEFAULT FALSE,
    push_agreed      BOOLEAN     NOT NULL DEFAULT FALSE,
    push_agreed_at   TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TRIGGER trg_briefing_settings_updated_at
    BEFORE UPDATE ON briefing_settings
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
