-- 007 트렌드 집계 엔진: 키워드 추출 → 슬롯 집계 → 이슈 스냅샷
-- 모두 기사(article_keyword)에서 재산출 가능. 기사 테이블 무변경.

-- 기사별 추출 키워드 (durable, 재추출 멱등: PK 충돌 무시)
CREATE TABLE article_keyword (
    article_id  BIGINT       NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    term        VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (article_id, term)
);
CREATE INDEX idx_article_keyword_term ON article_keyword (term);

-- (시간슬롯 × 카테고리 × term) 집계 (멱등 UPSERT)
CREATE TABLE trend_keyword_slot (
    slot_start    TIMESTAMPTZ NOT NULL,
    category      VARCHAR(32) NOT NULL,
    term          VARCHAR(64) NOT NULL,
    article_count INTEGER     NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (slot_start, category, term)
);
-- Top5/워드클라우드/WoW: 윈도우 범위 슬롯 스캔
CREATE INDEX idx_trend_slot_window ON trend_keyword_slot (slot_start);
-- 카테고리 필터 Top5
CREATE INDEX idx_trend_slot_category ON trend_keyword_slot (category, slot_start);

-- 최신 co-occurrence 이슈 (매 집계 전량 교체, cross-run 안정 ID 없음)
CREATE TABLE issue_snapshot (
    id                BIGSERIAL   PRIMARY KEY,
    derived_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    clustering_method VARCHAR(32) NOT NULL DEFAULT 'CO_OCCURRENCE',
    delta             NUMERIC(6,2),
    keywords          TEXT[]      NOT NULL,
    article_ids       BIGINT[]    NOT NULL
);
