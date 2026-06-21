# Implementation Plan: 006 편향 분석 엔진

**Branch**: `006-bias-analysis-engine` | **Date**: 2026-06-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `.specify/specs/006-bias-analysis-engine/spec.md`

---

## Summary

기사 수집 시 `bias_analysis` PENDING 행을 자동 생성하고, 전용 스케줄러가 `FOR UPDATE SKIP LOCKED` 패턴으로 배치 클레임 후 Gemini Flash를 호출하여 편향 점수(−100~+100)와 근거 키워드(2~5개)를 저장한다. 005 outbox 패턴을 재사용하며, 3회 실패 시 FAILED 전환 + 6h 후 one-shot 복구, 기사는 biasScore null로 정상 노출. 피드·상세 API에 biasScore 필드를 추가하고, 출처별 집계(FR-006)/전체 스펙트럼(FR-007)/칩 상세(FR-009) 3개 신규 API를 제공한다. Backfill은 최근 90일 기사 PENDING 일괄 생성 후 정상 claimer rate-safe 드레인.

---

## Technical Context

**Language/Version**: Java 25

**Framework**: Spring Boot 4.x + Spring Data JPA (Hibernate 6)

**Primary Dependencies**:
- Gemini Flash (`app.client.gemini.*`) — 편향 분석 AI 호출 (기존 `GeminiAiProvider` 확장)
- PostgreSQL 17 — `bias_analysis` 테이블, `TEXT[]` 배열, `FOR UPDATE SKIP LOCKED`
- Flyway — V13 마이그레이션
- SpringDoc OpenAPI — Swagger 문서화
- Testcontainers `BigmPostgresImage` — 통합 테스트 DB
- WireMock — Gemini API stub

**Storage**: PostgreSQL `bias_analysis` 단일 신규 테이블 (V13). 기사 테이블 오염 없음 (FR-010).

**Testing**: JUnit 5, Testcontainers (BigmPostgresImage), WireMock, @WebMvcTest, @DataJpaTest

**Target Platform**: Linux EC2 단일 인스턴스 (AiProcessingScheduler 동일 패턴)

**Performance Goals**:
- SC-001: 수집 후 24h 내 95% DONE
- SC-004: 출처 집계 p95 ≤ 3s (인덱스 기반, Redis 미사용)

**Constraints**:
- `AiProperties.delayBetweenCallsMs` 준수 (Gemini rate 제한)
- Backfill: 별도 버스트 없음, 정상 claimer 드레인
- 005 `attemptCount >= 3` threshold 재사용 (NotificationOutbox.java:103 확인)

**Scale/Scope**: 현재 5,494건 기사 (전량 90일 이내). Backfill = 전량 소급.

---

## Constitution Check

| Gate | 원칙 | 상태 | 비고 |
|------|------|------|------|
| I. 레이어드 아키텍처 | Controller→Service→Repository 단방향 | PASS | BiasController→BiasAnalysisService→BiasAnalysisRepository |
| II. Entity 비노출 | DTO record 사용, Entity 미노출 | PASS | BiasScoreResponse, OutletBiasResponse, BiasSpectrumResponse 신규 |
| III. 공통 응답 포맷 | ApiResponse<T> 래퍼 | PASS | 기존 ApiResponse.success() 사용 |
| IV. 테스트 필수 | Service 단위 테스트, DB 통합 테스트 | PASS | BigmPostgresImage + WireMock 계획 |
| V. Flyway 마이그레이션 | V13__add_bias_analysis.sql | PASS | data-model.md 참조 |
| VI. 보안 기본값 | JWT 기본값, FR-005 제외 신규 API 모두 인증 필수 | PASS | FR-006/007/009 JWT, Admin bias 엔드포인트 ROLE_ADMIN |
| VII. 멱등성 | UNIQUE(article_id) + SKIP LOCKED | PASS | FR-004 SC-005 |
| ADR | Gemini 프롬프트 설계 | 주의 | tasks.md에 ADR 작성 태스크 포함 필요 |
| 구조적 로깅 | FR-011/012 배치 로그 + 일일 SLA emit | PASS | MDC runId, log.info/warn 패턴 |
| 외부 API 복원력 | AiTransientException 기반 재시도 백오프 | PASS | 기존 패턴 재사용 |

---

## Project Structure

### Documentation (this feature)

```text
.specify/specs/006-bias-analysis-engine/
├── plan.md              ← 이 파일
├── research.md          ← Phase 0 완료
├── data-model.md        ← Phase 1 완료
├── quickstart.md        ← Phase 1 완료
├── contracts/
│   └── openapi-patch.yaml  ← Phase 1 완료 (신규 API + 스키마)
├── checklists/
│   └── spec-review.md
└── tasks.md             ← /speckit-tasks 명령 출력 (미생성)
```

### Source Code

**신규 파일**:

```text
src/main/java/com/newscurator/
├── domain/
│   ├── BiasAnalysis.java
│   └── enums/BiasStatus.java
├── repository/
│   └── BiasAnalysisRepository.java
├── service/
│   └── BiasAnalysisService.java
├── scheduler/
│   ├── BiasAnalysisScheduler.java     (또는 BiasAnalysisProcessor — @Scheduled 진입점)
│   └── BiasAnalysisClaimer.java       (별도 @Transactional 빈 — two-tx claim/persistResult, R-013)
├── controller/
│   └── BiasController.java
└── dto/
    └── response/
        ├── BiasScoreResponse.java
        ├── OutletBiasResponse.java
        └── BiasSpectrumResponse.java

src/main/resources/
└── db/migration/
    └── V13__add_bias_analysis.sql

src/main/java/com/newscurator/client/ai/
└── BiasAnalysisResult.java   (record)
```

**수정 파일**:

```text
src/main/java/com/newscurator/
├── client/ai/
│   ├── AiProvider.java               (+analyzeBias 메서드)
│   └── GeminiAiProvider.java         (+BIAS_PROMPT, +analyzeBias 구현)
├── service/
│   ├── CollectionService.java         (+신규 기사 수집 시 bias PENDING 생성 호출)
│   ├── ArticleDetailService.java      (+biasScore 필드 조회·매핑)
│   └── ArticleFeedService.java        (+biasScore 필드 조회·매핑)
└── dto/response/
    ├── ArticleDetailResponse.java     (+BiasScoreResponse biasScore 필드)
    └── ArticleFeedItem.java           (+BiasScoreResponse biasScore 필드)

src/main/resources/
└── application.yaml                   (+app.scheduler.bias.* 설정)

src/test/java/com/newscurator/
├── service/BiasAnalysisServiceTest.java
├── repository/BiasAnalysisRepositoryTest.java
└── controller/BiasControllerTest.java
```

**Structure Decision**: Spring Boot 단일 프로젝트 구조 유지. 기존 패키지 레이아웃(CLAUDE.md) 준수.

---

## Implementation Details

### 1. V13 Migration

`data-model.md` DDL 참조. 핵심:
- `UNIQUE(article_id)` — FR-004 멱등성 보장
- Partial index on `(next_retry_at) WHERE status='PENDING'` — Claimer 배치 조회
- Partial index on `(failed_at) WHERE status='FAILED' AND attempt_count=3` — One-shot 복구
- `update_bias_analysis_updated_at()` trigger

### 2. BiasStatus Enum

```java
public enum BiasStatus { PENDING, PROCESSING, DONE, FAILED }
```

ProcessingStatus와 별도 생성. 4-state 필요 (PROCESSING: claimer 점유 중).

### 3. BiasAnalysis Entity 핵심 메서드

```java
// 005 NotificationOutbox.incrementAttemptWithBackoff() 패턴 재사용
// two-tx 모델: claim 시 next_retry_at을 NOW()+lease로 미뤄, 처리 중 크래시(PROCESSING 고아) 시
// lease 만료 후 claimer가 회수하도록 한다. 정상 완료/실패는 complete()/incrementAttemptWithBackoff()가 덮어씀.
public void claim(int leaseMinutes) {
    this.status = BiasStatus.PROCESSING;
    this.nextRetryAt = Instant.now().plus(leaseMinutes, ChronoUnit.MINUTES);
}
public void complete(int value, String[] keywords) {
    this.value = value; this.rationaleKeywords = keywords;
    this.status = BiasStatus.DONE; this.analyzedAt = Instant.now();
}
public void incrementAttemptWithBackoff(int attempt1Minutes, int attempt2Minutes) {
    this.attemptCount++;
    Instant now = Instant.now();
    if (this.attemptCount >= 3) {
        this.status = BiasStatus.FAILED; this.failedAt = now;
    } else {
        this.status = BiasStatus.PENDING;
        this.nextRetryAt = switch (this.attemptCount) {
            case 1 -> now.plus(attempt1Minutes, ChronoUnit.MINUTES);
            case 2 -> now.plus(attempt2Minutes, ChronoUnit.MINUTES);
            default -> now.plus(attempt2Minutes, ChronoUnit.MINUTES);
        };
    }
}
public void completeOneShot(int value, String[] keywords) { complete(value, keywords); }
public void failTerminal() {
    this.attemptCount++; // 3 → 4: recovery 인덱스 술어(attempt_count=3) 에서 영구 이탈, 루프 방지
    this.status = BiasStatus.FAILED;
}
```

### 4. BiasAnalysisRepository 핵심 쿼리

```java
// Claimer: PENDING + lease 만료된 PROCESSING(stuck) 행 회수, SKIP LOCKED
// two-tx 모델(005와 동일하게 claim TX 커밋 후 락 해제 → Gemini 호출은 락 밖):
//   - 정상 처리 중 행: claim()이 next_retry_at = NOW()+lease(미래)로 세팅했으므로 next_retry_at > NOW() → 재조회 안 됨
//   - 크래시로 고아가 된 PROCESSING 행: lease 경과 후 next_retry_at <= NOW() → 재claim되어 회수
@Query(value = """
    SELECT * FROM bias_analysis
    WHERE status IN ('PENDING', 'PROCESSING') AND next_retry_at <= NOW()
    ORDER BY next_retry_at ASC
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
List<BiasAnalysis> lockAndClaimPending(@Param("limit") int limit);

// One-shot 복구 대상
@Query(value = """
    SELECT * FROM bias_analysis
    WHERE status = 'FAILED' AND attempt_count = 3
      AND failed_at + INTERVAL '6 hours' <= NOW()
    LIMIT 1
    FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
Optional<BiasAnalysis> lockOneShotRecoveryCandidate();

// SC-001: done_ratio 측정 (7일 롤링, 수집 후 24h 경과)
@Query(value = """
    SELECT COUNT(*) FILTER (WHERE ba.status = 'DONE') * 100.0 / NULLIF(COUNT(*), 0)
    FROM bias_analysis ba
    JOIN articles a ON ba.article_id = a.id
    WHERE a.first_collected_at < NOW() - INTERVAL '24 hours'
      AND a.first_collected_at >= NOW() - INTERVAL '7 days'
    """, nativeQuery = true)
Double computeDoneRatio7Day();

// 당일 FAILED 전환 건수
@Query(value = """
    SELECT COUNT(*) FROM bias_analysis
    WHERE status = 'FAILED' AND attempt_count = 3
      AND CAST(failed_at AS DATE) = CURRENT_DATE
    """, nativeQuery = true)
long countFailedToday();

// Outlet 집계
@Query(value = """
    SELECT AVG(ba.value)::NUMERIC(5,2), COUNT(*)
    FROM bias_analysis ba
    JOIN article_sources aso ON ba.article_id = aso.article_id
    WHERE aso.source_id = :sourceId AND ba.status = 'DONE'
      AND ba.analyzed_at >= NOW() - INTERVAL '90 days'
    """, nativeQuery = true)
Object[] aggregateOutletBias(@Param("sourceId") Long sourceId);

// Backfill INSERT
@Modifying
@Query(value = """
    INSERT INTO bias_analysis (article_id, status, next_retry_at, created_at, updated_at)
    SELECT id, 'PENDING', NOW(), NOW(), NOW()
    FROM articles
    WHERE first_collected_at >= NOW() - INTERVAL '90 days'
    ON CONFLICT (article_id) DO NOTHING
    """, nativeQuery = true)
int backfillPending();
```

### 5. Gemini Bias 프롬프트

```java
private static final String BIAS_PROMPT =
    "다음 뉴스 기사의 정치적 편향성을 분석하세요. "
    + "반드시 아래 JSON 형식으로만 답하세요 (다른 텍스트 없이):\n"
    + "{\"score\": <-100에서 +100 사이 정수>, "
    + "\"keywords\": [\"키워드1\", \"키워드2\", ...]}\n"
    + "score: -100=극진보, 0=중립, +100=극보수 (한국 뉴스 생태계 기준)\n"
    + "keywords: 편향의 근거 키워드 2~5개\n"
    + "순수 사실 보도(날씨·스포츠 결과 등)는 score=0, keywords=[\"사실 보도\"]\n\n"
    + "제목: %s\n내용: %s";
```

JSON 파싱 실패 시 `AiProviderException` throw (재시도 대상 아님 — 결정적 오류).

### 6. BiasAnalysisService 핵심 흐름

**two-tx 모델 (005 NotificationOutboxProcessor/Claimer 패턴 재사용)**: claim TX를 별도 빈
`BiasAnalysisClaimer`로 분리해 PROCESSING+lease 커밋 후 락을 해제하고, Gemini HTTP 호출은
DB 락 밖에서 실행한다. 결과는 `persistResult()`가 자체 TX로 저장한다.

```
processBatch():  // BiasAnalysisProcessor (스케줄러 빈)
  Phase 1 — BiasAnalysisClaimer.claimBatch(batchSize) [@Transactional 별도 빈]:
            lockAndClaimPending(batchSize) → 각 행 claim(leaseMinutes): PROCESSING + next_retry_at=NOW()+lease
            → TX 커밋 시 FOR UPDATE SKIP LOCKED 락 해제
  Phase 2 — 각 BiasAnalysis 행 (DB 락 밖):
     a. article title+content 조회 (ArticleRepository)
     b. aiProvider.analyzeBias(title, content)   ← Gemini HTTP, 락 미점유
     c. 성공: complete(value, keywords) → DONE
     d. AiTransientException: incrementAttemptWithBackoff() + 배치 조기 중단(break)
     e. AiProviderException(결정적): incrementAttemptWithBackoff()
     f. log.warn per failure (FR-011)
  Phase 3 — BiasAnalysisClaimer.persistResult(row) [@Transactional 별도 빈]: 결과 저장
  종료 — 시작/종료/처리건수/실패건수 log.info (FR-011)

  ※ 처리 중 인스턴스 크래시: 행이 PROCESSING+lease 상태로 고아 → lease(next_retry_at) 만료 후
    다음 claimBatch가 회수(재처리). incrementAttemptWithBackoff가 적용되지 않으므로 attempt_count 보존.

recoverOneShotFailed():  // 동일 two-tx, claimer/persistResult 경유
  1. lockOneShotRecoveryCandidate() (status=FAILED, attempt_count=3, failed_at+6h 경과, SKIP LOCKED)
  2. claim(leaseMinutes) → PROCESSING + lease (attempt_count=3 유지 — 아직 임계치 내)
  3. analyzeBias() 시도 (락 밖)
  4. 성공: complete() → DONE (attempt_count=3, 문제없음)
  5. 실패: failTerminal() → attemptCount++ (3→4) + status=FAILED(terminal)
         attempt_count=4 이므로 recovery 인덱스 술어(attempt_count=3) 에서 영구 이탈 → 무한루프 없음
  ※ one-shot 처리 중 크래시: PROCESSING+attempt_count=3+lease → 일반 claimBatch가 lease 만료 후 회수.
    이후 실패 시 incrementAttemptWithBackoff로 attempt_count 3→4 → terminal FAILED로 수렴.

emitDailySlaMetrics():  // @Scheduled(cron="0 0 0 * * *")
  1. computeDoneRatio7Day()
  2. countFailedToday()
  3. log.info("[BIAS-SLA] done_ratio=..., failed_today=..., ...")
```

### 7. CollectionService 수정 포인트

기존 신규 기사 저장 후:
```java
// 신규 기사 저장 완료 후 bias PENDING 생성
biasAnalysisService.createPendingForArticle(savedArticle.getId());
```

`createPendingForArticle()`: `BiasAnalysis.builder().articleId(id).build()` → save. ON CONFLICT는 DB 레벨에서 처리 (`UNIQUE` 제약 → DataIntegrityViolationException catch → 무시).

### 8. ArticleFeedService / ArticleDetailService 수정

**N+1 방지**: 피드 기사 목록 조회 후 article ID 목록으로 `biasAnalysisRepository.findAllByArticleIdIn(ids)` 배치 조회, Map으로 매핑.

```java
// 피드 조회 후 bias 배치 조회
List<Long> ids = articles.stream().map(Article::getId).toList();
Map<Long, BiasAnalysis> biasMap = biasAnalysisRepository
    .findAllByArticleIdIn(ids)
    .stream()
    .collect(Collectors.toMap(BiasAnalysis::getArticleId, b -> b));

// ArticleFeedItem 변환 시
BiasAnalysis bias = biasMap.get(article.getId());
BiasScoreResponse biasScore = bias != null ? toBiasScore(bias) : null;
```

### 9. BiasController 엔드포인트

| 메서드·경로 | 서비스 호출 | 응답 |
|------------|------------|------|
| `GET /api/v1/articles/{id}/bias` | `biasService.getBiasForArticle(id)` | `ApiResponse.success(BiasScoreResponse)` |
| `GET /api/v1/bias/outlets/{sourceId}` | `biasService.getOutletBias(sourceId)` | `ApiResponse.success(OutletBiasResponse)` |
| `GET /api/v1/bias/spectrum` | `biasService.getSpectrum()` | `ApiResponse.success(BiasSpectrumResponse)` |
| `POST /api/v1/admin/bias/backfill` | `biasService.backfill()` | `ApiResponse.accepted(BackfillResult)` |

### 10. 설정 추가 (application.yaml)

```yaml
app:
  scheduler:
    bias:
      interval-ms: 60000
      batch-size: 10
      recovery-interval-ms: 3600000  # 1h
      backoff-attempt1-minutes: 5
      backoff-attempt2-minutes: 30
      lease-minutes: 5               # claim 후 PROCESSING 점유 유예 (크래시 stuck 회수 기준)
```

`lease-minutes`는 Gemini 호출 1건의 최대 소요 + 여유보다 크게 잡는다. 정상 처리는 lease 만료 전 끝나고,
크래시로 PROCESSING에 고아가 된 행만 lease 경과 후 회수된다(two-tx 모델).

---

## Complexity Tracking

| 사항 | 내용 |
|------|------|
| AiProvider 인터페이스 변경 | `analyzeBias()` 추가. 기존 mock 테스트는 인터페이스 기반이므로 영향 범위 = 테스트 stub 업데이트 필요 |
| FR-009 vs FR-005 중복 우려 | FR-005는 피드/상세 응답 포함, FR-009는 칩 탭 시 경량 전용 엔드포인트 — 클라이언트 UX 분리 의도적 |
| Outlet 집계 FK 경로 복잡도 | `bias_analysis → article_sources → sources` 두 단계 JOIN. SC-004 p95 ≤ 3s 미달 시 materialized view 전환 경로 준비 (Assumptions) |
| Backfill 5494건 | 정상 claimer 드레인 (batch-size=10, interval=60s) → 약 549 사이클, Gemini delayBetweenCallsMs 고려 시 수 시간 소요 예상 — 운영 배포 시 off-peak 실행 권장 |

---

## Open Items (plan 단계 미결)

| ID | 항목 | 담당 |
|----|------|------|
| OI-001 | SC-001 7일 롤링 주기 Jace 최종 확정 | Jace |
| OI-002 | SC-004 동시요청 수 측정조건 Jace 최종 확정 | Jace |
| OI-003 | article_sources(source_id) 기존 인덱스 존재 여부 확인 | 구현 시 V1 DDL 재확인 |
| OI-004 | Gemini 편향 프롬프트 ADR 작성 | tasks.md T 단계 포함 |
| OI-005 | Backfill 실행 시점 (배포 직후 admin 수동 vs 자동 startup) | 배포 계획 시 결정 |
