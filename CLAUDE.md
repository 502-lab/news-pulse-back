# Todo Backend — Claude Context

## 프로젝트 개요

Todo 앱의 Backend 파트. Spring Boot 기반 REST API 서버.
파트 간 직접 협업 없음. API Contract 오너는 Backend.
openapi.yaml 변경 시 반드시 todo-spec 레포에 반영.

## 기술 스택

- Language: Java 17 / Kotlin (선택한 것으로 수정)
- Framework: Spring Boot 3.x
- ORM: Spring Data JPA + Hibernate
- DB: PostgreSQL
- 문서화: SpringDoc OpenAPI (Swagger UI)
- 빌드: Gradle
- 패키지 구조: com.todo.\*

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
- dev 서버: 서버에 application-dev.yaml 직접 배치
- prd 서버: 서버에 application-prd.yaml 직접 배치
- 프로파일 활성화: spring.profiles.active=local|dev|prd

## 코딩 규칙

### 아키텍처

- 3계층 구조 필수: Controller → Service → Repository
- Controller에 비즈니스 로직 금지
- Service에 JPA 쿼리 직접 작성 금지, Repository 통해서만
- 패키지 구조: controller / service / repository / domain / dto / exception

### API

- 모든 엔드포인트 변경 시 openapi.yaml 선반영 후 구현
- 응답 형식: 공통 ApiResponse<T> 래퍼 사용
- 에러 응답: RFC 7807 Problem Details 형식
- @RestControllerAdvice로 예외 중앙처리

### 도메인

- Entity에 비즈니스 로직 캡슐화 권장
- DTO ↔ Entity 변환은 Service 레이어에서 처리
- Lombok 사용 허용 (@Getter, @Builder, @RequiredArgsConstructor)

### 기타

- 스펙에 없는 기능 임의 추가 금지
- N+1 문제 주의, fetch join 또는 @BatchSize 사용
- 트랜잭션: @Transactional 읽기 전용은 readOnly = true
- System.out.println 커밋 금지, log.info/debug 사용

## AI 지침

- 구현 전 /specs/features/ 해당 스펙 파일 먼저 확인할 것
- API 변경 시 openapi.yaml 수정 내용을 먼저 제안할 것
- Entity 변경 시 migration SQL도 함께 생성할 것
- 테스트: Service 레이어 단위 테스트 필수
  Repository 테스트는 @DataJpaTest 사용
  Controller 테스트는 @WebMvcTest 사용
- 성능 이슈 감지 시 코드 작성 전에 먼저 알려줄 것

## 디렉토리 구조

src/
├── main/
│ ├── java/com/todo/
│ │ ├── controller/ ← REST API 엔드포인트
│ │ ├── service/ ← 비즈니스 로직
│ │ ├── repository/ ← JPA Repository
│ │ ├── domain/ ← Entity
│ │ ├── dto/ ← Request / Response DTO
│ │ └── exception/ ← 커스텀 예외, 핸들러
│ └── resources/
│ ├── application.yaml ← 공통 설정 (커밋)
│ ├── application-local.yaml ← 로컬 전용 (gitignore)
│ ├── application-dev.yaml ← dev 서버 전용 (gitignore)
│ ├── application-prd.yaml ← prd 서버 전용 (gitignore)
│ └── application-example.yaml ← 템플릿 (커밋)
└── test/
└── java/com/todo/
├── controller/
├── service/
└── repository/

specs/ ← todo-spec submodule (수정 금지)
