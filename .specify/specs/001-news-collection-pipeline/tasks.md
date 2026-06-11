# Tasks: 뉴스 수집·큐레이션 파이프라인과 카테고리 피드

**Feature**: 001-news-collection-pipeline  
**Plan**: [plan.md](plan.md) | **Spec**: [spec.md](spec.md) | **Data Model**: [data-model.md](data-model.md)  
**Contracts**: [contracts/openapi.yaml](contracts/openapi.yaml)  
**Date**: 2026-06-09

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: 동일 Phase 내 다른 파일을 건드리므로 병렬 진행 가능
- **[US1–US4]**: 어느 User Story에 속하는지
- 테스트 태스크가 구현 태스크보다 **앞에** 배치됨 (constitution Principle IV)

---

## Phase A: 기반 인프라 (모든 User Story 블로킹)

**목적**: Flyway 마이그레이션, 도메인 모델, 설정 바인딩, 전역 예외 처리 완비.  
이 Phase가 완료되기 전에는 어떤 User Story도 시작할 수 없다.

- [X] T001 Flyway 설정 + V1__create_core_tables.sql 작성: sources(call_budget_daily 포함), articles, article_sources, summaries(last_attempt_at·retry_count DEEP 재시도 추적 컬럼 포함), source_daily_usage 5개 테이블 + 6개 partial index + updated_at trigger in `src/main/resources/db/migration/V1__create_core_tables.sql`
- [X] T002 [P] V2__seed_initial_sources.sql 작성: 연합뉴스·YTN·한겨레 RSS 3개 + 네이버-경제 NAVER 1개 초기 출처 삽입 in `src/main/resources/db/migration/V2__seed_initial_sources.sql`
- [X] T003 도메인 enum 4개 생성: Category(displayName() 포함), ProcessingStatus, SummaryDepth, SummarySlotStatus in `src/main/java/com/newscurator/domain/enums/`
- [X] T004 [P] JPA Entity Source.java 생성 (call_budget_daily 컬럼 포함, last_collected_at, consecutive_failure_count) in `src/main/java/com/newscurator/domain/Source.java`
- [X] T005 [P] JPA Entity Article.java 생성 (category_status, summary_status, category/summary_retry_count, expires_at, feed_visible, user_saved — CHK004: user_saved는 단일 boolean, 다중 사용자 저장 추적은 spec 002 범위) in `src/main/java/com/newscurator/domain/Article.java`
- [X] T006 [P] JPA Entity ArticleSource.java 생성 (is_merge 필드, UNIQUE(article_id, source_id)) in `src/main/java/com/newscurator/domain/ArticleSource.java`
- [X] T007 [P] JPA Entity Summary.java 생성 (depth, status=SummarySlotStatus, content nullable, generated_at nullable, last_attempt_at nullable, retry_count=0 — DEEP 슬롯 백오프 추적 전용, BALANCED/BRIEF는 NULL/0 유지) in `src/main/java/com/newscurator/domain/Summary.java`
- [X] T008 [P] JPA Entity SourceDailyUsage.java 생성 (복합 PK: source_id + usage_date, call_count) in `src/main/java/com/newscurator/domain/SourceDailyUsage.java`
- [X] T009 Repository 인터페이스 5개 생성: ArticleRepository(lockAndClaimPending 포함 — SELECT … FOR UPDATE SKIP LOCKED 네이티브 쿼리 시그니처 정의, T032에서 호출), SourceRepository, ArticleSourceRepository, SummaryRepository, SourceDailyUsageRepository (기본 CRUD + 커스텀 쿼리 메서드 시그니처 정의) in `src/main/java/com/newscurator/repository/`
- [X] T010 [P] @ConfigurationProperties 4개 생성: CollectionProperties(interval-ms, batch-size), RetentionProperties(days, grace-period-days), FeedProperties(default-page-size, max-page-size), AiProperties(retry-limit, delay-between-calls-ms, deep-retry.cooldown-minutes=60, deep-retry.limit=5) in `src/main/java/com/newscurator/config/`
- [X] T011 [P] application.yaml(공통 기본값) + application-example.yaml(시크릿 치환 템플릿) 작성 in `src/main/resources/`
- [X] T012 [P] GlobalExceptionHandler(@RestControllerAdvice), ErrorResponse record, ArticleNotFoundException, AiProviderException 생성 in `src/main/java/com/newscurator/exception/`
- [X] T013 [P] UrlNormalizer 구현: 7단계 정규화(스킴 통일, 트래킹 파라미터 제거, 끝 슬래시 제거 등) in `src/main/java/com/newscurator/util/UrlNormalizer.java`
- [X] T014 [P] UrlNormalizerTest 단위 테스트: 트래킹 파라미터 제거, http→https 정규화, 끝 슬래시 제거, CHK014(brief 트런케이션 200자 기준은 AiProcessingService에서 처리) in `src/test/java/com/newscurator/util/UrlNormalizerTest.java`
- [X] T015 [P] ArticleRepositoryTest (@DataJpaTest + Testcontainers PostgreSQL): normalized_url unique 제약, feed partial index, PENDING queue index, cursor 정렬 검증, lockAndClaimPending 동시 클레임 검증(2 스레드 동시 호출 시 각각 다른 기사 클레임) in `src/test/java/com/newscurator/repository/ArticleRepositoryTest.java`
- [X] T056 [P] SummaryServiceTest (JUnit 5): truncateForBrief(200자 경계·null·200자 미만 입력), isDeepRetryAllowed(쿨다운 미경과 → false, retry_count ≥ deep-retry.limit → false, 정상 허용 → true) in `src/test/java/com/newscurator/service/SummaryServiceTest.java`
- [X] T055 [P] SummaryService 생성 (T056 단위 테스트 선행): truncateForBrief(String balancedContent): String (~200자, CHK014 기준 상수화), DEEP 슬롯 쿨다운 체크(last_attempt_at + cooldown-minutes 경과 여부), retry_count 한도 검사. T032(AiProcessingService)·T044(ArticleDetailService)가 이 클래스를 호출함 in `src/main/java/com/newscurator/service/SummaryService.java`

**Checkpoint A**: Flyway 마이그레이션 실행 후 테이블 5개 생성 확인, 모든 Entity 컴파일 통과, UrlNormalizerTest 통과, SummaryServiceTest 통과

---

## Phase B: US1 — 자동 뉴스 수집 및 중복 제거 (P1)

**목표**: 등록된 RSS·네이버 출처에서 기사를 주기적으로 수집하고, URL 기반 중복을 병합 처리하며, 출처별 일일 호출 예산을 추적한다.

**Independent Test**: CollectionScheduler 1회 실행 후 특정 기사가 정확히 1건 저장되고, 동일 URL 재수집 시 ArticleSource(provenance)가 추가됨을 확인한다. 출처 1개 타임아웃 시 나머지 출처 수집은 완료됨을 확인한다.

### 테스트 (구현 전 작성)

- [X] T016 [P] [US1] RssSourceAdapterTest: WireMock RSS 피드 스텁 파싱, 타임아웃 처리, malformed XML fallback(CHK009: SyndFeedException → log + skip) in `src/test/java/com/newscurator/client/RssSourceAdapterTest.java`
- [X] T017 [P] [US1] NaverSourceAdapterTest: WireMock 네이버 검색 API 응답 파싱, 인증 헤더 포함, 에러 응답 처리 in `src/test/java/com/newscurator/client/NaverSourceAdapterTest.java`
- [X] T018 [P] [US1] CollectionServiceTest (JUnit 5 + Mockito): dedup 로직(기존 URL → merge, 신규 URL → insert), per-source 독립 트랜잭션(출처 A 성공 + 출처 B 실패 시 A 커밋 유지), call_budget_daily 초과 시 해당 출처 중단 + PENDING 유지 검증 in `src/test/java/com/newscurator/service/CollectionServiceTest.java`
- [X] T019 [US1] 통합 테스트 스켈레톤 (Testcontainers @SpringBootTest): 수집 → dedup → provenance 전체 흐름, 동시 중복 수집 INSERT ON CONFLICT 검증 in `src/test/java/com/newscurator/integration/CollectionIntegrationTest.java`

### 구현

- [X] T020 [P] [US1] SourceAdapter 인터페이스 + ArticleCandidate VO 정의 in `src/main/java/com/newscurator/client/source/SourceAdapter.java`, `ArticleCandidate.java`
- [X] T021 [P] [US1] RssSourceAdapter 구현: ROME 2.x 파싱, RestClient timeout 설정, malformed XML(SyndFeedException) → log + consecutive_failure_count 증가 + 해당 출처 스킵(CHK009) in `src/main/java/com/newscurator/client/source/RssSourceAdapter.java`
- [X] T022 [P] [US1] NaverSourceAdapter 구현: 네이버 검색 API RestClient 호출, X-Naver-Client-Id/Secret 헤더, 응답 파싱, 타임아웃 in `src/main/java/com/newscurator/client/source/NaverSourceAdapter.java`
- [X] T023 [US1] SourceDailyUsage 예산 추적 구현: SourceDailyUsageRepository에 incrementAndGet(source_id, usage_date) + isOverBudget 메서드 추가. 날짜 기준은 Source.adapter_type에 따라 KST(NAVER: UTC 15:00 전환) 또는 UTC 사용 in `src/main/java/com/newscurator/repository/SourceDailyUsageRepository.java`
- [X] T024 [US1] CollectionService 구현: UrlNormalizer 호출 → normalized_url로 INSERT ON CONFLICT DO NOTHING(dedup) → 기존 기사면 ArticleSource upsert(is_merge=true) → 출처별 독립 @Transactional(FR-003, CHK020) → call_budget_daily 초과 시 해당 출처 루프 중단 + WARN 로그(CHK010) in `src/main/java/com/newscurator/service/CollectionService.java`
- [X] T025 [US1] CollectionScheduler 구현: @Scheduled fixedDelayString=${app.scheduler.collection.interval-ms}, MDC runId 설정, 활성 출처 목록 로드, CollectionService 위임, 시작/종료/실패 구조적 로그(FR-016) in `src/main/java/com/newscurator/scheduler/CollectionScheduler.java`

**Checkpoint B**: CollectionIntegrationTest 통과. DB에 articles 1건(동일 URL 2번 수집 시 1건 유지), article_sources 2건 확인.

---

## Phase C: US2 — 카테고리 분류 및 AI 요약 생성 (P2)

**목표**: PENDING 기사를 Gemini로 분류·요약하고, BRIEF를 balanced 트런케이션으로 즉시 생성하며, DEEP 슬롯은 NOT_GENERATED 상태로 초기화한다. 분류·요약 실패 및 재시도, 만료 처리도 포함한다.

**Independent Test**: PENDING 기사 1건에 AiProcessingScheduler 실행 → category_status=COMPLETED + summary_status=COMPLETED + balanced/brief 슬롯 COMPLETED + deep 슬롯 NOT_GENERATED 확인. 요약 실패 시 category_status 독립 유지 확인.

### 테스트 (구현 전 작성)

- [X] T026 [P] [US2] AiProcessingServiceTest (JUnit 5 + Mockito): PENDING→COMPLETED 전이, 분류 실패→OTHER 폴백(CHK013), summary_status 독립 처리(CHK002: article.summary_status=COMPLETED = balanced 슬롯 완료), retry_limit 초과 시 영구 FAILED, brief 200자 트런케이션(CHK014) 검증 in `src/test/java/com/newscurator/service/AiProcessingServiceTest.java`
- [X] T027 [P] [US2] GeminiAiProviderTest: WireMock 정상 응답 파싱, enum 외 카테고리 값 → OTHER 폴백 + category_raw_value WARN 로그, HTTP 오류 → AiProviderException in `src/test/java/com/newscurator/client/GeminiAiProviderTest.java`
- [X] T028 [P] [US2] ExpiryServiceTest: expires_at 초과 → feed_visible=false, grace period 초과 + user_saved=false → 물리 삭제 대상, user_saved=true → 삭제 제외 검증 in `src/test/java/com/newscurator/service/ExpiryServiceTest.java`
- [X] T029 [US2] AI 파이프라인 통합 테스트 스켈레톤 (Testcontainers): PENDING 기사 → AiProcessingService → COMPLETED/FAILED 전이 전체 흐름 in `src/test/java/com/newscurator/integration/AiProcessingIntegrationTest.java`

### 구현

- [X] T030 [P] [US2] AiProvider 인터페이스 정의: classify(title, content): Category, summarize(title, content, SummaryDepth): String in `src/main/java/com/newscurator/client/ai/AiProvider.java`
- [X] T031 [US2] GeminiAiProvider 구현: Gemini Flash API RestClient 호출, 응답 파싱·검증, enum 외 카테고리 → OTHER 폴백 + WARN 로그(category_raw_value 포함), timeout + delay-between-calls-ms 적용 in `src/main/java/com/newscurator/client/ai/GeminiAiProvider.java`
- [X] T032 [US2] AiProcessingService 구현: PENDING 배치를 `SELECT ... FOR UPDATE SKIP LOCKED`(ArticleRepository.lockAndClaimPending — T009에서 시그니처 정의, T015에서 동시 클레임 검증)로 row-level claim → 다중 인스턴스 safe(research #13). 분류(classify → category_status=COMPLETED; 실패 → retry_count++, retry_limit 초과 시 영구 FAILED), balanced 요약 생성(summary_status 독립 관리, CHK002), SummaryService.truncateForBrief()로 BRIEF 즉시 생성(CHK014), DEEP 슬롯 NOT_GENERATED 초기화. AI daily cap 없음: research #9 결론(960콜/일 ≪ 2,000 RPM) — HTTP 429 시 GeminiAiProvider에서 backoff 처리로 충분 in `src/main/java/com/newscurator/service/AiProcessingService.java`
- [X] T033 [US2] AiProcessingScheduler 구현: @Scheduled fixedDelayString=${app.scheduler.ai.interval-ms}, MDC runId, AiProcessingService 위임, 시작/종료/실패 구조적 로그(FR-016). 단일 EC2 인스턴스 전제(research #13) — fixedDelay로 JVM 내 중첩 없음, ShedLock 어노테이션 주석 준비(@SchedulerLock, scale-out 시 활성화) in `src/main/java/com/newscurator/scheduler/AiProcessingScheduler.java`
- [X] T034 [P] [US2] ExpiryService 구현: 1단계(feed_visible=false: expires_at < NOW() AND user_saved=false), 2단계(물리 삭제: feed_visible=false AND updated_at < NOW()-7days AND user_saved=false), user_saved=true 기사 보존 처리(FR-018, FR-019) in `src/main/java/com/newscurator/service/ExpiryService.java`
- [X] T035 [P] [US2] ExpiryScheduler 구현: @Scheduled cron 일 1회(새벽), ExpiryService 위임, 처리 건수 로그 in `src/main/java/com/newscurator/scheduler/ExpiryScheduler.java`

**Checkpoint C**: AiProcessingIntegrationTest 통과. PENDING 기사가 COMPLETED로 전이, balanced/brief 슬롯 COMPLETED, deep 슬롯 NOT_GENERATED 확인.

---

## Phase D: US3 — 앱 사용자 카테고리 피드 조회 (P3)

**목표**: 커서 기반 피드 목록 API(GET /api/v1/articles), 기사 상세 API(GET /api/v1/articles/{id}) 구현. DEEP lazy 생성, 0건 응답, VALIDATION_ERROR, FAILED→OTHER 모두 포함.

**Independent Test**: 피드 API 조회 → 최신순 목록 + nextCursor 반환. category=ECONOMY_FINANCE 필터 → 해당 카테고리만 반환. size=150 요청 → 100으로 clamp. 기사 상세 → deep NOT_GENERATED → 최초 조회 시 DEEP 생성 또는 FAILED 200 반환 확인.

### 테스트 (구현 전 작성)

- [X] T036 [P] [US3] ArticleFeedServiceTest (JUnit 5 + Mockito): 커서 decode/encode, category 필터, 0건 응답(data=[], nextCursor=null, hasMore=false, CHK030), size clamp(최대 100), FAILED→OTHER 매핑, feed_visible=true AND category_status∈{COMPLETED,FAILED} 조건 검증 in `src/test/java/com/newscurator/service/ArticleFeedServiceTest.java`
- [X] T037 [P] [US3] ArticleDetailServiceTest (JUnit 5 + Mockito): DEEP NOT_GENERATED → AI 호출 후 COMPLETED, DEEP AI 오류 → status=FAILED + 200 응답(CHK022), DEEP FAILED → 다음 요청 재시도(FAILED→PENDING 전이, CHK022 data-model 정합), CHK025(커서가 만료 기사 가리킬 때 graceful 처리 주석 확인) in `src/test/java/com/newscurator/service/ArticleDetailServiceTest.java`
- [X] T038 [P] [US3] ArticleFeedControllerTest (@WebMvcTest): 200 정상 목록, 200 빈 목록(CHK030), 400 VALIDATION_ERROR(category=INVALID_VALUE, CHK028), size 초과 → 100으로 clamp 후 200 응답. (401/403 인증 테스트는 @Disabled + "인증 구현 spec 002 이후 활성화") in `src/test/java/com/newscurator/controller/ArticleFeedControllerTest.java`
- [X] T039 [P] [US3] ArticleDetailControllerTest (@WebMvcTest): 200 모든 슬롯 포함, 200 deep FAILED(content=null + status=FAILED), 404 ARTICLE_NOT_FOUND in `src/test/java/com/newscurator/controller/ArticleDetailControllerTest.java`

### 구현

- [X] T040 [P] [US3] DTO record 일괄 생성: FeedRequest(cursor, size, category), ArticleFeedItem, ArticleFeedResponse(data, nextCursor nullable, hasMore, size), ArticleDetailResponse, SummarySlot(status, content nullable, generatedAt nullable), ArticleSourceRef in `src/main/java/com/newscurator/dto/`
- [X] T041 [US3] ArticleRepository 커서 페이지네이션 쿼리 추가: published_at+id 기반 커서 디코딩 조건(WHERE published_at < :cursorPublishedAt OR (published_at = :cursorPublishedAt AND id < :cursorId)), category 필터, feed partial index 활용. CHK025: 커서가 만료·삭제된 기사를 가리켜도 해당 커서 위치 이후 결과를 graceful하게 반환 (주석으로 동작 명시) in `src/main/java/com/newscurator/repository/ArticleRepository.java`
- [X] T042 [US3] ArticleFeedService 구현: FeedProperties에서 기본/최대 페이지 크기 로드, size clamp(초과 → 100), cursor Base64 decode/encode, ArticleRepository 위임, FAILED→OTHER 매핑, 0건 응답 구조 보장 in `src/main/java/com/newscurator/service/ArticleFeedService.java`
- [X] T043 [US3] ArticleFeedController 구현: GET /api/v1/articles, @Valid FeedRequest, category enum 유효성은 Spring 자동 변환 + @ExceptionHandler(MethodArgumentTypeMismatchException) → 400 VALIDATION_ERROR(CHK028) in `src/main/java/com/newscurator/controller/ArticleFeedController.java`
- [X] T044 [US3] ArticleDetailService 구현: 기사+summaries 로드. DEEP NOT_GENERATED → AiProvider.summarize(DEEP) 동기 호출(PENDING→COMPLETED/FAILED). DEEP FAILED → SummaryService.isDeepRetryAllowed(summary) 검사: 쿨다운 미경과 또는 retry_count ≥ deep-retry.limit 이면 현재 FAILED 상태 그대로 반환(AI 재호출 없음); 허용 시 PENDING→AI재시도→COMPLETED/FAILED. DEEP AI 오류 → Summary.status=FAILED + last_attempt_at/retry_count 갱신 + 200 반환(CHK022). brief NOT_GENERATED → SummaryService.truncateForBrief() in `src/main/java/com/newscurator/service/ArticleDetailService.java`
- [X] T045 [US3] ArticleDetailController 구현: GET /api/v1/articles/{id}, id 조회 실패 → ArticleNotFoundException → 404 ARTICLE_NOT_FOUND in `src/main/java/com/newscurator/controller/ArticleDetailController.java`

**Checkpoint D**: ArticleFeedControllerTest + ArticleDetailControllerTest 통과. 피드 조회 정상, 빈 응답 200, invalid category 400, 상세 deep FAILED 200 확인.

---

## Phase E: US4 — 관리자 파이프라인 모니터링 (P4)

**목표**: GET /api/v1/admin/pipeline/stats로 수집 건수·요약 완료율·병합 건수·카테고리별 분포를 제공한다. categoryBreakdown은 COMPLETED/FAILED 기준, FAILED→OTHER, PENDING 미포함(CHK029).

**Independent Test**: 오늘 수집 100건·완료 80건·병합 5건인 DB 상태에서 API 호출 → 반환값과 일치 확인. /admin/* 경로 분리 확인. 401/403 검증은 인증 구현(spec 002) 이후로 미룸.

### 테스트 (구현 전 작성)

- [X] T046 [P] [US4] PipelineStatsServiceTest (JUnit 5 + Mockito/DataJpaTest): articlesCollectedToday(first_collected_at=today), summaryCompletionRate 계산, mergeCount(is_merge=true today), categoryBreakdown(COMPLETED/FAILED 기준, FAILED→OTHER 집계, PENDING 미포함, CHK029), categoryPending/categoryFailed/summaryPending/summaryFailed 필드 in `src/test/java/com/newscurator/service/PipelineStatsServiceTest.java`
- [X] T047 [P] [US4] AdminPipelineControllerTest (@WebMvcTest): 200 통계 응답 구조. (401/403은 @Disabled + "인증 구현 spec 002 이후 활성화") in `src/test/java/com/newscurator/controller/AdminPipelineControllerTest.java`

### 구현

- [X] T048 [P] [US4] PipelineStatsResponse record 생성 (date, articlesCollectedToday, summaryCompletionRate, mergeCount, categoryBreakdown, pipelineStatus 중첩 record) in `src/main/java/com/newscurator/dto/response/PipelineStatsResponse.java`
- [X] T049 [US4] PipelineStatsService 구현 (@Transactional(readOnly=true)): DB 집계 쿼리 5개 — articlesCollectedToday(DATE(first_collected_at)=TODAY), summaryCompletionRate(COMPLETED/total×100), mergeCount(is_merge=true AND DATE(collected_at)=TODAY), categoryBreakdown(category_status∈{COMPLETED,FAILED} GROUP BY CASE WHEN category_status=FAILED THEN 'OTHER' ELSE category END), pipelineStatus(4개 카운트). CHK005: 집계 뷰 아닌 쿼리 방식으로 실시간 산출 in `src/main/java/com/newscurator/service/PipelineStatsService.java`
- [X] T050 [US4] AdminPipelineController 구현: GET /api/v1/admin/pipeline/stats, 경로 /admin/* 분리(향후 ROLE_ADMIN 보호를 위해), PipelineStatsService 위임 in `src/main/java/com/newscurator/controller/AdminPipelineController.java`
- [X] T051 [P] [US4] InternalTriggerController 구현 (local 프로파일 전용): 수집·AI처리·만료 수동 트리거 엔드포인트 (@Profile("local")) in `src/main/java/com/newscurator/controller/InternalTriggerController.java`

**Checkpoint E**: PipelineStatsServiceTest 통과. API 응답에서 categoryBreakdown PENDING 미포함, FAILED→OTHER 집계 확인.

---

## Phase F: 통합 검증 및 마무리

**목적**: 전체 파이프라인 E2E 검증, MDC 구조적 로깅 완비, 환경변수 템플릿 확인.

- [X] T052 E2E 통합 테스트 (@SpringBootTest + Testcontainers): 수집→AI처리→피드 API 전체 흐름. 기사 수집 → PENDING → AI처리 → COMPLETED → GET /api/v1/articles 응답에 포함 확인 in `src/test/java/com/newscurator/integration/PipelineE2ETest.java`
- [X] T053 [P] MDC runId 구조적 로깅 적용: CollectionScheduler + AiProcessingScheduler에 UUID runId를 MDC에 등록, 각 스케줄러 실행 단위 추적 가능하게(FR-016) in `src/main/java/com/newscurator/scheduler/`
- [X] T054 [P] application-example.yaml 최종 검토: 모든 외부 API 키(GEMINI_API_KEY, NAVER_CLIENT_ID, NAVER_CLIENT_SECRET, DB 접속 정보) 치환 토큰 포함, application.yaml에 시크릿 미포함 확인 in `src/main/resources/application-example.yaml`

> *(선택) SC-009(P95 1초)·SC-010(10,000건/일)은 운영 SLO·설계 헤드룸 — 빌드 게이트 아님. 배포 후 부하 테스트로 측정. quickstart.md의 k6 스모크 시나리오 참조.*

---

## MEDIUM 체크리스트 항목 → 구현 추적

아래 항목은 별도 태스크로 분리하지 않고, 해당 태스크 내에서 처리됨:

| CHK | 연결 태스크 | 처리 방식 |
|-----|------------|----------|
| CHK002 — summary_status=COMPLETED = balanced 슬롯 완료 | T026, T032, T037, T044 | 코드 주석 + 테스트 assertion |
| CHK004 — user_saved 단일 boolean 가정 | T005 | Article.java 주석으로 명시, 다중 사용자는 spec 002 범위 |
| CHK005 — PipelineStats = DB 집계 쿼리(뷰 없음) | T049 | @Transactional(readOnly=true) 실시간 쿼리 |
| CHK009 — malformed XML 처리 | T016, T021 | SyndFeedException catch → log + skip + failure count |
| CHK014 — brief 트런케이션 ~200자 기준 | T026, T032 | AiProcessingService 상수화 + 테스트 |
| CHK025 — 만료 기사 커서 동작 | T037, T041 | graceful skip 구현 + 주석 명시 |
| DEEP 슬롯 재시도 백오프 — last_attempt_at + retry_count 컬럼 | T007, T037, T044, T055 | data-model 컬럼 + SummaryService.isDeepRetryAllowed() + 테스트 |

---

## 의존성 및 실행 순서

### Phase 의존성

```
Phase A (기반) ──────────────────────────────┐
                                              ├──▶ Phase B (US1) ──▶ Phase C (US2) ──▶ Phase D (US3) ──▶ Phase E (US4)
                                              └─── (US2, US3, US4는 Phase A 완료 후 병렬 착수 가능)
```

- **Phase A**: 즉시 시작 가능
- **Phase B (US1)**: Phase A 완료 필요
- **Phase C (US2)**: Phase A 완료 필요. Phase B 완료 시 수집 데이터로 검증 가능하나, 독립 실행 가능
- **Phase D (US3)**: Phase A 완료 필요. Phase B+C 없이도 테스트 데이터로 독립 개발 가능
- **Phase E (US4)**: Phase A 완료 필요. Phase B+C+D 없이도 독립 개발 가능
- **Phase F**: 모든 Phase 완료 후

### User Story 내 순서

```
테스트 작성 (FAIL 확인) → 구현 → 테스트 PASS → Checkpoint 검증
```

### 병렬 실행 예시 (Phase B)

```
# 테스트 동시 작성:
T016 RssSourceAdapterTest
T017 NaverSourceAdapterTest
T018 CollectionServiceTest

# 어댑터 동시 구현 (T020 완료 후):
T021 RssSourceAdapter
T022 NaverSourceAdapter
```

---

## 구현 전략

### MVP (US1 → US2 → US3 순서 검증)

1. Phase A 완료 → Flyway + Entity 컴파일 확인
2. Phase B 완료 → 수집 파이프라인 검증 (CollectionIntegrationTest)
3. Phase C 완료 → AI 처리 검증 (AiProcessingIntegrationTest)
4. Phase D 완료 → 피드 API 검증 (E2E 동작)
5. Phase E 완료 → 관리자 통계 검증
6. Phase F → E2E 통합 테스트

### 통계

- **총 태스크**: 56개
- **US별**: Phase A 17개, Phase B 10개, Phase C 10개, Phase D 10개, Phase E 6개, Phase F 3개
- **테스트 우선 태스크**: T014-T015, T016-T019, T026-T029, T036-T039, T046-T047, T052, T056 = 17개
- **병렬 가능 [P] 태스크**: 34개

---

## 참고

- `[P]` 태스크 = 다른 파일, 동일 Phase 내 동시 작업 가능
- Checkpoint에서 FAIL 시 해당 Phase 내 버그 수정 후 진행
- 시크릿은 환경변수로만 주입 (application.yaml에 하드코딩 절대 금지)
- Flyway 외 스키마 변경 없음 (ddl-auto=validate)
