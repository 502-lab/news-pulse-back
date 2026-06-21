# Tasks: 편향 분석 엔진

**Input**: Design documents from `.specify/specs/006-bias-analysis-engine/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/openapi-patch.yaml, quickstart.md

**Tests**: 포함됨 — CLAUDE.md 규칙(Service 단위테스트 필수, Repository @DataJpaTest, Controller @WebMvcTest, 외부 API Mock 필수) + Constitution IV(테스트 없는 비즈니스 로직 금지) 강제.

**Organization**: 사용자 스토리별 단계 구성. 통합 테스트는 `BigmPostgresImage.NAME` 컨테이너 사용, Gemini는 WireMock 스텁.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 실행 가능 (서로 다른 파일, 미완료 의존성 없음)
- **[Story]**: US1/US2/US3/US4
- 모든 경로는 repo 루트 기준 절대 경로 아님(프로젝트 상대)

## Path Conventions

- 단일 Spring Boot 프로젝트: `src/main/java/com/newscurator/`, `src/test/java/com/newscurator/`
- 마이그레이션: `src/main/resources/db/migration/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 빌드 가능 상태 + OpenAPI 선반영

- [ ] T001 contracts/openapi-patch.yaml의 신규 경로·스키마를 news-pulse-spec 레포 openapi.yaml에 선반영 제안 (CLAUDE.md: 엔드포인트 변경 시 openapi.yaml 선반영 필수). 4개 경로(`/articles/{id}/bias`, `/bias/outlets/{sourceId}`, `/bias/spectrum`, `/admin/bias/backfill`) + BiasScoreResponse/OutletBiasResponse/BiasSpectrumResponse 스키마
- [X] T002 [P] `app.scheduler.bias.*` 설정 키를 `src/main/resources/application.yaml`에 추가 (interval-ms, batch-size, recovery-interval-ms, backoff-attempt1/2-minutes, lease-minutes) + `application-example.yaml` 동기화
- [X] T003 [P] `BiasProperties` 설정 record를 `src/main/java/com/newscurator/config/BiasProperties.java`에 생성 (`@ConfigurationProperties(prefix = "app.scheduler.bias")`), `NewsCuratorApplication` 또는 config 클래스에 `@EnableConfigurationProperties` 등록 확인

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리가 의존하는 엔티티·마이그레이션·enum·repository. **이 단계 완료 전 어떤 US도 시작 불가.**

⚠️ **CRITICAL**: BiasAnalysis 테이블/엔티티가 US1(조회)·US2(생산)·US3·US4 모두의 기반.

- [X] T004 `BiasStatus` enum 생성 in `src/main/java/com/newscurator/domain/enums/BiasStatus.java` (PENDING, PROCESSING, DONE, FAILED)
- [X] T005 Flyway 마이그레이션 `V13__add_bias_analysis.sql` 작성 in `src/main/resources/db/migration/` — data-model.md DDL 전체: bias_analysis 테이블 + `uq_bias_analysis_article_id`(UNIQUE) + `idx_bias_analysis_pending_queue`(PENDING OR PROCESSING) + `idx_bias_analysis_failed_recovery`(FAILED, attempt_count=3) + `idx_bias_analysis_done_analyzed` + `idx_article_sources_source_id` + updated_at 트리거
- [X] T006 `BiasAnalysis` 엔티티 생성 in `src/main/java/com/newscurator/domain/BiasAnalysis.java` — 필드(articleId, status, value, rationaleKeywords[String[] @JdbcTypeCode(SqlTypes.ARRAY)], attemptCount, nextRetryAt, analyzedAt, failedAt, createdAt, updatedAt) + 비즈니스 메서드: `claim(int leaseMinutes)`, `complete(int, String[])`, `incrementAttemptWithBackoff(int, int)`, `completeOneShot(int, String[])`, `failTerminal()` (plan.md §3 verbatim 로직)
- [X] T007 `BiasAnalysisRepository` 생성 in `src/main/java/com/newscurator/repository/BiasAnalysisRepository.java` — `lockAndClaimPending`(SKIP LOCKED, PENDING+PROCESSING+next_retry_at<=NOW), `lockOneShotRecoveryCandidate`(FAILED+attempt_count=3+failed_at+6h), `findAllByArticleIdIn`, `findByArticleId`, `aggregateOutletBias`, spectrum 집계 쿼리, `computeDoneRatio7Day`, `countFailedToday`, `backfillPending`(INSERT ON CONFLICT DO NOTHING) — plan.md §4 verbatim 쿼리
- [X] T008 [P] `BiasAnalysisResult` record 생성 in `src/main/java/com/newscurator/client/ai/BiasAnalysisResult.java` (`int value`, `List<String> rationaleKeywords`)
- [X] T009 `AiProvider` 인터페이스에 `BiasAnalysisResult analyzeBias(String title, String content)` 추가 in `src/main/java/com/newscurator/client/ai/AiProvider.java` + 기존 mock/stub 구현체 시그니처 갱신 확인
- [X] T010 `BiasAnalysisRepositoryTest` 작성 in `src/test/java/com/newscurator/repository/BiasAnalysisRepositoryTest.java` (@DataJpaTest, `BigmPostgresImage.NAME`) — SKIP LOCKED claim·UNIQUE 멱등·집계 쿼리·backfill ON CONFLICT 검증

**Checkpoint**: 엔티티·마이그레이션·repository 준비 완료 — US 구현 시작 가능

---

## Phase 3: User Story 2 — 비동기 편향 분석 파이프라인 (Priority: P1) 🎯 MVP 기반

**Goal**: 새 기사 수집 시 PENDING 생성, 스케줄러가 two-tx + lease로 클레임 후 Gemini 호출하여 점수·키워드 저장. 3회 실패 시 FAILED + 6h one-shot 복구.

**Independent Test**: 기사 수집 → bias_analysis PENDING 생성 → 스케줄러 처리 → value·rationale_keywords 저장·DONE 전환 확인 (quickstart Scenario 1·2·3·4·9).

> **Note**: US1(조회)은 US2가 생산한 데이터를 노출하지만, US1 자체는 DB에 행을 수동 삽입해도 검증 가능. spec 우선순위상 US1=P1이나 데이터 생산 인프라가 선행되어야 실데이터 흐름이 성립하므로 US2를 먼저 배치. 두 스토리 모두 P1.

### Gemini 클라이언트

- [X] T011 [US2] `GeminiAiProvider.analyzeBias()` 구현 in `src/main/java/com/newscurator/client/ai/GeminiAiProvider.java` — BIAS_PROMPT(JSON 출력 지시, plan.md §5 verbatim) + JSON 파싱(score/keywords) + 범위 검증(−100~+100, 키워드 2~5개) + 파싱 실패 시 `AiProviderException`(결정적) + 429/5xx/타임아웃 시 기존 `AiTransientException` 재사용
- [X] T012 [US2] `GeminiAiProvider.analyzeBias` 단위 테스트 in `src/test/java/com/newscurator/client/ai/GeminiAiProviderBiasTest.java` (WireMock 스텁, 경로 `/v1beta/models/{model}:generateContent`) — 정상 JSON·범위 초과·파싱 실패·429/5xx 케이스 + Gemini 프롬프트 ADR 작성 `/specs/adr/` (CLAUDE.md: 프롬프트 변경 시 ADR 필수)

### 파이프라인 서비스 (two-tx)

- [X] T013 [US2] `BiasAnalysisClaimer` 생성 in `src/main/java/com/newscurator/scheduler/BiasAnalysisClaimer.java` — 별도 `@Transactional` 빈: `claimBatch(int)`(lockAndClaimPending → 각 행 claim(leaseMinutes) → 커밋), `persistResult(BiasAnalysis)` (R-013 two-tx 모델, 005 NotificationOutboxClaimer 패턴)
- [X] T014 [US2] `BiasAnalysisService` 생성 in `src/main/java/com/newscurator/service/BiasAnalysisService.java` — `processBatch()`(Phase1 claimer.claimBatch → Phase2 락 밖 Gemini 호출 → 성공 complete / AiTransientException break / AiProviderException incrementAttemptWithBackoff → Phase3 persistResult), `recoverOneShotFailed()`(lockOneShotRecoveryCandidate → claim → 성공 complete / 실패 failTerminal), `createPendingForArticle(Long)`(멱등, UNIQUE 위반 catch), `backfill()`, `emitDailySlaMetrics()` — plan.md §6 흐름 verbatim. FR-011 log.info/log.warn(민감정보 제외)
- [X] T015 [US2] `BiasAnalysisScheduler` 생성 in `src/main/java/com/newscurator/scheduler/BiasAnalysisScheduler.java` — `@ConditionalOnProperty(app.scheduler.enabled)` + MDC runId 패턴(AiProcessingScheduler 참조): `run()`(@Scheduled fixedDelay interval-ms → processBatch), `recover()`(@Scheduled fixedDelay recovery-interval-ms → recoverOneShotFailed), `emitSla()`(@Scheduled cron="0 0 0 * * *" → emitDailySlaMetrics)
- [X] T016 [US2] 신규 기사 수집 시 bias PENDING 생성 연결 in `src/main/java/com/newscurator/service/CollectionService.java` — 신규 Article 저장 후 `biasAnalysisService.createPendingForArticle(savedArticle.getId())` 호출 (FR-001)

### 테스트

- [X] T017 [US2] `BiasAnalysisServiceTest` 단위 테스트 in `src/test/java/com/newscurator/service/BiasAnalysisServiceTest.java` (AiProvider mock) — 정상 처리·재시도 백오프(+5m/+30m)·3회 소진 FAILED·one-shot 복구 성공/실패(attempt_count 3→4 terminal)·멱등 createPending·SLA emit 검증
- [X] T018 [US2] 파이프라인 통합 테스트 in `src/test/java/com/newscurator/service/BiasAnalysisPipelineIT.java` (`BigmPostgresImage.NAME` + Gemini WireMock) — 수집→PENDING→DONE end-to-end, backfill ON CONFLICT 멱등 + rate-safe 드레인(PENDING 일괄 생성 후 정상 claimer가 batch-size씩 소비, live 굶기지 않음) (quickstart Scenario 1·2·8)
- [X] T044 [US2] one-shot 복구 **전용** 통합 테스트 in `src/test/java/com/newscurator/service/BiasOneShotRecoveryIT.java` (`BigmPostgresImage.NAME` + Gemini WireMock) — lease 회수와 **별개** 경로 검증: (a) status=FAILED·attempt_count=3·failed_at+6h **미경과** → 복구 대상 아님, (b) failed_at+6h **경과** → recoverOneShotFailed 1회 시도, (c) 성공 → completeOneShot → DONE(attempt_count=3 유지), (d) 실패 → failTerminal → attempt_count=4·status=FAILED(terminal) → recovery 술어(attempt_count=3) 재매칭 안 됨(무한루프 없음) 검증 (quickstart Scenario 9)
- [X] T045 [US2] two-tx claimer 동시성·lease 회수 **전용** 테스트 in `src/test/java/com/newscurator/scheduler/BiasClaimerConcurrencyIT.java` (`BigmPostgresImage.NAME`) — (a) 2워커 동시 claimBatch → 동일 행 1회만 claim(FOR UPDATE SKIP LOCKED 배타성, SC-005·Constitution VII), (b) in-flight 보호: claim 직후 next_retry_at=NOW()+lease로 미래라 동시 재조회 제외, (c) lease 만료 stuck 회수: PROCESSING + next_retry_at<=NOW() 행이 다음 claimBatch에 재집계됨

**Checkpoint**: 편향 데이터 생산 파이프라인 동작 (동시성·one-shot 복구 전용 검증 포함). US1이 노출할 실데이터 확보.

---

## Phase 4: User Story 1 — 기사 편향 점수·근거 키워드 조회 (Priority: P1) 🎯 MVP

**Goal**: 피드·상세 응답에 biasScore 포함(분석 미완료 시 null), 편향 칩 전용 API 제공.

**Independent Test**: DONE 기사의 피드/상세 응답에 `biasScore.value`·`rationaleKeywords` 포함, 미완료 기사는 `biasScore:null`, 칩 API가 점수 반환 (quickstart Scenario 2·5).

### DTO

- [X] T019 [P] [US1] `BiasScoreResponse` record 생성 in `src/main/java/com/newscurator/dto/response/BiasScoreResponse.java` (`Integer value`, `List<String> rationaleKeywords`, `String status`) + @Schema 문서화 (data-model.md)
- [X] T020 [US1] `ArticleFeedItem`에 `BiasScoreResponse biasScore` 필드 추가 in `src/main/java/com/newscurator/dto/response/ArticleFeedItem.java` (@Schema: 분석 미완료 시 null)
- [X] T021 [US1] `ArticleDetailResponse`에 `BiasScoreResponse biasScore` 필드 추가 in `src/main/java/com/newscurator/dto/response/ArticleDetailResponse.java` (@Schema)

### 서비스 매핑 (N+1 방지)

- [X] T022 [US1] `ArticleFeedService`에 biasScore 매핑 추가 in `src/main/java/com/newscurator/service/ArticleFeedService.java` — 피드 기사 ID 목록으로 `findAllByArticleIdIn` 배치 조회 후 Map 매핑(N+1 방지, plan.md §8), 행 없으면 null
- [X] T023 [US1] `ArticleDetailService`에 biasScore 매핑 추가 in `src/main/java/com/newscurator/service/ArticleDetailService.java` — `findByArticleId` 단건 조회, status별 value/keywords null 처리
- [X] T024 [US1] `BiasAnalysisService.getBiasForArticle(Long)` 추가 in `src/main/java/com/newscurator/service/BiasAnalysisService.java` — 칩 API용, 행 없으면 404(ResourceNotFoundException), 미완료 시 status만 채운 BiasScoreResponse 반환

### 컨트롤러

- [X] T025 [US1] `BiasController` 생성 + `GET /api/v1/articles/{articleId}/bias` in `src/main/java/com/newscurator/controller/BiasController.java` — JWT 필수(FR-009), @Tag/@Operation/@ApiResponses/@Parameter Swagger 문서화, `ApiResponse.success(BiasScoreResponse)`

### 테스트

- [X] T026 [P] [US1] `BiasControllerTest` in `src/test/java/com/newscurator/controller/BiasControllerTest.java` (@WebMvcTest, service mock) — 칩 API 200/401/404, biasScore 직렬화(DONE 값/미완료 null) 검증
- [X] T027 [US1] 피드·상세 biasScore 노출 통합 검증 in `src/test/java/com/newscurator/service/ArticleBiasExposureTest.java` (또는 기존 Feed/Detail 테스트 확장) — SC-002(필드 항상 포함, status≠DONE은 null) 검증

**Checkpoint**: MVP 완성 — 사용자가 편향 점수·근거를 피드/상세/칩에서 확인 가능.

---

## Phase 5: User Story 3 — 출처(Outlet) 편향 집계 조회 (Priority: P2)

**Goal**: 출처별 편향 단순평균(롤링 90일, 최소 10건)·분석완료 기사 수 제공.

**Independent Test**: 분석완료 10건+ 출처의 집계 API가 biasValue·articleCount 반환, 10건 미만 시 biasValue null (quickstart Scenario 6).

- [X] T028 [P] [US3] `OutletBiasResponse` record 생성 in `src/main/java/com/newscurator/dto/response/OutletBiasResponse.java` (sourceId, biasValue[Double, 최소10건 미달 null], articleCount) + @Schema
- [X] T029 [US3] `BiasAnalysisService.getOutletBias(Long sourceId)` 추가 in `src/main/java/com/newscurator/service/BiasAnalysisService.java` — aggregateOutletBias 호출, 최소 10건 미만 biasValue null, 출처 없으면 404
- [X] T030 [US3] `GET /api/v1/bias/outlets/{sourceId}` 추가 in `src/main/java/com/newscurator/controller/BiasController.java` — JWT 필수(FR-006), Swagger 문서화
- [X] T031 [P] [US3] 컨트롤러 테스트 추가 in `BiasControllerTest` — outlet 200/401/404, 10건 미만 null 케이스
- [X] T032 [US3] 집계 정확성 통합 테스트 in `src/test/java/com/newscurator/service/OutletBiasAggregationIT.java` (`BigmPostgresImage.NAME`) — 단순평균·롤링 90일·최소 10건 경계·DONE만 포함 검증, EXPLAIN으로 idx_article_sources_source_id 사용 확인(SC-004)

**Checkpoint**: 출처 편향 집계 동작.

---

## Phase 6: User Story 4 — 전체 편향 스펙트럼 조회 (Priority: P2)

**Goal**: 서비스 전체 분석완료 기사의 가중평균·진보/중립/보수 % 제공.

**Independent Test**: 다양한 점수 분포에서 스펙트럼 API가 가중평균·3버킷 % 합산 100% 반환, 기사 없으면 빈 응답 (quickstart Scenario 7).

- [X] T033 [P] [US4] `BiasSpectrumResponse` record 생성 in `src/main/java/com/newscurator/dto/response/BiasSpectrumResponse.java` (weightedAverage, liberalPercent, neutralPercent, conservativePercent, totalCount) + @Schema
- [X] T034 [US4] `BiasAnalysisService.getSpectrum()` 추가 in `src/main/java/com/newscurator/service/BiasAnalysisService.java` — spectrum 집계 쿼리(버킷 진보[−100,−34]/중립[−33,+33]/보수[+34,+100]), 기사 0건 시 모든 값 null/0
- [X] T035 [US4] `GET /api/v1/bias/spectrum` 추가 in `src/main/java/com/newscurator/controller/BiasController.java` — JWT 필수(FR-007), Swagger 문서화
- [X] T036 [US4] 스펙트럼 집계 테스트 in `src/test/java/com/newscurator/service/BiasSpectrumTest.java` — 버킷 경계값(−34→진보, +34→보수) 정확 분류, % 합산 100%, 빈 응답 검증

**Checkpoint**: 전체 스펙트럼 조회 동작.

---

## Phase 7: Admin & Polish (Cross-Cutting)

**Purpose**: Backfill 운영 엔드포인트 + 마무리.

- [ ] T037 [P] `BackfillResult` record 생성 in `src/main/java/com/newscurator/dto/response/BackfillResult.java` (`long created`) + @Schema
- [ ] T038 `POST /api/v1/admin/bias/backfill` 추가 in `src/main/java/com/newscurator/controller/BiasController.java` 또는 기존 `AdminPipelineController` — ROLE_ADMIN 필수, `biasAnalysisService.backfill()` → `ApiResponse.accepted(BackfillResult)`, Swagger 문서화
- [ ] T039 [P] backfill 멱등 컨트롤러 테스트 (@WebMvcTest) — 202/401/403, 2회 호출 시 두 번째 created=0
- [ ] T040 [P] SecurityConfig 경로 인증 규칙 확인 in `src/main/java/com/newscurator/config/SecurityConfig.java` — `/api/v1/bias/**`·`/api/v1/articles/*/bias` JWT 필수, `/api/v1/admin/bias/**` ROLE_ADMIN (FR-005는 기존 피드/상세 설정 상속이므로 변경 없음 확인)
- [ ] T041 CHANGELOG.html 항목 추가 (tag-feature, 영향 파일·결정 이유 — two-tx lease/one-shot 복구 설계 근거 포함)
- [ ] T042 quickstart.md 9개 시나리오 수동 검증 — Docker 기동 후 실제 Gemini 연동 1건 확인 또는 "런타임/배포 시 검증" 명시 (CLAUDE.md 외부 연동 검증 규칙)
- [ ] T043 OpenApiSpecExportTest 통과 확인 → sync-openapi 워크플로우로 news-pulse-spec 반영

---

## Dependencies & Execution Order

### Phase 의존성

- **Setup (P1)**: 의존 없음 — 먼저
- **Foundational (P2)**: Setup 완료 후. **모든 US 차단** (BiasAnalysis 엔티티/repo/마이그레이션)
- **US2 (Phase 3)**: Foundational 완료 후. 데이터 생산 — US1 실데이터의 선행
- **US1 (Phase 4)**: Foundational 완료 후 시작 가능. 실데이터 검증은 US2 필요(단위는 mock으로 독립)
- **US3 (Phase 5)**: Foundational + US2 데이터 필요. US1과 독립 병렬 가능
- **US4 (Phase 6)**: Foundational + US2 데이터 필요. US3과 독립 병렬 가능
- **Polish (Phase 7)**: 모든 US 후

### Story 독립성

- US1 ↔ US3 ↔ US4: 서로 독립 (BiasController에 메서드 추가로 파일 공유 — 순차 머지 권장하나 로직 독립)
- US2: 데이터 생산 인프라 (US1/3/4가 소비)

### 병렬 기회

- **Setup**: T002, T003 병렬
- **Foundational**: T008 병렬 (T004~T007은 엔티티 의존 체인)
- **US2 테스트**: T044(one-shot)·T045(동시성) 병렬 (서로 다른 테스트 파일, T013·T014 구현 완료 후)
- **US1**: T019·T026 병렬 (DTO/테스트)
- **US3**: T028·T031 병렬
- **US4**: T033 병렬 시작
- **US3 ↔ US4 전체**: Foundational+US2 완료 후 두 스토리 팀 분담 병렬
- **Polish**: T037·T039·T040 병렬

---

## Implementation Strategy

### MVP 범위 (최소 배포 단위)

**Phase 1 + 2 + 3(US2) + 4(US1)** = 편향 점수 생산 + 조회. 이것만으로 "독자가 기사 편향 점수·근거를 확인한다"는 핵심 가치(US1 P1) 달성.

### 증분 전달

1. **1차**: Setup → Foundational → US2 → US1 = MVP (편향 점수 생산·노출·칩)
2. **2차**: US3(출처 집계) + US4(스펙트럼) 병렬 — P2 상위 뷰
3. **3차**: Polish(backfill 운영, CHANGELOG, OpenAPI sync, 런타임 검증)

### Open Items 반영 (plan.md)

- OI-001(SC-001 7일 주기), OI-002(SC-004 동시요청 수): Jace 확정 대기 — 측정 코드는 구현하되 임계 판정은 TODO
- OI-004(Gemini 프롬프트 ADR): T012에 포함
- OI-005(Backfill 실행 시점): T038 admin 수동 트리거로 구현, 자동 startup 미채택
