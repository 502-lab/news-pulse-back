# News Curator AI — Backend Claude Context

## 도구 버전

- Claude Code: 1.0.3 (.claude-version 참조)
- 반드시 동일 버전 사용할 것. 버전 변경 시 이 파일도 함께 수정.

## 프로젝트 개요

AI 뉴스 큐레이터 앱의 Backend 파트.

- 뉴스 수집 스케줄러 (NewsAPI 연동)
- Gemini API를 활용한 기사 AI 요약 처리
- 사용자 관심사 기반 개인화 필터링
- 트렌드 키워드 집계 API 제공

파트 간 직접 협업 없음. API Contract 오너는 Backend.
openapi.yaml 변경 시 반드시 news-curator-spec 레포에 반영.

## 기술 스택

- Language: Java 25
- Framework: Spring Boot 4.x
- ORM: Spring Data JPA + Hibernate
- DB: PostgreSQL
- Cache: Redis (뉴스 캐싱, AI 요약 재호출 방지)
- 인증: Spring Security + JWT
- 문서화: SpringDoc OpenAPI (Swagger UI)
- 빌드: Gradle
- 패키지 구조: com.newscurator.\*

## 외부 API 연동

- NewsAPI: 뉴스 수집 스케줄러 (수집 주기 조정으로 100 req/day 한도 관리)
- Google Gemini API: 기사 요약, 편향 분석 (Flash 모델 사용)
- DeepL API: 영문 기사 번역 (필요시)

## 참조 문서 (구현 전 반드시 확인)

- 기능 스펙: /specs/features/
- API 계약: /specs/api-contract/openapi.yaml (내가 오너)
- 아키텍처 결정: /specs/adr/

## 브랜치 전략

- prd: 최종 배포 (직접 커밋 금지)
- dev: 개발 통합 (직접 커밋 금지)
- feat/xxx: 기능 개발 → dev로 PR
- hotfix/xxx: 긴급 수정 → prd로 PR 후 dev 역머지
- refactor/xxx: 리팩토링 → dev로 PR

## 환경변수

- 로컬: application-local.yaml (gitignore, application-example.yaml 참고)
- dev 서버: 서버에 application-dev.yaml 직접 배치 (gitignore)
- prd 서버: 서버에 application-prd.yaml 직접 배치 (gitignore)
- 프로파일 활성화: spring.profiles.active=local|dev|prd
- 외부 API 키는 절대 코드에 하드코딩 금지, 환경변수에서만 주입

## 코딩 규칙

### 아키텍처

- 3계층 구조 필수: Controller → Service → Repository
- Controller에 비즈니스 로직 금지
- Service에 JPA 쿼리 직접 작성 금지, Repository 통해서만
- 패키지 구조: controller / service / repository / domain / dto / exception / scheduler / client

### API

- 모든 엔드포인트 변경 시 openapi.yaml 선반영 후 구현
- 공통 응답 형식: ApiResponse<T> 래퍼 사용 (필드 4개: code·status·message·data)
  - `code`: HTTP 상태 코드 정수 (200, 201, 202 등)
  - `status`: 문자열 `"success"` (성공 응답 공통)
  - `message`: 사람이 읽을 수 있는 설명 (`"OK"`, `"Created"`, `"Accepted"` 등)
  - `data`: 실제 페이로드 (데이터 없는 경우 `null`)
  - factory 메서드: `ApiResponse.success(data)`, `.created(data)`, `.accepted(data)`, `.of(code, message, data)`
  - 204 No Content 응답은 body 없음 유지 (HTTP 규격 준수)
  - 참고: `ApiResponse.status` 필드명과 Swagger `@ApiResponse` 어노테이션이 충돌하므로 컨트롤러에서 `@io.swagger.v3.oas.annotations.responses.ApiResponse` fully-qualified 형태 사용
- 에러 응답: RFC 7807 Problem Details 형식
- @RestControllerAdvice로 예외 중앙 처리

### Swagger 문서화 (새 API 추가/변경 시 필수)

- Controller 클래스: @Tag(name, description) 필수
- 각 엔드포인트 메서드: @Operation(summary, description) 필수
  - summary: 한 줄 요약
  - description: 동작 방식, 제약 조건, 주의사항 포함
- 각 엔드포인트: @ApiResponses로 가능한 HTTP 상태코드 전부 명시 (200 외 4xx도 포함)
- @RequestParam / @PathVariable: @Parameter(description) 필수
- Request/Response DTO(record): @Schema(description) 필수
  - 클래스 레벨: @Schema(description) 로 DTO 전체 설명
  - 각 필드: @Schema(description)로 필드 설명, 값 범위·단위·null 가능 여부 명시
  - 가능한 경우 example 값 추가
- 페이지네이션 응답은 Spring Page<T> 래퍼 사용

### 외부 API 클라이언트

- NewsAPI, Gemini, DeepL 클라이언트는 /client 패키지에서만 관리
- 외부 API 호출 실패 시 fallback 처리 필수 (서킷브레이커 고려)
- Gemini 요약 결과는 Redis에 캐싱 (동일 기사 재호출 방지)
- NewsAPI 호출은 스케줄러에서만, 컨트롤러 직접 호출 금지
- **이메일 서비스**: Resend REST API — `POST /emails`, `Authorization: Bearer {KEY}`, body `{from, to[], subject, html}`. 환경변수: `email-service.base-url`, `email-service.api-key`, `email-service.from-address`

### 스케줄러

- 뉴스 수집 스케줄러는 /scheduler 패키지에서 관리
- 수집 주기는 환경변수로 관리 (하드코딩 금지)
- 중복 수집 방지 로직 필수 (기사 URL 기준 중복 체크)

### 도메인

- Entity에 비즈니스 로직 캡슐화 권장
- DTO ↔ Entity 변환은 Service 레이어에서 처리
- Lombok 사용 허용 (@Getter, @Builder, @RequiredArgsConstructor)

### 성능

- N+1 문제 주의, fetch join 또는 @BatchSize 사용
- 트랜잭션: 읽기 전용은 @Transactional(readOnly = true)
- 뉴스 피드 조회는 Redis 캐시 우선 조회

### 기타

- 스펙에 없는 기능 임의 추가 금지
- System.out.println 커밋 금지, log.info/debug 사용
- API 키, 비밀번호 로그 출력 절대 금지

## 변경 이력 관리

- 코드 수정·파일 생성·버그 수정 등 **모든 작업 완료 시** `CHANGELOG.html`에 항목을 추가할 것
- 날짜 그룹(`<div class="date-group">`) 하위에 `<div class="entry">` 블록으로 기록
- 각 항목에 반드시 포함할 내용:
  - category 태그: `tag-bugfix` / `tag-feature` / `tag-config` / `tag-db` / `tag-test` / `tag-docs` / `tag-hotfix`
  - 심각도(bugfix류): `sev-critical` / `sev-high` / `sev-medium` / `sev-low`
  - 문제(발견 경위), 근본 원인, 수정 내용, **결정 이유(왜 이 방법을 선택했는지 — 대안 대비 근거)**, 영향 파일
- 같은 날짜 그룹이 없으면 새 `<div class="date-group">` 블록을 최상단에 추가
- stats bar의 항목 수도 함께 갱신할 것
- **민감 정보 절대 포함 금지**: API 키, 비밀번호, DB 접속 정보, 토큰, 개인정보(이메일 등)는 기록하지 않을 것. URL 예시도 자격증명이 포함된 형태 사용 금지

## AI 지침

- 구현 전 /specs/features/ 해당 스펙 파일 먼저 확인할 것
- API 변경 시 openapi.yaml 수정 내용을 먼저 제안할 것
- Entity 변경 시 migration SQL도 함께 생성할 것
- 외부 API 연동 코드 작성 시 반드시 예외 처리 포함할 것
- Gemini API 프롬프트 변경 시 /specs/adr/ 에 결정 기록 남길 것
- 성능 이슈 감지 시 코드 작성 전 먼저 알려줄 것
- 테스트 작성 기준:
  - Service 레이어: 단위 테스트 필수
  - Repository: @DataJpaTest 사용
  - Controller: @WebMvcTest 사용
  - 외부 API 클라이언트: Mock 처리 필수
  - **통합 테스트 PostgreSQL 컨테이너**: 반드시 `BigmPostgresImage.NAME` 사용 — V9 마이그레이션(`V9__saved_articles_search_indexes.sql`)이 pg_bigm 확장을 요구하므로 `"postgres:16-alpine"` 직접 사용 금지
  - **이메일 발송 WireMock 스텁**: 경로는 `/emails` (Resend API 경로) — `/send-verification-code` 등 구 경로 사용 금지

## 구현된 주요 플로우 (변경 시 반드시 여기도 수정)

### 소셜 OAuth 인증

- **신규 가입 (2단계)**:
  1. `GET /auth/social/{provider}/authorize?redirectUri=...` → authorizeUrl 반환 (state JWT 포함, redirectUri 화이트리스트 검증)
  2. `POST /auth/social/{provider}/callback` `{code, state, redirectUri}` → **202** + `pendingToken`(10분 JWT) + 활성 약관 목록 반환. 이 시점에 계정 미생성.
  3. `POST /auth/social/complete` `{pendingToken, consents, ageConfirmed}` → **201** + account + tokens. 계정·SocialConnection·ConsentRecord 생성.
- **기존 로그인**: callback → **200** + account + tokens (pendingToken 없음)
- **이메일 충돌**: callback → **409** (동일 이메일 이메일 계정 존재)
- **redirectUri 화이트리스트**: `oauth.allowed-redirect-uris` (application.yaml). callback 요청 body에도 `redirectUri`(@NotBlank) 필수 — 없으면 422.

### OpenAPI 자동 동기화

- `dev`/`main` push 시 `.github/workflows/sync-openapi.yml` 워크플로우 실행
- `OpenApiSpecExportTest` → 앱 기동 후 `/api-docs` 호출 → JSON→YAML 변환 → `build/openapi.yaml` 저장
- `SPEC_REPO_TOKEN` 시크릿(news-pulse-spec repo write 권한 PAT)으로 news-pulse-spec 레포에 자동 push

## 구현·검증 규칙

- implement/검증 시 항상 Docker를 띄우고 통합·E2E 테스트를 실제 실행한다. 통합 테스트를 skip한 채 "통과"로 보고하지 않는다. skip이 불가피하면 그 사실을 명시 보고한다.
- 테스트를 @Disabled·assertion 약화·예외 삼킴으로 통과시키지 않는다. 불가피하면 사유를 적고 승인을 요청한다.
- 자율로 코드/설정/테스트를 수정했으면 변경 요약과 함께 "근본 수정 vs 증상 가리기"를 스스로 분류해 보고하고, 승인 없이 green만 만들지 않는다.
- 외부 실제 연동(Gemini·네이버·RSS) 검증 여부를 보고하고, 안 했으면 "런타임/배포 시 검증"으로 명시한다.

## 디렉토리 구조

src/
├── main/
│ ├── java/com/newscurator/
│ │ ├── controller/ ← REST API 엔드포인트
│ │ ├── service/ ← 비즈니스 로직
│ │ ├── repository/ ← JPA Repository
│ │ ├── domain/ ← Entity
│ │ ├── dto/ ← Request / Response DTO
│ │ ├── exception/ ← 커스텀 예외, 핸들러
│ │ ├── scheduler/ ← 뉴스 수집 스케줄러
│ │ └── client/ ← 외부 API 클라이언트
│ │ ├── NewsApiClient.java
│ │ ├── GeminiClient.java
│ │ └── DeepLClient.java
│ └── resources/
│ ├── application.yaml ← 공통 설정 (커밋)
│ ├── application-local.yaml ← 로컬 전용 (gitignore)
│ ├── application-dev.yaml ← dev 서버 전용 (gitignore)
│ ├── application-prd.yaml ← prd 서버 전용 (gitignore)
│ └── application-example.yaml ← 템플릿 (커밋)
└── test/
└── java/com/newscurator/
├── controller/
├── service/
├── repository/
└── client/

specs/ ← news-curator-spec submodule (수정 금지)

<!-- SPECKIT START -->

For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
at `.specify/specs/006-bias-analysis-engine/plan.md`.

<!-- SPECKIT END -->
