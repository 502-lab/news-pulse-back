# Research: 개인화 피드 · 검색 · 저장

**Feature**: 003-personalized-feed-search-save  
**Date**: 2026-06-12  
**Status**: 모든 결정 완료 — NEEDS CLARIFICATION 없음

---

## R1. 한국어 전문 검색 방식

**결정**: `pg_bigm` 1.2 (bigram) 확장 + GIN 인덱스

**배경**:  
PostgreSQL 기본 FTS(`tsvector`/`tsquery`)는 한국어 형태소 분석기가 내장되지 않아 단어 단위 분리가 되지 않는다. 대안:

| 방식 | 한국어 품질 | 설치 | 복잡도 |
|------|------------|------|--------|
| PostgreSQL 기본 FTS (simple dict) | ❌ 자모 분리 미지원 | 불필요 | 낮음 |
| pg_trgm (trigram, GIN) | ✅ 문자 n-gram — 한국어 부분 매칭 | 기본 번들 extension | 낮음 |
| **pg_bigm** (bigram, GIN) | ✅✅ CJK/한국어 전용 설계, 1~2자 쿼리 강점 | `apt install postgresql-16-pg-bigm` | 낮음 |
| Elasticsearch | ✅✅ 형태소 분석기 지원 | 별도 인프라 | 높음 |

**선택 이유**:  
자체호스팅 EC2 PostgreSQL 16 환경이므로 `apt install postgresql-16-pg-bigm`으로 컴파일 없이 설치 가능하다. pg_bigm 1.2는 PG16 공식 지원. CJK(한국어/중국어/일본어) 전용 설계로, 한국어 1~2자 짧은 쿼리에서 trigram 대비 매칭 품질이 우수하다(bigram=2-gram이 최소 단위 → 2자 이상 쿼리가 자연 하한).

**배포 요구사항** (004 배포 준비 항목에 포함):
```
apt install postgresql-16-pg-bigm
# postgresql.conf에 추가 후 PG 재시작 필수
shared_preload_libraries = 'pg_bigm'
```

**Testcontainers 커스텀 이미지** (CREATE EXTENSION pg_bigm이 테스트에서 작동하려면 필수):
```dockerfile
# src/test/resources/postgres-bigm/Dockerfile
FROM postgres:16
RUN apt-get update && apt-get install -y postgresql-16-pg-bigm
```
```yaml
# application-test.yaml 또는 @Container 코드
# DockerImageName.parse("custom/postgres-bigm:16")
```
`@Container`에서 커스텀 이미지를 사용하지 않으면 `CREATE EXTENSION pg_bigm`이 실패하므로 테스트 인프라에 반드시 반영.

**구현 방식**:
```sql
-- 마이그레이션에서 extension 활성화
CREATE EXTENSION IF NOT EXISTS pg_bigm;

-- GIN bigram 인덱스 (articles)
CREATE INDEX idx_articles_title_bigm    ON articles USING gin(title gin_bigm_ops);
CREATE INDEX idx_articles_content_bigm  ON articles USING gin(content gin_bigm_ops);
-- summaries는 text 필드에 (전 depth 슬롯 커버)
CREATE INDEX idx_summaries_text_bigm    ON summaries USING gin(text gin_bigm_ops);
```

**검색 쿼리 패턴** (`ArticleRepository.searchByQuery`):
```sql
SELECT DISTINCT a.*
FROM articles a
WHERE a.published_at >= now() - INTERVAL '90 days'
  AND (
    a.title   LIKE '%' || :query || '%'
    OR a.content LIKE '%' || :query || '%'
    OR EXISTS (
        SELECT 1 FROM summaries s
        WHERE s.article_id = a.id
          AND s.text LIKE '%' || :query || '%'
    )
  )
ORDER BY
  GREATEST(
    bigm_similarity(a.title,   :query),
    bigm_similarity(a.content, :query),
    (SELECT COALESCE(MAX(bigm_similarity(s.text, :query)), 0.0)
     FROM summaries s WHERE s.article_id = a.id)
  ) DESC,
  a.published_at DESC,
  a.id
```

- `LIKE`(대소문자 구분): `gin_bigm_ops`가 LIKE를 가속. 한국어는 대소문자 구분이 없으므로 ILIKE 불필요.
- `EXISTS` 서브쿼리: depth 필터 없이 전 슬롯 검색, 행 증식 방지.
- `GREATEST(bigm_similarity(...))`: 제목·본문·요약 중 가장 높은 유사도 기준 정렬. 본문/요약 강매칭 기사가 제목 유사도만 보는 경우 묻히지 않음.

---

### R1(c). 최소 검색어 길이

**결정**: 2자 이상 (FR-010 수정)

pg_bigm은 bigram(2-gram) 단위 인덱스이므로 2자 미만 쿼리는 인덱스를 활용할 수 없다. 대부분의 한국어 검색도 2자 이상이므로 하한을 2자로 설정한다. 단일 자음/모음('ㄱ', 'ㅏ' 등) 검색은 거부.

---

### R1(d). 요약 전체 슬롯 검색

**결정**: `depth` 필터 없이 모든 요약 슬롯 대상 검색

기존 쿼리의 `depth = 'brief'` 제한을 제거하고 EXISTS 서브쿼리로 교체한다. 이유:
- 기사에 따라 brief 슬롯이 없고 balanced/deep만 생성된 경우 해당 기사가 검색에서 누락됨
- `gin_bigm_ops` 인덱스가 `summaries.text` 전체 행을 커버하므로 depth 필터는 불필요
- DISTINCT + EXISTS로 행 증식 없이 전 슬롯 매칭 가능

---

## R2. 개인화 랭킹 가중치

**결정**: 3-요소 규칙 기반 가중치 합산, 설정 파일로 외부화

**가중치 공식**:

```
rank_score = category_score + keyword_score + recency_score

category_score = 50  (if article.category ∈ user_interests)
               =  0  (otherwise)

keyword_score  = Σ 30 per follow_keyword
                 (if keyword appears in article.title OR summary.text, max 5 keywords = 150)

recency_score  = max(0, RECENCY_WINDOW_HRS - hours_since_published)
                 / RECENCY_WINDOW_HRS * RECENCY_MAX_SCORE
               → 기본값: 30시간 window, 최대 20점
               → 방금 발행된 기사: +20, 30시간 경과: +0
```

**기본값 (application.yaml)**:
```yaml
feed:
  ranking:
    category-match-score: 50
    keyword-match-score: 30
    recency-window-hours: 30
    recency-max-score: 20
    feed-window-days: 7     # 피드 후보 기사 범위
```

**동점 처리**: `rank_score` 동일 시 `published_at` 내림차순 → `id` 사전순.

**Fallback 조건**: `rank_score == 0`인 기사만 있을 경우 또는 관심사/키워드가 없는 경우 → `personalized: false`, 최신순 반환.  
구체적으로: 상위 20건 중 `rank_score > 0`인 기사가 1건도 없으면 fallback.

**성능 고려**: 피드 후보 기사는 최근 `feed.ranking.feed-window-days`(기본 7일) 기사로 제한. 일반적으로 수백~수천 건이므로 Java 메모리 내 점수 계산 후 정렬이 현실적. 향후 캐싱 또는 DB 계산으로 이관 가능.

---

## R3. 커서 페이지네이션 전략

**결정**: 피드 = `(rank_score, published_at, article_id)` 인코딩 커서; 검색 = `(similarity, published_at, article_id)` 인코딩 커서

**배경**:  
001의 커서 페이지네이션은 `published_at + id` 조합으로 구현되어 있다. 003의 개인화 피드는 `rank_score`가 동적으로 계산되므로 순수 DB 커서 적용이 복잡하다.

**피드 커서 전략** — Keyset + 기준 시각 고정:
- 커서 = `Base64(rank_score|published_at|article_id|reference_ts)` 인코딩
- `reference_ts`: 첫 페이지 요청 시각을 커서에 포함. 다음 페이지 요청 시 `reference_ts` 기준으로 `recency_score`를 재계산 → 페이지 간 점수가 고정되어 중복/누락 없음(AS1.6 안정 정렬 보장).
- Keyset 조건: `WHERE (rank_score, published_at, id) < (cursor_score, cursor_published_at, cursor_id)` (정렬 기준 튜플 이후만 반환)
- 페이지 요청 시 최근 `feed-window-days`(7일) 기사 전체를 가져와 Java에서 점수 계산 후 정렬·슬라이스

**검색 커서 전략**:
- DB에서 `bigm_similarity()` 점수 계산 후 `ORDER BY similarity DESC, published_at DESC, id`
- 커서 = `Base64(similarity_score|published_at|article_id)`
- Keyset pagination: `WHERE (similarity_score, published_at, id) < (cursor_score, cursor_published_at, cursor_id)`

---

## R4. 요약 슬롯 Fallback 순서

**결정**: 선호 슬롯 → 인접 슬롯 우선 → 나머지 → null

**Fallback 매트릭스**:

| 선호(summary_depth) | 1순위 | 2순위 | 3순위 |
|---------------------|-------|-------|-------|
| DEEP                | DEEP  | BALANCED | BRIEF |
| BALANCED            | BALANCED | BRIEF | DEEP |
| BRIEF               | BRIEF | BALANCED | DEEP |

**이유**: BALANCED는 BRIEF의 확장이므로 BRIEF 누락 시 BALANCED가 더 유사. DEEP 누락 시 BALANCED가 BRIEF보다 더 가까운 대체본.

**응답 필드**:
```json
{
  "summary": {
    "text": "요약 본문 또는 null",
    "depth": "balanced",      // 실제 반환된 슬롯
    "isFallback": true        // true if depth ≠ reading_preferences.summary_depth
  }
}
```

---

## R5. 피드 엔드포인트 설계 결정

**결정**: `GET /api/v1/feed` — 개인화 피드 전용 엔드포인트, `GET /api/v1/articles` — 001 일반 목록 유지, 002에서 인증 게이팅

**이유**: 두 엔드포인트는 정렬 기준(랭킹 점수 vs 수집 순)과 응답 구조(personalized 플래그 포함 여부)가 다르다. 파라미터로 통합 시 `ArticleController`에 피드 로직이 혼입된다. 분리가 명확하고 001 코드를 건드리지 않는다.

---

## R6. 저장 상한 초과 시 동작

**결정**: 1,000건 초과 시 409 Conflict 반환 + 에러 코드 `SAVE_LIMIT_EXCEEDED`

**이유**: 429(Too Many Requests)는 rate limiting 의미, 422(Unprocessable)는 입력 오류 의미. 409가 "현재 상태와의 충돌"로 가장 적절.
