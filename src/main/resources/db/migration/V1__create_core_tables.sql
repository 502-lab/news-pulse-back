-- V1: 뉴스 수집·큐레이션 파이프라인 핵심 테이블

-- ================================================================
-- updated_at 자동 갱신 함수 + trigger
-- ================================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ================================================================
-- sources: 뉴스 출처
-- ================================================================
CREATE TABLE sources (
    id                          BIGSERIAL       PRIMARY KEY,
    name                        VARCHAR(255)    NOT NULL,
    feed_url                    VARCHAR(1024)   NOT NULL UNIQUE,
    adapter_type                VARCHAR(32)     NOT NULL,
    active                      BOOLEAN         NOT NULL DEFAULT TRUE,
    collection_interval_minutes INTEGER         NULL,
    call_budget_daily           INTEGER         NOT NULL DEFAULT 1000,
    consecutive_failure_count   INTEGER         NOT NULL DEFAULT 0,
    last_collected_at           TIMESTAMPTZ     NULL,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE TRIGGER trg_sources_updated_at
    BEFORE UPDATE ON sources
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ================================================================
-- articles: 기사
-- ================================================================
CREATE TABLE articles (
    id                      BIGSERIAL       PRIMARY KEY,
    normalized_url          VARCHAR(2048)   NOT NULL,
    original_url            VARCHAR(2048)   NOT NULL,
    title                   VARCHAR(1024)   NOT NULL,
    author                  VARCHAR(512)    NULL,
    published_at            TIMESTAMPTZ     NOT NULL,
    first_collected_at      TIMESTAMPTZ     NOT NULL,
    category                VARCHAR(32)     NULL,
    category_status         VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    category_retry_count    INTEGER         NOT NULL DEFAULT 0,
    summary_status          VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    summary_retry_count     INTEGER         NOT NULL DEFAULT 0,
    expires_at              TIMESTAMPTZ     NOT NULL,
    feed_visible            BOOLEAN         NOT NULL DEFAULT TRUE,
    user_saved              BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE TRIGGER trg_articles_updated_at
    BEFORE UPDATE ON articles
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- dedup 기준 UNIQUE 인덱스
CREATE UNIQUE INDEX idx_articles_normalized_url
    ON articles (normalized_url);

-- 피드 목록 조회 인덱스 (feed_visible=true, category_status∈{COMPLETED,FAILED})
CREATE INDEX idx_articles_feed
    ON articles (published_at DESC, id DESC)
    WHERE feed_visible = TRUE AND category_status IN ('COMPLETED', 'FAILED');

-- 카테고리 피드 조회 인덱스
CREATE INDEX idx_articles_category_feed
    ON articles (category, published_at DESC, id DESC)
    WHERE feed_visible = TRUE AND category_status IN ('COMPLETED', 'FAILED');

-- 카테고리 처리 큐 인덱스
CREATE INDEX idx_articles_category_queue
    ON articles (first_collected_at ASC)
    WHERE category_status = 'PENDING';

-- AI 요약 처리 큐 인덱스
CREATE INDEX idx_articles_summary_queue
    ON articles (first_collected_at ASC)
    WHERE summary_status = 'PENDING';

-- 만료 처리 인덱스
CREATE INDEX idx_articles_expiry
    ON articles (expires_at ASC)
    WHERE feed_visible = TRUE AND user_saved = FALSE;

-- ================================================================
-- article_sources: 수집 출처 이력 (provenance)
-- ================================================================
CREATE TABLE article_sources (
    id           BIGSERIAL   PRIMARY KEY,
    article_id   BIGINT      NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    source_id    BIGINT      NOT NULL REFERENCES sources(id),
    collected_at TIMESTAMPTZ NOT NULL,
    is_merge     BOOLEAN     NOT NULL DEFAULT FALSE,
    UNIQUE (article_id, source_id)
);

CREATE INDEX idx_article_sources_article ON article_sources (article_id);
CREATE INDEX idx_article_sources_merge_today
    ON article_sources (collected_at)
    WHERE is_merge = TRUE;

-- ================================================================
-- summaries: AI 생성 요약 슬롯 (BRIEF / BALANCED / DEEP)
-- ================================================================
CREATE TABLE summaries (
    id              BIGSERIAL   PRIMARY KEY,
    article_id      BIGINT      NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    depth           VARCHAR(32) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'NOT_GENERATED',
    content         TEXT        NULL,
    generated_at    TIMESTAMPTZ NULL,
    last_attempt_at TIMESTAMPTZ NULL,
    retry_count     INTEGER     NOT NULL DEFAULT 0,
    ai_generated    BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (article_id, depth)
);

CREATE TRIGGER trg_summaries_updated_at
    BEFORE UPDATE ON summaries
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE INDEX idx_summaries_article ON summaries (article_id);

-- ================================================================
-- source_daily_usage: 출처별 일일 호출 사용량
-- ================================================================
CREATE TABLE source_daily_usage (
    source_id   BIGINT  NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
    usage_date  DATE    NOT NULL,
    call_count  INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (source_id, usage_date)
);
