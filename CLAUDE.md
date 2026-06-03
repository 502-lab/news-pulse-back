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
- 공통 응답 형식: ApiResponse<T> 래퍼 사용
- 에러 응답: RFC 7807 Problem Details 형식
- @RestControllerAdvice로 예외 중앙 처리
- 페이지네이션 응답은 Spring Page<T> 래퍼 사용

### 외부 API 클라이언트

- NewsAPI, Gemini, DeepL 클라이언트는 /client 패키지에서만 관리
- 외부 API 호출 실패 시 fallback 처리 필수 (서킷브레이커 고려)
- Gemini 요약 결과는 Redis에 캐싱 (동일 기사 재호출 방지)
- NewsAPI 호출은 스케줄러에서만, 컨트롤러 직접 호출 금지

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
