---

description: "005 알림 (푸시·인앱) Task List"
---

# Tasks: 005 알림 (푸시·인앱)

**Input**: Design documents from `.specify/specs/005-push-inapp-notifications/`

**Prerequisites**: plan.md ✅ spec.md ✅ research.md ✅ data-model.md ✅ contracts/openapi.yaml ✅

**Tests**: 고위험 영역(outbox 클레임, 멱등성, 포트 격리, FCM 토큰 수명, 주간 이메일 멱등) 포함 — 명시적 요청에 따라 실질 테스트 태스크 포함.

**Organization**: User Story 기준 Phase 분리 — US1·US2가 MVP, US3·US4는 P2, US5는 P3.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 실행 가능 (다른 파일, 의존성 없음)
- **[Story]**: 해당 User Story 레이블 (US1–US5)
- Setup/Foundational/Polish 단계는 Story 레이블 없음

---

## Phase 1: Setup (공유 인프라)

**Purpose**: build.gradle 의존성 추가, 환경변수 설정, Flyway V12 마이그레이션 파일 생성

- [X] T001 Add `com.google.firebase:firebase-admin:9.4.2` dependency to `build.gradle` (신규 추가, AWS sesv2 미추가 — Resend 재사용)
- [X] T002 [P] Add firebase/notification-scheduler/weekly-email config to `src/main/resources/application.yaml` and `src/main/resources/application-example.yaml` (`firebase.service-account-json`, `app.scheduler.notification.*`, `app.weekly-email.*`)
- [X] T003 [P] Create Flyway migration `src/main/resources/db/migration/V12__add_notification_tables.sql` — 6개 테이블(device_tokens, topic_subscriptions, notification_preferences, email_subscriptions, notifications, notification_outbox) + 4개 인덱스 (data-model.md DDL 기준). **주의**: V11은 terms_content에 할당됨 — V12 사용 필수

---

## Phase 2: Foundational (공통 Enum·인터페이스 — 모든 US 선행)

**Purpose**: 모든 User Story에서 공유되는 Enum 및 Port 인터페이스 정의

**⚠️ CRITICAL**: 이 Phase 완료 전 User Story 구현 불가

- [X] T004 [P] Create `src/main/java/com/newscurator/domain/enums/NotificationType.java` enum (BREAKING, BRIEFING, TTS_READY, SYSTEM)
- [X] T005 [P] Create `src/main/java/com/newscurator/domain/enums/DevicePlatform.java` enum (IOS, ANDROID, WEB)
- [X] T006 [P] Create `src/main/java/com/newscurator/domain/enums/NotificationChannel.java` enum (PUSH, EMAIL)
- [X] T007 [P] Create `src/main/java/com/newscurator/domain/enums/NotificationOutboxStatus.java` enum (PENDING, PROCESSING, SENT, FAILED)
- [X] T008 [P] Create `src/main/java/com/newscurator/domain/enums/NotificationTopic.java` enum (BREAKING, BRIEFING, TTS_READY)
- [X] T009 [P] Create `src/main/java/com/newscurator/domain/enums/EmailSubscriptionType.java` enum (WEEKLY_EMAIL)
- [X] T010 [P] Create `src/main/java/com/newscurator/client/notification/PushNotificationPort.java` interface — `void send(String token, String title, String body) throws FcmUnregisteredException`
- [X] T011 [P] Create `src/main/java/com/newscurator/client/notification/EmailPort.java` interface — `void send(String to, String subject, String htmlBody)`

**Checkpoint**: Phase 2 완료 — US1 구현 시작 가능

---

## Phase 3: US1 — 디바이스 토큰 등록·토픽 구독 (Priority: P1) 🎯 MVP

**Goal**: 인증된 사용자가 FCM 디바이스 토큰을 upsert 등록/삭제하고, 알림 토픽(BREAKING/BRIEFING/TTS_READY)을 구독 관리한다.

**Independent Test**: Docker 실행 후 `./gradlew test --tests "*DeviceToken*" --tests "*TopicSubscription*"` 통과. `device_tokens` upsert(토큰 재등록 시 DB row 증가 없음), max-5 eviction, topic replace-all DB 단언.

### Tests for US1

- [X] T012 [P] [US1] Create `src/test/java/com/newscurator/repository/DeviceTokenRepositoryTest.java` — (1) 동일 token upsert 2회 → row 1개만 존재(idempotency), (2) account당 6번째 등록 시 가장 오래된 토큰 삭제(max-5 eviction), (3) account 삭제 시 cascade delete. Testcontainers BigmPostgresImage.
- [X] T013 [P] [US1] Create `src/test/java/com/newscurator/repository/TopicSubscriptionRepositoryTest.java` — (1) replaceAll: 기존 구독 전부 삭제 후 신규 저장, (2) account 삭제 시 cascade delete. BigmPostgresImage.
- [X] T014 [P] [US1] Create `src/test/java/com/newscurator/controller/DeviceTokenControllerTest.java` — @WebMvcTest: POST /me/device-tokens → 201, 동일 token 재등록 → 201 (upsert), DELETE /me/device-tokens/{id} → 204, 존재하지 않는 id → 404, 인증 없음 → 401

### Implementation for US1

- [X] T015 [P] [US1] Create `src/main/java/com/newscurator/domain/DeviceToken.java` entity — @Entity, @Builder, @Getter, id(BIGINT IDENTITY), accountId, token(VARCHAR 512), platform(DevicePlatform), createdAt, updatedAt
- [X] T016 [P] [US1] Create `src/main/java/com/newscurator/domain/TopicSubscription.java` entity and `src/main/java/com/newscurator/domain/TopicSubscriptionId.java` @EmbeddedId — accountId + topic(NotificationTopic), subscribedAt
- [X] T017 [P] [US1] Create `src/main/java/com/newscurator/repository/DeviceTokenRepository.java` — upsert native query (`INSERT … ON CONFLICT (token) DO UPDATE SET updated_at=now()`), `findTop5ByAccountIdOrderByUpdatedAtAsc` (max-5 eviction용), `deleteByIdAndAccountId`
- [X] T018 [P] [US1] Create `src/main/java/com/newscurator/repository/TopicSubscriptionRepository.java` — `deleteAllByAccountId`, `findByAccountId`
- [X] T019 [US1] Create `src/main/java/com/newscurator/service/DeviceTokenService.java` — register(upsert + max-5 eviction: count>5이면 oldest 삭제), delete(소유권 검증), deleteByToken(FCM 토큰 무효화 시 사용)
- [X] T020 [US1] Create `src/main/java/com/newscurator/service/TopicSubscriptionService.java` — getTopics(accountId), replaceAll(accountId, topics) @Transactional(deleteAll + saveAll)
- [X] T021 [P] [US1] Create DTOs: `src/main/java/com/newscurator/dto/request/DeviceTokenRequest.java` (@NotBlank token, @NotNull platform), `src/main/java/com/newscurator/dto/response/DeviceTokenResponse.java`, `src/main/java/com/newscurator/dto/request/TopicSubscriptionsRequest.java`, `src/main/java/com/newscurator/dto/response/TopicSubscriptionsResponse.java`
- [X] T022 [US1] Create `src/main/java/com/newscurator/controller/DeviceTokenController.java` — POST /api/v1/me/device-tokens → 201(ApiResponse.created), DELETE /api/v1/me/device-tokens/{tokenId} → 204 (no body)
- [X] T023 [US1] Create `src/main/java/com/newscurator/controller/TopicSubscriptionController.java` — GET /api/v1/me/topic-subscriptions → 200, PUT /api/v1/me/topic-subscriptions → 200

**Checkpoint**: US1 완료 — 디바이스 토큰 등록·삭제, 토픽 구독 replace-all 독립 검증 가능

---

## Phase 4: US2 — 인앱 알림 목록·읽음 (Priority: P1) 🎯 MVP

**Goal**: 수신된 인앱 알림을 목록 조회하고 개별/전체 읽음 처리한다. 90일 만료 정책 적용.

**Independent Test**: `./gradlew test --tests "*NotificationRepository*" --tests "*NotificationController*"` 통과. DB 직접 삽입 후 목록 조회·읽음 처리·빈 목록 200(404 아님) 단언.

### Tests for US2

- [X] T024 [P] [US2] Create `src/test/java/com/newscurator/repository/NotificationRepositoryTest.java` — (1) pageable 조회, (2) unread=true 필터, (3) markAllReadByAccountId @Modifying 벌크 업데이트 DB 단언, (4) deleteExpired WHERE expires_at < now(). BigmPostgresImage.
- [X] T025 [P] [US2] Create `src/test/java/com/newscurator/controller/NotificationControllerTest.java` — @WebMvcTest: GET /me/notifications → 200, 알림 없을 때 빈 배열 200(404 금지), PATCH /{id}/read → 200, 다른 account ID → 404, PATCH /read-all → 200

### Implementation for US2

- [X] T026 [P] [US2] Create `src/main/java/com/newscurator/domain/Notification.java` entity — id(IDENTITY), accountId, type(NotificationType), title, body, referenceId(nullable VARCHAR 100), isRead(default false), createdAt, expiresAt(= createdAt + 90일, @PrePersist 계산)
- [X] T027 [P] [US2] Create `src/main/java/com/newscurator/repository/NotificationRepository.java` — `findByAccountId(accountId, pageable)`, `findByAccountIdAndIsReadFalse(accountId, pageable)`, `@Modifying @Query markAllReadByAccountId(accountId)`, `@Modifying @Query deleteByExpiresAtBefore(now)`, `findByIdAndAccountId` (소유권 검증)
- [X] T028 [US2] Create `src/main/java/com/newscurator/service/NotificationService.java` — createNotification(accountId, type, title, body, referenceId), listNotifications(accountId, unread, pageable), markRead(accountId, notificationId), markAllRead(accountId)
- [X] T029 [P] [US2] Create `src/main/java/com/newscurator/dto/response/NotificationResponse.java` record — id, type, title, body, referenceId, isRead, createdAt, expiresAt
- [X] T030 [US2] Create `src/main/java/com/newscurator/controller/NotificationController.java` — GET /api/v1/me/notifications(?unread, ?page, ?size) → 200 Page, PATCH /{id}/read → 200, PATCH /read-all → 200
- [X] T031 [US2] Create `src/main/java/com/newscurator/scheduler/NotificationExpiryScheduler.java` — @Scheduled(cron = `${app.scheduler.notification.expiry-cron}`, zone="UTC"), `notificationRepository.deleteByExpiresAtBefore(Instant.now())`

**Checkpoint**: US1+US2 완료 — MVP 기능(토큰·구독·인앱 알림 목록·읽음) 독립 검증 가능. 이 시점이 배포 가능한 최소 범위.

---

## Phase 5: US3 — 발송 파이프라인 (Priority: P2)

**Goal**: FCM 푸시 / Resend 이메일 비동기 발송. DB outbox + FOR UPDATE SKIP LOCKED 클레임 패턴(004 재사용). FCM UNREGISTERED 토큰 정리. 멱등 enqueue.

**Independent Test**: `./gradlew test --tests "*NotificationOutboxRepository*" --tests "*NotificationSendService*" --tests "*NotificationOutboxProcessor*"` 통과. 클레임 동시성, 멱등성, UNREGISTERED 토큰 삭제, 3회 재시도 FAILED 단언.

### Tests for US3 ⚠️ 고위험 영역

- [X] T032 [P] [US3] Create `src/test/java/com/newscurator/scheduler/NotificationOutboxRepositoryTest.java` — BigmPostgresImage, **FOR UPDATE SKIP LOCKED 클레임 동시성 검증**: (1) 2개 스레드(ExecutorService)가 동시에 `claimPendingBatch(1)` 호출 → 둘 중 하나만 1개 row 반환, 나머지 빈 결과. (2) 같은 트랜잭션 내 row는 PROCESSING으로 마킹됨(커밋 전 재클레임 시 빈 결과). (3) UNIQUE(idempotency_key) 위반 시 DataIntegrityViolationException — 004 `TtsAudioClaimer` 테스트와 동일 수준.
- [X] T033 [P] [US3] Create `src/test/java/com/newscurator/service/NotificationSendServiceTest.java` — **멱등성 단위 테스트** (mock repository): (1) PUSH:{accountId}:{notificationId} 동일 key 두 번 호출 시 두 번째는 DataIntegrityViolationException catch → 무시(positive 중복 차단). (2) 다른 notificationId로 호출 시 정상 통과(negative). (3) EMAIL:WEEKLY:{accountId}:{yearWeek} 동일 week 두 번 호출 시 중복 차단, 다른 weekKey 통과.
- [X] T034 [P] [US3] Create `src/test/java/com/newscurator/scheduler/NotificationOutboxProcessorTest.java` — BigmPostgresImage, mock PushNotificationPort + mock EmailPort: **(1)** PENDING→PROCESSING(tx 내)→SENT flow: DB row status 확인. **(2)** FCM UNREGISTERED 응답 시뮬레이션 → PushNotificationPort.send() throws FcmUnregisteredException → device_tokens row 삭제 확인 + outbox status=FAILED. **(3)** attempt_count 3회 후 FAILED 유지(next_retry_at 갱신 안 됨). **(4)** 동시 2 인스턴스 시뮬레이션(ExecutorService): 같은 PENDING row를 두 인스턴스가 동시 처리 시 PushNotificationPort.send() 1회만 호출됨. **(5)** FirebaseApp 미초기화(`Optional<FirebaseApp>` empty) 시 FcmPushNotificationAdapter.send() 예외 발생 → outbox status=FAILED, 단 해당 accountId의 `notifications` row는 정상 존재(인앱 생성 영향 없음 — FR-012 직접 단언).
- [X] T035 [P] [US3] Create trigger unit tests — `src/test/java/com/newscurator/service/BriefingServiceTriggerTest.java`: `getOrCreateTodayBrief()` 신규 생성 시 `NotificationSendService.enqueueBriefing(accountId)` 1회 호출, 캐시 반환 시 0회. `src/test/java/com/newscurator/service/AiProcessingTriggerTest.java`: `processArticle()` 관심사 매칭 account 존재 시 `enqueueBreaking(accountId, articleId)` 호출. `src/test/java/com/newscurator/scheduler/TtsProcessingSchedulerTriggerTest.java`: `tts.complete()` 후 `enqueueTtsReady(accountId, ttsAudioId)` 호출. 모두 mock NotificationSendService.

### Implementation for US3

- [X] T036 [P] [US3] Create `src/main/java/com/newscurator/exception/FcmUnregisteredException.java` (RuntimeException) — token 필드 포함 (NotificationOutboxProcessor에서 token 삭제 시 사용)
- [X] T037 [P] [US3] Create `src/main/java/com/newscurator/config/FirebaseConfig.java` — `@ConditionalOnProperty(name="firebase.service-account-json", matchIfMissing=false)` + Base64 decode → ServiceAccountCredentials → FirebaseApp.initializeApp(). 빈 값 시 Firebase 미초기화(테스트·로컬 안전).
- [X] T038 [P] [US3] Create `src/main/java/com/newscurator/client/notification/FcmPushNotificationAdapter.java` implements PushNotificationPort — injects `Optional<FirebaseApp>`, `FirebaseMessaging.getInstance(app).send(Message)`, `UNREGISTERED`/`INVALID_ARGUMENT` 에러코드 감지 시 `FcmUnregisteredException(token)` throw
- [X] T039 [P] [US3] Create `src/main/java/com/newscurator/client/notification/ResendEmailProvider.java` implements EmailPort — `HttpEmailServiceClient` 위임, `EmailServiceRequest` 생성·발송
- [X] T040 [P] [US3] Create `src/main/java/com/newscurator/domain/NotificationOutbox.java` entity — id(IDENTITY), accountId, notificationId(nullable), channel(NotificationChannel), status(NotificationOutboxStatus, default PENDING), payload(@Column(columnDefinition="jsonb") String), idempotencyKey, attemptCount(default 0), nextRetryAt, createdAt, updatedAt
- [X] T041 [P] [US3] Create `src/main/java/com/newscurator/repository/NotificationOutboxRepository.java` — native query `claimPendingBatch(@Param("limit") int limit)`: `SELECT … FROM notification_outbox WHERE status='PENDING' AND next_retry_at<=now() ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED`, `updateStatusById(id, status, nextRetryAt, attemptCount)`, `deleteByIdempotencyKey`
- [X] T042 [US3] Create `src/main/java/com/newscurator/service/NotificationSendService.java` — `enqueuePush(accountId, notificationId, token, title, body)`: idempotency_key=`PUSH:{accountId}:{notificationId}`, INSERT ON CONFLICT DO NOTHING. `enqueueBreaking`, `enqueueBriefing`, `enqueueTtsReady`, `enqueueWeeklyEmail(accountId, yearWeek)`: idempotency_key=`EMAIL:WEEKLY:{accountId}:{yearWeek}`. `enqueueSystem(accountId, title, body)`. 내부적으로 NotificationService.createNotification() 호출(inapp record) + NotificationOutbox INSERT.
- [X] T043 [US3] Create `src/main/java/com/newscurator/scheduler/NotificationOutboxProcessor.java` — `@Scheduled(fixedDelayString="${app.scheduler.notification.outbox-interval-ms}")`, @Transactional(propagation=REQUIRES_NEW)으로 claimPendingBatch → tx commit → 락 밖에서 PushNotificationPort/EmailPort 호출. 성공: status→SENT. FcmUnregisteredException: deviceTokenService.deleteByToken(token) + status→FAILED. 그 외 실패: attemptCount++ + backoff(1m/5m/15m) + status→FAILED 또는 PENDING reset. attempt>=3 시 FAILED 유지.
- [X] T044 [P] [US3] Integrate BriefingService trigger in `src/main/java/com/newscurator/service/BriefingService.java` — `getOrCreateTodayBrief()` 내 신규 brief 생성 경로(isNew 분기)에서 `notificationSendService.enqueueBriefing(account.getId())` 호출
- [X] T045 [P] [US3] Integrate AiProcessing trigger in `src/main/java/com/newscurator/service/AiProcessingService.java` — `processArticle()` 완료 후 관심사 매칭 accountId 목록 조회 → `notificationSendService.enqueueBreaking(accountId, article.getId())` 호출 (TopicSubscriptionRepository로 BREAKING 구독자 + 관심사 교차 필터)
- [X] T046 [P] [US3] Integrate TtsService trigger in `src/main/java/com/newscurator/scheduler/TtsProcessingScheduler.java` — `tts.complete(audioKey, null)` 직후 `notificationSendService.enqueueTtsReady(tts.getAccountId(), tts.getId())` 호출

**Checkpoint**: US3 완료 — 푸시·이메일 비동기 발송, 클레임 클레임, 멱등 enqueue, 트리거 연동 독립 검증 가능

---

## Phase 6: US4 — 알림 설정·이메일 채널 (Priority: P2)

**Goal**: 채널(push/email)/카테고리(rising/bias) 알림 설정 세분화 + 주간 이메일 구독·해지. 주간 이메일 스케줄러 멱등.

**Independent Test**: `./gradlew test --tests "*NotificationPreferences*" --tests "*WeeklyEmail*"` 통과. lazy init 기본값, PUT persist, 동일 week 중복 발송 없음 단언.

### Tests for US4

- [X] T047 [P] [US4] Create `src/test/java/com/newscurator/service/NotificationPreferencesServiceTest.java` — (1) 미존재 accountId → getOrDefault 전부 true 반환(lazy init, DB row 없음), (2) PUT persist 후 GET 일치, (3) pushEnabled=false 시 두 가지 모두 단언: **a)** NotificationSendService.enqueuePush 호출 경로에서 해당 account 제외 확인(mock NotificationSendService), **b)** 동일 트리거 이벤트 시 `notifications` row는 DB에 정상 생성됨(인앱 알림 생성 경로 독립 — FR-015 직접 단언).
- [X] T048 [P] [US4] Create `src/test/java/com/newscurator/scheduler/WeeklyEmailSchedulerTest.java` — BigmPostgresImage: **(1)** active 구독자 1명 존재 시 스케줄러 실행 → notification_outbox에 EMAIL:WEEKLY:{accountId}:{yearWeek} 1건. **(2)** 같은 yearWeek 재실행 → outbox 추가 없음(중복 차단). **(3)** 다음 주차로 Mock clock 변경 후 실행 → outbox 신규 1건 추가.

### Implementation for US4

- [X] T049 [P] [US4] Create `src/main/java/com/newscurator/domain/NotificationPreferences.java` entity — @Id=accountId(@MapsId 또는 직접 FK), pushEnabled(default true), emailEnabled(default true), risingEnabled(default true), biasEnabled(default true)
- [X] T050 [P] [US4] Create `src/main/java/com/newscurator/domain/EmailSubscription.java` entity and `src/main/java/com/newscurator/domain/EmailSubscriptionId.java` @EmbeddedId — accountId + type(EmailSubscriptionType), active, subscribedAt
- [X] T051 [P] [US4] Create `src/main/java/com/newscurator/repository/NotificationPreferencesRepository.java` and `src/main/java/com/newscurator/repository/EmailSubscriptionRepository.java` — EmailSubscriptionRepository: `findByIdAccountIdAndIdType`, `existsByIdAccountIdAndIdTypeAndActiveTrue`
- [X] T052 [US4] Create `src/main/java/com/newscurator/service/NotificationPreferencesService.java` — `getOrDefault(accountId)`: findById 없으면 new NotificationPreferences(all true), `update(accountId, request)`: upsert 저장
- [X] T053 [US4] Create `src/main/java/com/newscurator/service/EmailSubscriptionService.java` — `subscribe(accountId, WEEKLY_EMAIL)`: 이미 active → 409, `unsubscribe(accountId, WEEKLY_EMAIL)`: 없으면 404, `isActive(accountId, type)`
- [X] T054 [P] [US4] Create DTOs: `src/main/java/com/newscurator/dto/request/NotificationSettingsRequest.java`, `src/main/java/com/newscurator/dto/response/NotificationSettingsResponse.java`, `src/main/java/com/newscurator/dto/response/EmailSubscriptionResponse.java`
- [X] T055 [US4] Create `src/main/java/com/newscurator/controller/NotificationSettingsController.java` — GET/PUT /api/v1/me/notification-settings → 200, POST /api/v1/me/subscriptions/weekly-email → 201(ApiResponse.created), DELETE /api/v1/me/subscriptions/weekly-email → 204
- [X] T056 [US4] Create `src/main/java/com/newscurator/scheduler/WeeklyEmailScheduler.java` — @Scheduled(cron="${app.weekly-email.cron}", zone="UTC"), cron="0 0 0 * * MON". EmailSubscriptionRepository로 active WEEKLY_EMAIL 구독자 조회 → `notificationSendService.enqueueWeeklyEmail(accountId, yearWeek)` 호출. yearWeek = `YearWeek.now(ZoneId.of("Asia/Seoul"))` 포맷 "yyyy-Www".

**Checkpoint**: US4 완료 — 알림 설정·이메일 채널 독립 검증 가능

---

## Phase 7: US5 — 어드민 수동 발송 (Priority: P3)

**Goal**: ADMIN 권한으로 전체/계정목록/토픽구독자 대상 SYSTEM 알림 수동 발송.

**Independent Test**: `./gradlew test --tests "*AdminNotification*"` 통과. USER 권한 403, ADMIN 권한 202, targetType 분기별 bulk enqueue 단언.

### Tests for US5

- [X] T057 [P] [US5] Create `src/test/java/com/newscurator/controller/AdminNotificationControllerTest.java` — @WebMvcTest: USER role → 403, ADMIN role + targetType=ALL → 202(ApiResponse.accepted), targetType=ACCOUNT_IDS [1,2] → 202, targetType=TOPIC_SUBSCRIBERS + topic=BRIEFING → 202. mock AdminNotificationService.
- [X] T058 [P] [US5] Create `src/test/java/com/newscurator/service/AdminNotificationServiceTest.java` — mock TopicSubscriptionRepository + mock NotificationSendService: **(1)** targetType=ALL → 전체 active account 조회 후 enqueueSystem 호출 횟수 단언, **(2)** targetType=ACCOUNT_IDS [1,2] → enqueueSystem이 정확히 accountId 1·2에게만 호출됨, **(3)** targetType=TOPIC_SUBSCRIBERS + topic=BRIEFING → TopicSubscriptionRepository.findByTopic(BRIEFING) 결과 대상만 enqueueSystem 호출.

### Implementation for US5

- [X] T059 [P] [US5] Create `src/main/java/com/newscurator/domain/enums/AdminTargetType.java` enum (ALL, ACCOUNT_IDS, TOPIC_SUBSCRIBERS)
- [X] T060 [P] [US5] Create `src/main/java/com/newscurator/dto/request/AdminNotificationRequest.java` record — @NotBlank title, @NotBlank body, @NotNull targetType(AdminTargetType), accountIds(nullable List), topic(nullable NotificationTopic)
- [X] T061 [US5] Create `src/main/java/com/newscurator/service/AdminNotificationService.java` — `sendNotification(request)`: targetType별 accountId 목록 조회(ALL: 전체 active accounts, ACCOUNT_IDS: 직접 지정, TOPIC_SUBSCRIBERS: TopicSubscriptionRepository.findByTopic) → `notificationSendService.enqueueSystem(accountId, title, body)` bulk 호출 (@Async 또는 순차)
- [X] T062 [US5] Create `src/main/java/com/newscurator/controller/AdminNotificationController.java` — POST /api/v1/admin/notifications/send (@PreAuthorize("hasRole('ADMIN')"), @Valid @RequestBody, → AdminNotificationService.sendNotification → ApiResponse.accepted(null) 202)

**Checkpoint**: US5 완료 — 어드민 발송 독립 검증 가능

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T063 [P] Add Swagger `@Tag`, `@Operation`, `@io.swagger.v3.oas.annotations.responses.ApiResponses` to all 5 new controllers: `src/main/java/com/newscurator/controller/DeviceTokenController.java`, `TopicSubscriptionController.java`, `NotificationController.java`, `NotificationSettingsController.java`, `AdminNotificationController.java`
- [X] T064 [P] Update `src/test/java/com/newscurator/integration/OpenApiSpecExportTest.java` DynamicPropertySource — add `firebase.service-account-json=` (empty string) to suppress FirebaseConfig conditional bean during test app boot
- [X] T065 Update `CHANGELOG.html` — add `tag-feature` entry for 005 알림 기능 (device tokens, in-app notifications, outbox pipeline, notification settings, weekly email, admin send). 날짜 그룹·stats bar 갱신.
- [X] T066 Run `./gradlew build` from repo root — verify (1) Flyway V12 migration chain 통과, (2) 기존 테스트 회귀 없음, (3) 신규 테스트 통과 수 보고

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 의존 없음 — 즉시 시작 가능
- **Foundational (Phase 2)**: Phase 1 완료 후 — 모든 US 선행 조건 (**BLOCKS 모든 US**)
- **US1 (Phase 3)**: Phase 2 완료 후 — US2/US3/US4/US5와 독립적으로 병렬 진행 가능
- **US2 (Phase 4)**: Phase 2 완료 후 — US1과 독립, 단 US3의 NotificationSendService가 NotificationService.createNotification()에 의존
- **US3 (Phase 5)**: Phase 2 + **US1(DeviceTokenService)** + **US2(NotificationService)** 완료 후
- **US4 (Phase 6)**: Phase 2 + US3의 NotificationSendService 완료 후
- **US5 (Phase 7)**: Phase 2 + US3의 NotificationSendService + US1의 TopicSubscriptionRepository 완료 후
- **Polish (Phase 8)**: 모든 US 완료 후

### User Story Dependencies

- **US1 (P1)** → Phase 2 완료면 독립 시작 가능
- **US2 (P1)** → Phase 2 완료면 독립 시작 가능 (US1과 병렬 가능)
- **US3 (P2)** → US1 DeviceTokenService + US2 NotificationService 완료 필요
- **US4 (P2)** → US3 NotificationSendService 완료 필요 (enqueueWeeklyEmail 사용)
- **US5 (P3)** → US3 NotificationSendService + US1 TopicSubscriptionRepository 완료 필요

### Parallel Opportunities (Phase 내)

- Phase 2: T004–T011 전부 병렬 가능 (독립 enum/interface 파일)
- US1: T015–T018 entity+repo 4개 병렬, T019–T020 서비스는 entity 완료 후 병렬
- US2: T026–T027 entity+repo 병렬, T028 서비스는 repo 완료 후
- US3: T036–T041 entity+adapter+repo 6개 병렬, T044–T046 트리거 3개 병렬
- US4: T049–T051 entity+repo 병렬
- US5: T059(AdminTargetType enum) + T060(DTO) 병렬

---

## Parallel Example: US3 (가장 복잡한 Phase)

```bash
# Phase 5 시작 시 병렬 실행 가능 태스크 그룹:
Group A (테스트 선작성): T032, T033, T034, T035
Group B (인프라): T036(FcmUnregisteredException), T037(FirebaseConfig), T038(FcmAdapter), T039(ResendProvider)
Group C (엔티티+레포): T040(NotificationOutbox entity), T041(OutboxRepository)

# T042 (NotificationSendService) — T040, T041 완료 후
# T043 (OutboxProcessor) — T042 + T038 + T039 완료 후
# T044, T045, T046 (트리거) — T042 완료 후 병렬
```

---

## Implementation Strategy

### MVP First (US1 + US2 Only — Phase 1~4)

1. Phase 1: Setup (T001–T003)
2. Phase 2: Foundational enum + interfaces (T004–T011)
3. Phase 3: US1 — 디바이스 토큰·토픽 구독 (T012–T023)
4. Phase 4: US2 — 인앱 알림 목록·읽음 (T024–T031)
5. **STOP & VALIDATE**: `./gradlew build` + quickstart 시나리오 1·2 검증
6. 배포 가능한 최소 기능: 토큰 등록/삭제, 토픽 구독, 인앱 알림 조회/읽음

### Incremental Delivery

1. MVP(US1+US2) → 배포/검증
2. US3 파이프라인 추가 → FCM 푸시 실제 발송 검증 (Firebase 키 필요)
3. US4 설정·이메일 → 알림 채널 제어 검증
4. US5 어드민 → 수동 발송 검증

---

## Crown Jewel Tests (고위험 영역 테스트 태스크)

| Task | 테스트 대상 | 핵심 단언 |
|------|-----------|----------|
| **T032** | NotificationOutboxRepositoryTest | FOR UPDATE SKIP LOCKED 동시 클레임 — 1개 row를 2스레드가 요청 시 1개만 성공 |
| **T033** | NotificationSendServiceTest | idempotency_key 중복 enqueue 차단 (PUSH+EMAIL:WEEKLY) positive+negative |
| **T034** | NotificationOutboxProcessorTest | PENDING→SENT flow + UNREGISTERED 토큰 삭제 + 3회 FAILED + 멀티인스턴스 중복 발송 없음 |
| **T035** | BriefingServiceTriggerTest 외 2개 | 트리거 3종(Briefing/Breaking/TtsReady) enqueue 호출 경로 단언 |
| **T048** | WeeklyEmailSchedulerTest | 동일 yearWeek 멱등(중복 없음) + 신규 주차 정상 발송 |

---

## Notes

- **BigmPostgresImage**: 통합 테스트는 `BigmPostgresImage.NAME` 사용 — `"postgres:16-alpine"` 직접 금지 (V9 pg_bigm 의존)
- **[P]**: 다른 파일, 선행 의존 없는 태스크 — 병렬 실행 가능
- **Story 레이블**: Setup/Foundational/Polish Phase 제외, 모든 US 태스크에 필수
- **커밋**: 각 Checkpoint(Phase 완료) 시점에 커밋 권장
- **Flyway V12 체인**: V11(terms_content)까지 존재 확인 후 V12 작성 — V11은 이미 점유됨
- **로그 금지**: NotificationOutbox payload(email주소 포함) 로그 출력 절대 금지 (constitution VI)
