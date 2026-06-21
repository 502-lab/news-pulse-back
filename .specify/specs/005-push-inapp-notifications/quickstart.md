# Quickstart: 005 알림 (푸시·인앱) 검증 가이드

**Date**: 2026-06-18

---

## 전제 조건

- Docker 실행 중 (Testcontainers)
- `./gradlew build` 통과 상태
- Firebase Admin SDK 환경변수 미설정 시 FCM 발송은 Runtime 검증 항목
- Resend API 키 미설정 시 이메일 발송은 Runtime 검증 항목

---

## 시나리오 1: 디바이스 토큰 등록·조회·삭제 (US1)

```bash
# 1. 로그인
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"test@example.com","password":"Test1234!"}' \
  | jq -r '.data.accessToken')

# 2. 토큰 등록
curl -s -X POST localhost:8080/api/v1/me/device-tokens \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"token":"fcm-test-token-abc123","platform":"IOS"}' \
  | jq .
# 기대: 201, data.id 존재

# 3. 재등록 (동일 토큰 upsert)
curl -s -X POST localhost:8080/api/v1/me/device-tokens \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"token":"fcm-test-token-abc123","platform":"IOS"}' \
  | jq .
# 기대: 201, 새 row가 아닌 기존 row updatedAt 갱신 (DB 확인)

# 4. 삭제
TOKEN_ID=1  # step 2에서 반환된 id
curl -s -X DELETE localhost:8080/api/v1/me/device-tokens/$TOKEN_ID \
  -H "Authorization: Bearer $TOKEN"
# 기대: 204
```

**검증 포인트**: `device_tokens` 테이블에서 토큰 존재/삭제 확인.

---

## 시나리오 2: 인앱 알림 조회 및 읽음 처리 (US2)

```bash
# (사전) DB에 알림 레코드 직접 삽입 또는 admin send API 사용

# 1. 전체 알림 목록
curl -s "localhost:8080/api/v1/me/notifications?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN" | jq .
# 기대: 200, data.content 배열, data.totalElements

# 2. 미읽은 알림만
curl -s "localhost:8080/api/v1/me/notifications?unread=true" \
  -H "Authorization: Bearer $TOKEN" | jq '.data.content[].isRead'
# 기대: 전부 false

# 3. 개별 읽음 처리
NOTIF_ID=1
curl -s -X PATCH localhost:8080/api/v1/me/notifications/$NOTIF_ID/read \
  -H "Authorization: Bearer $TOKEN" | jq '.data.isRead'
# 기대: true

# 4. 전체 읽음 처리
curl -s -X PATCH localhost:8080/api/v1/me/notifications/read-all \
  -H "Authorization: Bearer $TOKEN" | jq .
# 기대: 200

# 5. 빈 목록 (알림 없을 때)
curl -s "localhost:8080/api/v1/me/notifications" \
  -H "Authorization: Bearer $TOKEN" | jq '.data.content | length'
# 기대: 0 (404 아닌 200)
```

---

## 시나리오 3: 알림 설정 변경 (US4)

```bash
# 1. 기본값 조회
curl -s localhost:8080/api/v1/me/notification-settings \
  -H "Authorization: Bearer $TOKEN" | jq .
# 기대: pushEnabled=true, emailEnabled=true, risingEnabled=true, biasEnabled=true

# 2. 푸시 off 설정
curl -s -X PUT localhost:8080/api/v1/me/notification-settings \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"pushEnabled":false,"emailEnabled":true,"risingEnabled":true,"biasEnabled":true}' \
  | jq '.data.pushEnabled'
# 기대: false

# 3. 재조회 (영속 확인)
curl -s localhost:8080/api/v1/me/notification-settings \
  -H "Authorization: Bearer $TOKEN" | jq '.data.pushEnabled'
# 기대: false
```

---

## 시나리오 4: 주간 이메일 구독·해지 (US4)

```bash
# 1. 구독
curl -s -X POST localhost:8080/api/v1/me/subscriptions/weekly-email \
  -H "Authorization: Bearer $TOKEN" | jq .
# 기대: 201

# 2. 중복 구독 시도
curl -s -X POST localhost:8080/api/v1/me/subscriptions/weekly-email \
  -H "Authorization: Bearer $TOKEN" | jq '.code'
# 기대: 409

# 3. 해지
curl -s -X DELETE localhost:8080/api/v1/me/subscriptions/weekly-email \
  -H "Authorization: Bearer $TOKEN"
# 기대: 204
```

---

## 시나리오 5: 어드민 수동 발송 (US5)

```bash
ADMIN_TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@newsift.app","password":"ADMIN_PASS"}' \
  | jq -r '.data.accessToken')

# 전체 발송
curl -s -X POST localhost:8080/api/v1/admin/notifications/send \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"서비스 점검 안내","body":"오후 2시~4시 점검 예정","targetType":"ALL"}' \
  | jq .
# 기대: 202

# USER 권한으로 시도 → 403
curl -s -X POST localhost:8080/api/v1/admin/notifications/send \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"테스트","body":"테스트","targetType":"ALL"}' \
  | jq '.code'
# 기대: 403
```

---

## 시나리오 6: Outbox 발송 파이프라인 (US3) — 통합 테스트

```bash
# ./gradlew test 실행 후 아래 테스트 클래스 통과 확인
# 실제 FCM/Resend 연동은 Runtime 검증 필요

# NotificationOutboxProcessorTest:
# - PENDING 항목 클레임 → PROCESSING 전환 확인
# - MockFcmClient/MockEmailPort 성공 시 → SENT 전환 확인
# - MockFcmClient 실패(3회) → FAILED 상태 확인
# - UNREGISTERED 에러 → device_tokens 행 삭제 확인

./gradlew test --tests "com.newscurator.scheduler.NotificationOutboxProcessorTest"
```

---

## Runtime 검증 필요 항목 (배포 환경에서만 검증 가능)

| 항목 | 이유 |
|------|------|
| FCM 실제 푸시 수신 | Firebase 서비스 계정 JSON 키 필요 |
| Resend 주간 이메일 실제 수신 | 배포 환경 Resend API 키 + `newsift.app` 도메인 검증 |
| FCM UNREGISTERED 실제 토큰 정리 | 실제 만료 토큰 발급 필요 |

---

## API 계약 참조

- [contracts/openapi.yaml](./contracts/openapi.yaml) — 11개 엔드포인트 전체 스키마
- [data-model.md](./data-model.md) — 6개 신규 테이블 DDL
