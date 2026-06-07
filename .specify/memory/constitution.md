<!--
SYNC IMPACT REPORT
==================
Version change: [UNVERSIONED] → 1.0.0
Modified principles: N/A (initial ratification — all 7 principles newly defined)
Added sections:
  - Core Principles (I ~ VII)
  - Additional Standards
  - Governance
Removed sections: N/A (template placeholders replaced)
Templates reviewed:
  - .specify/templates/plan-template.md ✅ (Constitution Check section is generic; no update needed)
  - .specify/templates/spec-template.md ✅ (DTO/validation guidance aligns with Principle II)
  - .specify/templates/tasks-template.md ✅ (Testing & observability phases align with Principles IV and Additional Standards)
Follow-up TODOs: None — all placeholders resolved.
-->

# news-pulse-back Constitution

## Core Principles

### I. 레이어드 아키텍처와 단방향 의존

Controller → Service → Repository 단방향 의존을 강제한다.
비즈니스 로직은 Controller가 아닌 Service/도메인 계층에만 둔다.
Repository는 영속화만 책임지며 비즈니스 규칙을 포함하지 않는다.
트랜잭션 경계는 Service 계층에 두고, 조회 전용 메서드는 `@Transactional(readOnly = true)`를 사용한다.

**Rationale**: 레이어 간 책임 명확화는 변경 범위를 예측 가능하게 하고 테스트 가능성을 높인다.

### II. API 경계에서 Entity 비노출과 입력 검증

JPA Entity를 Controller 요청/응답에 직접 사용하지 않는다. 요청/응답은 DTO(Java record 권장)로
주고받는다. Entity ↔ DTO 변환은 명시적인 매핑 계층(Service 또는 전용 Mapper)에서 수행한다.
모든 외부 입력 DTO는 Bean Validation(`@Valid`)으로 검증하며, 검증 실패는 전역 핸들러에서
동일한 에러 포맷으로 응답한다.

**Rationale**: Entity 직접 노출은 내부 스키마 변경이 API 계약을 깨뜨리는 주 원인이다.
DTO 레이어는 API 안정성과 도메인 모델의 독립적 진화를 보장한다.

### III. 일관된 응답 포맷과 전역 예외 처리

모든 예외는 `@RestControllerAdvice` 전역 핸들러에서 처리한다.
에러 응답은 통일된 포맷(`code`, `message`, `timestamp`)을 따른다.
HTTP 상태 코드를 의미에 맞게 사용하고, 200으로 에러를 감싸지 않는다.

**Rationale**: 클라이언트(React, Android)가 동일한 에러 파싱 로직을 공유할 수 있고,
디버깅 시간을 단축한다.

### IV. 테스트 없는 비즈니스 로직 금지

Service 계층 핵심 비즈니스 규칙은 단위 테스트를 필수로 작성한다.
DB·외부 연동 통합 테스트는 Testcontainers로 실제 환경에 가깝게 검증한다.
테스트가 깨진 상태로 `main` 브랜치에 병합하지 않는다.

**Rationale**: 뉴스 수집·알림 로직의 멱등성 보장과 외부 API 의존성 관리는
자동화 테스트 없이 회귀를 발견하기 어렵다.

### V. 스키마 변경은 마이그레이션으로만

DB 스키마 변경은 Flyway 마이그레이션 스크립트로만 수행한다.
운영 환경에서 Hibernate `ddl-auto`는 `none`(또는 `validate`)으로 고정한다.
수동 DDL이나 자동 스키마 생성에 의존하지 않는다.

**Rationale**: 마이그레이션 히스토리는 배포 재현성과 롤백 경로를 보장하며,
자동 DDL은 운영 데이터 손실의 잠재적 원인이다.

### VI. 보안 기본값과 시크릿 외부화

인증은 JWT 기반이며, "인증 필요"가 기본값이다. 공개 엔드포인트만 명시적으로 `permitAll`한다.
PostgreSQL 접속 정보·API 키·시크릿은 코드/깃에 커밋하지 않고 환경변수·외부 설정으로 주입한다.
프로파일을 `local` / `dev` / `prod`로 분리한다.

**Rationale**: Secure-by-default는 신규 엔드포인트가 실수로 인증 없이 노출되는 사고를 예방한다.

### VII. 수집·알림의 멱등성과 중복 방지

뉴스 수집과 알림 발송은 멱등하게 설계한다(동일 기사·동일 알림 중복 처리 금지).
기사 식별 키(소스 + 원문 ID/URL)로 dedup한다.
스케줄 작업은 중복 실행·동시 실행 상황에서도 안전하게 동작한다.

**Rationale**: 외부 뉴스 소스는 동일 기사를 여러 번 반환할 수 있고, 스케줄러는 재시도·중첩 실행이
발생할 수 있다. 중복 처리는 데이터 오염과 불필요한 알림 발송으로 이어진다.

## Additional Standards

**시간대 관리**: 모든 시각은 UTC로 저장하고 표시 시점에만 타임존을 변환한다.
외부 소스의 "발행 시각(`published_at`)"과 시스템의 "수집 시각(`collected_at`)"을 구분해 보관한다.

**API 버저닝과 하위 호환성**: 공개 API는 버저닝(`/api/v1`)하며, 배포된 버전의 응답 계약을 깨는
변경(필드 제거·타입 변경)을 하지 않는다. 계약 변경은 새 버전 또는 하위 호환 방식으로만 한다.

**외부 API 복원력**: 외부 API 호출에는 timeout과 재시도·백오프를 적용하고, 외부 소스 일부 실패가
전체 수집을 중단시키지 않도록 격리한다(graceful degradation).
외부 소스의 rate limit과 이용약관을 준수한다.

**구조적 로깅과 관측 가능성**: 구조적 로깅과 요청/작업 추적 ID를 적용한다.
시크릿·개인정보(이메일 등)·토큰은 로그에 남기지 않는다.
스케줄·배치 작업은 시작/종료/실패를 관측 가능하게 기록한다.

## Governance

이 원칙들은 spec/plan/tasks/구현 전반에서 준수한다.
원칙을 위반해야 하는 경우, 위반 사유와 채택한 대안을 해당 feature의 `plan.md`
Complexity Tracking 섹션에 명시적으로 기록한다.

원칙 변경 절차:

1. 변경 사유와 영향 범위를 문서화한다.
2. `CONSTITUTION_VERSION`을 시맨틱 버저닝에 따라 올린다:
   원칙 제거/재정의 → MAJOR, 원칙 추가/확장 → MINOR, 표현 개선 → PATCH.
3. 변경 이유를 `Last Amended` 날짜와 함께 이 파일에 남긴다.
4. 영향받는 템플릿(`plan-template.md`, `spec-template.md`, `tasks-template.md`)을 동기화한다.

**Version**: 1.0.0 | **Ratified**: 2026-06-07 | **Last Amended**: 2026-06-07
