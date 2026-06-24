-- 008 어드민 대시보드: 공지·감사·스케줄러설정·제외키워드 + 기사 admin 숨김 컬럼

-- 1) 기사 관리자 숨김(가역, feed_visible과 독립 — 만료 물리삭제와 분리)
ALTER TABLE articles ADD COLUMN admin_hidden_at TIMESTAMPTZ NULL;
-- 사용자향 읽기 제외용 부분 인덱스(노출 대상만)
CREATE INDEX idx_articles_admin_visible
    ON articles (published_at DESC, id DESC)
    WHERE admin_hidden_at IS NULL;

-- 2) 공지
CREATE TABLE notice (
    id                  BIGSERIAL    PRIMARY KEY,
    title               VARCHAR(200) NOT NULL,
    content             TEXT         NOT NULL,
    published           BOOLEAN      NOT NULL DEFAULT FALSE,
    author_account_id   UUID         NOT NULL REFERENCES accounts(id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_notice_published ON notice (published, created_at DESC);

-- 3) 어드민 감사 로그(변형 액션)
CREATE TABLE admin_audit_log (
    id                BIGSERIAL    PRIMARY KEY,
    actor_account_id  UUID         NOT NULL REFERENCES accounts(id),
    action            VARCHAR(64)  NOT NULL,
    target_type       VARCHAR(32)  NOT NULL,
    target_id         VARCHAR(64)  NULL,
    detail            JSONB        NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_created ON admin_audit_log (created_at DESC);
CREATE INDEX idx_audit_target  ON admin_audit_log (target_type, target_id);

-- 4) 스케줄러 설정(런타임 토글·영속)
CREATE TABLE scheduler_setting (
    scheduler_key        VARCHAR(64) PRIMARY KEY,
    enabled              BOOLEAN     NOT NULL DEFAULT TRUE,
    interval_override_ms BIGINT      NULL,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by           UUID        NULL REFERENCES accounts(id)
);
-- 알려진 스케줄러 키 시드(enabled=true) — @Scheduled 메서드 12개 1:1 (grep 권위 확인)
INSERT INTO scheduler_setting (scheduler_key) VALUES
    ('collection'), ('ai_processing'), ('bias_analysis'), ('bias_recovery'), ('bias_sla'),
    ('trend_aggregation'), ('trend_cleanup'), ('tts_processing'),
    ('notification_outbox'), ('notification_expiry'), ('weekly_email'), ('expiry');

-- 5) 제외 키워드
CREATE TABLE excluded_keyword (
    id          BIGSERIAL    PRIMARY KEY,
    keyword     VARCHAR(100) NOT NULL UNIQUE,
    created_by  UUID         NOT NULL REFERENCES accounts(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
