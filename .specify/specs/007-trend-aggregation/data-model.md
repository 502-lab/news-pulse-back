# Data Model: 007 트렌드 집계 엔진

**Date**: 2026-06-22 · **이슈 모델**: re-derive (OI-4, R-001)

---

## 신규 테이블 (V14)

### 1. article_keyword — 기사별 추출 키워드 (durable, 재추출 멱등)

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `article_id` | BIGINT | NOT NULL, FK→articles(id) ON DELETE CASCADE | |
| `term` | VARCHAR(64) | NOT NULL | Nori 명사(NNG/NNP) |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |
| PK | (`article_id`, `term`) | | 기사당 1회 dedup + 재추출 멱등(ON CONFLICT) |

### 2. trend_keyword_slot — (시간슬롯 × 카테고리 × term) 집계 (멱등 UPSERT)

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `slot_start` | TIMESTAMPTZ | NOT NULL | `date_trunc('hour', first_collected_at)` |
| `category` | VARCHAR(32) | NOT NULL | Category enum, 미분류는 'OTHER' |
| `term` | VARCHAR(64) | NOT NULL | |
| `article_count` | INTEGER | NOT NULL | 해당 슬롯·카테고리에서 term 등장 기사 수(DISTINCT) |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |
| PK | (`slot_start`, `category`, `term`) | | 멱등 재집계 키 |

### 3. issue_snapshot — 최신 co-occurrence 이슈 (매 실행 전량 교체, cross-run 안정 ID 없음)

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `id` | BIGSERIAL | PK | run 내 임시 식별(외부 참조 비보장) |
| `derived_at` | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | 산출 시각 |
| `clustering_method` | VARCHAR(32) | NOT NULL DEFAULT 'CO_OCCURRENCE' | 정보성/감사(Q5). v2 전환 시 값 분기 |
| `delta` | NUMERIC(6,2) | NULLABLE | 이슈 증감(멤버 키워드 WoW 집계 파생) |
| `keywords` | TEXT[] | NOT NULL | 대표 키워드 3개 |
| `article_ids` | BIGINT[] | NOT NULL | 관련 기사 묶음 |

```sql
-- V14__add_trend_aggregation.sql
CREATE TABLE article_keyword (
    article_id  BIGINT       NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    term        VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (article_id, term)
);
-- 추출 대상(미보유 기사) 탐색 보조
CREATE INDEX idx_article_keyword_term ON article_keyword (term);

CREATE TABLE trend_keyword_slot (
    slot_start    TIMESTAMPTZ NOT NULL,
    category      VARCHAR(32) NOT NULL,
    term          VARCHAR(64) NOT NULL,
    article_count INTEGER     NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (slot_start, category, term)
);
-- Top5/워드클라우드/WoW: 윈도우 범위 슬롯을 term 합산 — slot_start 범위 스캔
CREATE INDEX idx_trend_slot_window ON trend_keyword_slot (slot_start);
-- 카테고리 필터 Top5
CREATE INDEX idx_trend_slot_category ON trend_keyword_slot (category, slot_start);
-- 보존 정리(90일 경과)
--   DELETE FROM trend_keyword_slot WHERE slot_start < NOW() - INTERVAL '90 days';

CREATE TABLE issue_snapshot (
    id                BIGSERIAL   PRIMARY KEY,
    derived_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    clustering_method VARCHAR(32) NOT NULL DEFAULT 'CO_OCCURRENCE',
    delta             NUMERIC(6,2),
    keywords          TEXT[]      NOT NULL,
    article_ids       BIGINT[]    NOT NULL
);
```

> 기사 카테고리는 `articles.category`(분류 실패 시 'OTHER' 매핑)를 슬롯 집계 시점에 사용. article_keyword에는 category를 두지 않음(카테고리는 기사 속성이라 JOIN으로 파생 → 재분류 시 자동 반영).

---

## 포트 (격리 인터페이스)

### KeywordExtractor (R-002)

```java
// com.newscurator.client.keyword.KeywordExtractor
public interface KeywordExtractor {
    /** 텍스트에서 한국어 명사(NNG/NNP) 키워드를 추출한다(불용어 제거, 중복 제거). */
    java.util.Set<String> extractNouns(String text);
}
```
- **NoriKeywordExtractor** (구현): Lucene `KoreanTokenizer` + `KoreanPartOfSpeechStopFilter`(NNG/NNP 유지) + 커스텀 불용어 필터(`stopwords-ko.txt`). term 길이 가드(예: 2자 이상), 소문자/정규화.
- 교체 가능: 향후 다른 분석기/사전 → 구현만 교체(FR-001 격리).

### IssueClusterer (R-001)

```java
// com.newscurator.service.trend.IssueClusterer
public interface IssueClusterer {
    /** 윈도우 기사+키워드에서 이슈(관련 기사 묶음 + 대표 키워드 3개 + 증감)를 재산출한다. */
    java.util.List<DerivedIssue> cluster(IssueClusterContext ctx);
}
```
- **CoOccurrenceIssueClusterer** (MVP 구현): 동일 기사에서 함께 등장한 키워드 쌍의 동시출현 빈도로 그래프를 만들고, 연결 성분/임계 이상 동시출현을 이슈로 묶음. 각 이슈에 동시출현 상위 키워드 3개 + 관련 article_ids + delta(멤버 키워드 WoW 집계).
- `DerivedIssue` record(keywords, articleIds, delta) → `issue_snapshot`로 직렬화, `clusteringMethod='CO_OCCURRENCE'` 부여.
- 교체 가능: `EmbeddingIssueClusterer`(v2, 범위 밖) 추가 시 스냅샷 재작성 로직만 교체(FR-011/012).

---

## 집계 멱등 흐름 (R-004)

`TrendAggregationService.aggregate()` — 스케줄러(default 10분)가 호출, 단일 TX 경계는 단계별로 분리:

```
1) 추출 (article_keyword UPSERT, 멱등) — summary-race 게이팅
   대상 SELECT: 최근 extract-window(25h) 기사 중 article_keyword 미보유 + 아래 게이트 통과 기사만
     · summary_status='COMPLETED'                          → 제목 + summaries.content (BALANCED)
     · summary_status='FAILED'                             → 제목만 (fallback)
     · summary_status='PENDING' AND first_collected_at < NOW()-1h  → 제목만 (1h 경과 미요약, 더 안 기다림)
     · summary_status='PENDING' AND first_collected_at >= NOW()-1h → 이번 run SKIP (다음 run 대기, 요약 도착 시 본문 포함)
   (게이트 SQL 예)
     WHERE NOT EXISTS (SELECT 1 FROM article_keyword ak WHERE ak.article_id = a.id)
       AND a.first_collected_at >= NOW() - INTERVAL '25 hours'
       AND ( a.summary_status IN ('COMPLETED','FAILED')
             OR (a.summary_status = 'PENDING' AND a.first_collected_at < NOW() - INTERVAL '1 hour') )
   for each gated article:
     bal = summaryRepository.findByArticleIdAndDepth(id, BALANCED)   // Optional
     useSummary = (summary_status='COMPLETED') AND bal.present AND bal.content non-null·non-blank
     text = useSummary ? (title + " " + bal.content) : title          // 그 외/null이면 제목만(NPE 방어)
     terms = keywordExtractor.extractNouns(text)           // NNG/NNP, 불용어/중복 제거
     INSERT INTO article_keyword(article_id, term) VALUES ... ON CONFLICT DO NOTHING
   // race 제거: 요약 도착 전 제목만으로 굳히지 않고, recent-PENDING은 skip→다음 run에 본문 포함 재추출.
   //          article_keyword PK 멱등이므로 동일 기사 재추출은 안전(이미 본 term은 no-op).

2) 슬롯 집계 (trend_keyword_slot UPSERT, 멱등)
   영향 슬롯: 최근 윈도우 + WoW 2주 경계
   INSERT INTO trend_keyword_slot (slot_start, category, term, article_count, updated_at)
   SELECT date_trunc('hour', a.first_collected_at) AS slot_start,
          COALESCE(a.category, 'OTHER')            AS category,
          ak.term,
          COUNT(DISTINCT a.id)                     AS article_count,
          NOW()
   FROM article_keyword ak
            JOIN articles a ON a.id = ak.article_id
   WHERE a.first_collected_at >= :windowStart
   GROUP BY 1, 2, 3
   ON CONFLICT (slot_start, category, term)
       DO UPDATE SET article_count = EXCLUDED.article_count, updated_at = NOW();
   -- 동일 입력 → 동일 결과(멱등). 재집계는 article_count 덮어씀.

3) 이슈 재산출 (issue_snapshot 전량 교체, clean cutover)
   issues = issueClusterer.cluster(currentWindowContext)   // co-occurrence
   [단일 TX] TRUNCATE issue_snapshot;
            INSERT INTO issue_snapshot(clustering_method, delta, keywords, article_ids) VALUES ...;

4) 로그: 시작/종료/처리 기사 수/추출 term 수/이슈 수/실패 수 log.info (FR-014)
```

**보존 정리** (`cleanup-cron` 03:30): `DELETE FROM trend_keyword_slot WHERE slot_start < NOW() - INTERVAL '90 days'`. article_keyword는 기사(90일 보존) CASCADE로 자연 정리.

**멱등성(Constitution VII)**: article_keyword PK / trend_keyword_slot PK로 재실행·중첩 안전. 단일 EC2 fixedDelay 전제(scale-out 시 ShedLock).

---

## 조회 파생 (read, on-the-fly 재계산 없음)

- **Top5/워드클라우드** (최근 24h): `SELECT term, SUM(article_count) ... WHERE slot_start >= NOW()-24h [AND category=:c] GROUP BY term HAVING SUM>= :minArticleCount ORDER BY <급상승점수> LIMIT 5`. deltaPct는 24h vs 직전 24h 비교.
- **히트맵**: `SELECT slot_start, category, SUM(article_count) ... GROUP BY slot_start, category` (윈도우 범위).
- **WoW**: 이번 주 vs 지난주 term별 SUM 비교. prev=0 → deltaPct=null+isNew. 정렬 = `(cur+1)/(prev+1)` 평활비. cur<2 제외.
- **이슈**: `SELECT * FROM issue_snapshot ORDER BY delta DESC NULLS LAST`.

## 신규 DTO (요약)

- `TrendKeywordResponse(term, count, deltaPct, isNew)` — Top5/WoW 항목 (KeywordView)
- `WordcloudItemResponse(term, weight)`
- `HeatmapCellResponse(slotStart, category, intensity)` + 격자 래퍼
- `IssueResponse(keywords[], articleIds[], delta, clusteringMethod)`
- 모두 `ApiResponse<T>` 래퍼. (003 페이지네이션 불요 — 고정 크기 집계)
