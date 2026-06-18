# Implementation Plan: 알림 (푸시·인앱)

**Branch**: `005-push-inapp-notifications` | **Date**: 2026-06-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `.specify/specs/005-push-inapp-notifications/spec.md`

---

## Summary

인앱 알림 저장·조회·읽음과 FCM 푸시·AWS SES 이메일 비동기 발송을 구현한다.
발송 신뢰성은 004에서 확립한 **DB outbox + SELECT FOR UPDATE SKIP LOCKED 클레임 패턴**을 그대로 재사용한다.
Firebase Admin SDK(푸시)를 신규 추가하며, 주간 이메일은 기존 Resend 클라이언트를 `EmailPort` 인터페이스로 추상화하여 재사용한다.
신규 Flyway 마이그레이션 V11 단일 파일로 6개 테이블을 추가한다.

---

## Technical Context

**Language/Version**: Java 25

**Primary Dependencies** (신규 추가):
- `com.google.firebase:firebase-admin:9.4.2` — FCM 푸시 발송
- 기존: Spring Boot 4.0.5, Spring Data JPA, Flyway, SpringDoc OpenAPI 3, AWS SDK v2 BOM, Testcontainers

**Storage**:
- PostgreSQL — 6개 신규 테이블 (V11 마이그레이션)
  - `device_tokens`, `topic_subscriptions`, `notification_preferences`,
    `email_subscriptions`, `notifications`, `notification_outbox`
- 기존 `accounts`, `reading_preferences`, `interests`, `tts_audios` 재사용

**Testing**:
- 단위 테스트: JUnit 5 + Mockito (Service 레이어)
- 통합 테스트: Testcontainers `BigmPostgresImage` (기존 패턴)
- Controller: @WebMvcTest + MockMvc
- FCM/SES: `PushNotificationPort` / `EmailNotificationPort` 인터페이스 mock (SDK 직접 mock 불필요)
- Outbox Processor: 실 DB Testcontainers로 FOR UPDATE SKIP LOCKED 검증

**Target Platform**: Linux server (JVM 25)

**Project Type**: REST API web-service (Spring Boot 백엔드)

**Performance Goals**:
- SC-001: 토큰 등록 응답 < 1초
- SC-002: 인앱 알림 목록 조회 < 300ms (20건 기준)
- SC-003: 트리거 → 푸시 수신 < 30초

**Constraints**:
- Firebase 서비스 계정 JSON → `FIREBASE_SERVICE_ACCOUNT_JSON`(Base64) 환경변수. 코드/git 커밋 절대 금지 (VI 원칙)
- Outbox payload(email 주소 등 개인정보) → 로그 출력 절대 금지 (VI 원칙, constitution)
- `FOR UPDATE SKIP LOCKED` — 단순 status 폴링 절대 금지 (멀티 인스턴스 중복 발송 방지, VII 원칙)
- FCM UNREGISTERED 응답 → 해당 `device_tokens` 행 즉시 삭제
- 계정당 디바이스 토큰 최대 5개 (초과 시 oldest 삭제)

**Scale/Scope**:
- 초기: 단일 EC2 인스턴스. FOR UPDATE SKIP LOCKED로 향후 멀티 인스턴스 확장 지원.

---

## Constitution Check

*GATE: 설계 전·후 모두 통과 확인*

| 원칙 | 상태 | 비고 |
|------|------|------|
| I. 레이어드 아키텍처 (Controller→Service→Repository) | ✅ PASS | DeviceTokenController → DeviceTokenService → DeviceTokenRepository. 5개 컨트롤러, 7개 서비스, 6개 레포지토리 모두 단방향. Outbox Processor는 scheduler 패키지. |
| II. Entity 비노출 + DTO 검증 | ✅ PASS | DeviceTokenRequest/Response, NotificationResponse 등 모든 컨트롤러 I/O는 DTO record. @Valid 검증. |
| III. 일관된 응답 포맷 + 전역 예외 처리 | ✅ PASS | 기존 `ApiResponse<T>` + `GlobalExceptionHandler` 재사용. 새 예외 없음 (기존 `ResourceNotFoundException` 재사용). |
| IV. 테스트 없는 비즈니스 로직 금지 | ✅ PASS | NotificationService(읽음), NotificationSendService(enqueue 멱등), NotificationOutboxProcessor(클레임) 모두 단위 테스트 필수. FOR UPDATE SKIP LOCKED → Testcontainers 통합 테스트 검증. |
| V. 스키마 변경은 마이그레이션으로만 | ✅ PASS | Flyway V11 단일 파일. ddl-auto=validate 유지. |
| VI. 보안 기본값 + 시크릿 외부화 | ✅ PASS | 모든 /me/** 인증 필수. /admin/** hasRole(ADMIN). Firebase 키·SES 설정 환경변수만. payload 로그 출력 금지. |
| VII. 멱등성 + 중복 방지 | ✅ PASS | notification_outbox.idempotency_key UNIQUE 제약. FOR UPDATE SKIP LOCKED. device_tokens.token UNIQUE(upsert). |

**헌법 위반 없음**

---

## Project Structure

### Documentation (this feature)

```text
.specify/specs/005-push-inapp-notifications/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0: 5가지 설계 결정 (FCM, SES, Outbox, 토큰수명, 발송분리)
├── data-model.md        # Phase 1: 6개 신규 테이블 DDL + enum 정의
├── quickstart.md        # Phase 1: 검증 시나리오 가이드 (6개 시나리오)
├── contracts/
│   └── openapi.yaml     # Phase 1: API 계약 (11개 엔드포인트)
├── checklists/
│   └── requirements.md  # 스펙 품질 체크리스트 (16/16 통과)
└── tasks.md             # Phase 2 출력 (speckit-tasks 명령으로 생성)
```

### Source Code (repository root)

```text
src/main/java/com/newscurator/
├── controller/
│   ├── DeviceTokenController.java            # POST /me/device-tokens, DELETE /me/device-tokens/{id}
│   ├── TopicSubscriptionController.java      # GET/PUT /me/topic-subscriptions
│   ├── NotificationController.java           # GET /me/notifications, PATCH .../read, .../read-all
│   ├── NotificationSettingsController.java   # GET/PUT /me/notification-settings, POST/DELETE /me/subscriptions/weekly-email
│   └── AdminNotificationController.java      # POST /admin/notifications/send
├── service/
│   ├── DeviceTokenService.java               # 등록(upsert), 삭제, 한도 체크
│   ├── TopicSubscriptionService.java         # 구독 조회, replace-all 갱신
│   ├── NotificationService.java              # 인앱 알림 생성, 목록 조회, 읽음 처리
│   ├── NotificationPreferencesService.java   # 알림 설정 조회/갱신 (lazy init 기본값)
│   ├── EmailSubscriptionService.java         # 주간 이메일 구독/해지
│   ├── NotificationSendService.java          # outbox enqueue (BRIEFING/BREAKING/TTS_READY/SYSTEM)
│   └── AdminNotificationService.java         # 어드민 발송 대상 조회 + bulk enqueue
├── repository/
│   ├── DeviceTokenRepository.java
│   ├── TopicSubscriptionRepository.java
│   ├── NotificationRepository.java           # 읽음 처리 벌크 @Modifying 포함
│   ├── NotificationPreferencesRepository.java
│   ├── EmailSubscriptionRepository.java
│   └── NotificationOutboxRepository.java     # FOR UPDATE SKIP LOCKED native query 포함
├── domain/
│   ├── DeviceToken.java
│   ├── TopicSubscription.java
│   ├── TopicSubscriptionId.java              # @EmbeddedId
│   ├── Notification.java
│   ├── NotificationPreferences.java
│   ├── EmailSubscription.java
│   ├── EmailSubscriptionId.java              # @EmbeddedId
│   └── NotificationOutbox.java
├── domain/enums/
│   ├── NotificationType.java                 # BREAKING, BRIEFING, TTS_READY, SYSTEM
│   ├── DevicePlatform.java                   # IOS, ANDROID, WEB
│   ├── NotificationChannel.java              # PUSH, EMAIL
│   ├── NotificationOutboxStatus.java         # PENDING, PROCESSING, SENT, FAILED
│   ├── NotificationTopic.java                # BREAKING, BRIEFING, TTS_READY
│   └── EmailSubscriptionType.java            # WEEKLY_EMAIL
├── dto/request/
│   ├── DeviceTokenRequest.java
│   ├── TopicSubscriptionsRequest.java
│   ├── NotificationSettingsRequest.java
│   └── AdminNotificationRequest.java
├── dto/response/
│   ├── DeviceTokenResponse.java
│   ├── TopicSubscriptionsResponse.java
│   ├── NotificationResponse.java
│   ├── NotificationSettingsResponse.java
│   └── EmailSubscriptionResponse.java
├── client/
│   └── notification/
│       ├── PushNotificationPort.java         # interface (테스트 격리용 추상화)
│       ├── FcmPushNotificationAdapter.java   # Firebase Admin SDK 구현체
│       ├── EmailNotificationPort.java        # interface
│       └── ResendEmailProvider.java          # Resend 구현체 (주간 이메일 — EmailPort 구현)
├── config/
│   └── FirebaseConfig.java                   # FirebaseApp 초기화 빈
└── scheduler/
    ├── NotificationOutboxProcessor.java      # FOR UPDATE SKIP LOCKED 클레임·발송 스케줄러
    ├── WeeklyEmailScheduler.java             # 매주 월요일 00:00 UTC (cron "0 0 0 * * MON")
    └── NotificationExpiryScheduler.java      # 90일 만료 인앱 알림 삭제

src/main/resources/db/migration/
└── V11__add_notification_tables.sql

src/test/java/com/newscurator/
├── controller/
│   ├── DeviceTokenControllerTest.java
│   ├── TopicSubscriptionControllerTest.java
│   ├── NotificationControllerTest.java
│   ├── NotificationSettingsControllerTest.java
│   └── AdminNotificationControllerTest.java
├── service/
│   ├── DeviceTokenServiceTest.java
│   ├── NotificationServiceTest.java
│   ├── NotificationPreferencesServiceTest.java
│   ├── EmailSubscriptionServiceTest.java
│   └── NotificationSendServiceTest.java
├── repository/
│   └── NotificationOutboxRepositoryTest.java # FOR UPDATE SKIP LOCKED 검증 (Testcontainers)
└── scheduler/
    ├── NotificationOutboxProcessorTest.java  # 클레임 패턴 + mock port 발송 검증
    └── WeeklyEmailSchedulerTest.java
```

**Structure Decision**: 기존 3계층 구조 그대로 확장. `client/notification/` 하위에 FCM/SES adapter 추가(port-adapter 패턴으로 테스트 격리). `config/FirebaseConfig.java` 신규 추가.

---

## Implementation Phases

### Phase 0 — Research ✅ 완료

산출물: [`research.md`](./research.md)

| 결정 | 결과 |
|------|------|
| 푸시 제공자 | Firebase Admin SDK v9.x (FCM) |
| 주간 이메일 | 기존 Resend 재사용 (EmailPort 추상화) |
| 발송 큐 | DB outbox + FOR UPDATE SKIP LOCKED (004 패턴 재사용) |
| 토큰 수명 관리 | UNREGISTERED 에러 시 즉시 삭제, 등록은 upsert |
| 인앱·발송 분리 | 인앱 레코드 동기 INSERT + 채널 발송 outbox 비동기 |

### Phase 1 — Design & Contracts ✅ 완료

산출물: [`data-model.md`](./data-model.md), [`contracts/openapi.yaml`](./contracts/openapi.yaml), [`quickstart.md`](./quickstart.md)

- **데이터 모델**: 6개 신규 테이블. Flyway V11 단일 마이그레이션.
  - `device_tokens`: UNIQUE(token), INDEX(account_id)
  - `topic_subscriptions`: 복합 PK (account_id, topic)
  - `notification_preferences`: 1:1 with accounts, lazy init (없으면 기본값 반환)
  - `email_subscriptions`: 복합 PK (account_id, type)
  - `notifications`: INDEX(account_id, is_read, created_at DESC), INDEX(expires_at)
  - `notification_outbox`: UNIQUE(idempotency_key), INDEX(status, next_retry_at) WHERE PENDING/PROCESSING
- **API 계약**: 11개 엔드포인트 (디바이스 토큰 2, 토픽 구독 2, 인앱 알림 3, 알림 설정 2, 이메일 구독 2)
- **트리거 매핑**:
  - `BriefingService` 완료 시 → `NotificationSendService.enqueueBriefing(accountId)`
  - `AiProcessingService` 관심사 매칭 신규 기사 → `NotificationSendService.enqueueBreaking(accountId, articleId)`
  - `TtsService` status READY 전이 시 → `NotificationSendService.enqueueTtsReady(accountId, ttsAudioId)`
  - `AdminNotificationService` → `NotificationSendService.enqueueSystem(targetAccountIds, title, body)`

---

## 의존 패키지 변경 (build.gradle)

```groovy
implementation 'com.google.firebase:firebase-admin:9.4.2'
```

---

## 환경변수 추가 (application.yaml / application-example.yaml)

```yaml
firebase:
  service-account-json: ${FIREBASE_SERVICE_ACCOUNT_JSON:}

app:
  scheduler:
    notification:
      outbox-batch-size: 50
      outbox-interval-ms: 10000
      expiry-cron: "0 0 3 * * *"
  weekly-email:
    sender: ${WEEKLY_EMAIL_SENDER:no-reply@newsift.app}
    cron: "0 0 0 * * MON"
```

---

## 복잡성 추적 (Complexity Tracking)

| 항목 | 이유 |
|------|------|
| port-adapter 패턴 (PushNotificationPort/EmailNotificationPort) | Firebase Admin SDK는 gRPC 기반으로 HTTP WireMock mock 불가. 인터페이스 추상화로 Service 단위 테스트 격리. |
| EmailPort 추상화 (ResendEmailProvider) | 주간 이메일도 Resend 재사용(MVP 저볼륨). EmailPort 인터페이스로 추상화하여 향후 볼륨 증가 시 SES 구현체로 무중단 교체 가능. |
