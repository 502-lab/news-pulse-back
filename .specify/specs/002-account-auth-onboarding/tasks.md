# Tasks: 계정·인증·온보딩·인가

**Input**: `.specify/specs/002-account-auth-onboarding/`  
**Branch**: `002-account-auth-onboarding`  
**Stack**: Java 25 / Spring Boot 4.0.x / Spring Security 6.x / PostgreSQL + Flyway / Testcontainers + WireMock

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: 병렬 실행 가능 (다른 파일, 의존성 없음)
- **[Story]**: 해당 태스크가 속한 User Story
- 모든 경로는 `src/main/java/com/newscurator/` 기준 (이하 `…/`)

---

## Phase 1: Setup (공유 인프라)

**Purpose**: 빌드 의존성 + DB 마이그레이션 + 환경변수 템플릿 — 이후 모든 Phase가 의존

- [X] T001 build.gradle에 jjwt(0.12.x), spring-security, BCrypt 의존성 추가 (`build.gradle`)
- [X] T002 Flyway V2 계정·인증 스키마 마이그레이션 생성: accounts, social_connections, terms_versions, consent_records, refresh_tokens, verification_codes, user_profiles, user_interests, follow_keywords, reading_preferences, briefing_settings (`src/main/resources/db/migration/V2__account_auth_schema.sql`)
- [X] T003 Flyway V3 ADMIN 시드 생성: Flyway placeholder(`${admin-email}`, `${admin-password-hash}`)로 BCrypt 해시 주입, `ON CONFLICT DO NOTHING` (`src/main/resources/db/migration/V3__seed_admin.sql`)
- [X] T004 [P] application-example.yaml에 JWT·소셜·이메일서비스 환경변수 키 템플릿 추가 (jwt.secret, jwt.access-ttl-seconds, jwt.refresh-ttl-days, oauth.kakao/google/apple, email-service.base-url, flyway.placeholders.admin-*) (`src/main/resources/application-example.yaml`)

**Checkpoint**: 마이그레이션 파일 준비 완료 — `./gradlew flywayMigrate` 성공 확인

---

## Phase 2: Foundational (보안 스택 — 모든 US 블로킹)

**Purpose**: SecurityFilterChain·JWT·이메일 클라이언트·예외 클래스 — 이 Phase 전까지 어떤 US도 착수 불가

**⚠️ CRITICAL**: Phase 1 완료 후 착수. Phase 2 완료 전까지 US 구현 금지.

- [X] T005 도메인 Enum 10종 생성: AccountStatus(ACTIVE/SUSPENDED/DELETED), AccountRole(USER/ADMIN), SignupType(EMAIL/SOCIAL), SocialProvider(KAKAO/GOOGLE/APPLE), TermsType(SERVICE/PRIVACY/MARKETING), VerificationPurpose(EMAIL_VERIFY/PASSWORD_RESET), AgeGroup, SummaryDepth(BRIEF/BALANCED/DEEP), ConsumeMode(READ/LISTEN/BOTH), KeywordType(COMPANY/THEME/PERSON) (`…/domain/enums/*.java`)
- [X] T006 [P] 신규 예외 클래스 6종 생성: AccountLockedException, EmailNotVerifiedException, TokenReusedException, EmailAlreadyExistsException, SocialOnlyAccountException, EmailDeliveryException (`…/exception/*.java`)
- [X] T007 [P] GlobalExceptionHandler.java에 T006 예외 핸들러 등록: AccountLocked→401, EmailNotVerified→403, TokenReused→401, EmailAlreadyExists→409, SocialOnlyAccount→422, EmailDelivery→503 (`…/exception/GlobalExceptionHandler.java`)
- [X] T008 [P] JwtConfig.java 생성: `@ConfigurationProperties("jwt")` — secret, access-ttl-seconds, refresh-ttl-days (`…/config/JwtConfig.java`)
- [X] T009 [P] CustomUserDetails.java 생성: accountId(UUID), role(AccountRole), emailVerified(boolean) 포함 (`…/security/CustomUserDetails.java`)
- [X] T010 JwtTokenProvider.java 생성: 액세스 토큰 생성(sub=accountId, role, emailVerified, exp=1h)·파싱·검증, 리프레시 토큰 raw 생성(SecureRandom UUID), SHA-256 해시 유틸 (`…/security/JwtTokenProvider.java`)
- [X] T011 JwtAuthenticationFilter.java 생성: `OncePerRequestFilter` 상속, Authorization Bearer 추출·검증, `emailVerified=false` 계정의 `/me/**` 접근 시 `EmailNotVerifiedException` throw, `/auth/email-verification/**` 는 emailVerified 불문 통과 (`…/security/JwtAuthenticationFilter.java`)
- [X] T012 SecurityConfig.java 생성: STATELESS, CSRF disabled, permitAll 화이트리스트(signup/login/refresh/social/password-reset/actuator/health), `/api/v1/admin/**` hasRole(ADMIN), 그 외 authenticated(), `AuthenticationEntryPoint`(401), `AccessDeniedHandler`(403), T011 필터 등록 (`…/config/SecurityConfig.java`)
- [X] T013 [P] EmailServiceClient.java interface + HttpEmailServiceClient.java 구현: `sendVerificationCode()`, `sendPasswordResetCode()`, `sendSocialOnlyNotice()` — 실패 시 EmailDeliveryException throw, base-url/api-key 환경변수 주입 (`…/client/email/EmailServiceClient.java`, `HttpEmailServiceClient.java`)
- [X] T014 [P] OAuthConfig.java 생성: `@ConfigurationProperties("oauth")` — Kakao/Google/Apple client-id, client-secret, redirect-uri, Apple team-id/key-id/private-key (`…/config/OAuthConfig.java`)

**Checkpoint**: SecurityConfig 로드 성공, JWT 발급·검증 단위 테스트 통과

---

## Phase 3: US1 — 이메일 회원가입 (Priority: P1) 🎯 MVP

**Goal**: 이메일+비밀번호+약관 동의로 계정 생성, 토큰 발급, 이메일 인증 코드 트리거

**Independent Test**: `POST /api/v1/auth/signup` 호출 → 201 + 토큰 쌍. 중복·약관 미동의·연령 미달·비밀번호 정책 위반 시 각각 409·422 반환.

### 엔티티 & 리포지토리

- [X] T015 [P] [US1] Account.java Entity 생성: id(UUID PK), email(UNIQUE), passwordHash, role(AccountRole), status(AccountStatus), emailVerified, onboardingCompleted, signupType, failedLoginCount, lockedUntil, createdAt, updatedAt (`…/domain/Account.java`)
- [X] T016 [P] [US1] TermsVersion.java Entity 생성: id, type(TermsType), version, effectiveDate, isRequired, isActive (`…/domain/TermsVersion.java`)
- [X] T017 [P] [US1] ConsentRecord.java Entity 생성: id, account(ManyToOne), termsVersion(ManyToOne), agreed, agreedAt (`…/domain/ConsentRecord.java`)
- [X] T018 [P] [US1] AccountRepository.java 생성: findByEmailIgnoreCase, existsByEmailIgnoreCase (`…/repository/AccountRepository.java`)
- [X] T019 [P] [US1] TermsVersionRepository.java 생성: findByIsActiveTrue, findByTypeAndIsActiveTrue, findByIsActiveTrueAndIsRequiredTrue() — requiresReConsent 계산 시 "활성 AND 필수" 약관을 DB에서 직접 조회(서비스 레이어 stream 필터 불필요) (`…/repository/TermsVersionRepository.java`)
- [X] T020 [P] [US1] ConsentRecordRepository.java 생성: findByAccountId, findByAccountIdAndTermsVersionId (`…/repository/ConsentRecordRepository.java`)

### DTO

- [X] T021 [P] [US1] SignupRequest.java(record) 생성: email(@Email), password(@Size(min=8)), consents(List<ConsentInput>), ageConfirmed(@AssertTrue) (`…/dto/request/SignupRequest.java`)
- [X] T022 [P] [US1] ConsentInput.java(record) + AccountSummaryResponse.java(`requiresReConsent: boolean` 포함) + TokenPairResponse.java 생성 — AccountSummaryResponse는 login·GET /me 양쪽에서 공통 사용, requiresReConsent는 서비스 계층에서 계산해 주입 (`…/dto/request/ConsentInput.java`, `…/dto/response/AccountSummaryResponse.java`, `…/dto/response/TokenPairResponse.java`)

### 서비스 & 컨트롤러

- [X] T023 [US1] TermsService.java 생성: `getActiveTerms()` — 활성 약관 버전 목록 조회 (`…/service/TermsService.java`)
- [X] T024 [US1] AuthService.java 생성 + `signup()` 구현: 이메일 소문자 정규화·중복 체크(409), 비밀번호 정책 검증(영문+숫자 8자 이상, 422), 필수 약관 동의 체크(422), 연령 동의 체크(422), BCrypt 해시, Account 저장, ConsentRecord 일괄 저장, 토큰 발급(TokenService 위임) (`…/service/AuthService.java`)
- [X] T025 [US1] TermsController.java 생성: `GET /api/v1/terms` — 활성 약관 목록 반환(permitAll) (`…/controller/TermsController.java`)
- [X] T026 [US1] AuthController.java 생성 + `POST /api/v1/auth/signup` 구현: @Valid, 201 반환 (`…/controller/AuthController.java`)

### 통합 테스트

- [X] T027 [US1] AuthIntegrationTest.java 생성 — 가입 5개 시나리오: 정상 201·이메일 중복 409·필수 약관 미동의 422·연령 미달 422·비밀번호 정책 위반 422 (@SpringBootTest + Testcontainers) (`src/test/java/com/newscurator/integration/AuthIntegrationTest.java`)

**Checkpoint**: 가입 API 독립 동작 확인, T027 모두 PASS

---

## Phase 4: US9 — 이메일 인증 (Priority: P1)

**Goal**: 가입 후 이메일 인증 코드 발송, 코드 검증으로 계정 인증 상태 전환

**Independent Test**: 가입 후 emailVerified=false 상태에서 `/me` 접근 제한 확인 → 코드 검증 후 emailVerified=true, 접근 허용.

### 엔티티 & 리포지토리

- [X] T028 [P] [US9] VerificationCode.java Entity 생성: id, account(ManyToOne), purpose(VerificationPurpose), codeHash(VARCHAR 64), expiresAt, attemptCount, hourlyCount, windowStart, isUsed, createdAt (`…/domain/VerificationCode.java`)
- [X] T029 [P] [US9] VerificationCodeRepository.java 생성: findByAccountIdAndPurposeAndIsUsedFalse, countByAccountIdAndPurposeAndWindowStartAfter (`…/repository/VerificationCodeRepository.java`)

### DTO

- [X] T030 [P] [US9] EmailVerificationRequestDto.java(record) + EmailVerificationVerifyRequest.java(record) 생성 (`…/dto/request/EmailVerificationRequestDto.java`, `…/dto/request/EmailVerificationVerifyRequest.java`)

### 서비스 & 컨트롤러

- [X] T031 [US9] EmailVerificationService.java 생성: `requestCode()` — 기존 미사용 코드 무효화, 시간당 5회 초과 시 429, EmailServiceClient 호출(실패 시 503·한도 미차감), 6자리 SecureRandom 코드 생성·SHA-256 해시 저장(expiresAt=+15분); `verifyCode()` — 코드 해시 비교, 만료 체크(410), attemptCount 증가·5회 초과 무효화, 성공 시 account.emailVerified=true 전환 (`…/service/EmailVerificationService.java`)
- [X] T032 [US9] AuthService.signup() 수정: 가입 완료 후 EmailVerificationService.requestCode() 호출로 인증 코드 자동 발송 (`…/service/AuthService.java`)
- [X] T033 [US9] EmailVerificationController.java 생성: `POST /api/v1/auth/email-verification/request`(authenticated, emailVerified=false 허용) + `POST /api/v1/auth/email-verification/verify` (`…/controller/EmailVerificationController.java`)

### 통합 테스트

- [X] T034 [US9] EmailVerificationIntegrationTest.java 생성 — 4개 시나리오: 미인증 계정 /me 접근 제한·코드 검증 후 인증 전환·코드 만료 410·이메일 발송 실패 503+한도 미차감, WireMock 이메일 서비스 stub (`src/test/java/com/newscurator/integration/EmailVerificationIntegrationTest.java`)

**Checkpoint**: 이메일 인증 게이팅 독립 동작 확인, T034 모두 PASS

---

## Phase 5: US2 — 이메일 로그인 + Token Rotation (Priority: P1)

**Goal**: 이메일 로그인·로그아웃·refresh, 계정 잠금, Refresh Token Rotation(grace·family blast·에스컬레이션)

**Independent Test**: 로그인 → refresh → 기존 토큰 재사용(grace 초과) → family blast 확인. 연속 5회 실패 → 잠금.

### 엔티티 & 리포지토리

- [X] T035 [P] [US2] RefreshToken.java Entity 생성: id, account(ManyToOne), familyId(UUID), tokenHash(VARCHAR 64 UNIQUE), deviceId(nullable), issuedAt, expiresAt, consumedAt(nullable), isRevoked (`…/domain/RefreshToken.java`)
- [X] T036 [P] [US2] RefreshTokenRepository.java 생성: findByTokenHash, findByFamilyIdAndConsumedAtIsNullAndIsRevokedFalse, revokeByFamilyId(familyId), revokeByAccountId(accountId), countRecentFamilyBlastsByAccountId(accountId, since) (`…/repository/RefreshTokenRepository.java`)

### DTO

- [X] T037 [P] [US2] LoginRequest.java(record) + RefreshRequest.java(record) + LogoutRequest.java(record) 생성 (`…/dto/request/LoginRequest.java`, `…/dto/request/RefreshRequest.java`, `…/dto/request/LogoutRequest.java`)

### 서비스 & 컨트롤러

- [X] T038 [US2] TokenService.java 생성: `issueTokenPair()` — 새 familyId 생성, raw refresh token 발급, SHA-256 해시 DB 저장, JWT 액세스 토큰 발급; `rotate()` — 기존 토큰 consumedAt 설정, grace 30초 내 재사용 시 멱등 반환, 30초 초과 재사용 시 family blast + 5분 내 2회 이상이면 account-wide blast(TokenReusedException); `revoke()` — 단일 세션 무효화 (`…/service/TokenService.java`)
- [X] T039 [US2] AuthService.java에 `login()` 추가: 소문자 이메일 조회, SUSPENDED 403, 소셜 전용 계정 SocialOnlyAccountException(422), 잠금 상태 체크(locked_until > now → 401 균일), BCrypt 비교, 실패 시 failedLoginCount++ + 5회 이상 lockedUntil=+30분, 성공 시 failedLoginCount=0, TokenService.issueTokenPair(); **활성 필수 TermsVersion 목록 vs 계정 ConsentRecord 비교 → `requiresReConsent` 계산 후 AccountSummaryResponse에 포함**; `buildAccountSummary(account)` 공통 메서드로 분리(T044의 GET /me에서도 재사용); `logout()` — TokenService.revoke(); `refresh()` — TokenService.rotate() (`…/service/AuthService.java`)
- [X] T040 [US2] AuthController.java에 `POST /api/v1/auth/login`, `POST /api/v1/auth/logout`, `POST /api/v1/auth/refresh` 구현 (`…/controller/AuthController.java`)

### 단위 & 통합 테스트

- [X] T041 [US2] AuthServiceTest.java 생성: 계정 잠금(5회→잠금, 6번째 올바른 비밀번호도 401), 잠금 해제 후 정상 로그인, SOCIAL_ONLY_ACCOUNT 422 단위 테스트(Mockito) (`src/test/java/com/newscurator/auth/AuthServiceTest.java`)
- [X] T042 [US2] TokenRotationIntegrationTest.java 생성 — Rotation 4개 시나리오: 정상 갱신, grace 30초 내 재사용(멱등), grace 초과 재사용(family blast), 5분 내 2회 family blast(account-wide 에스컬레이션) (`src/test/java/com/newscurator/integration/TokenRotationIntegrationTest.java`)
- [X] T042a [US2] TokenServiceTest.java 생성 — Mockito 단위 테스트 6개 시나리오(Constitution IV):
  (a) 정상 rotation: 기존 토큰 consumedAt 설정 + 같은 familyId로 신규 토큰 발급 + 이전 토큰 is_revoked=false·consumedAt ≠ null 확인
  (b) grace 30초 내 재사용: 동일 family 최신 active 토큰 반환, revokeByFamilyId 미호출(blast 없음) 확인
  (c) grace 초과 재사용: revokeByFamilyId 호출, **다른 family의 토큰은 revoke 대상 아님** 확인(family 격리 검증)
  (d) 5분 내 family blast 2회: countRecentFamilyBlasts >= 2 → revokeByAccountId 호출, TokenReusedException throw
  (e) 에스컬레이션 window 리셋: family blast 1회 → 5분 경과 → blast 1회 추가 → countRecentFamilyBlasts=1 → account-wide 에스컬레이션 **안 됨**(계정 전체 세션 유지) 확인
  (f) 만료(expires_at < now) 또는 is_revoked=true 토큰 재사용: 거부(401) 처리, family blast **트리거 안 함** 확인 (`src/test/java/com/newscurator/auth/TokenServiceTest.java`)

**Checkpoint**: 로그인·잠금·Rotation 독립 동작 확인, T041·T042·T042a 모두 PASS

---

## Phase 6: US4 — 역할 기반 접근 제어 (Priority: P1)

**Goal**: `/admin/**` ADMIN 전용 게이트 적용, 001 AdminPipelineController 보호 완성

**Independent Test**: 미인증 401·USER 403·ADMIN 200 세 케이스 확인.

### 구현

- [X] T043 [P] [US4] SecurityConfig.java 확인·보완: `/api/v1/admin/**` hasRole("ADMIN") 게이트, emailVerified 게이팅(JwtAuthenticationFilter의 `/me/**` 차단), `/api/v1/me` authenticated 적용 확인 (`…/config/SecurityConfig.java`)
- [X] T044 [P] [US4] MeController.java 생성: `GET /api/v1/me` — `AuthService.buildAccountSummary()` 호출로 AccountSummaryResponse(requiresReConsent 포함) 반환 (`…/controller/MeController.java`)
  *(T043·T044 병렬 실행 가능 — 서로 다른 파일. T045 RbacIntegrationTest는 두 태스크 완료 후 실행)*

### 통합 테스트

- [X] T045 [US4] RbacIntegrationTest.java 생성 — 6개 시나리오: /admin/** 미인증 401·USER 403·ADMIN 200, /api/v1/me USER 200, emailVerified=false /me 접근 제한, emailVerified=true 접근 허용 (@SpringBootTest + Testcontainers) (`src/test/java/com/newscurator/integration/RbacIntegrationTest.java`)

**Checkpoint**: REQUIRED GATE CHK001~005 충족, T045 모두 PASS → 001의 /admin/** 미보호 이슈 영구 해소

---

## Phase 7: US5 — 비밀번호 재설정 (Priority: P2)

**Goal**: 코드 발급(FR-026 균일 202)→코드 검증→비밀번호 변경(세션 전체 무효화) 3단계

**Independent Test**: 3단계 순서대로 호출 완료 → 기존 refresh token 401 확인.

### DTO

- [ ] T046 [P] [US5] PasswordResetRequestDto.java + PasswordResetVerifyRequest.java + PasswordResetConfirmRequest.java + PasswordResetVerifyResponse.java 생성 (`…/dto/request/*.java`, `…/dto/response/PasswordResetVerifyResponse.java`)

### 서비스 & 컨트롤러

- [ ] T047 [US5] PasswordResetService.java 생성: `requestCode()` — 계정 존재 여부 불문 동일 처리, 이메일 계정이면 코드 생성·발송(발송 실패 503·한도 미차감), 소셜 전용 계정이면 안내 이메일 발송(코드 미생성, FR-026); `verifyCode()` — 코드 검증, 성공 시 단일 사용 resetToken(JWT, TTL=10분, jti=UUID) 발급; `confirmReset()` — resetToken 검증(서명·exp·jti 미사용 확인), 비밀번호 정책 검증, BCrypt 해시 업데이트, TokenService로 계정 전체 세션 무효화(FR-025) (`…/service/PasswordResetService.java`)
- [ ] T048 [US5] PasswordResetController.java 생성: `POST /api/v1/auth/password-reset/request`, `POST /auth/password-reset/verify`, `POST /auth/password-reset/confirm` (모두 permitAll) (`…/controller/PasswordResetController.java`)

### 단위 & 통합 테스트

- [ ] T049 [US5] PasswordResetServiceTest.java 생성: requestCode 소셜 전용→안내이메일·코드미생성, 이메일 발송 실패→503 단위 테스트(Mockito) (`src/test/java/com/newscurator/auth/PasswordResetServiceTest.java`)
- [ ] T050 [US5] PasswordResetIntegrationTest.java 생성 — 7개 시나리오: 3단계 정상·코드 만료 410·오입력 5회 무효화·재전송 초과 429·이메일 장애 503+한도 미차감·소셜 전용 202+안내이메일·재설정 후 기존 refresh 401, WireMock 이메일 stub (`src/test/java/com/newscurator/integration/PasswordResetIntegrationTest.java`)

**Checkpoint**: 비밀번호 재설정 3단계 독립 동작, T049·T050 모두 PASS

---

## Phase 8: US3 — 소셜 가입·로그인 (Priority: P1, 이메일 기반 완성 후 구현)

**Goal**: 카카오·구글·애플 OAuth, HMAC JWT state CSRF 방지, 가입/로그인 분기

**Independent Test**: WireMock으로 소셜 provider 토큰 교환 stub → state 일치 시 토큰 발급, state 불일치 시 400.

### 엔티티 & 리포지토리

- [ ] T051 [P] [US3] SocialConnection.java Entity 생성: id, account(ManyToOne), provider(SocialProvider), providerUserId, connectedAt (`…/domain/SocialConnection.java`)
- [ ] T052 [P] [US3] SocialConnectionRepository.java 생성: findByProviderAndProviderUserId (`…/repository/SocialConnectionRepository.java`)

### 소셜 client 포트·어댑터

- [ ] T053 [P] [US3] OAuthProviderPort.java interface + OAuthUserInfo.java record(providerId, email, provider) + OAuthProviderFactory.java(SocialProvider→adapter 매핑) 생성 (`…/client/social/OAuthProviderPort.java`, `OAuthUserInfo.java`, `OAuthProviderFactory.java`)
- [ ] T054 [P] [US3] KakaoOAuthAdapter.java 구현: authorizationUrl 생성, 토큰 교환(RestClient), userInfo 조회, OAuthUserInfo 매핑(이메일 nullable) (`…/client/social/KakaoOAuthAdapter.java`)
- [ ] T055 [P] [US3] GoogleOAuthAdapter.java 구현: authorizationUrl 생성, 토큰 교환·ID Token decode 또는 userInfo API 호출, OAuthUserInfo 매핑 (`…/client/social/GoogleOAuthAdapter.java`)
- [ ] T056 [US3] AppleOAuthAdapter.java 구현: client_secret JWT 생성(ES256, 6개월 TTL, 기동 시 캐싱), form_post 콜백에서 ID Token decode(sub/email 추출), 중계 이메일(@privaterelay.appleid.com) 그대로 저장 (`…/client/social/AppleOAuthAdapter.java`)

### DTO & 서비스 & 컨트롤러

- [ ] T057 [P] [US3] SocialCallbackRequest.java(record) + SocialAuthorizeResponse.java(record) 생성 (`…/dto/request/SocialCallbackRequest.java`, `…/dto/response/SocialAuthorizeResponse.java`)
- [ ] T058 [US3] SocialAuthService.java 구현: `getAuthorizationUrl()` — HMAC JWT state 생성(Claims: nonce UUID·provider·exp 10분); `handleCallback()` — state 서명·exp·provider 검증(실패 시 400), OAuthProviderFactory로 어댑터 선택, fetchOAuthUserInfo(), SocialConnection 조회(기존→로그인/신규→가입 분기), 이메일 충돌 체크(409), 신규 가입 시 약관 동의 검증(422), Account+SocialConnection 저장(emailVerified=true), 토큰 발급 (`…/service/SocialAuthService.java`)
- [ ] T059 [US3] SocialAuthController.java 구현: `GET /api/v1/auth/social/{provider}/authorize` + `POST /api/v1/auth/social/{provider}/callback` (모두 permitAll) (`…/controller/SocialAuthController.java`)

### 통합 테스트

- [ ] T060 [US3] SocialAuthIntegrationTest.java 생성 — WireMock 카카오/구글 provider stub으로 5개 시나리오: state CSRF 위조 400·신규 소셜 가입 201·기존 소셜 로그인 200·이메일 계정 충돌 409·약관 미동의 422 (`src/test/java/com/newscurator/integration/SocialAuthIntegrationTest.java`)

**Checkpoint**: 소셜 로그인 독립 동작(WireMock 기반), T060 모두 PASS

---

## Phase 9: US6 — 온보딩 (Priority: P2)

**Goal**: 프로필·관심사·키워드·읽는방식·브리핑 설정 일괄 저장, 개인화 활성화 플래그

**Independent Test**: `POST /api/v1/me/onboarding` — 관심 카테고리 3개 이상 시 personalizationActive=true.

### 엔티티 & 리포지토리

- [ ] T061 [P] [US6] UserProfile.java Entity 생성: id, account(OneToOne), nickname, ageGroup(AgeGroup), occupation (`…/domain/UserProfile.java`)
- [ ] T062 [P] [US6] UserInterests.java Entity 생성: id, account(ManyToOne), category(String) — UNIQUE(account_id, category) (`…/domain/UserInterests.java`)
- [ ] T063 [P] [US6] FollowKeyword.java Entity 생성: id, account(ManyToOne), keyword, type(KeywordType) (`…/domain/FollowKeyword.java`)
- [ ] T064 [P] [US6] ReadingPreference.java Entity 생성: id, account(OneToOne), summaryDepth(SummaryDepth), consumeMode(ConsumeMode) (`…/domain/ReadingPreference.java`)
- [ ] T065 [P] [US6] BriefingSettings.java Entity 생성: id, account(OneToOne), briefingTime(LocalTime), timezoneOffset(Short), voiceEnabled, pushAgreed, pushAgreedAt (`…/domain/BriefingSettings.java`)
- [ ] T066 [P] [US6] UserProfileRepository, UserInterestsRepository, FollowKeywordRepository, ReadingPreferenceRepository, BriefingSettingsRepository 생성 (`…/repository/*.java`)

### DTO

- [ ] T067 [P] [US6] OnboardingRequest.java(record) + OnboardingStatusResponse.java(record) 생성 (`…/dto/request/OnboardingRequest.java`, `…/dto/response/OnboardingStatusResponse.java`)

### 서비스 & 컨트롤러

- [ ] T068 [US6] OnboardingService.java 구현: `submitOnboarding()` — interests 3개 미만 422, UserProfile·UserInterests(기존 삭제 후 재저장)·FollowKeyword·ReadingPreference·BriefingSettings 저장, pushAgreed=true 시 pushAgreedAt=now(), onboardingCompleted=true, personalizationActive 계산; `getStatus()` (`…/service/OnboardingService.java`)
- [ ] T069 [US6] OnboardingController.java 구현: `POST /api/v1/me/onboarding` + `GET /api/v1/me/onboarding/status` (`…/controller/OnboardingController.java`)

### 통합 테스트

- [ ] T070 [US6] OnboardingIntegrationTest.java 생성 — 4개 시나리오: 정상 저장+personalizationActive·카테고리 2개 422·온보딩 미완료 상태 재진입·브리핑 푸시 동의 시각 기록 (`src/test/java/com/newscurator/integration/OnboardingIntegrationTest.java`)

**Checkpoint**: 온보딩 독립 동작 확인, T070 모두 PASS

---

## Phase 10: US7 — 프로필·관심사·설정 조회·수정 (Priority: P3)

**Goal**: 가입 후 프로필·관심사·키워드·읽는방식·브리핑 설정을 개별 조회·수정

**Independent Test**: `PUT /api/v1/me/interests` — 카테고리 2개로 줄이려는 시도 422 확인.

### DTO

- [ ] T071 [P] [US7] 요청 DTO 5종 생성: UserProfileRequest(record), UserInterestsRequest(record, minItems=3), FollowKeywordsRequest(record), ReadingPreferenceRequest(record), BriefingSettingsRequest(record) (`…/dto/request/*.java`)
- [ ] T072 [P] [US7] 응답 DTO 5종 생성: UserProfileResponse, UserInterestsResponse, FollowKeywordsResponse, ReadingPreferenceResponse, BriefingSettingsResponse (`…/dto/response/*.java`)

### 서비스 & 컨트롤러

- [ ] T073 [US7] ProfileService.java 구현: 프로필·관심사·키워드·읽는방식·브리핑 각각 조회(readOnly=true)·수정(interests 3개 미만 422, FollowKeyword 교체는 기존 전체 삭제 후 재저장) (`…/service/ProfileService.java`)
- [ ] T074 [US7] MeController.java에 엔드포인트 추가: `GET/PUT /api/v1/me/profile`, `GET/PUT /api/v1/me/interests`, `GET/PUT /api/v1/me/keywords`, `GET/PUT /api/v1/me/reading-preference`, `GET/PUT /api/v1/me/briefing-settings` (`…/controller/MeController.java`)

**Checkpoint**: 프로필·설정 조회·수정 독립 동작 확인

---

## Phase 11: US8 — 약관 버전 관리 및 재동의 (Priority: P3)

**Goal**: 관리자 새 약관 버전 등록, 사용자 동의 이력 조회·재동의 제출

**Independent Test**: 새 약관 버전 등록 → 기존 사용자 재동의 필요 플래그 확인.

### DTO

- [ ] T075 [P] [US8] CreateTermsVersionRequest.java(record) + TermsVersionResponse.java + ConsentRecordResponse.java 생성 (`…/dto/request/CreateTermsVersionRequest.java`, `…/dto/response/TermsVersionResponse.java`, `…/dto/response/ConsentRecordResponse.java`)

### 서비스 & 컨트롤러

- [ ] T076 [US8] TermsService.java 확장: `createVersion()` — 동일 type+version 중복 409, 신규 버전 활성화; `getConsentHistory()` — 버전별 동의 이력 반환; `submitConsents()` — 이미 동의한 버전 무시(멱등), ConsentRecord 저장 (`…/service/TermsService.java`)
- [ ] T077 [US8] TermsController.java 확장: `POST /api/v1/admin/terms`(ADMIN), `GET /api/v1/me/consents`, `POST /api/v1/me/consents` (`…/controller/TermsController.java`)

**Checkpoint**: 약관 관리 독립 동작 확인

---

## Phase 12: Polish & Cross-Cutting Concerns

**Purpose**: Swagger 문서화, 감사 로그, E2E 검증

- [ ] T078 [P] Swagger 어노테이션 전체 컨트롤러에 추가: `@Tag`, `@Operation(summary, description)`, `@ApiResponses`, `@Parameter`, `@Schema` — `AuthController`, `SocialAuthController`, `EmailVerificationController`, `PasswordResetController`, `MeController`, `OnboardingController`, `TermsController`
- [ ] T079 [P] EmailVerificationServiceTest.java 생성: requestCode 발송 실패→503+한도 미차감, 재전송 5회 초과→429 단위 테스트(Mockito) (`src/test/java/com/newscurator/auth/EmailVerificationServiceTest.java`)
- [ ] T080 [P] 감사 로그 구현(FR-028): AuthService·TokenService·PasswordResetService에 SLF4J MDC 기반 보안 이벤트 구조적 로깅 추가 — LOGIN_FAILED, ACCOUNT_LOCKED, TOKEN_REUSE_DETECTED, PASSWORD_CHANGED (로그에 이메일·토큰 원문·비밀번호 절대 출력 금지, accountId UUID만)
- [ ] T081 quickstart.md 7개 E2E 시나리오 실행: 이메일인증 게이팅·계정잠금·Rotation·RBAC·비번재설정·이메일장애 503·CSRF state 검증 수동 확인
- [ ] T082 CHANGELOG.html 갱신

---

## Dependencies & Execution Order

### Phase Dependencies

```
Phase 1 (Setup)
  └─► Phase 2 (Foundational) ──┬─► Phase 3 (US1) ──► Phase 4 (US9) ──► Phase 5 (US2) ──► Phase 6 (US4)
                                │                                                    └─► Phase 7 (US5)
                                │   Phase 3~6 완료 후 ──────────────────────────────────► Phase 8 (US3)
                                │   Phase 3~6 완료 후 ──────────────────────────────────► Phase 9 (US6)
                                │   Phase 9 완료 후 ────────────────────────────────────► Phase 10 (US7)
                                └─► Phase 3 완료 후 ─────────────────────────────────────► Phase 11 (US8)
                                    모든 Phase 완료 후 ──────────────────────────────────► Phase 12 (Polish)
```

### User Story Dependencies

| Story | 의존 | 이유 |
|-------|------|------|
| US1 (가입) | Phase 2 완료 | Account entity·SecurityConfig 필요 |
| US9 (이메일 인증) | US1 완료 | VerificationCode → Account FK, 가입 시 코드 발송 |
| US2 (로그인) | US1 완료 | Account entity·BCrypt 인증 필요 |
| US4 (RBAC) | US2 완료 | 인증 토큰 발급 흐름 필요 |
| US5 (비번재설정) | US9 완료 | VerificationCode entity 재사용(PURPOSE=PASSWORD_RESET) |
| US3 (소셜) | US1+US2 완료 | Account 생성·토큰 발급 인프라 필요 |
| US6 (온보딩) | US1+US2 완료 | 인증 세션 필요 |
| US7 (프로필) | US6 완료 | 온보딩에서 생성된 프로필 엔티티 재사용 |
| US8 (약관) | US1 완료 | TermsVersion·ConsentRecord 이미 생성 |

### Within Each Phase

- 엔티티·리포지토리 [P] 태스크 → 병렬 실행 가능
- DTO [P] 태스크 → 병렬 실행 가능
- Service → Controller → 통합 테스트 순서 (순차)

---

## Parallel Execution Examples

### Phase 3 (US1) 병렬 실행
```
동시 실행 가능:
  T015 Account.java    T016 TermsVersion.java    T017 ConsentRecord.java
  T018 AccountRepo     T019 TermsVersionRepo      T020 ConsentRecordRepo
  T021 SignupRequest   T022 TokenPairResponse      (독립 파일)

순차 실행:
  T023 TermsService → T024 AuthService.signup → T025 TermsController → T026 AuthController → T027 통합 테스트
```

### Phase 8 (US3) 병렬 실행
```
동시 실행 가능:
  T051 SocialConnection    T052 SocialConnectionRepo
  T053 OAuthProviderPort   T054 KakaoOAuthAdapter    T055 GoogleOAuthAdapter
  T057 SocialCallbackRequest

순차 실행:
  T056 AppleOAuthAdapter (KakaoOAuthAdapter 패턴 참고 후) →
  T058 SocialAuthService → T059 SocialAuthController → T060 통합 테스트
```

---

## Implementation Strategy

### MVP (Phase 1~6 완료)

1. Phase 1: Setup (T001–T004)
2. Phase 2: Foundational (T005–T014) — **블로킹, 완료 전 다음 착수 금지**
3. Phase 3: US1 이메일 회원가입 (T015–T027)
4. Phase 4: US9 이메일 인증 (T028–T034)
5. Phase 5: US2 이메일 로그인 (T035–T042)
6. Phase 6: US4 RBAC (T043–T045) ← **REQUIRED GATE 통과**
7. **MVP STOP & VALIDATE** → 001 `/admin/**` 보안 해소 확인, 이메일 가입·로그인·인증 E2E 동작

### 이후 증분 배포

- Phase 7: US5 비밀번호 재설정 → 배포
- Phase 8: US3 소셜 가입·로그인 → 배포
- Phase 9: US6 온보딩 → Phase 10: US7 프로필 → 배포
- Phase 11: US8 약관 관리 → 배포
- Phase 12: Polish → 최종 배포

---

## Notes

- `@DataJpaTest`·`@WebMvcTest`는 Spring Boot 4.0.5에서 제거됨 → `@SpringBootTest + Testcontainers` 일관 사용 (project_spring_boot4_test_findings.md 참조)
- Redis 의존성 없음 — OAuth state는 HMAC JWT stateless 방식 (research.md R1)
- 외부 연동(소셜 provider 실제 API·이메일 서비스 실제 발송) 검증은 배포 후 E2E로 명시
- 모든 구현 완료 후 `./gradlew test` 실행 — skip 불가. 불가피한 경우 사유 명시 후 승인 요청
- WireMock URL 패턴: `.*generateContent.*` (점 앞 `.*` 필요) — 001 통합 테스트 교훈
- `System.out` 출력 커밋 금지, `log.info`/`log.debug` 사용
- 모든 Phase 완료 시 CHANGELOG.html 갱신
