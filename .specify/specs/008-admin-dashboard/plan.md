# Implementation Plan: 어드민 대시보드 (운영 관리 레이어)

**Branch**: `feat/008-admin-dashboard` | **Date**: 2026-06-23 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/.specify/specs/008-admin-dashboard/spec.md`

## Summary

기존 데이터·파이프라인 위에 도는 **ADMIN 전용 운영 레이어**. 사용자 관리(role·활성/비활성), 운영 모니터링
조회(KPI·파이프라인·스케줄러·수집량), 수집·콘텐츠 제어(스케줄러 토글/수동실행·제외 키워드·요약 재시도·기사 숨김),
공지 CRUD + 005 어드민 푸시. 모든 엔드포인트 `/api/v1/admin/**` `hasRole('ADMIN')`(기존 SecurityConfig 재활용,
신규 role 없음). 신규 영속 footprint: **Notice·AdminAuditLog·SchedulerSetting·ExcludedKeyword** 4개 엔티티 +
`articles.admin_hidden_at` 컬럼 1개 + `AuditTargetType` enum. clarify(2026-06-23) 5결정 반영.

핵심 설계 결정(grep 실증):
- **OI-A 기사 hidden = 전용 컬럼 `articles.admin_hidden_at`** (feed_visible 재활용 금지 — feed_visible는 ExpiryService 물리삭제와 엮임).
- **OI-B hidden = 읽기 쿼리에서 제외**(article_keyword 행 삭제 안 함, 가역).
- **OI-C 감사 대상 타입 = 신규 `AuditTargetType`** (기존 `AdminTargetType`=푸시 대상 선택자라 부적합).

## Technical Context

**Language/Version**: Java 25

**Primary Dependencies**: Spring Boot 4.x, Spring Data JPA(Hibernate), Spring Security + JWT, SpringDoc OpenAPI, Flyway

**Storage**: PostgreSQL (신규 4 테이블 + articles 컬럼 추가, 단일 V15 마이그레이션). Redis 미사용.

**Testing**: JUnit5 + Mockito(서비스 단위), Testcontainers `BigmPostgresImage.NAME`(통합/리포지토리), 표준 MockMvc + 실 SecurityConfig(@SpringBootTest)로 인가 IT. (Spring Boot 4: @DataJpaTest/@WebMvcTest 제거됨 → @SpringBootTest 기반)

**Target Platform**: Linux 서버(단일 인스턴스 EC2), 단일 인스턴스 스케줄러 전제

**Project Type**: web-service (기존 com.newscurator.* 3계층 백엔드). admin UI 렌더링은 범위 밖(API 계약만).

**Performance Goals**: 모니터링 단일 조회 ≤5s(SC-003), 사용자 검색/변경 ≤30s 작업(SC-001). 어드민 트래픽은 소규모(운영자 한정).

**Constraints**: ADMIN 외 100% 차단(SC-002), hidden 사용자향 노출 0건(SC-005), 변형 액션 100% 감사(SC-009), 스케줄러 토글 영속(SC-010), 빈 데이터 0/빈값 안전(SC-007).

**Scale/Scope**: 운영자 소수, 사용자 수천~수만. 5 User Story(P1×2·P2×2·P3×1), 약 4 신규 엔티티 + 다수 admin 엔드포인트.

## Constitution Check

*GATE: Phase 0 전 통과, Phase 1 후 재확인.*

- **I. 레이어드 아키텍처·단방향 의존**: ✅ Controller→Service→Repository 준수. admin 컨트롤러는 비즈니스 로직 없음. 신규 AdminUserService/AdminMonitoringService/AdminOpsService/NoticeService/SchedulerControlService 등으로 분리.
- **II. API 경계 Entity 비노출·입력 검증**: ✅ 응답 전용 DTO(record) + `@Valid` 요청 DTO. accounts·articles 엔티티 직접 노출 금지(FR-003).
- **III. 일관 응답·전역 예외**: ✅ `ApiResponse<T>` 래퍼, 에러 RFC7807, `@RestControllerAdvice` 재활용. 신규 예외(예: LastAdminProtectedException, ArticleNotFound 등) 중앙 처리.
- **IV. 테스트 없는 비즈니스 로직 금지**: ✅ 서비스 단위 테스트 + 통합(BigmPostgresImage) + 인가 IT(ADMIN/비ADMIN/비인증). hidden 일관성·lockout 가드·감사 캡처·스케줄러 토글은 크라운주얼 테스트.
- **V. 스키마 변경 = 마이그레이션만**: ✅ 단일 `V15__admin_dashboard.sql`(4 테이블 + articles.admin_hidden_at 컬럼 + 인덱스). Entity 변경과 함께 생성.
- **VI. 보안 기본값·시크릿 외부화**: ✅ 전 엔드포인트 `/api/v1/admin/** hasRole(ADMIN)`(기존 line 78 재활용). 신규 시크릿 없음. 신규 공개 엔드포인트(공개 공지 조회)는 permitAll 명시 선언.
- **VII. 수집·알림 멱등성**: ✅ 어드민 푸시는 005 outbox `uq_outbox_idempotency`로 멱등. 스케줄러 수동 실행은 단일 인스턴스 전제 + 동시 실행 가드.

**위반 없음** → Complexity Tracking 비움. (공개 공지 조회 엔드포인트는 Principle VI에 따라 SecurityConfig에 permitAll 명시.)

## Project Structure

### Documentation (this feature)

```text
.specify/specs/008-admin-dashboard/
├── plan.md              # 본 파일
├── research.md          # Phase 0 — OI 결정·캡처/연결 설계·기존 표면 확인
├── data-model.md        # Phase 1 — 4 엔티티 + enum + V15 DDL + hidden 읽기경로
├── quickstart.md        # Phase 1 — 검증 시나리오
├── contracts/           # Phase 1 — admin API 계약(openapi-patch.yaml)
└── tasks.md             # /speckit-tasks 산출(이 명령 아님)
```

### Source Code (repository root)

```text
src/main/java/com/newscurator/
├── controller/
│   ├── AdminUserController.java          # US1 사용자 관리
│   ├── AdminMonitoringController.java     # US2 KPI·파이프라인·스케줄러·수집량·bias/trend 뷰
│   ├── AdminOpsController.java            # US3 스케줄러 제어·제외키워드·요약재시도·기사 숨김
│   ├── AdminNoticeController.java         # US4 공지 CRUD
│   ├── NoticeController.java              # US4 공개 공지 조회(permitAll)
│   ├── AdminOpsStatsController.java       # US5 OpsStats·ErrorLog·수집량 상세
│   ├── AdminNotificationController.java   # (기존 005) 어드민 푸시 — 재활용/확장
│   └── AdminPipelineController.java       # (기존 001) pipeline/stats — 재활용/확장
├── service/
│   ├── admin/AdminUserService.java
│   ├── admin/AdminMonitoringService.java
│   ├── admin/AdminOpsService.java
│   ├── admin/SchedulerControlService.java # SchedulerSetting 읽기/쓰기
│   ├── admin/NoticeService.java
│   ├── admin/AdminAuditService.java       # 감사 캡처(서비스 명시 호출)
│   └── admin/AdminOpsStatsService.java
├── domain/
│   ├── Notice.java
│   ├── AdminAuditLog.java
│   ├── SchedulerSetting.java
│   ├── ExcludedKeyword.java
│   ├── Article.java                       # (기존) admin_hidden_at 필드 추가
│   └── enums/AuditTargetType.java         # 신규
├── repository/
│   ├── NoticeRepository.java
│   ├── AdminAuditLogRepository.java
│   ├── SchedulerSettingRepository.java
│   └── ExcludedKeywordRepository.java
├── dto/{request,response}/...             # admin 요청/응답 DTO(record)
└── scheduler/                             # @Scheduled 메서드 12개(9 클래스)에 SchedulerSetting 게이트 주입

src/main/resources/db/migration/
└── V15__admin_dashboard.sql               # 4 테이블 + articles.admin_hidden_at + 인덱스

src/test/java/com/newscurator/
├── service/admin/...                      # 서비스 단위
├── repository/...                         # 리포지토리 통합(BigmPostgresImage)
└── integration/Admin*IT.java              # 인가·hidden일관성·lockout·감사·스케줄러토글 IT
```

**Structure Decision**: 기존 `com.newscurator.*` 단일 백엔드 3계층에 admin 하위 패키지(service/admin)를 더한다.
기존 `AdminPipelineController`(001)·`AdminNotificationController`(005)는 재활용·확장하고, 나머지 admin 영역은
신규 컨트롤러로 분리한다. UI는 범위 밖.

## Complexity Tracking

> Constitution 위반 없음 — 비움.
