-- V12: 알림 (푸시·인앱) 관련 테이블 6개 추가
-- V11은 terms_content에 할당됨 — V12 사용 필수
-- accounts.id = UUID → account_id 컬럼 전부 UUID

CREATE TABLE device_tokens (
    id          BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    account_id  UUID         NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    token       VARCHAR(512) NOT NULL,
    platform    VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_device_token UNIQUE (token)
);

CREATE INDEX idx_device_tokens_account ON device_tokens (account_id);

CREATE TABLE topic_subscriptions (
    account_id    UUID        NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    topic         VARCHAR(30) NOT NULL,
    subscribed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, topic)
);

CREATE TABLE notification_preferences (
    account_id     UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    push_enabled   BOOLEAN NOT NULL DEFAULT true,
    email_enabled  BOOLEAN NOT NULL DEFAULT true,
    rising_enabled BOOLEAN NOT NULL DEFAULT true,
    bias_enabled   BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE email_subscriptions (
    account_id    UUID        NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    type          VARCHAR(30) NOT NULL,
    active        BOOLEAN     NOT NULL DEFAULT true,
    subscribed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, type)
);

CREATE TABLE notifications (
    id           BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    account_id   UUID         NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    type         VARCHAR(30)  NOT NULL,
    title        VARCHAR(255) NOT NULL,
    body         TEXT,
    reference_id VARCHAR(100),
    is_read      BOOLEAN      NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_notifications_account_feed
    ON notifications (account_id, is_read, created_at DESC);

CREATE INDEX idx_notifications_expiry
    ON notifications (expires_at);

CREATE TABLE notification_outbox (
    id               BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    account_id       UUID         NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    notification_id  BIGINT       REFERENCES notifications(id) ON DELETE SET NULL,
    channel          VARCHAR(20)  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    payload          JSONB        NOT NULL,
    idempotency_key  VARCHAR(200) NOT NULL,
    attempt_count    INT          NOT NULL DEFAULT 0,
    next_retry_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_outbox_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_outbox_claim
    ON notification_outbox (status, next_retry_at)
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE INDEX idx_outbox_account ON notification_outbox (account_id);
