-- V9: spec 003 — saved_articles 테이블 + pg_bigm 전문 검색 인덱스
-- 전제: shared_preload_libraries=pg_bigm 로 PostgreSQL 기동 (Dockerfile CMD 참조)

-- ================================================================
-- saved_articles: 사용자 기사 저장 북마크
-- ================================================================
CREATE TABLE IF NOT EXISTS saved_articles (
    id         BIGSERIAL   PRIMARY KEY,
    account_id UUID        NOT NULL,
    article_id BIGINT      NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    saved_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT saved_articles_uq UNIQUE (account_id, article_id)
);

CREATE INDEX IF NOT EXISTS saved_articles_account_idx
    ON saved_articles (account_id, saved_at DESC);

-- ================================================================
-- pg_bigm 전문 검색 인덱스 (항상 생성 — 컨테이너·프로덕션 양쪽 pg_bigm 필수)
-- ================================================================
CREATE EXTENSION IF NOT EXISTS pg_bigm;

CREATE INDEX IF NOT EXISTS articles_title_bigm_idx
    ON articles USING gin (title gin_bigm_ops);

CREATE INDEX IF NOT EXISTS summaries_content_bigm_idx
    ON summaries USING gin (content gin_bigm_ops);
