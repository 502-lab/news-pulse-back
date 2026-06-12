-- V9: spec 003 — saved_articles 테이블 + pg_bigm 전문 검색 인덱스

-- ================================================================
-- saved_articles: 사용자 기사 저장 북마크 (항상 생성)
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
-- pg_bigm 전문 검색 인덱스 (선택적: 환경에 따라 생략 가능)
-- requires shared_preload_libraries=pg_bigm in postgresql.conf
-- 없는 환경(로컬 테스트)에서도 마이그레이션이 실패하지 않도록 DO 블록 사용
-- ================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'pg_bigm') THEN
        CREATE EXTENSION IF NOT EXISTS pg_bigm;

        IF NOT EXISTS (
            SELECT 1 FROM pg_indexes WHERE indexname = 'articles_title_bigm_idx'
        ) THEN
            CREATE INDEX articles_title_bigm_idx
                ON articles USING gin (title gin_bigm_ops);
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM pg_indexes WHERE indexname = 'summaries_content_bigm_idx'
        ) THEN
            CREATE INDEX summaries_content_bigm_idx
                ON summaries USING gin (content gin_bigm_ops);
        END IF;
    ELSE
        RAISE WARNING 'pg_bigm not available — GIN 인덱스 생략. 전문 검색은 LIKE fallback으로 동작.';
    END IF;
END;
$$;
