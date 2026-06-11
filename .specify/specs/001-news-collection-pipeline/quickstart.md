# Quickstart: 뉴스 수집·큐레이션 파이프라인 검증 가이드

**Feature**: 001-news-collection-pipeline  
**Date**: 2026-06-09

이 문서는 기능이 end-to-end로 동작하는지 검증하는 시나리오를 정의한다.  
구현 세부 사항은 `tasks.md`, 스키마는 `data-model.md`, API 계약은 `contracts/openapi.yaml` 참조.

---

## 사전 조건

### 환경 설정

```bash
# 필수 환경변수 설정 (application-local.yaml 또는 환경변수)
DB_URL=jdbc:postgresql://localhost:5432/newspulse
DB_USERNAME=newspulse
DB_PASSWORD=...
GEMINI_API_KEY=...
NAVER_CLIENT_ID=...
NAVER_CLIENT_SECRET=...

# 프로파일 활성화
SPRING_PROFILES_ACTIVE=local
```

### 로컬 DB 시작

```bash
# docker-compose (또는 로컬 PostgreSQL)
docker compose up -d postgres

# Flyway 마이그레이션 적용 (앱 시작 시 자동)
./gradlew bootRun
```

### 헬스체크 확인

```bash
curl http://localhost:8080/actuator/health
# 기대: {"status":"UP"}
```

---

## 검증 시나리오

### S1. 수집 파이프라인 기본 동작 (US1)

**목표**: 스케줄러가 실행되면 새 기사가 저장된다.

```bash
# 1. 수집 스케줄러 수동 트리거 (개발용 Actuator 엔드포인트 또는 직접 API)
# 참고: 테스트 편의를 위해 POST /api/v1/internal/trigger-collection 엔드포인트 구현
curl -X POST http://localhost:8080/api/v1/internal/trigger-collection

# 2. 수집된 기사 확인
psql -c "SELECT COUNT(*), category_status FROM articles GROUP BY category_status;"
```

**기대 결과**:
- 기사 레코드가 1건 이상 생성됨
- 모든 기사: `category_status = 'PENDING'`, `summary_status = 'PENDING'`
- `first_collected_at` ≠ NULL, `published_at` ≠ NULL
- `feed_visible = true`

---

### S2. URL 중복 병합 (US1 - dedup)

**목표**: 동일 URL 기사는 병합되고 provenance에 출처가 추가된다.

```bash
# 1. 동일 URL을 두 번 수집 시뮬레이션 (테스트용 API 또는 단위 테스트로 검증)
# 또는: 실제 RSS와 네이버 검색 API에서 동일 기사가 수집되는 케이스 확인

# 2. Article 레코드 수 확인 (병합 시 신규 생성 안 됨)
psql -c "SELECT COUNT(*) FROM articles WHERE normalized_url = '<테스트URL>';"
# 기대: 1

# 3. ArticleSource provenance 확인
psql -c "SELECT s.name, ars.is_merge, ars.collected_at
         FROM article_sources ars
         JOIN sources s ON s.id = ars.source_id
         WHERE ars.article_id = <article_id>;"
# 기대: 두 번째 출처에 is_merge=true 레코드
```

**기대 결과**:
- `articles` 테이블에 해당 URL 기사 1건만 존재
- `article_sources` 에 출처별 레코드 2건 (첫 번째 is_merge=false, 두 번째 is_merge=true)

---

### S3. AI 처리 파이프라인 (US2)

**목표**: AI 스케줄러가 PENDING 기사를 분류하고 요약을 생성한다.

```bash
# 1. AI 처리 스케줄러 수동 트리거
curl -X POST http://localhost:8080/api/v1/internal/trigger-ai-processing

# 2. 처리 결과 확인
psql -c "SELECT id, title, category, category_status, summary_status
         FROM articles ORDER BY updated_at DESC LIMIT 5;"
```

**기대 결과**:
- `category_status = 'COMPLETED'` AND `category ≠ NULL` (또는 category='OTHER')
- `summary_status = 'COMPLETED'`
- `summaries` 테이블에 `depth='BALANCED'` + `status='COMPLETED'` 레코드 존재
- `summaries` 테이블에 `depth='BRIEF'` + `status='COMPLETED'` 레코드 존재 (트런케이션)
- `summaries` 테이블에 `depth='DEEP'` + `status='NOT_GENERATED'` 레코드 존재

---

### S4. 피드 목록 API (US3 - AS1, AS2)

**목표**: 피드가 최신순으로 반환되고 카테고리 필터가 동작한다.

```bash
# 전체 피드 조회 (기본 20건)
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8080/api/v1/articles"

# 특정 카테고리 필터
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8080/api/v1/articles?category=ECONOMY_FINANCE"

# 커서 페이지네이션
NEXT_CURSOR=$(curl -s ... | jq -r '.nextCursor')
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8080/api/v1/articles?cursor=$NEXT_CURSOR"

# 최대값 clamp 확인 (size=150 → 100으로 clamp)
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8080/api/v1/articles?size=150"
```

**기대 결과**:
- `data` 배열: `publishedAt` 내림차순 정렬
- 각 항목: `id, title, category, categoryStatus, primarySource, publishedAt, preview, summaryStatus` 포함
- `category_status = 'PENDING'` 기사는 응답에 없음
- 카테고리 필터: 해당 카테고리 기사만 반환
- size=150 요청: 실제 반환 수 최대 100건

---

### S5. 기사 상세 API + Deep 요약 lazy 생성 (US3 - AS3, AS4)

**목표**: 상세 조회 시 3슬롯 반환, deep이 최초 조회 시 생성된다.

```bash
# 1. 상세 조회 (deep 미생성 상태)
ARTICLE_ID=<id>
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8080/api/v1/articles/$ARTICLE_ID"
# 기대: summaries.deep.status = "NOT_GENERATED"

# 2. 동일 기사 상세 재조회 (deep 생성 트리거)
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8080/api/v1/articles/$ARTICLE_ID"
# 기대: summaries.deep.status = "COMPLETED", summaries.deep.content ≠ null

# 3. 요약 실패 기사 조회 (summary_status=FAILED)
psql -c "UPDATE articles SET summary_status='FAILED' WHERE id=<id>;"
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8080/api/v1/articles/$ARTICLE_ID"
# 기대: summaryStatus="FAILED", summaries 각 슬롯은 실제 상태 반환
```

**기대 결과**:
- 첫 번째 조회: `summaries.deep.status = "NOT_GENERATED"`
- 두 번째 조회: `summaries.deep.status = "COMPLETED"` (생성 후 저장됨)
- `aiDisclaimer` 필드 포함
- 요약 실패 기사: `summaryStatus = "FAILED"` (피드에서는 노출 유지)

---

### S6. 관리자 통계 API (US4)

**목표**: 파이프라인 통계가 DB 실제 값과 일치한다.

```bash
# 통계 조회
curl -H "Authorization: Bearer <admin-token>" \
  "http://localhost:8080/api/v1/admin/pipeline/stats"

# DB 직접 검증
psql -c "SELECT COUNT(*) FROM articles WHERE DATE(first_collected_at) = CURRENT_DATE;"
psql -c "SELECT COUNT(*) FROM article_sources WHERE is_merge=true AND DATE(collected_at)=CURRENT_DATE;"
```

**기대 결과**:
- `articlesCollectedToday` = DB COUNT 일치
- `mergeCount` = DB `is_merge=true` COUNT 일치
- `summaryCompletionRate` = DB 비율 일치
- `categoryBreakdown` 합계 = `feed_visible=true` 기사 합계 일치
- 일반 사용자 토큰으로 요청 시: 403 Forbidden

---

### S7. 기사 만료 처리

**목표**: 만료 기사가 피드에서 제외되고 단계적으로 삭제된다.

```bash
# 1. 만료 기사 시뮬레이션
psql -c "UPDATE articles SET expires_at = NOW() - INTERVAL '1 day'
         WHERE id = <test_id> AND user_saved = false;"

# 2. 만료 스케줄러 트리거
curl -X POST http://localhost:8080/api/v1/internal/trigger-expiry

# 3. 피드에서 제외됨 확인
psql -c "SELECT feed_visible FROM articles WHERE id = <test_id>;"
# 기대: feed_visible = false

# 4. 피드 조회에서 제외 확인
curl -H "Authorization: Bearer <token>" "http://localhost:8080/api/v1/articles"
# 기대: 해당 기사 미포함

# 5. user_saved=true 기사는 보호됨
psql -c "UPDATE articles SET expires_at=NOW()-INTERVAL '1 day', user_saved=true WHERE id=<other_id>;"
curl -X POST http://localhost:8080/api/v1/internal/trigger-expiry
psql -c "SELECT feed_visible, user_saved FROM articles WHERE id=<other_id>;"
# 기대: user_saved=true이면 feed_visible 변경 안 됨
```

---

## 통합 테스트 전략

### Testcontainers 설정

```java
// 모든 통합 테스트에 공통 적용
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class ArticleFeedIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("newspulse_test")
        .withUsername("test")
        .withPassword("test");
    // Flyway 마이그레이션 자동 적용
}
```

### 외부 의존 모킹

```java
// AI 공급자 Mock
@MockBean AiProvider aiProvider;

// SourceAdapter Mock
@MockBean SourceAdapter rssSourceAdapter;

// Gemini 응답 스텁 예시
given(aiProvider.classify(any(), any()))
    .willReturn(new AiClassificationResult(Category.ECONOMY_FINANCE, 0.92));
given(aiProvider.summarize(any(), eq(SummaryDepth.BALANCED), any()))
    .willReturn("균형 요약 텍스트...");
```

### 테스트 레이어별 전략

| 레이어 | 도구 | 범위 |
|--------|------|------|
| Service 단위 | JUnit 5 + Mockito | 비즈니스 로직, 상태 전이 |
| Repository | @DataJpaTest + Testcontainers | 쿼리, 인덱스, 페이지네이션 |
| Controller | @WebMvcTest | 요청/응답 직렬화, 에러 처리 |
| 파이프라인 통합 | @SpringBootTest + Testcontainers | 수집→AI처리→피드 전체 흐름 |
| SourceAdapter | Mock HTTP (MockServer) | RSS/Naver API 응답 스텁 |

### 비동기 파이프라인 검증

```java
// AI 처리 스케줄러 직접 호출로 비동기 없이 검증
@Autowired AiProcessingScheduler aiProcessingScheduler;

@Test
void processesAllPendingArticles() {
    // Given: PENDING 기사 3건 저장
    // When: 스케줄러 직접 실행
    aiProcessingScheduler.processNext();
    // Then: category_status, summary_status 확인
}
```

---

## 성능 검증 (SC-009)

**목표**: 피드 조회 API 서버측 P95 < 1초 (100~200 concurrent users)

```bash
# k6 로드 테스트 (예시)
k6 run --vus 150 --duration 60s - <<'EOF'
import http from 'k6/http';
export default function() {
  http.get('http://localhost:8080/api/v1/articles', {
    headers: { Authorization: 'Bearer <token>' }
  });
}
EOF

# EXPLAIN ANALYZE로 쿼리 플랜 확인
psql -c "EXPLAIN ANALYZE
  SELECT * FROM articles
  WHERE feed_visible = true AND category_status IN ('COMPLETED', 'FAILED')
  ORDER BY published_at DESC, id DESC
  LIMIT 20;"
# 기대: Index Scan on idx_articles_feed, 실행 시간 < 5ms
```
