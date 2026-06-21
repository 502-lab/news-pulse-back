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

**Decision**: BiasAnalysisScheduler 내 별도 `@Scheduled(fixedDelayString)` 메서드. 쿼리: `WHERE status = 'FAILED' AND attempt_count = 3 AND failed_at + INTERVAL '6 hours' <= NOW()`. 클레임 후 attempt_count = 4로 한 번만 시도, 성공→DONE, 실패→terminal FAILED(attempt_count=4).

**Rationale**: attempt_count=4 상태가 terminal. 별도 `recoveredAt` 컬럼 없이 attempt_count로 구분 가능.

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

기존 `AiProperties.delayBetweenCallsMs` 재사용 (Gemini 호출 간 딜레이).
