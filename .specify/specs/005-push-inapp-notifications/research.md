# Research: 005 알림 (푸시·인앱)

**Date**: 2026-06-18
**Branch**: `005-push-inapp-notifications`

---

## 결정 1: 푸시 알림 제공자 — Firebase Admin SDK (FCM)

**Decision**: Firebase Admin SDK v9.x (`com.google.firebase:firebase-admin`) 사용.

**Rationale**:
- iOS·Android·Web 단일 SDK로 통합 — 플랫폼별 APNs/FCM/Web Push 분기 불필요
- 서버 측 무과금(FCM은 메시지 발송 자체 무료)
- 서비스 계정 JSON 방식 인증 — IAM 토큰 갱신 없이 장기 운용 가능
- Firebase Console에서 토픽·디바이스 그룹 관리 가능

**Alternatives considered**:
- APNs 직접 연동: iOS 전용, Android 별도 FCM 필요 → 코드 분기 증가
- Expo Push Notifications: React Native 전용, 현재 스택 불일치
- OneSignal: 유료 플랜(Free tier 5만 구독자 제한), 벤더 락인

**Implementation detail**:
- 서비스 계정 JSON 키 → 환경변수 `FIREBASE_SERVICE_ACCOUNT_JSON`(Base64)로 주입
  - 배포: EC2 인스턴스 환경변수 또는 AWS Secrets Manager
  - 절대 git commit 금지 (VI 원칙)
- `FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(...).build())` — Spring `@Configuration` 빈으로 초기화
- UNREGISTERED 토큰: FCM에서 `UNREGISTERED`/`INVALID_ARGUMENT` 에러 응답 시 해당 `device_tokens` 행 삭제(stale 정리)

---

## 결정 2: 주간 이메일 — 기존 Resend 재사용 (EmailPort 추상화)

**Decision**: 기존 `HttpEmailServiceClient`(Resend) 재사용. `EmailPort` 인터페이스로 추상화하여 향후 교체 가능하게 유지.

**Rationale**:
- MVP 저볼륨에서 트랜잭션/대량 이메일 분리는 premature: Resend 무료 플랜(3,000건/월)이 현재 가입자 규모에 충분
- 기존 Resend 도메인 검증(`newsift.app` SPF/DKIM), API 키 설정이 이미 완료 → 신규 procurement 0
- `EmailPort` 인터페이스(TtsProvider 패턴과 동일)로 추상화: 향후 볼륨 증가 시 `AwsSesEmailProvider` 구현체 추가만으로 교체 가능

**Alternatives considered**:
- AWS SES v2 신규 도입: 샌드박스 해제, 도메인 DKIM/SPF 재검증, IAM 권한, Identity 생성 등 신규 procurement 필요 — MVP 단계에서 오버헤드
- SendGrid: 별도 API 키 관리 필요

**발송 추상화 구조**:
```
EmailPort (interface)
  └── ResendEmailProvider (구현체 — HttpEmailServiceClient 위임)
```
- `NotificationOutboxProcessor`는 `EmailPort`만 참조 → 구현체 변경 시 processor 코드 무수정
- 인증 이메일(`HttpEmailServiceClient`)과 알림 이메일(`ResendEmailProvider`) 모두 동일 Resend 키 공유

**발송 스케줄**: 매주 월요일 09:00 KST → UTC 00:00 Monday → cron `"0 0 0 * * MON"`.

**Runtime 검증 필요** (실제 이메일 수신 확인):
- 주간 이메일 템플릿 HTML 수신 확인 (배포 환경)

---

## 결정 3: 발송 신뢰성 — DB Outbox + 004 Claim 패턴 재사용

**Decision**: `notification_outbox` 테이블 + `FOR UPDATE SKIP LOCKED` 클레임 패턴. 004의 `TtsProcessingScheduler` 패턴 그대로 재사용.

**Rationale**:
- 현재 단일 EC2 환경이지만 향후 멀티 인스턴스 확장 시에도 중복 발송 방지 가능
- Redis Pub/Sub, SQS 대비 현재 인프라(PostgreSQL) 재사용으로 운영 복잡도 최소화
- 004에서 검증된 `SELECT … FOR UPDATE SKIP LOCKED` 패턴 재사용 — 코드 일관성

**Alternatives considered**:
- Redis Pub/Sub: 현재 Redis 미도입, 별도 인프라 필요
- SQS/SNS: 유료, 인프라 추가 설정 필요
- Spring Events (`@TransactionalEventListener`): 단일 인스턴스 로컬 이벤트만 처리 — 스케일아웃 불가

**Claim 처리 흐름**:
```
[Scheduler] FOR UPDATE SKIP LOCKED
  → PENDING → PROCESSING (tx commit)
  → [밖에서] FCM or Resend 호출
  → 성공: PROCESSING → SENT
  → 실패: PROCESSING → FAILED (attempt_count++, next_retry_at = now + backoff)
  → attempt_count >= 3: FAILED 유지 (재시도 중단)
```

**멱등성 보장**: `idempotency_key` UNIQUE 제약 (`PUSH:{accountId}:{notificationId}`, `EMAIL:WEEKLY:{accountId}:{yearWeek}`)

---

## 결정 4: FCM 토큰 수명 관리

**Decision**: 등록은 `INSERT … ON CONFLICT (token) DO UPDATE` upsert. 발송 실패(UNREGISTERED) 시 해당 토큰 삭제.

**Rationale**:
- FCM 토큰은 앱 재설치·OS 업그레이드 시 만료됨. Stale 토큰에 발송 시도하면 FCM 에러 반환.
- UNREGISTERED 에러 감지 후 즉시 삭제가 가장 단순하고 효과적인 정리 전략.
- `device_tokens.token`에 UNIQUE 제약 → 동일 토큰 재등록 시 `updated_at` 갱신만 수행.

**계정당 토큰 한도**: 최대 5개. 초과 시 `updated_at ASC` 기준 가장 오래된 토큰 삭제.

---

## 결정 5: 인앱 알림과 푸시 발송 분리

**Decision**: 알림 생성(인앱 레코드)과 채널 발송(PUSH/EMAIL)을 분리. 발송 설정 off여도 인앱 레코드는 생성.

**Rationale**:
- spec FR-015: push=false인 사용자에게도 인앱 알림은 생성 필요
- 인앱 레코드는 즉시 DB INSERT (동기). 채널 발송은 outbox를 통해 비동기.
- `NotificationService.create(...)` → 인앱 INSERT + `NotificationOutboxService.enqueue(...)` 분리

---

## 외부 의존성 요약 (배포 전 작업 필요)

| 항목 | 담당 | 준비 상태 |
|------|------|-----------|
| Firebase 서비스 계정 JSON 키 발급 | 인프라 담당 | 미완 — Firebase Console에서 프로젝트 생성 + 서비스 계정 키 다운로드 후 `FIREBASE_SERVICE_ACCOUNT_JSON`(Base64) EC2 환경변수 설정 |
