# Implementation Plan: 009 읽기 추적(Read Tracking) — 조회 이벤트 BE 단독 기록

**Branch**: `feat/009-read-tracking` | **Date**: 2026-06-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `.specify/specs/009-read-tracking/spec.md`

## Summary

인증 사용자가 기사 상세(`GET /api/v1/articles/{id}`)를 열 때, 서버가 **best-effort·비차단**으로 조회 이벤트(`article_event`)를 기록한다. 기록은 기사 상세(핫패스, lazy 요약 write 포함)와 **물리적으로 분리된 트랜잭션**에서 수행되며, 실패해도 상세 응답을 깨지 않는다(try-catch 후 로깅만). 동일 (account, article)의 30분 내 재조회는 디바운스로 1건 억제한다. 사용자는 본인 읽은수(distinct article)·조회 이력(최신순)을 `/api/v1/me/...`로 조회한다. 체류·완료율·클릭·공유는 클라이언트 계약 의존 → 후속 사이클(forward-seam만 스키마에 남김).

## Technical Context

**Language/Version**: Java 25

**Primary Dependencies**: Spring Boot 4.x, Spring Data JPA(Hibernate), Spring Security(JWT), PostgreSQL, Flyway, SpringDoc OpenAPI

**Storage**: PostgreSQL — 신규 `article_event` 테이블(V17). 기존 `accounts`(UUID PK)·`articles`(BIGINT PK) 참조만.

**Testing**: JUnit5 + Mockito(서비스 단위), Testcontainers `BigmPostgresImage`(통합/리포지토리), @SpringBootTest RANDOM_PORT(인가/E2E)

**Target Platform**: Linux 서버(EC2, pm2 + bootJar)

**Project Type**: 단일 백엔드(web-service). 3계층(controller/service/repository)

**Performance Goals**: 조회 기록이 기사 상세 p95에 **유의미한 영향 없음**(SC-002). 기록 경로 = 디바운스 SELECT 1 + 조건부 INSERT 1, 핫패스 비차단.

**Constraints**: best-effort = **실패 격리(보장) + 지연 비격리(MVP 수용)**. REQUIRES_NEW + try-catch가 기록 **예외**를 잡아 상세 조회를 절대 안 깸(FR-003 "실패 비차단"). **단 동기 호출이라 기록 지연은 상세 응답에 전파 가능**(타임아웃은 try-catch가 못 잡음) — 저볼륨+디바운스로 작게 유지, 진짜 지연 격리(@Async)는 forward-note. 단일 인스턴스 가정(007/008 상속). 외부 API 無(BE 단독).

**Scale/Scope**: `article_event`는 서비스에서 가장 빠르게 성장하는 테이블(조회당 최대 1행). 인덱스 2개 + 성장/파티셔닝 forward-note.

## Constitution Check

*GATE: Phase 0 전 통과, Phase 1 후 재확인.*

- **3계층**: Controller(상세 + best-effort 기록 디스패치)·Service(`ReadTrackingService` 디바운스/INSERT, `ReadHistoryService` 조회)·Repository(`ArticleEventRepository`). ✅
- **ApiResponse 래퍼 + RFC7807 + @RestControllerAdvice**: 신규 조회 API에 적용. ✅
- **Swagger 어노테이션**: 신규 컨트롤러/메서드/DTO에 @Tag·@Operation·@ApiResponses·@Schema. ✅
- **openapi 선반영**: `contracts/openapi-patch.yaml` → dev push 시 sync-openapi가 news-pulse-spec 반영. ✅
- **Entity 변경 시 migration SQL 동반**: V17 동반. ✅
- **외부 API 예외처리**: 해당 없음(외부 연동 無). ✅
- **테스트 기준**: 서비스 단위 + 리포지토리 @DataJpaTest 대체(Testcontainers) + 인가 통합 + best-effort 격리 IT. ✅
- **성능 이슈 선보고**: 핫패스에 디바운스 SELECT 1회 추가 → best-effort(try-catch) 안에 포함, 상세 비차단으로 흡수. 아래 research D2에 명시. ✅

**위반 없음** → Phase 0 진행.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/009-read-tracking/
├── plan.md              # 본 파일
├── research.md          # Phase 0 — 설계 결정(TX 분리·try-catch 위치·디바운스·인덱스)
├── data-model.md        # Phase 1 — article_event V17 + 인덱스
├── quickstart.md        # Phase 1 — 검증 시나리오
├── contracts/
│   └── openapi-patch.yaml  # Phase 1 — /me/read-count·/me/read-history
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
src/main/java/com/newscurator/
├── controller/
│   ├── ArticleDetailController.java     # 변경: @AuthenticationPrincipal 주입 + 기록 best-effort 디스패치
│   └── ReadHistoryController.java       # 신규: GET /api/v1/me/read-count·/me/read-history
├── service/
│   ├── ReadTrackingService.java         # 신규: recordView(REQUIRES_NEW, 디바운스+조건부 INSERT)
│   └── ReadHistoryService.java          # 신규: 읽은수(distinct)·이력(역순) 조회
├── repository/
│   └── ArticleEventRepository.java      # 신규: 디바운스 EXISTS·distinct count·history page
├── domain/
│   └── ArticleEvent.java                # 신규 Entity (+ ArticleEventType·ArticleEventSource enum, P1=VIEW/SERVER)
└── dto/response/
    ├── ReadCountResponse.java           # 신규
    └── ReadHistoryItemResponse.java     # 신규
src/main/resources/db/migration/
└── V17__read_tracking.sql               # 신규: article_event + 인덱스 2개
```

**Structure Decision**: 기존 3계층 구조에 그대로 편입. `ArticleDetailController`만 최소 변경(principal 주입 + 상세 성공 후 best-effort 기록 디스패치), 나머지는 신규 파일.

## Complexity Tracking

> 위반 없음 — 비움.
