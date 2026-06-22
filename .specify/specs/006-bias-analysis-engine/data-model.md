# Data Model: 006 편향 분석 엔진

**Date**: 2026-06-21

---

## 신규 Entity

### BiasAnalysis

기사별 편향 분석 작업 단위 (1기사 1행).

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `id` | BIGSERIAL | PK | |
| `article_id` | BIGINT | NOT NULL, UNIQUE, FK→articles(id) ON DELETE CASCADE | |
| `status` | VARCHAR(32) | NOT NULL, DEFAULT 'PENDING' | PENDING/PROCESSING/DONE/FAILED |
| `value` | INTEGER | NULLABLE | −100~+100, DONE 시 설정 |
| `rationale_keywords` | TEXT[] | NULLABLE | 2~5개 키워드, DONE 시 설정 |
| `attempt_count` | INTEGER | NOT NULL, DEFAULT 0 | 총 시도 횟수 (최대 4: 3 fast + 1 one-shot) |
| `next_retry_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | 다음 처리 가능 시각 (claimer 기준) |
| `analyzed_at` | TIMESTAMPTZ | NULLABLE | DONE 전환 시각 |
| `failed_at` | TIMESTAMPTZ | NULLABLE | 3회 소진 FAILED 전환 시각 (one-shot 기준) |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | |

**Java Entity**: `com.newscurator.domain.BiasAnalysis`
**Java Enum**: `com.newscurator.domain.enums.BiasStatus` (PENDING, PROCESSING, DONE, FAILED)

**State machine**:
```
PENDING ──(claimer claim)──► PROCESSING ──(Gemini 성공)──► DONE
                                │
                                └──(Gemini 실패, attemptCount < 3)──► PENDING (next_retry_at +5m/+30m)
                                │
                                └──(attemptCount == 3)──► FAILED (failed_at 기록)
                                                              │
                                                              └──(failed_at + 6h, one-shot)──► PROCESSING
                                                                                                    │
                                                                                               성공→DONE
                                                                                               실패→FAILED(terminal, attemptCount=4)
```

**UNIQUE(article_id)** 제약으로 중복 생성 방지 (FR-004).

---

## 신규 뷰 개념 (집계 쿼리, 별도 테이블 없음)

### OutletBiasSummary (인덱스 기반 집계)

실제 테이블 없음. `GET /api/v1/bias/outlets/{sourceId}` 시 실시간 집계:

```sql
SELECT
    AVG(ba.value)::NUMERIC(5,2) AS bias_value,
    COUNT(*)                     AS article_count
FROM bias_analysis ba
         JOIN article_sources aso ON ba.article_id = aso.article_id
WHERE aso.source_id = :sourceId
  AND ba.status = 'DONE'
  AND ba.analyzed_at >= NOW() - INTERVAL '90 days';
```

최소 10건 미만 시 `biasValue: null` 반환.

### BiasSpectrum (인덱스 기반 집계)

실제 테이블 없음. `GET /api/v1/bias/spectrum` 시 전체 집계:

```sql
SELECT
    AVG(value)::NUMERIC(5,2)                                          AS weighted_average,
    100.0 * COUNT(*) FILTER (WHERE value BETWEEN -100 AND -34) / COUNT(*) AS liberal_percent,
    100.0 * COUNT(*) FILTER (WHERE value BETWEEN -33  AND  33) / COUNT(*) AS neutral_percent,
    100.0 * COUNT(*) FILTER (WHERE value BETWEEN  34  AND 100) / COUNT(*) AS conservative_percent,
    COUNT(*)                                                           AS total_count
FROM bias_analysis
WHERE status = 'DONE';
```

---

## Flyway Migration: V13__add_bias_analysis.sql

```sql
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

-- 멱등성 보장 (FR-004)
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
```

---

## 수정 Entity/DTO

### ArticleDetailResponse (수정)

`BiasScoreResponse biasScore` 필드 추가 (기존 10 필드 + 1):

```java
@Schema(description = "편향 분석 점수. 분석 미완료 또는 미존재 시 null")
BiasScoreResponse biasScore
```

### ArticleFeedItem (수정)

`BiasScoreResponse biasScore` 필드 추가:

```java
@Schema(description = "편향 분석 점수. 분석 미완료 시 null")
BiasScoreResponse biasScore
```

---

## 신규 DTO

### BiasScoreResponse

```java
// com.newscurator.dto.response.BiasScoreResponse
public record BiasScoreResponse(
    @Schema(description = "편향 점수 −100(극진보)~+100(극보수). DONE 상태에서만 non-null")
    Integer value,

    @Schema(description = "근거 키워드 2~5개. DONE 상태에서만 non-null")
    List<String> rationaleKeywords,

    @Schema(description = "분석 상태: PENDING/PROCESSING/DONE/FAILED")
    String status
) {}
```

### OutletBiasResponse

```java
// com.newscurator.dto.response.OutletBiasResponse
public record OutletBiasResponse(
    @Schema(description = "출처 ID")
    Long sourceId,

    @Schema(description = "편향 점수 단순평균 (롤링 90일, 분석완료 10건 이상 시). 미달 시 null")
    Double biasValue,

    @Schema(description = "분석 완료 기사 수 (롤링 90일)")
    long articleCount
) {}
```

### BiasSpectrumResponse

```java
// com.newscurator.dto.response.BiasSpectrumResponse
public record BiasSpectrumResponse(
    @Schema(description = "전체 분석완료 기사 가중평균. 기사 없으면 null")
    Double weightedAverage,

    @Schema(description = "진보[−100,−34] 비율 %. 기사 없으면 null")
    Double liberalPercent,

    @Schema(description = "중립[−33,+33] 비율 %. 기사 없으면 null")
    Double neutralPercent,

    @Schema(description = "보수[+34,+100] 비율 %. 기사 없으면 null")
    Double conservativePercent,

    @Schema(description = "집계 대상 분석완료 기사 수")
    long totalCount
) {}
```

### BiasAnalysisResult (client/ai 내부 record)

```java
// com.newscurator.client.ai.BiasAnalysisResult
public record BiasAnalysisResult(int value, List<String> rationaleKeywords) {}
```

---

## 신규 Request DTO

없음. Admin backfill은 body 없이 POST만 사용.

---

## 인덱스 전략 요약

| 인덱스 | 목적 |
|--------|------|
| `uq_bias_analysis_article_id` | FR-004 멱등성, ON CONFLICT DO NOTHING |
| `idx_bias_analysis_pending_queue` (PENDING OR PROCESSING) | Claimer SKIP LOCKED 배치 조회 + 크래시 후 stuck PROCESSING 회수 |
| `idx_bias_analysis_failed_recovery` (FAILED, attempt_count=3) | One-shot 복구 폴러 (attempt_count=4 terminal은 이 술어 벗어남) |
| `idx_bias_analysis_done_analyzed` | SC-001 SLA 측정 |
| `idx_article_sources_source_id` (V13 신규 추가) | Outlet 집계 JOIN — V1에 source_id 단독 인덱스 없음 |
