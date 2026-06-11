# Implementation Plan: 계정·인증·온보딩·인가

**Branch**: `002-account-auth-onboarding` | **Date**: 2026-06-11 | **Spec**: [spec.md](spec.md)

**Input**: `.specify/specs/002-account-auth-onboarding/spec.md`

## Summary

이메일·소셜(카카오/구글/애플) 가입·로그인, JWT 기반 인증 세션, Refresh Token Rotation, 이메일 인증 게이팅, 비밀번호 재설정, RBAC(USER/ADMIN), 온보딩·프로필·관심사·설정 저장을 구현한다. 001에서 인증 없이 노출됐던 `/admin/**`를 Spring Security ADMIN 역할 게이트로 영구 보호한다.

## Technical Context

**Language/Version**: Java 25  
**Primary Dependencies**: Spring Boot 4.0.x, Spring Security 6.x, jjwt (JWT), BCrypt, Spring Data JPA, Flyway  
**Storage**: PostgreSQL (Flyway migration V2+) — OAuth state는 HMAC JWT stateless(research.md R1), Redis 불필요  
**Testing**: JUnit 5, Testcontainers (PostgreSQL), WireMock (소셜 provider + 이메일 서비스 stub)  
**Target Platform**: Linux server (Spring Boot 임베디드)  
**Project Type**: REST API (web-service)  
**Performance Goals**: 가입·로그인·토큰 갱신 p95 < 300ms (SC-001 60초 E2E 기준 내 여유)  
**Constraints**: 001 테이블 수정 금지, ddl-auto=validate, API 키·시크릿 환경변수 전용, 로그에 이메일·토큰·비밀번호 출력 금지  
**Scale/Scope**: 단일 Spring Boot 인스턴스, 초기 수천 명 규모

## Constitution Check

*GATE: 구현 착수 전 통과 필수. Phase 1 설계 완료 후 재검증.*

| # | Principle | Status | Evidence |
|---|-----------|--------|---------|
| I | 레이어드 아키텍처와 단방향 의존 | ✅ PASS | Controller→Service→Repository. `AuthController`는 `AuthService`만 호출. 트랜잭션은 Service 계층에만. JPA 쿼리는 Repository 통해서만. |
| II | API 경계에서 Entity 비노출과 입력 검증 | ✅ PASS | 모든 요청/응답은 Java record DTO. `Account` Entity는 Controller에 절대 노출 안 함. 모든 요청 DTO에 `@Valid`. |
| III | 일관된 응답 포맷과 전역 예외 처리 | ✅ PASS | 기존 `GlobalExceptionHandler` 확장. 신규 예외(`AccountLockedException`, `EmailNotVerifiedException`, `TokenReusedException` 등) 추가 등록. |
| IV | 테스트 없는 비즈니스 로직 금지 | ✅ PASS | `AuthService` 단위 테스트(Mockito). Testcontainers 통합 테스트: RBAC, Token Rotation, 계정 잠금, 이메일 인증 게이팅, 비밀번호 재설정. 소셜 provider·이메일 서비스는 WireMock stub. |
| V | 스키마 변경은 마이그레이션으로만 | ✅ PASS | `V2__account_auth_schema.sql` + `V3__seed_admin.sql`. 001 테이블 무수정. `ddl-auto=validate` 유지. |
| VI | 보안 기본값과 시크릿 외부화 | ✅ PASS | Secure-by-default: 화이트리스트만 `permitAll`. JWT secret, provider API key, 이메일 서비스 key는 환경변수에서만 주입. HTTPS 강제는 인프라(배포) 담당. |
| VII | 수집·알림의 멱등성과 중복 방지 | ✅ PASS | 이메일 인증/비번재설정 코드 요청은 멱등: 기존 코드 즉시 무효화 후 신규 발급. 약관 동의 `POST /me/consents`는 이미 동의된 버전 무시(멱등). Refresh Token Rotation grace period로 네트워크 재시도 안전. |

**REQUIRED GATE (security.md CHK001~005) 사전 해소**:
- CHK001: `/admin/**` 와일드카드 범위 — `SecurityFilterChain`에서 `/api/v1/admin/**`로 명시, ADMIN 역할만 허용.
- CHK002: 401(미인증) vs 403(권한부족) 분리 — `AuthenticationEntryPoint`(401), `AccessDeniedHandler`(403) 별도 구성.
- CHK003: ADMIN seed 보안 — Flyway placeholder(`${admin-password-hash}`)로 BCrypt 해시만 저장, 초기 자격증명 변경 강제는 운영 정책.
- CHK004: ADMIN 토큰 만료 후 재접근 → 401 (JWT 표준 만료 처리).
- CHK005: 비밀번호 변경 후 ADMIN 세션도 무효화 — FR-025가 "모든 활성 세션" 명시, ADMIN 포함.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/002-account-auth-onboarding/
├── plan.md              ← 이 파일
├── research.md          ← Phase 0 출력
├── data-model.md        ← Phase 1 출력
├── quickstart.md        ← Phase 1 출력
├── contracts/
│   └── openapi.yaml     ← Phase 1 출력
├── spec.md
└── checklists/
    ├── requirements.md
    └── security.md
```

### Source Code

```text
src/main/java/com/newscurator/
├── controller/
│   ├── AuthController.java               # 가입·로그인·refresh·logout
│   ├── SocialAuthController.java         # 소셜 OAuth authorize·callback
│   ├── EmailVerificationController.java  # 이메일 인증 request·verify
│   ├── PasswordResetController.java      # 비번재설정 3단계
│   ├── MeController.java                 # 내 계정·프로필·관심사·키워드·설정
│   ├── OnboardingController.java         # 온보딩 저장·상태
│   └── TermsController.java              # 약관 목록, 동의 이력
│   # 기존: AdminPipelineController (security config만 변경, 코드 수정 없음)
│
├── service/
│   ├── AuthService.java                  # 가입·로그인·RBAC 공통 로직
│   ├── TokenService.java                 # JWT 생성·검증, RefreshToken Rotation
│   ├── SocialAuthService.java            # 소셜 OAuth 처리
│   ├── EmailVerificationService.java     # 이메일 인증 코드 생성·검증
│   ├── PasswordResetService.java         # 비번재설정 3단계
│   ├── OnboardingService.java            # 온보딩 데이터 저장·조회
│   ├── ProfileService.java               # 프로필·관심사·키워드·설정 CRUD
│   └── TermsService.java                 # 약관 버전 관리, 동의 처리
│
├── repository/
│   ├── AccountRepository.java
│   ├── SocialConnectionRepository.java
│   ├── RefreshTokenRepository.java
│   ├── VerificationCodeRepository.java
│   ├── TermsVersionRepository.java
│   ├── ConsentRecordRepository.java
│   ├── UserProfileRepository.java
│   ├── UserInterestsRepository.java
│   ├── FollowKeywordRepository.java
│   ├── ReadingPreferenceRepository.java
│   └── BriefingSettingsRepository.java
│
├── domain/
│   ├── Account.java
│   ├── SocialConnection.java
│   ├── RefreshToken.java
│   ├── VerificationCode.java             # purpose=EMAIL_VERIFY|PASSWORD_RESET
│   ├── TermsVersion.java
│   ├── ConsentRecord.java
│   ├── UserProfile.java
│   ├── UserInterests.java
│   ├── FollowKeyword.java
│   ├── ReadingPreference.java
│   ├── BriefingSettings.java
│   └── enums/
│       ├── AccountStatus.java            # ACTIVE|SUSPENDED|DELETED
│       ├── AccountRole.java              # USER|ADMIN
│       ├── SignupType.java               # EMAIL|SOCIAL
│       ├── SocialProvider.java           # KAKAO|GOOGLE|APPLE
│       ├── TermsType.java                # SERVICE|PRIVACY|MARKETING
│       ├── VerificationPurpose.java      # EMAIL_VERIFY|PASSWORD_RESET
│       ├── AgeGroup.java
│       ├── SummaryDepth.java
│       ├── ConsumeMode.java
│       └── KeywordType.java              # COMPANY|THEME|PERSON
│
├── dto/
│   ├── request/
│   │   ├── SignupRequest.java
│   │   ├── LoginRequest.java
│   │   ├── SocialCallbackRequest.java
│   │   ├── RefreshRequest.java
│   │   ├── LogoutRequest.java
│   │   ├── PasswordResetRequestDto.java
│   │   ├── PasswordResetVerifyRequest.java
│   │   ├── PasswordResetConfirmRequest.java
│   │   ├── EmailVerificationRequestDto.java
│   │   ├── EmailVerificationVerifyRequest.java
│   │   ├── OnboardingRequest.java
│   │   ├── UserProfileRequest.java
│   │   ├── UserInterestsRequest.java
│   │   ├── FollowKeywordsRequest.java
│   │   ├── ReadingPreferenceRequest.java
│   │   ├── BriefingSettingsRequest.java
│   │   └── ConsentInput.java
│   └── response/
│       ├── TokenPairResponse.java
│       ├── AccountSummaryResponse.java
│       ├── SocialAuthorizeResponse.java
│       ├── PasswordResetVerifyResponse.java
│       ├── OnboardingStatusResponse.java
│       ├── UserProfileResponse.java
│       ├── UserInterestsResponse.java
│       ├── FollowKeywordsResponse.java
│       ├── ReadingPreferenceResponse.java
│       ├── BriefingSettingsResponse.java
│       ├── TermsVersionResponse.java
│       └── ConsentRecordResponse.java
│
├── exception/
│   ├── AccountLockedException.java
│   ├── EmailNotVerifiedException.java
│   ├── TokenReusedException.java
│   ├── EmailAlreadyExistsException.java
│   ├── SocialOnlyAccountException.java
│   └── EmailDeliveryException.java
│
├── client/
│   ├── social/
│   │   ├── OAuthProviderPort.java        # interface
│   │   ├── OAuthUserInfo.java            # record (providerId, email, provider)
│   │   ├── OAuthProviderFactory.java     # provider → adapter 매핑
│   │   ├── KakaoOAuthAdapter.java
│   │   ├── GoogleOAuthAdapter.java
│   │   └── AppleOAuthAdapter.java        # client_secret JWT 생성 포함
│   └── email/
│       ├── EmailServiceClient.java       # interface
│       └── HttpEmailServiceClient.java   # RestClient 기반 구현
│
├── config/
│   ├── SecurityConfig.java               # SecurityFilterChain, RBAC, whitelist
│   ├── JwtConfig.java                    # JWT secret, TTL 설정
│   └── OAuthConfig.java                  # OAuth provider 설정값 바인딩
│
└── security/
    ├── JwtAuthenticationFilter.java      # OncePerRequestFilter
    ├── JwtTokenProvider.java             # 생성·파싱·검증
    └── CustomUserDetails.java            # accountId, role, emailVerified 포함

src/main/resources/db/migration/
├── V1__init_schema.sql                   # (기존 001)
├── V2__account_auth_schema.sql           # 이 feature 메인 마이그레이션
└── V3__seed_admin.sql                    # ADMIN 계정 시드

src/test/java/com/newscurator/
├── auth/
│   ├── AuthServiceTest.java              # 단위 테스트 (Mockito)
│   ├── TokenServiceTest.java
│   ├── PasswordResetServiceTest.java
│   └── EmailVerificationServiceTest.java
├── integration/
│   ├── AuthIntegrationTest.java          # 가입·로그인·이메일 인증 통합
│   ├── RbacIntegrationTest.java          # 401·403·200 RBAC
│   ├── TokenRotationIntegrationTest.java # Rotation + 재사용 감지
│   └── SocialAuthIntegrationTest.java    # WireMock 소셜 provider stub
```

**Structure Decision**: 기존 001 패키지 구조(`com.newscurator.*`)를 그대로 확장. 신규 Controller/Service/Repository만 추가. 001 코드(`AdminPipelineController` 등)는 Security 설정 변경만 영향받음.

## Complexity Tracking

> 아래는 Constitution 원칙과 충돌하는 예외 설계를 명시한다.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| `SecurityConfig`에서 일부 엔드포인트 `permitAll`이지만 Service에서 계정 검증 | 이메일 인증 요청/검증은 미인증 계정도 호출해야 하므로 Security 레이어에서 막을 수 없음 | 완전 인증 필수 시 가입 직후 이메일 인증 자체가 불가 |
| Apple client_secret JWT 생성 (암호화 로직 client 패키지에 포함) | Apple OAuth 규격상 ES256 서명 JWT가 client_secret으로 요구됨 | 표준 shared secret 불가 — Apple 규격 강제 |
| 변경 허가 토큰(resetToken)을 DB 미저장, 서명된 JWT로 발급 | TTL 10분 단기 토큰을 DB에 저장하면 불필요한 레코드 라이프사이클 관리 필요 | Redis 임시 저장 가능하나 JWT 서명 검증으로 충분한 보안 보장 + jti를 VerificationCode의 is_used 플래그로 이중 검증 |

## Phase 계획

### Phase 0 — 완료 (research.md 참조)

- [x] OAuth provider 플로우 확인 (Kakao/Google/Apple)
- [x] Spring Security JWT stateless 설정 패턴
- [x] Refresh Token Rotation + grace period 전략
- [x] VerificationCode 통합 엔티티 설계
- [x] Email service client WireMock 호환성 확인

### Phase 1 — 완료 (이 파일 + 산출물)

- [x] data-model.md (11개 테이블 + 관계 + 마이그레이션 전략)
- [x] contracts/openapi.yaml (전체 엔드포인트)
- [x] quickstart.md (7개 검증 시나리오)

### Phase 2 — 구현 (tasks.md에서 상세 분해)

우선순위 순서:

1. **P0 인프라**: SecurityConfig (stateless JWT, whitelist, RBAC), JwtTokenProvider, Flyway V2/V3 마이그레이션, GlobalExceptionHandler 신규 예외 등록
2. **P1 이메일 인증 기반**: Account·RefreshToken·VerificationCode Entity, 이메일 가입·로그인, TokenService (Rotation + grace), EmailVerificationService, EmailServiceClient
3. **P1 RBAC 완성**: ADMIN seed, `/admin/**` 게이트 테스트 (RbacIntegrationTest)
4. **P1 비밀번호 재설정**: PasswordResetService (3단계 + 세션 무효화)
5. **P2 소셜 OAuth**: OAuthProviderPort, KakaoOAuthAdapter, GoogleOAuthAdapter, AppleOAuthAdapter
6. **P2 온보딩·프로필**: UserProfile·UserInterests·FollowKeyword·ReadingPreference·BriefingSettings
7. **P3 약관 관리**: TermsVersion, ConsentRecord, 재동의 플래그

### 테스트 전략 요약

| 계층 | 도구 | 대상 |
|------|------|------|
| 단위 | JUnit 5 + Mockito | AuthService, TokenService, PasswordResetService, EmailVerificationService |
| 통합 | @SpringBootTest + Testcontainers | RBAC(401/403/200), Token Rotation, 계정 잠금, 이메일 인증 게이팅, 비번재설정, 이메일 실패 503 |
| 소셜·이메일 stub | WireMock | 카카오/구글/애플 토큰 교환, 이메일 서비스 성공·실패 |
| Repository | @DataJpaTest + Testcontainers | (Spring Boot 4.0.5 — @DataJpaTest 사용 불가 시 @SpringBootTest 슬라이스 대체) |

> ⚠️ Spring Boot 4.0.5 breaking change: `@DataJpaTest`/`@WebMvcTest` 제거됨 — project_spring_boot4_test_findings.md 참조. `@SpringBootTest + Testcontainers` 일관 사용.

### 구현·검증 원칙 (CLAUDE.md §구현·검증 규칙)

- Docker 기동 후 통합 테스트 실제 실행 (`./gradlew test`) — skip 시 명시 보고
- 외부 연동(소셜 provider, 이메일 서비스) 실제 연동 검증은 배포 후 E2E 확인으로 명시
- @Disabled·assertion 약화로 테스트 통과 금지
- 모든 변경 완료 후 CHANGELOG.html 갱신
