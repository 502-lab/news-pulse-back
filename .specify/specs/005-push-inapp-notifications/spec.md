# Feature Specification: 알림 (푸시·인앱)

**Feature Branch**: `005-push-inapp-notifications`

**Created**: 2026-06-18

**Status**: Draft

**Input**: 디바이스 토큰 등록, 토픽 구독(속보·브리핑·TTS완료), 푸시 발송, 인앱 목록·읽음·모두읽음, 알림 타입(속보·AI브리핑·TTS완료·시스템), 알림 설정 세분화(push/email/rising/bias), 이메일 채널(주간 이메일 구독), 어드민 발송 파이프라인.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — 디바이스 토큰 등록 및 토픽 구독 (Priority: P1)

사용자가 앱을 설치하면 디바이스 토큰을 서버에 등록하고, 관심 있는 알림 토픽(속보·브리핑·TTS완료)을 구독한다. 이후 해당 토픽으로 발송된 푸시 알림을 수신할 수 있다.

**Why this priority**: 모든 푸시 알림의 전제 조건. 토큰 등록 없이 어떤 알림도 발송할 수 없으며, 토픽 구독 없이는 사용자별 수신 여부를 제어할 수 없다.

**Independent Test**: `POST /me/device-tokens`로 토큰을 등록한 뒤 `GET /me/device-tokens`로 등록 목록을 조회해 존재 여부를 확인. 토픽 구독 설정 후 시스템이 해당 토픽 수신 대상에 계정을 포함하는지 DB 수준에서 검증 가능.

**Acceptance Scenarios**:

1. **Given** 인증된 사용자가 **When** iOS 디바이스 토큰과 플랫폼 정보를 등록하면 **Then** 해당 토큰이 계정과 연결되어 저장되고 201이 반환된다.
2. **Given** 이미 등록된 토큰으로 **When** 재등록(갱신)하면 **Then** 기존 토큰을 덮어쓰고 200이 반환된다(중복 방지, 멱등).
3. **Given** 등록된 디바이스가 **When** 앱을 삭제하거나 로그아웃하면 **Then** `DELETE /me/device-tokens/{tokenId}`로 토큰이 제거되어 이후 발송 대상에서 제외된다.
4. **Given** 인증된 사용자가 **When** BRIEFING 토픽을 구독하면 **Then** 브리핑 시간 트리거 발송 대상에 포함된다.
5. **Given** 인증된 사용자가 **When** 구독 중인 토픽을 해제하면 **Then** 해당 토픽 발송 대상에서 제외된다.
6. **Given** 비인증 요청이 **When** 디바이스 토큰 등록을 시도하면 **Then** 401이 반환된다.

---

### User Story 2 — 인앱 알림 목록 조회 및 읽음 처리 (Priority: P1)

사용자가 앱 내 알림 센터에서 수신된 알림 목록을 확인하고, 개별 또는 전체를 읽음 처리할 수 있다.

**Why this priority**: 푸시 알림을 놓쳤거나 앱을 열었을 때 인앱에서 확인할 수 있어야 한다. US1과 함께 알림 기능의 핵심 사용자 가치.

**Independent Test**: 알림 레코드를 DB에 직접 삽입한 뒤 `GET /me/notifications`로 목록 조회, `PATCH /notifications/{id}/read`·`PATCH /notifications/read-all`로 읽음 처리 후 상태 변경 확인.

**Acceptance Scenarios**:

1. **Given** 인증된 사용자가 **When** `GET /me/notifications`를 요청하면 **Then** 최신순 알림 목록(읽음 여부·타입·제목·시각 포함)이 페이지네이션으로 반환된다.
2. **Given** 알림 목록 조회 시 **When** `?unread=true` 필터를 사용하면 **Then** 읽지 않은 알림만 반환된다.
3. **Given** 미읽은 알림이 있을 때 **When** `PATCH /notifications/{id}/read`를 호출하면 **Then** 해당 알림이 읽음 상태로 변경된다.
4. **Given** 여러 미읽은 알림이 있을 때 **When** `PATCH /notifications/read-all`을 호출하면 **Then** 해당 계정의 모든 미읽은 알림이 읽음 처리된다.
5. **Given** 다른 사용자의 알림 ID로 **When** 읽음 처리를 시도하면 **Then** 404가 반환된다.
6. **Given** 알림이 90일을 초과하면 **Then** 만료 정책에 따라 자동 삭제된다.

---

### User Story 3 — 알림 발송 파이프라인 (Priority: P2)

시스템이 트리거 조건(개인화 매칭 기사 등장, 브리핑 시간 도달)을 감지하면 해당 사용자의 디바이스에 푸시 알림을 비동기로 발송하고, 동시에 인앱 알림 레코드를 생성한다.

**Why this priority**: US1·US2가 기반을 제공한 뒤, 실제 자동 발송 파이프라인이 동작해야 사용자 가치가 완성된다.

**Independent Test**: 브리핑 시간 트리거를 직접 호출하거나 `POST /internal/trigger/notifications`로 수동 실행, 대상 계정에 인앱 알림 레코드가 생성되고 발송 큐에 항목이 추가됨을 DB에서 확인.

**Acceptance Scenarios**:

1. **Given** 사용자가 브리핑 시간(002 설정값)에 **When** 브리핑 알림 스케줄러가 실행되면 **Then** BRIEFING 토픽 구독자에게 푸시 알림이 발송되고 인앱 알림 레코드가 생성된다.
2. **Given** 사용자의 관심사와 일치하는 새 기사가 수집될 때 **When** 개인화 매칭 로직이 실행되면 **Then** 해당 사용자에게 BREAKING 타입 알림이 발송된다(007 급상승 트리거 전까지는 관심사 직접 매칭).
3. **Given** TTS 처리가 완료될 때 **When** TTS_READY 이벤트가 발생하면 **Then** 요청 사용자에게 TTS_READY 타입 알림이 발송된다.
4. **Given** 발송 중 외부 푸시 서비스 오류가 발생하면 **When** 재시도 로직이 동작하면 **Then** 최대 3회 재시도 후 실패 상태로 기록되며 서비스 전체에 영향을 주지 않는다.
5. **Given** 동일 알림 조건이 중복 발생하면 **When** 발송 큐에 추가 시 **Then** 동일 계정·동일 참조 ID 중복 발송이 방지된다(멱등).
6. **Given** 사용자가 push 알림 설정을 off로 설정했을 때 **When** 발송 파이프라인이 실행되면 **Then** 해당 사용자에게 푸시는 발송하지 않지만 인앱 알림은 생성된다.

---

### User Story 4 — 알림 설정 세분화 및 이메일 채널 (Priority: P2)

사용자가 채널(푸시·이메일)과 카테고리(속보·브리핑·급상승·편향) 단위로 수신 여부를 제어하고, 주간 이메일 뉴스레터를 구독/해지한다.

**Why this priority**: 사용자 맞춤 제어가 없으면 알림 피로도로 이탈을 유발한다. 이메일 채널은 푸시와 독립된 부가 채널.

**Independent Test**: `PUT /me/notification-settings`로 설정 변경 후 `GET /me/notification-settings`로 저장 확인. 주간 이메일 구독 `POST /me/subscriptions/weekly-email` → 구독 레코드 생성 확인.

**Acceptance Scenarios**:

1. **Given** 인증된 사용자가 **When** `PUT /me/notification-settings`로 push=false를 설정하면 **Then** 이후 발송 파이프라인에서 해당 사용자에게 푸시가 발송되지 않는다.
2. **Given** 인증된 사용자가 **When** email=true, rising=false로 설정하면 **Then** 이메일 알림은 수신하지만 급상승 알림은 받지 않는다.
3. **Given** 인증된 사용자가 **When** `POST /me/subscriptions/weekly-email`을 호출하면 **Then** 주간 이메일 구독이 활성화된다.
4. **Given** 이미 구독 중인 사용자가 **When** `DELETE /me/subscriptions/weekly-email`을 호출하면 **Then** 구독이 해지된다.
5. **Given** 주간 이메일 발송 스케줄(매주 월요일 09:00 KST)이 되면 **When** 스케줄러가 실행되면 **Then** 구독 활성 사용자에게만 이메일이 발송된다.

---

### User Story 5 — 어드민 수동 발송 파이프라인 (Priority: P3)

관리자가 특정 대상(전체·특정 계정·토픽 구독자)에게 SYSTEM 타입 알림을 수동으로 발송한다.

**Why this priority**: 008 어드민 UI 연동을 위한 API 기반 준비. 운영상 긴급 공지나 시스템 알림에 필요하며, US1~US4 완성 후 추가.

**Independent Test**: `POST /api/v1/admin/notifications/send`로 전송 요청 후 대상 계정의 인앱 알림 레코드 생성 확인. ADMIN 외 계정 요청 시 403 반환 확인.

**Acceptance Scenarios**:

1. **Given** ADMIN 권한 계정이 **When** `POST /api/v1/admin/notifications/send`로 전체 대상 SYSTEM 알림을 요청하면 **Then** 모든 활성 계정에 인앱 알림이 생성된다.
2. **Given** ADMIN이 특정 계정 ID 목록을 지정하면 **When** 발송 요청을 하면 **Then** 해당 계정들에게만 알림이 생성된다.
3. **Given** USER 권한 계정이 **When** 어드민 발송 API를 호출하면 **Then** 403이 반환된다.

---

### Edge Cases

- 디바이스 토큰이 만료(FCM 토큰 갱신)된 경우 발송 실패 시 토큰 자동 삭제 처리.
- 한 계정에 최대 5개 디바이스 토큰까지 허용(초과 시 가장 오래된 토큰 자동 삭제).
- 알림 발송 직전 사용자 계정이 비활성화(탈퇴·정지)된 경우 발송 중단.
- 동일 계정에 동시 다수 알림이 발생할 경우 배치 처리로 묶어 발송(폭주 방지).
- 인앱 알림 목록이 비어있을 때 빈 배열과 200 반환(404 금지).
- 주간 이메일 발송 중 일부 수신자 실패 시 나머지 발송 계속 진행(격리).

---

## Requirements *(mandatory)*

### Functional Requirements

**디바이스 토큰 & 토픽 구독**
- **FR-001**: 시스템은 인증된 사용자의 디바이스 토큰(플랫폼: iOS/Android/Web)을 등록·갱신·삭제해야 한다.
- **FR-002**: 동일 토큰 재등록 시 중복 생성 없이 기존 레코드를 갱신(upsert)해야 한다.
- **FR-003**: 사용자는 토픽(BREAKING/BRIEFING/TTS_READY) 단위로 구독을 설정·해제할 수 있어야 한다.
- **FR-004**: 계정당 최대 5개 디바이스 토큰을 허용하며 초과 시 가장 오래된 토큰을 자동 삭제해야 한다.

**인앱 알림**
- **FR-005**: 시스템은 알림 발송 시 인앱 알림 레코드(타입·제목·본문·읽음여부·생성시각)를 생성해야 한다.
- **FR-006**: 사용자는 인앱 알림 목록을 최신순·페이지네이션으로 조회할 수 있어야 한다.
- **FR-007**: 읽음 여부 필터(`?unread=true`)를 지원해야 한다.
- **FR-008**: 개별 알림(`PATCH /notifications/{id}/read`) 및 전체 읽음(`PATCH /notifications/read-all`)을 지원해야 한다.
- **FR-009**: 인앱 알림은 생성 후 90일이 지나면 자동 삭제되어야 한다.

**발송 파이프라인**
- **FR-010**: 발송은 비동기 큐(DB 기반 outbox 패턴) 방식으로 처리하며, 발송 실패 시 최대 3회 재시도해야 한다.
- **FR-011**: 동일 계정·동일 참조 ID(기사 ID 등)에 대한 중복 발송을 방지해야 한다(멱등).
- **FR-012**: 외부 푸시 서비스(Firebase) 장애 시 인앱 알림 생성은 영향받지 않아야 한다.
- **FR-013**: 알림 타입별 트리거는 다음과 같다:
  - BRIEFING: 002에서 설정한 브리핑 시간 기준 스케줄러
  - BREAKING: 사용자 관심사와 매칭되는 신규 기사 수집 시(007 급상승 감지 전까지 직접 매칭)
  - TTS_READY: 004 TTS 처리 완료 이벤트
  - SYSTEM: 어드민 수동 발송

**알림 설정**
- **FR-014**: 사용자는 채널(push·email)과 카테고리(rising·bias) 단위로 수신 여부를 설정할 수 있어야 한다.
- **FR-015**: push=false인 사용자에게는 푸시를 발송하지 않되 인앱 알림은 생성해야 한다.
- **FR-016**: 사용자는 주간 이메일 뉴스레터를 구독·해지할 수 있어야 한다.
- **FR-017**: 주간 이메일은 매주 월요일 09:00 KST 고정 발송하며, 구독 활성 사용자에게만 발송한다.

**어드민**
- **FR-018**: ADMIN 권한 계정만 `POST /api/v1/admin/notifications/send`를 호출할 수 있어야 한다.
- **FR-019**: 발송 대상을 전체·특정 계정 목록·토픽 구독자 중 선택할 수 있어야 한다.

### Key Entities

- **Notification**: 인앱 알림 레코드. `id, accountId, type(BREAKING/BRIEFING/TTS_READY/SYSTEM), title, body, referenceId(optional), read, createdAt, expiresAt`
- **DeviceToken**: 사용자 디바이스 푸시 토큰. `id, accountId, token, platform(IOS/ANDROID/WEB), updatedAt`
- **TopicSubscription**: 토픽 구독 관계. `accountId, topic(BREAKING/BRIEFING/TTS_READY), subscribedAt`
- **NotificationSettings**: 사용자 알림 수신 설정. `accountId, pushEnabled, emailEnabled, risingEnabled, biasEnabled`
- **EmailSubscription**: 이메일 채널 구독. `accountId, type(WEEKLY_EMAIL), active, subscribedAt`
- **NotificationOutbox**: 발송 큐(outbox 패턴). `id, accountId, notificationId, channel(PUSH/EMAIL), status(PENDING/SENT/FAILED), attemptCount, nextRetryAt`

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 디바이스 토큰 등록 요청은 1초 이내에 완료되어야 한다.
- **SC-002**: 인앱 알림 목록 조회는 300ms 이내에 응답해야 한다(페이지당 20건 기준).
- **SC-003**: 발송 트리거 발생 후 푸시 알림이 사용자 디바이스에 도달하는 시간이 30초 이내여야 한다.
- **SC-004**: 발송 큐의 실패 항목은 재시도 3회 이후 100% 실패 상태로 기록되어 운영자가 추적 가능해야 한다.
- **SC-005**: 동일 조건의 알림이 중복 발생해도 사용자는 동일 알림을 1회만 수신해야 한다.
- **SC-006**: push 설정 off 사용자는 푸시를 0건 수신하되 인앱 알림은 정상 수신해야 한다.
- **SC-007**: 외부 푸시 서비스 장애 상황에서도 인앱 알림 생성 성공률은 99% 이상이어야 한다.

---

## Assumptions

- Firebase Admin SDK를 푸시 발송 채널로 사용한다(iOS·Android·Web 통합 지원, 별도 APNs 직접 연동 불필요).
- 발송 큐는 DB 기반 outbox 패턴으로 구현한다(현재 인프라 재사용, Redis/SQS 도입 불필요).
- 주간 이메일 발송 시점은 매주 월요일 09:00 KST 고정이다(사용자 설정 불필요 — 단순화).
- 인앱 알림 보존 기간은 90일로 001 기사 보존 정책과 동일하게 적용한다.
- rising(급상승) 알림 트리거는 007 스펙 구현 후 연결한다. 이번 스펙에서는 타입 정의만 포함.
- bias(편향) 알림 트리거는 006 스펙 구현 후 연결한다. 이번 스펙에서는 타입 정의만 포함.
- TTS_READY 발송 트리거는 004 TtsProcessingScheduler 완료 이벤트에 연결한다.
- 002에서 정의된 `briefing_settings.brief_time`을 브리핑 알림 발송 시간으로 재사용한다.
- 003에서 정의된 관심사(interests·keywords)를 BREAKING 매칭 기준으로 재사용한다.
- 이메일 발송은 기존 email-service(Resend)를 재사용한다.

## Clarifications

### Session 2026-06-18

- Q: 주간 이메일 발송 시점 → A: 매주 월요일 09:00 KST 고정 (사용자 설정 불필요, 단순화)
