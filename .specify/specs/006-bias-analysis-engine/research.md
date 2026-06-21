# Research: 006 편향 분析 엔진

**Date**: 2026-06-21

---

## R-001 BiasStatus Enum 분리

**Decision**: `ProcessingStatus`를 재사용하지 않고 신규 `BiasStatus` enum 생성.

**Rationale**: Spec이 PENDING/PROCESSING/DONE/FAILED 4-state를 요구하지만 기존 `ProcessingStatus`는 PENDING/COMPLETED/FAILED 3-state만 존재한다. PROCESSING 상태(claimer가 점유했지만 Gemini 호출 전)와 DONE 상태를 명확히 분리해야 SKIP LOCKED claimer 패턴에서 재처리 방지가 가능하다.

**Alternatives**: ProcessingStatus에 PROCESSING/DONE 추가 → 기존 summary/category 처리 코드 영향 범위가 넓고 편향 분析 전용 의미가 섞임.

---

## R-002 rationale_keywords 저장 포맷

**Decision**: PostgreSQL `TEXT[]` 컬럼, Hibernate `@JdbcTypeCode(SqlTypes.ARRAY)` 매핑, Java `String[]`.

**Rationale**: 2~5개 소규모 배열로 별도 조인 테이블 불필요. Spring Boot 4 + Hibernate 6에서 `SqlTypes.ARRAY` 지원 안정. JSONB 대비 쿼리 단순성 우위.

**Alternatives**: JSONB → 스키마 유연하나 배열 원소 타입 검증 약함. 별도 `bias_rationale_keywords` 조인 테이블 → 오버엔지니어링.

---

## R-003 Gemini 응답 포맷 — JSON 구조화

**Decision**: 프롬프트가 JSON `{"score": <정수 -100~+100>, "keywords": ["k1","k2",...]}` 형식으로만 답하도록 지시. GeminiAiProvider에서 JSON 파싱.

**Rationale**: 기존 분류(카테고리) 프롬프트는 단순 문자열 반환이나 편향은 복합 값(숫자+배열)이 필요하다. JSON은 파싱 신뢰성이 가장 높고, Gemini Flash는 JSON 출력 제어에 안정적.

**Alternatives**: 첫 줄 점수 + 나머지 줄 키워드 → 파싱 fragile. XML → 불필요한 복잡성.

**ADR 작성 필요**: `/specs/adr/` — CLAUDE.md 요구사항. tasks.md T단계에서 작성.

---

## R-004 AiProvider 인터페이스 확장

**Decision**: `AiProvider` 인터페이스에 `BiasAnalysisResult analyzeBias(String title, String content)` 추가. `BiasAnalysisResult`는 `client/ai/` 패키지 내 record.

**Rationale**: 기존 포트-어댑터 패턴 유지. Mock 테스트 시 인터페이스만 stub.

---

## R-005 Outlet 집계 FK 경로 및 인덱스

**Decision**: 집계 경로 `bias_analysis.article_id → article_sources.article_id → sources.id`. 인덱스: `bias_analysis (article_id, value) WHERE status='DONE'` + `article_sources (source_id)` (기존 존재 여부 확인 필요).

**Rationale**: Article ↔ Source 관계가 `article_sources` 다대다 테이블을 통함. Outlet 집계 쿼리: `SELECT AVG(ba.value), COUNT(*) FROM bias_analysis ba JOIN article_sources aso ON ba.article_id = aso.article_id WHERE aso.source_id = :sourceId AND ba.status = 'DONE'`.

**Scale note**: 규모 증가 시 `bias_analysis (source_id)` 파생 컬럼 또는 materialized view 전환(Assumptions 참조).

---

## R-006 One-Shot FAILED 복구 스케줄

**Decision**: BiasAnalysisScheduler 내 별도 `@Scheduled(fixedDelayString)` 메서드. 쿼리: `WHERE status = 'FAILED' AND attempt_count = 3 AND failed_at + INTERVAL '6 hours' <= NOW()`. claim() 후 attempt_count=3 유지인 채 analyzeBias() 1회 시도:
- 성공 → complete(): status=DONE, attempt_count=3 (정상)
- 실패 → failTerminal(): `this.attemptCount++` (3→4) + status=FAILED(terminal)
  → attempt_count=4이므로 recovery 인덱스 술어 `attempt_count=3`에서 영구 이탈 → **무한 복구 루프 없음**

**Rationale**: attempt_count=4 상태가 terminal 식별자. failTerminal()에서 증가시키는 이유: claim() 시점에 올리면 성공 케이스도 attempt_count=4로 남아 오해 소지. 실패 시점(failTerminal)에 올리는 것이 의미 명확. 별도 `recoveredAt` 컬럼 없이 attempt_count로 구분 가능.

---

## R-007 Backfill 전략

**Decision**: `POST /api/v1/admin/bias/backfill` 엔드포인트. 내부적으로 `INSERT INTO bias_analysis (article_id, status, next_retry_at, ...) SELECT id, 'PENDING', NOW(), ... FROM articles WHERE first_collected_at >= NOW() - INTERVAL '90 days' ON CONFLICT (article_id) DO NOTHING`. 반환: `{created: N}`.

**Rationale**: 멱등, 별도 버스트 없음. 삽입 후 정상 claimer가 rate-safe하게 드레인. AdminPipelineController 패턴 재사용.

---

## R-008 Daily SLA Emit 스케줄

**Decision**: `BiasAnalysisScheduler.emitDailySlaMetrics()` — `@Scheduled(cron = "0 0 0 * * *")` (자정 UTC). 쿼리: 최근 7일 롤링 기준 `first_collected_at < NOW() - INTERVAL '24 hours'` 기사 중 `status = 'DONE'` 비율 + 당일 FAILED 전환 건수.

**Rationale**: FR-012 요구: 일 1회 SLA 지표 구조적 로그. 배치 실행별 카운트(FR-011)와 분리.

---

## R-009 동시성 안전

**Decision**: `UNIQUE(article_id)` 제약 + `FOR UPDATE SKIP LOCKED` claimer 패턴으로 커버. 005 pattern 재사용.

**Rationale**: Spec Clarification Q3(동시성): SKIP LOCKED claim + UNIQUE(article_id)로 커버됨 — 별도 낙관적 잠금 불필요.

---

## R-010 Article 응답 DTO 수정

**Decision**: `ArticleDetailResponse`와 `ArticleFeedItem`에 `BiasScoreResponse biasScore` 필드 추가. 분析 미완료/없음 시 `biasScore: null`.

`BiasScoreResponse`: `Integer value` (DONE 시), `List<String> rationaleKeywords` (DONE 시), `String status` (PENDING/PROCESSING/DONE/FAILED).

**Rationale**: FR-005 — 기존 API에 biasScore 포함. 클라이언트가 status 보고 칩 표시 여부 결정.

---

## R-011 FR-009 — 편향 칩 전용 API

**Decision**: `GET /api/v1/articles/{articleId}/bias` — `BiasScoreResponse` 반환. FR-005(피드/상세 응답 포함)와 별도로 칩 탭 시 경량 조회용.

**Rationale**: 피드에서 칩 탭할 때 전체 기사 상세 재조회 없이 bias 데이터만 가져오는 UX 최적화. JWT 필수(FR-009).

---

## R-012 Configuration 추가 사항

기존 `app.ai.*` 프로퍼티와 별도로 bias 전용 배치 설정 필요:
- `app.scheduler.bias.interval-ms` — 편향 분析 배치 주기 (기본 60000ms)
- `app.scheduler.bias.batch-size` — 배치 크기 (기본 10)
- `app.scheduler.bias.recovery-interval-ms` — one-shot recovery 폴 주기 (기본 3600000ms = 1h)
- `app.scheduler.bias.backoff-attempt1-minutes` — 5 (1회 실패 후 딜레이)
- `app.scheduler.bias.backoff-attempt2-minutes` — 30 (2회 실패 후 딜레이)
- `app.scheduler.bias.lease-minutes` — 5 (claim 후 PROCESSING 점유 lease; 크래시 stuck 회수 기준, R-013)

기존 `AiProperties.delayBetweenCallsMs` 재사용 (Gemini 호출 간 딜레이).

---

## R-013 Claimer 트랜잭션 모델 — two-tx + lease

**Decision**: 006 편향 분析 파이프라인은 **two-tx 모델**을 채택한다.
- Phase 1 `BiasAnalysisClaimer.claimBatch()` (별도 @Transactional 빈): `lockAndClaimPending` → 각 행 `claim(leaseMinutes)` (PROCESSING + `next_retry_at = NOW() + lease`) → 커밋 시 FOR UPDATE SKIP LOCKED 락 해제.
- Phase 2: Gemini HTTP 호출은 DB 락 밖에서 실행.
- Phase 3 `persistResult()` (별도 @Transactional 빈): 결과 저장.

stuck 회수: 처리 중 인스턴스 크래시로 PROCESSING에 고아가 된 행은 lease(`next_retry_at`) 만료 후 일반 `claimBatch`가 재claim한다. claimer 인덱스/쿼리 술어는 `status IN ('PENDING','PROCESSING') AND next_retry_at <= NOW()`.

**005 코드 확인 근거**:
- `NotificationOutboxClaimer.claimBatch()` = 별도 @Transactional 빈, `markProcessing()` 후 커밋 → "TX가 커밋되면 FOR UPDATE SKIP LOCKED 락이 해제되고, 이후 FCM/Email HTTP 호출이 DB 락 없이 실행"(클래스 Javadoc). → **two-tx 확정**.
- `NotificationOutboxProcessor.process()` = Phase 1 claim / Phase 2 HTTP / Phase 3 persistResult 3단계.

**005와의 차이(개선점)**:
- 005 `findPendingWithLock`은 `WHERE status = 'PENDING' AND next_retry_at <= now()` — **PENDING-only**이고 claim 시 `next_retry_at`을 미루지 **않는다**(lease 없음). 따라서 005는 markProcessing 후 크래시하면 PROCESSING 행이 영구 고아가 되며 회수 메커니즘이 없다(알림은 손실 허용 도메인).
- 006은 편향 점수 누락이 SC-001(95% DONE)에 직접 영향을 주므로, lease + PENDING/PROCESSING 회수를 추가해 stuck 행을 자동 회수한다.

**Rationale**: single-tx(claim+Gemini를 한 TX에서 락 잡은 채)면 Gemini 호출 동안 행 락이 유지되어 동시성·DB 커넥션 점유가 악화된다. 005가 two-tx로 외부 호출을 락 밖으로 뺀 설계를 그대로 따르되, 006은 lease를 더해 two-tx의 고아 위험을 보강한다.

**Alternatives**: single-tx → 크래시가 PENDING으로 자동 롤백되어 인덱스를 PENDING-only로 둘 수 있으나, Gemini 호출 동안 락·커넥션 장기 점유. 채택 안 함.
