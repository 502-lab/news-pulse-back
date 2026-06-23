# Tasks: 어드민 대시보드 (운영 관리 레이어)

**Feature**: 008-admin-dashboard | **Branch**: `feat/008-admin-dashboard`
**Input**: plan.md · research.md · data-model.md · contracts/openapi-patch.yaml · quickstart.md

> 우선순위: **P1**(US1 사용자관리·US2 모니터링) → **P2**(US3 수집제어·US4 공지+푸시) → **P3**(US5 심층통계).
> 전 엔드포인트 `/api/v1/admin/** hasRole(ADMIN)`(기존 SecurityConfig:78). 공개 `/api/v1/notices`만 permitAll.
> 통합/리포지토리 테스트 = Testcontainers `BigmPostgresImage.NAME`. 인가 IT = `@SpringBootTest` 실 SecurityConfig.
> **MVP 범위 밖(후속)**: 스케줄러 주기 동적 변경, 기사 영구 삭제, 알림 딥링크 hidden(#14).

---

## Phase 1: Setup

- [ ] T001 contracts/openapi-patch.yaml의 admin 엔드포인트 경로/요약을 검토·확정 in `.specify/specs/008-admin-dashboard/contracts/openapi-patch.yaml` (구현과 동기화 기준점, news-pulse-spec 선반영은 Polish T079)
- [x] T002 `SecurityConfig`에 공개 공지 조회 `GET /api/v1/notices` permitAll 명시 추가 in `src/main/java/com/newscurator/config/SecurityConfig.java` (Constitution VI: 공개 엔드포인트 명시 선언; `/api/v1/admin/**`는 기존 hasRole ADMIN 재활용 — 변경 없음)

---

## Phase 2: Foundational (Blocking Prerequisites)

**모든 US 선행 — 신규 스키마·엔티티·리포지토리·횡단 서비스. 완료 전 US 구현 불가.**

- [x] T003 V15 마이그레이션 작성 in `src/main/resources/db/migration/V15__admin_dashboard.sql` — `articles.admin_hidden_at TIMESTAMPTZ NULL` 컬럼 + `idx_articles_admin_visible`(WHERE admin_hidden_at IS NULL) + 테이블 `notice`·`admin_audit_log`·`scheduler_setting`·`excluded_keyword` + 인덱스 + `scheduler_setting` 12키 시드(collection·ai_processing·bias_analysis·bias_recovery·bias_sla·trend_aggregation·trend_cleanup·tts_processing·notification_outbox·notification_expiry·weekly_email·expiry) (data-model.md DDL)
- [x] T004 [P] `AuditTargetType` enum 생성 in `src/main/java/com/newscurator/domain/enums/AuditTargetType.java` (ACCOUNT·ARTICLE·SCHEDULER·NOTICE·PUSH·EXCLUDED_KEYWORD·SUMMARY — ★ 기존 AdminTargetType 재활용 금지)
- [x] T005 [P] `Notice` 엔티티 in `src/main/java/com/newscurator/domain/Notice.java` (title·content·published·authorAccountId·created/updatedAt, 게시 상태 전이 캡슐화)
- [x] T006 [P] `AdminAuditLog` 엔티티 in `src/main/java/com/newscurator/domain/AdminAuditLog.java` (actorAccountId·action·targetType(AuditTargetType)·targetId·detail JSONB·createdAt)
- [x] T007 [P] `SchedulerSetting` 엔티티 in `src/main/java/com/newscurator/domain/SchedulerSetting.java` (schedulerKey PK·enabled·intervalOverrideMs(미노출)·updatedAt·updatedBy)
- [x] T008 [P] `ExcludedKeyword` 엔티티 in `src/main/java/com/newscurator/domain/ExcludedKeyword.java` (keyword UNIQUE·createdBy·createdAt)
- [x] T009 [P] `Article`에 `admin_hidden_at` 필드 + `hideByAdmin(Instant)`/`unhideByAdmin()`/`isAdminHidden()` 캡슐화 in `src/main/java/com/newscurator/domain/Article.java`
- [x] T010 [P] `NoticeRepository` in `src/main/java/com/newscurator/repository/NoticeRepository.java` (published 필터 조회·관리자 전체 조회)
- [x] T011 [P] `AdminAuditLogRepository` in `src/main/java/com/newscurator/repository/AdminAuditLogRepository.java` (시간 역순·targetType/action/기간 필터)
- [x] T012 [P] `SchedulerSettingRepository` in `src/main/java/com/newscurator/repository/SchedulerSettingRepository.java` (findBySchedulerKey·findAll)
- [x] T013 [P] `ExcludedKeywordRepository` in `src/main/java/com/newscurator/repository/ExcludedKeywordRepository.java` (전체·존재여부·insert/delete)
- [x] T014 `AdminAuditService` in `src/main/java/com/newscurator/service/admin/AdminAuditService.java` — `record(actorId, action, AuditTargetType, targetId, detailMap)` 단일 진입점(같은 TX 내 명시 호출, research D4)
- [x] T015 `SchedulerControlService` in `src/main/java/com/newscurator/service/admin/SchedulerControlService.java` — `isEnabled(schedulerKey)`(행 부재 시 true 기본)·`setEnabled(key, enabled, actor)`(영속 + 감사)
- [x] T016 [P] 공통 admin 예외(LastAdminProtectedException·SelfMutationForbiddenException·AdminTargetNotFoundException 등) + `@RestControllerAdvice` RFC7807 매핑 in `src/main/java/com/newscurator/exception/`

**Checkpoint**: 스키마·엔티티·리포·횡단서비스 준비 → US 구현 가능.

---

## Phase 3: User Story 1 - 사용자 관리 (Priority: P1) 🎯 MVP

**Goal**: 사용자 목록·검색, UserStats, role USER↔ADMIN, 활성/비활성. 자기보호 가드(FR-014 a/b).
**Independent Test**: ADMIN이 목록 조회→role 왕복→비활성(로그인 차단)→재활성. 자기/마지막 ADMIN 강등·비활성 거부. USER 토큰 403.

- [x] T017 [P] [US1] 응답 DTO `AdminUserSummaryResponse`·`AdminUserDetailResponse(UserStats)`·요청 `RoleChangeRequest`·`StatusChangeRequest`(record, @Schema, @Valid) in `src/main/java/com/newscurator/dto/`
- [x] T018 [US1] `AdminUserService` in `src/main/java/com/newscurator/service/admin/AdminUserService.java` — 목록(페이지·필터 email/status/role/signupType), 상세+UserStats(기존 데이터 산출), changeRole, changeStatus
- [x] T019 [US1] ★ 자기보호 가드 in `AdminUserService` — (a) 자기 자신 강등/비활성 거부, (b) 마지막 ADMIN 강등/비활성 거부(ADMIN 카운트 확인). 거부 시 도메인 예외(FR-014)
- [x] T020 [US1] 비활성 계정 로그인·토큰 발급 차단 연동 in `src/main/java/com/newscurator/service/AuthService.java` (AccountStatus 비활성 검사 — 기존 인증 경로에 가드, 002 재활용/확장)
- [x] T021 [US1] role 변경·활성/비활성에 `AdminAuditService.record()` 호출(같은 TX, before/after diff) in `AdminUserService`
- [x] T022 [US1] `AdminUserController` in `src/main/java/com/newscurator/controller/AdminUserController.java` — GET /api/v1/admin/users, GET /{id}, PATCH /{id}/role, PATCH /{id}/status (ApiResponse 래퍼, @Tag/@Operation/@ApiResponses)
- [x] T023 [P] [US1] `AdminUserServiceTest`(단위) in `src/test/java/com/newscurator/service/admin/AdminUserServiceTest.java` — 목록 필터, role 변경, 상태 변경
- [x] T024 [P] [US1] `LastAdminGuardIT`(실 PG) in `src/test/java/com/newscurator/integration/LastAdminGuardIT.java` — 자기 자신 강등/비활성 거부 + 마지막 ADMIN 강등/비활성 거부(둘 다)
- [x] T025 [P] [US1] 비활성 로그인 차단 테스트 in `src/test/java/com/newscurator/service/AuthServiceTest.java`(확장) — 비활성 계정 로그인 실패
- [x] T026 [P] [US1] `AdminAuditCaptureTest`(role·status 액션당 audit 1건+diff) in `src/test/java/com/newscurator/service/admin/AdminAuditCaptureTest.java`

**Checkpoint**: 사용자 관리 독립 동작 + 자기보호.

---

## Phase 4: User Story 2 - 핵심 운영 모니터링 조회 (Priority: P1)

**Goal**: KPI·파이프라인 stats·수집량·스케줄러 상태·bias/trend 어드민 뷰(읽기 전용, 빈 데이터 0/빈값 안전).
**Independent Test**: ADMIN이 KPI·pipeline·schedulers·collection 조회 → 기존 데이터 집계. 빈 환경서 0/빈값.

- [x] T027 [P] [US2] 응답 DTO `AdminKpiResponse`·`SchedulerStatusResponse`·`CollectionVolumeResponse`·`BiasAdminViewResponse`·`TrendAdminViewResponse` in `src/main/java/com/newscurator/dto/response/`
- [x] T028 [US2] `AdminMonitoringService` in `src/main/java/com/newscurator/service/admin/AdminMonitoringService.java` — KPI(총·활성 사용자·수집기사·요약완료율·편향완료율·트렌드이슈수), 빈값 안전(COALESCE/NULLIF)
- [x] T029 [P] [US2] 파이프라인 stats 확장 — 기존 `PipelineStatsService` 재활용/확장(요약·편향 status 분포) in `src/main/java/com/newscurator/service/PipelineStatsService.java`
- [x] T030 [P] [US2] 소스별 수집량 집계(source_daily_usage call_count, 기간) in `AdminMonitoringService`
- [x] T031 [P] [US2] 스케줄러 상태 조회(SchedulerSetting enabled + 최근실행/주기 메타) in `AdminMonitoringService` (SchedulerControlService 연동)
- [x] T032 [P] [US2] bias(006)·trend(007) 어드민 요약 뷰 집계 in `AdminMonitoringService`
- [x] T033 [US2] `AdminMonitoringController` in `src/main/java/com/newscurator/controller/AdminMonitoringController.java` — GET kpi·collection·schedulers·monitoring/bias·monitoring/trend. 파이프라인은 기존 `AdminPipelineController` 재활용
- [x] T034 [P] [US2] `AdminMonitoringServiceTest`(단위) — KPI 계산·빈값 안전 in `src/test/java/com/newscurator/service/admin/AdminMonitoringServiceTest.java`
- [x] T035 [P] [US2] 빈 데이터 안전 IT(실 PG, 신규 환경 0/빈값) in `src/test/java/com/newscurator/integration/AdminMonitoringEmptyIT.java`
- [x] T036 [P] [US2] 수집량 집계 리포지토리 테스트(BigmPostgresImage) in `src/test/java/com/newscurator/repository/SourceDailyUsageRepositoryTest.java`(확장)

**Checkpoint**: 운영 관측 가능 (P1 완료 → MVP 후보).

---

## Phase 5: User Story 3 - 수집·콘텐츠 운영 제어 (Priority: P2)

**Goal**: 스케줄러 토글/수동실행, 제외 키워드, 요약 재시도, 기사 숨김 + ★hidden 일관성 + 12 스케줄러 게이트.
**Independent Test**: 스케줄러 비활성→skip→재기동 유지, 수동실행 동작. 키워드 추가→집계 배제. 기사 hide→모든 사용자향 경로 0건·상세404·admin포함·unhide복귀. FAILED 요약 재시도.

### ★ hidden 읽기경로 enforcement (research D2 표 #1~#13) — 누락 시 "숨겼는데 노출" 회귀

- [x] T037 [US3] ArticleRepository 피드 6쿼리에 `AND a.adminHiddenAt IS NULL` 추가 in `src/main/java/com/newscurator/repository/ArticleRepository.java` (findFeedPage·findFeedPageWithCursor·findFeedPageByCategory·findFeedPageByCategoryWithCursor·findFeedCandidates·findFeedCandidatesByCategory) — #1~#6
- [x] T038 [US3] ArticleRepository 검색 2쿼리(native)에 `AND a.admin_hidden_at IS NULL` 추가 in `ArticleRepository.java` (searchByQuery·searchByQueryWithCursor) — #7~#8 (003)
- [x] T039 [US3] ★ `ArticleDetailService.findById` 일반 사용자 hidden 404 가드(신규) in `src/main/java/com/newscurator/service/ArticleDetailService.java` — admin 컨텍스트는 hidden 포함 조회 허용 — #9
- [x] T040 [US3] 트렌드 추출/히트맵 JOIN에 hidden 제외 in `src/main/java/com/newscurator/repository/ArticleKeywordRepository.java` (windowArticleKeywords·heatmap `AND a.admin_hidden_at IS NULL`) — #10~#11 (007)
- [x] T041 [US3] 트렌드 슬롯 UPSERT JOIN에 hidden 제외 in `src/main/java/com/newscurator/repository/TrendKeywordSlotRepository.java` (upsertSlots) — #12 (007)
- [x] T042 [US3] 북마크 목록 hidden 제외 in `src/main/java/com/newscurator/service/SavedArticleService.java` (목록 쿼리 admin_hidden_at IS NULL) — #13
- [x] T043 [US3] 기사 숨김/해제 서비스 in `src/main/java/com/newscurator/service/admin/AdminOpsService.java` — hide(admin_hidden_at=now)·unhide(null) + 감사 record() + admin 기사 조회(hidden 포함)

### 스케줄러 제어 (12 @Scheduled 게이트)

- [x] T044 [P] [US3] CollectionScheduler·AiProcessingScheduler에 `isEnabled(key)` 게이트(disabled면 skip) in `src/main/java/com/newscurator/scheduler/CollectionScheduler.java`·`AiProcessingScheduler.java` (keys: collection·ai_processing)
- [x] T045 [P] [US3] BiasAnalysisScheduler 3메서드 게이트 in `src/main/java/com/newscurator/scheduler/BiasAnalysisScheduler.java` (keys: bias_analysis·bias_recovery·**bias_sla**)
- [ ] T046 [P] [US3] TrendAggregationScheduler 2메서드 게이트 in `src/main/java/com/newscurator/scheduler/TrendAggregationScheduler.java` (keys: trend_aggregation·trend_cleanup)
- [x] T047 [P] [US3] TtsProcessingScheduler·NotificationOutboxProcessor·NotificationExpiryScheduler·WeeklyEmailScheduler·ExpiryScheduler 게이트 in 각 파일 (keys: tts_processing·notification_outbox·notification_expiry·weekly_email·expiry)
- [x] T048 [US3] 스케줄러 수동 실행(1회 즉시 트리거 + 동시실행 가드) + 토글 setEnabled in `AdminOpsService` (+ 감사)

### 제외 키워드 · 요약 재시도

- [x] T049 [US3] 제외 키워드 CRUD 서비스(add unique·list·delete) + 트렌드 집계 배제 연동(`term NOT IN excluded_keyword`) in `AdminOpsService` + `TrendKeywordSlotRepository.upsertSlots`/추출 쿼리
- [x] T050 [US3] 실패(FAILED) 요약 재시도 트리거(상태 재처리 전환) in `AdminOpsService` (+ 감사)
- [x] T051 [US3] DTO(`ArticleHideRequest`·`ExcludedKeywordRequest`·`SchedulerToggleRequest`=**{enabled} only**(interval 필드 미수용 — FR-031 거짓약속 회피 정합)) + `AdminOpsController` in `src/main/java/com/newscurator/controller/AdminOpsController.java` — schedulers run/toggle(enabled)·excluded-keywords·summary/retry·articles hide/unhide·admin articles

### US3 테스트 (크라운주얼)

- [x] T052 [US3] ★ `ArticleHiddenConsistencyIT`(실 PG) in `src/test/java/com/newscurator/integration/ArticleHiddenConsistencyIT.java` — hide 후 피드·검색·트렌드 추출 입력·기사상세(일반=404)에서 0건, admin 포함, unhide 즉시 복귀. 경로 #1~#13 회귀 가드
- [x] T053 [US3] ★ `SchedulerTogglePersistenceIT`(실 PG) in `src/test/java/com/newscurator/integration/SchedulerTogglePersistenceIT.java` — **12 scheduler_key 전부 검증**(부분커버 금지): (a) `@ParameterizedTest`로 12키 each → 해당 키 disabled 토글 후 그 `@Scheduled` 메서드 직접 호출 시 **본문 skip(작업 미수행)** 단언, (b) 재조회 시 disabled 영속 유지(재기동 시뮬레이션 = 새 컨텍스트/재조회, SC-010). ★ `weekly_email`(WeeklyEmailScheduler — 전역 @ConditionalOnProperty 없어 DB 게이트가 유일 차단책) 반드시 포함. + **게이트 커버리지 단언**: 12개 @Scheduled 메서드가 모두 진입 시 `SchedulerControlService.isEnabled(key)`를 호출함을 12키 each 검증(T044~T047 주입 누락 색출)
- [x] T054 [P] [US3] `AdminOpsServiceTest`(단위) — hide/unhide·키워드·요약재시도·토글 + 각 변형액션 audit 1건 in `src/test/java/com/newscurator/service/admin/AdminOpsServiceTest.java`
- [x] T055 [P] [US3] 제외 키워드 집계 배제 IT(실 PG) — 등록 키워드가 다음 트렌드 집계 슬롯서 제외 in `src/test/java/com/newscurator/integration/ExcludedKeywordIT.java`

**Checkpoint**: 능동 운영 제어 + hidden 일관성 + 스케줄러 영속 토글.

---

## Phase 6: User Story 4 - 공지 + 어드민 푸시 (Priority: P2)

**Goal**: Notice CRUD·게시 노출, 공개 조회(published만), 005 멱등 푸시.
**Independent Test**: 공지 생성(초안)→공개 미노출→게시→노출→비게시→미노출. 동일 푸시 2회→중복 0.

- [x] T056 [P] [US4] DTO `NoticeCreateRequest`·`NoticeUpdateRequest`·`NoticeResponse`·`AdminPushRequest`(record·@Valid·@Schema) in `src/main/java/com/newscurator/dto/`
- [x] T057 [US4] `NoticeService`(CRUD·게시 전환·검증 빈값/최대길이) + 감사 record() in `src/main/java/com/newscurator/service/admin/NoticeService.java`
- [x] T058 [US4] `AdminNoticeController`(admin CRUD) + `NoticeController`(공개 GET /api/v1/notices, published=true만) in `src/main/java/com/newscurator/controller/`
- [x] T059 [US4] 어드민 푸시 dedup 키 규칙 적용 — 공지 `ADMIN:NOTICE:{noticeId}:{accountId}` / 캠페인 `ADMIN:CAMPAIGN:{serverUuid}:{accountId}`(매 발송 고유) in `src/main/java/com/newscurator/service/AdminNotificationService.java`(확장) + `NotificationSendService` admin enqueue 경로(research D6)
- [x] T060 [US4] 어드민 푸시 발송에 감사 record() + `AdminNotificationController` 확장(발송 1회당 1 campaignId 발급) in `src/main/java/com/newscurator/controller/AdminNotificationController.java`
- [x] T061 [P] [US4] `NoticeServiceTest`(단위) — CRUD·게시 전환·검증 in `src/test/java/com/newscurator/service/admin/NoticeServiceTest.java`
- [x] T062 [P] [US4] 공지 게시 노출 IT(실 PG) — 초안 미노출/게시 노출/비게시 미노출(SC-006) in `src/test/java/com/newscurator/integration/NoticePublishIT.java`
- [x] T063 [P] [US4] ★ `AdminPushIdempotencyIT`(실 PG) — 동일 공지 푸시 2회 outbox 중복 0(uq_outbox_idempotency), 의도적 재발송(새 campaignId)은 별건 in `src/test/java/com/newscurator/integration/AdminPushIdempotencyIT.java`
- [x] T064 [P] [US4] 공개 공지 permitAll 동작 확인(인증 없이 200, 초안 제외) in `AdminAuthorizationIT`(T071와 통합 가능)

**Checkpoint**: 공지·푸시 운영.

---

## Phase 7: User Story 5 - 심층 통계·에러 로그·수집량 상세 (Priority: P3)

**Goal**: OpsStats 추이·에러 로그(기존 FAILED 집계, 신규 테이블 없음)·수집량 드릴다운·감사 조회.
**Independent Test**: 기간 OpsStats·수집량 상세·에러 로그(시간역순) 조회. 감사 로그 조회.

- [ ] T065 [P] [US5] DTO `OpsStatsResponse`·`ErrorLogItemResponse`·`CollectionDetailResponse`·`AuditLogItemResponse` in `src/main/java/com/newscurator/dto/response/`
- [ ] T066 [US5] `AdminOpsStatsService` — OpsStats(일자별 수집·요약·편향·트렌드 처리량 추이) in `src/main/java/com/newscurator/service/admin/AdminOpsStatsService.java`
- [ ] T067 [US5] 에러 로그 = 기존 FAILED 집계(summary_status·bias status·notification_outbox FAILED, 필터·페이지·시간역순) in `AdminOpsStatsService` (★ 신규 저장소 없음)
- [ ] T068 [US5] 수집량 상세 드릴다운(소스별 일자 call_count·예산 대비) + 감사 로그 조회 서비스 in `AdminOpsStatsService`/`AdminAuditService`
- [ ] T069 [US5] `AdminOpsStatsController` — GET ops/stats·ops/errors·ops/collection/{sourceId}·audit in `src/main/java/com/newscurator/controller/AdminOpsStatsController.java`
- [ ] T070 [P] [US5] `AdminOpsStatsServiceTest`(단위) — FAILED 집계·OpsStats in `src/test/java/com/newscurator/service/admin/AdminOpsStatsServiceTest.java`

**Checkpoint**: 심층 가시성.

---

## Phase 8: Polish & Cross-Cutting

- [ ] T071 ★ `AdminAuthorizationIT`(실 SecurityConfig, @SpringBootTest RANDOM_PORT) in `src/test/java/com/newscurator/integration/AdminAuthorizationIT.java` — 5개 영역 대표 엔드포인트 × {비인증 401·USER 403·ADMIN 200} + 공개 `/api/v1/notices` 200
- [ ] T072 전체 변형 액션 audit 커버리지 검증 테스트(role·status·hide·scheduler·notice·push 6종 누락 0) in `src/test/java/com/newscurator/service/admin/AdminAuditCoverageTest.java`
- [ ] T073 [P] Swagger 문서화 마무리 — 전 admin 컨트롤러 @Tag/@Operation/@ApiResponses, DTO @Schema 점검
- [ ] T074 [P] CHANGELOG.html 항목 추가(tag-feature·tag-db, stats 갱신, 결정 이유 — hidden 전용컬럼·감사 명시캡처·스케줄러 영속토글·푸시 dedup) in `CHANGELOG.html`
- [ ] T075 [P] ADR 작성 in `.specify/specs/008-admin-dashboard/adr/ADR-001-admin-hidden-audit-scheduler.md` — admin_hidden_at(feed_visible 비재활용)·AdminAuditLog 명시캡처·SchedulerSetting 게이트·FR-031 범위 한정 결정
- [ ] T076 [P] quickstart 12 시나리오 수동 검증(Docker, 또는 "런타임/배포 시 검증" 명시) per `quickstart.md`
- [ ] T077 OpenApiSpecExportTest 통과 확인 → dev push 시 sync-openapi로 `/api/v1/admin/**`·`/api/v1/notices` news-pulse-spec 반영
- [x] T078 보존/정합 점검 — admin_hidden_at 인덱스 적용 확인, ExpiryService와 admin_hidden_at 독립성(만료 물리삭제가 hidden과 무관) 회귀 테스트 in `src/test/java/com/newscurator/integration/HiddenExpiryIndependenceIT.java`
- [ ] T079 분석문자(U+6790) 0건 점검 + 전체 스위트 0 fail 확인 후 dev로 PR

---

## Dependencies & Execution Order

- **Setup(P1)**: T001·T002 (의존 없음)
- **Foundational(P2)**: Setup 후. T003(V15)→T004~T009(엔티티/enum, 병렬)→T010~T013(리포, 병렬)→T014·T015(횡단 서비스)→T016. **모든 US 차단**.
- **US1(P3)**: Foundational 후. MVP 핵심.
- **US2(P4)**: Foundational 후. US1과 독립(병렬 가능).
- **US3(P5)**: Foundational 후. hidden 읽기경로(T037~T042)는 003/007 코드 수정 — 독립 파일 다수 병렬. 스케줄러 게이트(T044~T047) 병렬.
- **US4(P6)**: Foundational + 005 재활용. US3와 독립.
- **US5(P7)**: Foundational + US2 집계 패턴 참고. 최후순위.
- **Polish(P8)**: 전 US 후. T071 인가 IT는 엔드포인트 존재 필요.

### 병렬 기회
- Foundational: T004·T005·T006·T007·T008·T009 동시 / T010·T011·T012·T013 동시.
- US1: T017·T023·T024·T025·T026 / US2: T027·T034·T035·T036.
- US3 hidden 경로: T037·T038·T039·T040·T041·T042 동시(다른 파일). 스케줄러: T044·T045·T046·T047 동시.
- US 간: US1 ↔ US2 ↔ US4 병렬(Foundational 후).

## Implementation Strategy (MVP first)

- **MVP = Phase 1+2+US1(P1)**: 사용자 관리 + 자기보호 — 운영 위험 통제 최소선. 독립 배포·검증 가능.
- **+US2(P1)**: 운영 관측 추가 → P1 완성.
- **+US3·US4(P2)**: 능동 제어·공지·푸시. hidden 일관성은 US3 핵심 회귀 가드.
- **+US5(P3)**: 심층 통계.
- 각 Phase는 독립 테스트 가능 증분. 통합/인가 IT는 BigmPostgresImage·실 SecurityConfig.
