# Data Model: 005 알림 (푸시·인앱)

**Date**: 2026-06-18
**Flyway**: V12 단일 마이그레이션 (`V12__add_notification_tables.sql`)

---

## 신규 테이블

### device_tokens

FCM 디바이스 푸시 토큰 (계정:토큰 = 1:N, 계정당 최대 5개).

```sql
CREATE TABLE device_tokens (
    id          BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    account_id  BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    token       VARCHAR(512) NOT NULL,
    platform    VARCHAR(20)  NOT NULL,  -- IOS | ANDROID | WEB
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_device_token UNIQUE (token)
);

CREATE INDEX idx_device_tokens_account ON device_tokens (account_id);
```

**Java Entity**: `DeviceToken.java`
```
id, accountId, token, platform(DevicePlatform enum), createdAt, updatedAt
```

---

### topic_subscriptions

알림 토픽 구독 (계정:토픽 = N:M, 복합 PK).

```sql
CREATE TABLE topic_subscriptions (
    account_id    BIGINT      NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    topic         VARCHAR(30) NOT NULL,  -- BREAKING | BRIEFING | TTS_READY
    subscribed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, topic)
);
```

**Java Entity**: `TopicSubscription.java` + `TopicSubscriptionId.java` (`@EmbeddedId`)
```
id(TopicSubscriptionId: accountId+topic), subscribedAt
```

---

### notification_preferences

사용자별 채널·카테고리 알림 수신 설정 (계정:설정 = 1:1, 없으면 기본값=전부 ON).

```sql
CREATE TABLE notification_preferences (
    account_id     BIGINT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    push_enabled   BOOLEAN NOT NULL DEFAULT true,
    email_enabled  BOOLEAN NOT NULL DEFAULT true,
    rising_enabled BOOLEAN NOT NULL DEFAULT true,
    bias_enabled   BOOLEAN NOT NULL DEFAULT true
);
```

**Java Entity**: `NotificationPreferences.java`
```
accountId, pushEnabled, emailEnabled, risingEnabled, biasEnabled
```

---

### email_subscriptions

이메일 채널 구독 (주간 이메일 등).

```sql
CREATE TABLE email_subscriptions (
    account_id    BIGINT      NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    type          VARCHAR(30) NOT NULL,  -- WEEKLY_EMAIL
    active        BOOLEAN     NOT NULL DEFAULT true,
    subscribed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, type)
);
```

**Java Entity**: `EmailSubscription.java` + `EmailSubscriptionId.java` (`@EmbeddedId`)
```
id(EmailSubscriptionId: accountId+type), active, subscribedAt
```

---

### notifications

인앱 알림 레코드 (90일 보존).

```sql
CREATE TABLE notifications (
    id           BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    account_id   BIGINT       NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    type         VARCHAR(30)  NOT NULL,    -- BREAKING | BRIEFING | TTS_READY | SYSTEM
    title        VARCHAR(255) NOT NULL,
    body         TEXT,
    reference_id VARCHAR(100),             -- nullable: article_id, tts_audio_id 등
    is_read      BOOLEAN      NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ  NOT NULL     -- = created_at + 90 days
);

CREATE INDEX idx_notifications_account_feed
    ON notifications (account_id, is_read, created_at DESC);
CREATE INDEX idx_notifications_expiry
    ON notifications (expires_at);
```

**Java Entity**: `Notification.java`
```
id, accountId, type(NotificationType enum), title, body, referenceId,
isRead, createdAt, expiresAt
```

---

### notification_outbox

비동기 발송 큐 (Outbox 패턴 — FOR UPDATE SKIP LOCKED 클레임).

```sql
CREATE TABLE notification_outbox (
    id               BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    account_id       BIGINT       NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    notification_id  BIGINT       REFERENCES notifications(id) ON DELETE SET NULL,
    channel          VARCHAR(20)  NOT NULL,               -- PUSH | EMAIL
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING|PROCESSING|SENT|FAILED
    payload          JSONB        NOT NULL,               -- {token, title, body} or {to, subject, html}
    idempotency_key  VARCHAR(200) NOT NULL,
    attempt_count    INT          NOT NULL DEFAULT 0,
    next_retry_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_outbox_idempotency UNIQUE (idempotency_key)
);

-- 클레임 쿼리용 인덱스 (PENDING/PROCESSING 상태만)
CREATE INDEX idx_outbox_claim
    ON notification_outbox (status, next_retry_at)
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE INDEX idx_outbox_account ON notification_outbox (account_id);
```

**Java Entity**: `NotificationOutbox.java`
```
id, accountId, notificationId(nullable), channel(NotificationChannel enum),
status(NotificationOutboxStatus enum), payload(String/JSON), idempotencyKey,
attemptCount, nextRetryAt, createdAt, updatedAt
```

**Payload 구조**:
- PUSH: `{"token":"fcm-token","title":"제목","body":"내용"}`
- EMAIL WEEKLY: `{"to":"user@email.com","subject":"주간 뉴스레터","htmlBody":"..."}`

**주의**: payload에 개인정보(이메일) 포함 → 로그 출력 절대 금지 (constitution VI)

---

## 열거형 (Enum)

```
NotificationType:   BREAKING, BRIEFING, TTS_READY, SYSTEM
DevicePlatform:     IOS, ANDROID, WEB
NotificationChannel: PUSH, EMAIL
NotificationOutboxStatus: PENDING, PROCESSING, SENT, FAILED
NotificationTopic:  BREAKING, BRIEFING, TTS_READY
EmailSubscriptionType: WEEKLY_EMAIL
```

---

## 기존 테이블 — 변경 없음

- `accounts`: 재사용 (account_id FK 참조)
- `reading_preferences`: 재사용 (브리핑 시간 참조 — `brief_time`)
- `interests`: 재사용 (BREAKING 매칭 기준)
- `tts_audios`: 재사용 (TTS_READY 트리거 참조 — `reference_id`)

---

## 마이그레이션 파일

`src/main/resources/db/migration/V12__add_notification_tables.sql`

상기 CREATE TABLE 6개 + CREATE INDEX 4개를 단일 파일에 포함.

---

## 상태 전이 — notification_outbox

```
PENDING
  └─[클레임 스케줄러]─→ PROCESSING
       ├─[FCM/Resend 성공]──→ SENT (종료)
       └─[실패, attempt_count < 3]──→ FAILED(next_retry_at += backoff)──→ PENDING(재시도)
       └─[실패, attempt_count >= 3]──→ FAILED (종료)
```

Backoff: 1분 → 5분 → 15분 (attempt_count 1/2/3).
