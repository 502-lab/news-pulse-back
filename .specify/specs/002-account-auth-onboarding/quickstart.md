# Quickstart — 검증 시나리오 가이드

**Feature**: 002-account-auth-onboarding  
**Date**: 2026-06-11  
**Contract**: [contracts/openapi.yaml](contracts/openapi.yaml)  
**Data Model**: [data-model.md](data-model.md)

---

## 사전 조건

```bash
# Docker 실행 중이어야 함 (Testcontainers용)
docker info

# 앱 빌드
./gradlew clean build -x test

# 로컬 프로파일로 앱 기동 (PostgreSQL + Redis 로컬 필요)
# 또는 통합 테스트만 실행 (Testcontainers가 PostgreSQL/Redis 자동 기동)
./gradlew test  # Testcontainers 사용
```

---

## 시나리오 1 — 이메일 가입 → 이메일 인증 → 피드 조회

**검증 목표**: 가입 후 미인증 → 인증 완료 → 보호 기능 접근

```bash
# 1. 현재 활성 약관 버전 조회
GET /api/v1/terms
# → terms_version_id 메모

# 2. 이메일 가입 (미인증 상태로 토큰 발급)
POST /api/v1/auth/signup
{
  "email": "test@example.com",
  "password": "Password1",
  "consents": [
    {"termsVersionId": "<SERVICE_V1_ID>", "agreed": true},
    {"termsVersionId": "<PRIVACY_V1_ID>", "agreed": true},
    {"termsVersionId": "<MARKETING_V1_ID>", "agreed": false}
  ],
  "ageConfirmed": true
}
# → 201, tokens.accessToken (emailVerified: false)

# 3. 보호 엔드포인트 접근 시도 (미인증) → 실패 확인
GET /api/v1/articles
Authorization: Bearer <accessToken>
# → 403 (이메일 인증 필요) 또는 spec이 정의한 에러

# 4. 이메일 인증 코드 검증 (WireMock stub에서 코드 추출 또는 테스트 고정 코드 사용)
POST /api/v1/auth/email-verification/verify
Authorization: Bearer <accessToken>
{"code": "123456"}
# → 200, emailVerified: true

# 5. 보호 엔드포인트 재접근 → 성공
GET /api/v1/articles
Authorization: Bearer <accessToken>
# → 200
```

**합격 기준**: 단계 3에서 접근 제한, 단계 5에서 접근 허용.

---

## 시나리오 2 — 계정 잠금 (FR-019)

**검증 목표**: 5회 연속 실패 후 잠금, 30분 자동 해제

```bash
# 가입된 계정으로 잘못된 비밀번호 5회 연속 시도
for i in 1 2 3 4 5; do
  POST /api/v1/auth/login
  {"email": "test@example.com", "password": "WrongPass1"}
  # → 401
done

# 6번째 시도 (올바른 비밀번호로도)
POST /api/v1/auth/login
{"email": "test@example.com", "password": "Password1"}
# → 401 (잠금 안내, 잘못된 비밀번호와 동일 형식 — FR-026)
```

**합격 기준**: 5회 이후 올바른 비밀번호로도 401 반환. `locked_until = now() + 30min` DB 확인.

---

## 시나리오 3 — Refresh Token Rotation + 재사용 감지 (FR-020)

**검증 목표**: Rotation 동작 및 재사용 시 family blast

```bash
# 1. 로그인 → refreshToken1 획득
POST /api/v1/auth/login ...
# → refreshToken: "token1"

# 2. Rotation — token1 사용
POST /api/v1/auth/refresh
{"refreshToken": "token1"}
# → 200, 새 refreshToken: "token2"

# 3. token1 재사용 (Grace 30초 초과 후) → family blast
POST /api/v1/auth/refresh
{"refreshToken": "token1"}
# → 401 (재사용 감지, 모든 세션 무효화)

# 4. token2도 무효화됐는지 확인
POST /api/v1/auth/refresh
{"refreshToken": "token2"}
# → 401 (family blast로 무효화)
```

**합격 기준**: token1 재사용 감지 → token2도 401.

---

## 시나리오 4 — RBAC (SC-006)

**검증 목표**: `/admin/**` 미인증 401, USER 403, ADMIN 200

```bash
# 미인증
GET /api/v1/admin/pipeline/status
# → 401

# USER 토큰
GET /api/v1/admin/pipeline/status
Authorization: Bearer <user_access_token>
# → 403

# ADMIN 토큰 (Flyway seed 계정으로 로그인)
POST /api/v1/auth/login
{"email": "${ADMIN_EMAIL}", "password": "${ADMIN_PASSWORD}"}
# → 200, tokens
GET /api/v1/admin/pipeline/status
Authorization: Bearer <admin_access_token>
# → 200
```

**합격 기준**: 세 케이스 모두 의도한 HTTP 상태 코드 반환.

---

## 시나리오 5 — 비밀번호 재설정 3단계 + 이전 세션 무효화 (FR-025)

**검증 목표**: 재설정 완료 후 기존 refresh token 전체 무효화

```bash
# 준비: 로그인해서 refreshTokenOld 획득
POST /api/v1/auth/login {"email": "test@example.com", "password": "Password1"}
# → refreshTokenOld

# 1단계: 코드 발급
POST /api/v1/auth/password-reset/request
{"email": "test@example.com"}
# → 202 (WireMock이 수신한 코드 확인 또는 DB 조회)

# 2단계: 코드 검증
POST /api/v1/auth/password-reset/verify
{"email": "test@example.com", "code": "123456"}
# → 200, resetToken

# 3단계: 비밀번호 변경
POST /api/v1/auth/password-reset/confirm
{"resetToken": "...", "newPassword": "NewPassword2"}
# → 204

# 이전 refresh token 무효화 확인
POST /api/v1/auth/refresh
{"refreshToken": "<refreshTokenOld>"}
# → 401 (세션 무효화)
```

**합격 기준**: 재설정 후 기존 refreshToken 전체 무효화.

---

## 시나리오 6 — 이메일 서비스 장애 시 503 + 한도 미차감 (FR-011)

**검증 목표**: 이메일 발송 실패 → 코드 미생성, 503, 재전송 한도 미차감

```bash
# WireMock 설정: 이메일 서비스 500 반환
stubFor(post(urlMatching(".*/send.*")).willReturn(serverError()))

# 코드 발급 시도
POST /api/v1/auth/password-reset/request
{"email": "test@example.com"}
# → 503

# 이메일 서비스 정상화 후 재시도 → 한도가 차감되지 않았으므로 성공
stubFor(post(urlMatching(".*/send.*")).willReturn(ok()))
POST /api/v1/auth/password-reset/request
{"email": "test@example.com"}
# → 202 (첫 발송으로 처리됨)
```

**합격 기준**: 503 응답 후 DB의 `hourly_count` 증가 없음.

---

## 시나리오 7 — 소셜 로그인 CSRF 방지 (FR-027)

**검증 목표**: state 불일치 시 400 반환

```bash
# 1. 인증 URL 발급
GET /api/v1/auth/social/kakao/authorize?redirectUri=...
# → {authorizationUrl, state: "legit-state"}

# 2. 위조된 state로 콜백
POST /api/v1/auth/social/kakao/callback
{"code": "valid-code", "state": "forged-state", "redirectUri": "..."}
# → 400 (state 검증 실패)
```

**합격 기준**: state 불일치 → 400.

---

## 통합 테스트 실행 (권장)

```bash
# 전체 통합 테스트 실행 (Docker 필요)
./gradlew test --tests "*AuthIntegrationTest*"
./gradlew test --tests "*RbacIntegrationTest*"
./gradlew test --tests "*TokenRotationIntegrationTest*"

# 위 테스트가 없으면 @SpringBootTest + Testcontainers 기반 테스트로 대체
./gradlew test
```

---

## WireMock 스텁 설정 참고

001과 동일한 패턴 (`CollectionIntegrationTest.java` 참조):

```java
@RegisterExtension
static WireMockExtension wireMock = WireMockExtension.newInstance().build();

// 이메일 서비스 정상 응답
wireMock.stubFor(post(urlMatching(".*/send.*"))
    .willReturn(aResponse().withStatus(200)));

// 카카오 토큰 교환 성공
wireMock.stubFor(post(urlPathEqualTo("/oauth/token"))
    .willReturn(aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"access_token\":\"mock-kakao-token\"}")));
```
