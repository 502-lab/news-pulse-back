# Quickstart Validation Guide: 006 편향 분析 엔진

**Date**: 2026-06-21

이 가이드는 006 기능의 end-to-end 동작을 검증하는 시나리오를 설명한다.
구현 코드·테스트 코드 전문은 포함하지 않는다.

---

## Prerequisites

1. **Docker**: `docker compose up -d postgres` — PostgreSQL 컨테이너 실행
2. **환경변수**: `application-local.yaml` 준비 (application-example.yaml 참고):
   ```yaml
   app:
     client:
       gemini:
         api-key: <YOUR_GEMINI_KEY>
         model: gemini-2.0-flash
         base-url: https://generativelanguage.googleapis.com
     scheduler:
       bias:
         interval-ms: 5000        # 테스트용 5초
         batch-size: 5
         recovery-interval-ms: 60000
         backoff-attempt1-minutes: 1
         backoff-attempt2-minutes: 5
   ```
3. **V13 migration 확인**: `./gradlew flywayInfo` 실행 후 V13 적용 상태 확인
4. **JWT 토큰**: 기존 로그인 API로 발급

---

## Scenario 1 — 신규 기사 수집 후 자동 PENDING 생성 (FR-001)

**방법**:
1. `./gradlew bootRun` 으로 앱 실행 (스케줄러 포함)
2. 수집 스케줄러 1회 실행 또는 `POST /api/v1/internal/trigger-collection`

**검증 SQL**:
```sql
-- bias_analysis PENDING 행 생성 확인
SELECT a.title, ba.status, ba.attempt_count
FROM bias_analysis ba
JOIN articles a ON ba.article_id = a.id
WHERE ba.status = 'PENDING'
ORDER BY ba.created_at DESC
LIMIT 5;
```

**기대 결과**: 신규 수집 기사마다 `status=PENDING, attempt_count=0` 행 존재

---

## Scenario 2 — 편향 분析 파이프라인 완료 (FR-002, SC-002)

**방법**: 스케줄러가 자동 실행 또는 interval-ms=5000 대기 (5~15초)

**검증 SQL**:
```sql
SELECT ba.status, ba.value, ba.rationale_keywords, ba.attempt_count, ba.analyzed_at
FROM bias_analysis ba
WHERE ba.status = 'DONE'
ORDER BY ba.analyzed_at DESC
LIMIT 5;
```

**기대 결과**: `value` −100~+100 정수, `rationale_keywords` 2~5개 배열, `analyzed_at` NOT NULL

**API 검증**:
```bash
# 피드에서 biasScore 필드 확인 (SC-002: 필드 누락 0%)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/feed | jq '.data.articles[0].biasScore'

# 기사 상세에서 biasScore 확인
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/articles/{id} | jq '.data.biasScore'
```

**기대 결과**: `biasScore.value`, `biasScore.rationaleKeywords`, `biasScore.status` 필드 항상 존재 (DONE이면 값, 미완료면 null+status)

---

## Scenario 3 — 재시도 백오프 및 FAILED 처리 (FR-003, SC-003)

**방법**: Gemini API 키를 잘못된 값으로 설정하거나 WireMock으로 5xx 응답 주입

**검증 SQL**:
```sql
-- 재시도 진행 중
SELECT ba.status, ba.attempt_count, ba.next_retry_at
FROM bias_analysis ba
WHERE ba.status = 'PENDING' AND ba.attempt_count > 0;

-- 3회 소진 FAILED
SELECT ba.status, ba.attempt_count, ba.failed_at
FROM bias_analysis ba
WHERE ba.status = 'FAILED' AND ba.attempt_count = 3;
```

**기대 결과**:
- `attempt_count=1` → `next_retry_at ≈ NOW() + 5min`
- `attempt_count=2` → `next_retry_at ≈ NOW() + 30min`
- `attempt_count=3` → `status=FAILED`, `failed_at` NOT NULL

**피드 확인 (SC-003)**:
```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/articles/{failedId} | jq '.data.biasScore'
# 기대: {"value": null, "rationaleKeywords": null, "status": "FAILED"} — 기사 자체는 정상 반환
```

---

## Scenario 4 — 멱등성: 중복 생성 없음 (FR-004, SC-005)

**방법**: 이미 bias_analysis 행이 있는 기사에 대해 `createForArticle` 또는 backfill 재실행

**검증 SQL**:
```sql
-- article_id 중복 없음
SELECT article_id, COUNT(*) FROM bias_analysis GROUP BY article_id HAVING COUNT(*) > 1;
-- 기대: 0 rows
```

---

## Scenario 5 — 편향 칩 API (FR-009)

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/articles/{articleId}/bias
```

**기대 결과 (DONE 기사)**:
```json
{
  "code": 200, "status": "success", "message": "OK",
  "data": {
    "value": -45,
    "rationaleKeywords": ["편향적 프레이밍", "단일 시각"],
    "status": "DONE"
  }
}
```

---

## Scenario 6 — 출처 편향 집계 (FR-006, SC-004)

```bash
# sourceId=1 기준 (분析완료 기사 10건+ 전제)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/bias/outlets/1
```

**기대 결과**:
```json
{
  "data": {
    "sourceId": 1,
    "biasValue": -23.5,
    "articleCount": 142
  }
}
```

**응답 시간**: p95 ≤ 3s 목표 (SC-004). `EXPLAIN ANALYZE`로 인덱스 사용 확인.

---

## Scenario 7 — 전체 편향 스펙트럼 (FR-007)

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/bias/spectrum
```

**기대 결과**:
```json
{
  "data": {
    "weightedAverage": -12.3,
    "liberalPercent": 42.5,
    "neutralPercent": 38.2,
    "conservativePercent": 19.3,
    "totalCount": 5123
  }
}
```

**검증**: `liberalPercent + neutralPercent + conservativePercent ≈ 100.0` (부동소수점 오차 허용)

---

## Scenario 8 — Backfill (FR-001 확장)

```bash
# 관리자 토큰 필요
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/api/v1/admin/bias/backfill
```

**기대 결과**:
```json
{"data": {"created": 5494}}
```

**멱등 검증**: 두 번 호출 시 두 번째는 `{"created": 0}`

---

## Scenario 9 — One-Shot FAILED 복구

**방법**: `failed_at`을 7시간 전으로 수동 업데이트 후 recovery 스케줄러 대기

```sql
-- 테스트용: failed_at 조작
UPDATE bias_analysis SET failed_at = NOW() - INTERVAL '7 hours'
WHERE status = 'FAILED' AND attempt_count = 3
LIMIT 1;
```

**기대**: 복구 스케줄러 실행 후 해당 행 PROCESSING → DONE (또는 terminal FAILED if Gemini 실패)

---

## Integration Test 구조 (참고)

- `BiasAnalysisServiceTest`: Gemini Mock(WireMock), BigmPostgresImage DB
- `BiasControllerTest`: @WebMvcTest, BiasAnalysisService mock
- `BiasAnalysisRepositoryTest`: @DataJpaTest, BigmPostgresImage
- WireMock 스텁: `POST /v1beta/models/gemini-2.0-flash:generateContent` → JSON 응답

---

## SC-001 검증 (7일 롤링)

SLA 자동 산출은 `@Scheduled(cron = "0 0 0 * * *")` 실행 후 로그 확인:
```
[BIAS-SLA] done_ratio=0.97, failed_today=3, window_articles=5123, runId=...
```

또는 수동 SQL:
```sql
SELECT
    COUNT(*) FILTER (WHERE ba.status = 'DONE') * 100.0 / COUNT(*) AS done_ratio,
    COUNT(*)                                                         AS total
FROM bias_analysis ba
JOIN articles a ON ba.article_id = a.id
WHERE a.first_collected_at < NOW() - INTERVAL '24 hours'
  AND a.first_collected_at >= NOW() - INTERVAL '7 days';
-- 기대: done_ratio >= 95
```
