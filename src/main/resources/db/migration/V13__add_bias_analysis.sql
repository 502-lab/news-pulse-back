-- 006 편향 분석 엔진: 기사별 편향 분석 작업 단위 테이블 (1기사 1행)
-- FR-010 분리저장: 기사 테이블에 bias 컬럼 추가 없이 전용 테이블만 사용

CREATE TABLE bias_analysis (
    id                  BIGSERIAL       PRIMARY KEY,
    article_id          BIGINT          NOT NULL
        CONSTRAINT fk_bias_analysis_article
            REFERENCES articles (id) ON DELETE CASCADE,
    status              VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    value               INTEGER,
    rationale_keywords  TEXT[],
    attempt_count       INTEGER         NOT NULL DEFAULT 0,
    next_retry_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    analyzed_at         TIMESTAMPTZ,
    failed_at           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- 멱등성 보장 (FR-004): 동일 기사 중복 행 방지 + ON CONFLICT 대상
CREATE UNIQUE INDEX uq_bias_analysis_article_id
    ON bias_analysis (article_id);

-- Claimer 쿼리: PENDING 기사 + lease 만료된 PROCESSING(stuck) 행 회수 (two-tx lease 모델)
-- claim 시 next_retry_at=NOW()+lease로 미루므로 정상 처리 중 행은 next_retry_at>NOW()라 제외,
-- 크래시 고아 PROCESSING 행만 lease 경과 후 next_retry_at<=NOW()로 회수됨
CREATE INDEX idx_bias_analysis_pending_queue
    ON bias_analysis (next_retry_at)
    WHERE status = 'PENDING' OR status = 'PROCESSING';

-- One-shot 복구 쿼리: 3회 소진 FAILED + failed_at + 6h
CREATE INDEX idx_bias_analysis_failed_recovery
    ON bias_analysis (failed_at)
    WHERE status = 'FAILED' AND attempt_count = 3;

-- SC-001 측정 쿼리: 수집 후 24h 기준 DONE 비율
CREATE INDEX idx_bias_analysis_done_analyzed
    ON bias_analysis (analyzed_at)
    WHERE status = 'DONE';

-- Outlet 집계 JOIN 보조: article_sources(source_id) 단독 인덱스
-- V1에 UNIQUE(article_id, source_id)만 존재 — source_id 선행 인덱스 없음 → 여기서 추가
CREATE INDEX idx_article_sources_source_id
    ON article_sources (source_id);

CREATE OR REPLACE FUNCTION update_bias_analysis_updated_at()
    RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_bias_analysis_updated_at
    BEFORE UPDATE ON bias_analysis
    FOR EACH ROW EXECUTE FUNCTION update_bias_analysis_updated_at();
