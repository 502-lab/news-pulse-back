# Research: 계정·인증·온보딩·인가

**Feature**: 002-account-auth-onboarding  
**Date**: 2026-06-11  
**Status**: Complete — no NEEDS CLARIFICATION remain

---

## R1. OAuth Provider 플로우 (Kakao / Google / Apple)

### Decision
state는 **HMAC 서명 JWT(stateless)** 로 발급한다. Claims: `{ nonce(UUID), provider, exp(10분) }`. 백엔드가 state를 생성해 프론트엔드에 반환하고, 프론트엔드는 redirect URL 이동 시 포함한다. 콜백 시 서명·exp·provider 일치를 검증해 CSRF를 방지한다. 서버측 저장소(Redis 등) 불필요.

### Rationale
- FR-027(CSRF 방지)을 위해 state는 백엔드가 단방향으로 발급해야 검증 신뢰성이 보장된다.
- 모바일/SPA 모두 동일한 `POST /auth/social/{provider}/callback` 엔드포인트를 사용할 수 있어 클라이언트 타입 분기가 필요 없다.
- 앱에 spring-data-redis 의존성·사용이 없음(검증됨). 운영 단일 t3.medium에 Redis 미프로비저닝. docker-compose의 redis 컨테이너는 현재 앱에서 미사용 — 002에서도 사용하지 않는다.
- 단기 CSRF nonce는 서명 검증 + 짧은 TTL(10분)로 충분. 서버측 폐기 없이도 재사용 방지 가능(exp 기반 만료).

### Alternatives Considered
- Redis 임시 저장 방식: state를 Redis에 TTL 10분으로 저장. 서버측 폐기 가능하나 Redis 인프라 필요 — 미프로비저닝 환경에서 과도한 의존성.
- 프론트엔드 직접 state 관리: 모바일 환경에서 PKCE를 사용하면 가능하나, state 검증 책임이 클라이언트로 이동해 백엔드 제어 범위가 줄어든다.

### Provider-Specific Notes

#### Kakao
- Authorization URL: `https://kauth.kakao.com/oauth/authorize`
- Token endpoint: `https://kauth.kakao.com/oauth/token`
- UserInfo endpoint: `https://kapi.kakao.com/v2/user/me`
  - 반환 필드: `id`(고유 숫자 ID), `kakao_account.email`, `kakao_account.profile.nickname`
  - 이메일은 선택 동의 항목 — nullable 처리 필수
- client_secret: 선택(보안 강화 시 사용). 환경변수로만 주입.

#### Google
- Authorization URL: `https://accounts.google.com/o/oauth2/v2/auth`
- Token endpoint: `https://oauth2.googleapis.com/token`
- UserInfo endpoint: `https://www.googleapis.com/oauth2/v3/userinfo`
  - 반환 필드: `sub`(고유 ID), `email`, `name`
  - OpenID Connect ID token으로도 userinfo 추출 가능(JWT 디코드)
- 이메일은 표준 scope `email` 요청 시 항상 반환됨.

#### Apple
- Authorization URL: `https://appleid.apple.com/auth/authorize`
  - 추가 파라미터: `response_mode=form_post` (ID Token 수신을 위해)
- Token endpoint: `https://appleid.apple.com/auth/token`
- **client_secret**: 표준 shared secret 불가. Apple은 **ES256 서명된 JWT**를 client_secret으로 요구.
  - JWT claims: `iss`=Team ID, `sub`=Client ID(Service ID), `aud`=`https://appleid.apple.com`, `exp`(6개월 이내), `iat`, `kid`=Key ID
  - 서명 키: Apple Developer에서 발급한 `.p8` Private Key (환경변수로만 주입, 절대 커밋 금지)
  - **생성 주기**: 최대 6개월 TTL이므로 서버 기동 시 또는 만료 전 재생성 로직 필요. 초기 구현은 애플리케이션 기동 시 생성 후 캐싱.
- **Identity Token**: Apple은 ID Token(JWT)을 반환하므로 `sub`(고유 사용자 ID)와 `email`을 직접 추출 가능.
- **이메일 숨기기(Hide My Email)**: `@privaterelay.appleid.com` 형식의 중계 이메일 반환. 이를 그대로 저장하고 식별자로 사용. Account.email은 nullable, 소셜 연결의 `provider_user_id`(`sub`)가 primary 식별자.
- **최초 로그인에만 userInfo 반환**: Apple은 첫 인증 시에만 이메일/이름을 반환함. 이후에는 ID Token의 `sub`만 신뢰. SocialConnection에 저장 필수.

### 공통 Adapter/Port 패턴
```
OAuthProviderPort (interface)
  + getAuthorizationUrl(state, redirectUri) : String
  + fetchOAuthUserInfo(code, redirectUri) : OAuthUserInfo
  
OAuthUserInfo (record)
  + providerId : String       // provider의 고유 사용자 ID
  + email : String (nullable)
  + provider : SocialProvider

KakaoOAuthAdapter implements OAuthProviderPort
GoogleOAuthAdapter implements OAuthProviderPort
AppleOAuthAdapter implements OAuthProviderPort
  // Apple은 추가로 generateClientSecretJwt() 내부 메서드
```

---

## R2. Spring Security JWT Stateless 설정

### Decision
Spring Security 6.x의 `SecurityFilterChain`을 완전 stateless JWT Bearer 방식으로 구성한다.
`JwtAuthenticationFilter` (extends `OncePerRequestFilter`)를 `UsernamePasswordAuthenticationFilter` 앞에 삽입한다.

### Key Configuration Points
```
SecurityFilterChain:
  - sessionManagement: STATELESS
  - csrf: disabled (JWT Bearer는 CSRF 취약하지 않음 — state 파라미터로 소셜 CSRF만 별도 처리)
  - permitAll 화이트리스트 (명시적):
      POST /api/v1/auth/signup
      POST /api/v1/auth/login
      POST /api/v1/auth/refresh
      GET  /api/v1/auth/social/{provider}/authorize
      POST /api/v1/auth/social/{provider}/callback
      POST /api/v1/auth/password-reset/request
      POST /api/v1/auth/password-reset/verify
      POST /api/v1/auth/password-reset/confirm
      GET  /actuator/health
  - 그 외 모든 경로: authenticated()
      → 001의 GET /api/v1/articles 등 기존 엔드포인트도 이 규칙에 포함됨 (전역 적용)
  - /api/v1/admin/**: hasRole("ADMIN")
```

### 이메일 인증 엔드포인트 — authenticated (emailVerified=false 허용)

이메일 인증 엔드포인트(`/api/v1/auth/email-verification/**`)는 `permitAll`이 **아니라** `authenticated`다. 가입 시 발급된 액세스 토큰(emailVerified=false)을 사용해 호출한다. 이를 구현하는 방법:

- **`/me/**`**: emailVerified=true 필수 — `JwtAuthenticationFilter` 또는 `@PreAuthorize`에서 JWT claim `emailVerified`를 검사. false이면 403(이메일 인증 필요).
- **`/auth/email-verification/**`**: authenticated(유효한 JWT 필수)이되, emailVerified=false 계정도 허용 — Security 레이어에서 별도 게이트 없이 통과, Service 계층에서 이미 인증된 계정인지만 확인.
- 두 경로를 구분하는 방식: `SecurityFilterChain`에서 순서대로 `.requestMatchers("/api/v1/auth/email-verification/**").authenticated()` 먼저, `.anyRequest().authenticated()` 뒤에 배치하고, `/me/**` 진입 시 `JwtAuthenticationFilter`가 `emailVerified` claim을 검사해 false이면 `EmailNotVerifiedException`(403) 던지는 방식 채택.

### JWT Claims
```
Access Token:
  sub: accountId (UUID)
  role: USER | ADMIN
  emailVerified: boolean
  exp, iat, iss

Refresh Token: DB에만 해시 저장, 토큰 본체는 클라이언트에만.
  본체: accountId + familyId + random UUID → HMAC-SHA256 or SecureRandom UUID
  DB 저장: SHA-256 해시
```

### RBAC 구현
- `@PreAuthorize("hasRole('ADMIN')")` 또는 SecurityFilterChain에서 `.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")`
- 최초 ADMIN: `V2__seed_admin.sql` (Flyway) — 비밀번호는 BCrypt 해시, 환경변수에서 읽어 Flyway placeholder로 주입
  ```sql
  -- application.yaml에서 flyway.placeholders.admin-email, admin-password-hash 주입
  INSERT INTO accounts (..., role) VALUES (${admin-email}, ${admin-password-hash}, 'ADMIN', ...)
  ON CONFLICT DO NOTHING;
  ```
  또는 애플리케이션 기동 시 CommandLineRunner로 환경변수 기반 seed (ADMIN 계정 미존재 시만 생성).

---

## R3. Refresh Token Rotation + Grace Period

### Decision
다음 전략을 순서대로 적용한다:

1. **정상 사용**: access token 만료 → refresh token으로 갱신 → 기존 refresh token 소비(consumed_at 설정) + 새 refresh token 발급(같은 family_id 유지).

2. **Grace Period (멱등 처리)**: 소비된 refresh token이 재사용되었을 때, `consumed_at`으로부터 **30초 이내**이면 정상 재시도로 간주. 같은 family_id + consumed_at 이후 발급된 가장 최신 토큰이 아직 유효하면 그것을 반환(멱등 응답).

3. **재사용 감지 (Grace 초과)**: 30초 초과 후 소비된 토큰 재사용 → **family-first blast**: 해당 `family_id`의 모든 활성 refresh token을 즉시 무효화.

4. **에스컬레이션**: 동일 accountId에서 짧은 시간(5분) 내 family blast가 2회 이상 발생 → **account-wide blast**: 해당 계정 모든 refresh token 무효화 후 재로그인 요구.

> 스펙 FR-020 "모든 활성 세션 즉시 무효화"는 4번 에스컬레이션까지 포함. 기본 동작은 3번(family blast)이며, 강한 도난 신호 시 4번으로 자동 상승.

### RefreshToken Entity 핵심 필드
```
id            UUID PK
account_id    FK → accounts
family_id     UUID  -- 기기 세션 체인 식별자
token_hash    VARCHAR(64)  -- SHA-256(token)
device_id     VARCHAR(255) nullable
issued_at     TIMESTAMP WITH TIME ZONE
expires_at    TIMESTAMP WITH TIME ZONE
consumed_at   TIMESTAMP WITH TIME ZONE nullable  -- 소비 시각
is_revoked    BOOLEAN DEFAULT FALSE  -- 강제 무효화(blast)
```

### Alternatives Considered
- 단순 invalidate-all: grace period 없으면 동시 요청(모바일 네트워크 재시도)으로 오탐 발생.
- family 없이 token-by-token: blast radius 제어 불가.

---

## R4. VerificationCode 엔티티 통합 설계

### Decision
`VerificationCode` **단일 엔티티** + `purpose` 필드 (`EMAIL_VERIFY` / `PASSWORD_RESET`)

### Rationale
- PasswordResetCode와 EmailVerificationCode의 라이프사이클이 동일: 코드 해시, 만료 15분, 재전송 5회/시간, 오입력 5회 초과 무효화.
- 테이블 1개 / Service 메서드 재사용 → 코드 중복 제거.
- 향후 새 purpose(e.g., PHONE_VERIFY) 추가 시 enum 값 추가로 확장 가능.

### Schema Concept
```sql
verification_codes (
  id           UUID PK,
  account_id   FK → accounts,
  purpose      ENUM('EMAIL_VERIFY', 'PASSWORD_RESET'),
  code_hash    VARCHAR(64),      -- SHA-256(6자리 코드)
  expires_at   TIMESTAMP WITH TIME ZONE,
  attempt_count  SMALLINT DEFAULT 0,
  issued_count_hourly SMALLINT DEFAULT 0,  -- 시간 단위 재전송 횟수
  window_start TIMESTAMP WITH TIME ZONE,   -- 시간 window 시작
  is_used      BOOLEAN DEFAULT FALSE,
  created_at   TIMESTAMP WITH TIME ZONE
)
```

### Alternatives Considered
- 별도 테이블 (`password_reset_codes`, `email_verification_codes`): 구조 중복, JOIN 없어지는 장점이 있으나 동일 로직 두 곳에 유지 필요.

---

## R5. Email Service Client 통합

### Decision
`EmailServiceClient` 인터페이스 + 환경별 구현체. 외부 연동 실패 시 `EmailDeliveryException` 던지기 → Service에서 캐치 후 503 반환.

### Interface
```java
public interface EmailServiceClient {
    void sendVerificationCode(String toEmail, String code) throws EmailDeliveryException;
    void sendPasswordResetCode(String toEmail, String code) throws EmailDeliveryException;
}
```

### Integration Pattern
- 실 구현체: HTTP client (RestClient) 기반. base URL / API key는 환경변수.
- 테스트 구현체: WireMock stubbing. 발송 성공 stub → 정상 흐름. 5xx stub → `EmailDeliveryException` 발생 → Service에서 코드 미생성, 503 반환, 한도 미차감 확인.

### WireMock 호환성 확인 (001에서 검증된 패턴)
- `SimpleClientHttpRequestFactory`를 사용하는 RestClientConfig + WireMock base URL 환경변수 주입.
- HTTP/1.1 강제 (h2c 비활성화) — 001 통합 테스트에서 이미 검증됨.
