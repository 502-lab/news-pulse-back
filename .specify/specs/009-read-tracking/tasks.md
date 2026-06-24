# Tasks: 009 읽기 추적(Read Tracking) — 조회 이벤트 BE 단독 기록

**Feature**: `feat/009-read-tracking` | **Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

**범위**: P1(US1 조회 자동기록 + US2 읽은수·조회이력). US3(클라이언트 계측 이벤트)은 후속 사이클 — 본 tasks 제외.

크라운주얼 테스트(실 PG, `BigmPostgresImage`): best-effort 격리(T011)·디바운스(T012)·읽은수 distinct+이력 역순(T018)·본인 스코프(T018)·forward-seam(T012 단언).

---

## Phase 1: Setup

- [x] T001 OpenAPI 선반영 확인 — `.specify/specs/009-read-tracking/contracts/openapi-patch.yaml`(`/me/read-count`·`/me/read-history`)가 구현 대상과 일치하는지 점검(news-pulse-spec 반영은 dev push 시 sync-openapi가 수행).

---

## Phase 2: Foundational (차단 선행 — 모든 US 선행)

- [x] T002 V17 마이그레이션 작성 in `src/main/resources/db/migration/V17__read_tracking.sql` — `article_event`(account_id UUID NOT NULL FK→accounts ON DELETE CASCADE, article_id BIGINT NOT NULL FK→articles ON DELETE CASCADE, event_type VARCHAR(16) DEFAULT 'VIEW', metric_value INTEGER NULL, source VARCHAR(8) DEFAULT 'SERVER', occurred_at TIMESTAMPTZ DEFAULT now()) + 인덱스 2개 `idx_article_event_debounce (account_id, article_id, occurred_at)`·`idx_article_event_history (account_id, occurred_at DESC)`. (data-model.md DDL)
- [x] T003 [P] enum `ArticleEventType`(VIEW + forward-seam DWELL/COMPLETE/AI_CLICK/SHARE) in `src/main/java/com/newscurator/domain/enums/ArticleEventType.java`
- [x] T004 [P] enum `ArticleEventSource`(SERVER + forward-seam CLIENT) in `src/main/java/com/newscurator/domain/enums/ArticleEventSource.java`
- [x] T005 `ArticleEvent` 엔티티 in `src/main/java/com/newscurator/domain/ArticleEvent.java` — 필드 매핑(@Enumerated STRING) + 정적 팩토리 `ArticleEvent.view(UUID accountId, Long articleId)`(event_type=VIEW·source=SERVER·occurred_at=now, metric_value=null). Lombok @Getter @Builder. (T003·T004 선행)
- [x] T006 `ArticleEventRepository` in `src/main/java/com/newscurator/repository/ArticleEventRepository.java` — (a) 디바운스 조건부 INSERT(native `@Modifying`: `INSERT ... SELECT ... WHERE NOT EXISTS (account,article,VIEW,30분 윈도우)`, 반환 영향행수), (b) `countDistinctArticleByAccount`(읽은수: `COUNT(DISTINCT article_id) WHERE account_id=? AND event_type='VIEW'`), (c) 조회 이력 페이지 — **article 기준 최신 1건**(`DISTINCT ON (article_id) ... WHERE account_id=? AND event_type='VIEW' ORDER BY article_id, occurred_at DESC` 후 occurred_at DESC 재정렬, 또는 `GROUP BY article_id, max(occurred_at)`). 같은 기사 여러 조회여도 이력엔 1회만(중복 제거), 커서 페이지네이션. (T005 선행)

**Checkpoint**: 스키마·엔티티·리포지토리 준비 → US1/US2 착수 가능.

---

## Phase 3: US1 — 인증 사용자 기사 조회 자동 기록 (Priority: P1) 🎯 MVP

**목표**: 인증 사용자가 기사 상세를 열면 서버가 best-effort(실패 격리, 별개 TX)로 조회 이벤트 1건 기록 + 30분 디바운스.

**독립 테스트**: 상세 조회 1회 → article_event 1건(VIEW·SERVER·metric_value=null). 기록 강제 실패 시에도 상세 200 + 요약 write 롤백 없음.

### 구현

- [x] T007 [US1] `ReadTrackingService` in `src/main/java/com/newscurator/service/ReadTrackingService.java` — `recordView(UUID accountId, Long articleId)` = `@Transactional(propagation = REQUIRES_NEW)`, 내부에서 T006(a) 조건부 INSERT 호출(디바운스 판정 포함). 비즈니스 로직 전부 여기(컨트롤러 아님). (research D1·D2)
- [x] T008 [US1] `ArticleDetailController` 변경 in `src/main/java/com/newscurator/controller/ArticleDetailController.java` — `getDetail`에 `@AuthenticationPrincipal CustomUserDetails userDetails` 주입(002 패턴). `articleDetailService.getDetail(id)` **반환 직후** `try { readTrackingService.recordView(userDetails.getAccountId(), id); } catch (Exception e) { log.warn(...); }`(상세 성공 후 best-effort 디스패치, 예외는 로깅만). principal null(비로그인)이면 미기록(D3). Swagger @Operation 보강.

### 테스트 (크라운주얼)

- [x] T009 [P] [US1] `ArticleEventRepositoryTest`(Testcontainers `BigmPostgresImage`) in `src/test/java/com/newscurator/repository/ArticleEventRepositoryTest.java` — 조건부 INSERT 영향행수(신규=1·30분내 중복=0), countDistinct, history page 정확.
- [x] T010 [P] [US1] `ReadTrackingServiceTest`(단위, Mockito) in `src/test/java/com/newscurator/service/ReadTrackingServiceTest.java` — recordView가 repo 조건부 INSERT 호출, VIEW·SERVER·metric_value=null로만 기록(forward-seam 미사용).
- [x] T011 [US1] ★ `ReadTrackingBestEffortIT`(@SpringBootTest RANDOM_PORT, 실 PG) in `src/test/java/com/newscurator/integration/ReadTrackingBestEffortIT.java` — **크라운주얼 #1**: recordView 강제 실패(예: 존재하지 않는 article_id FK 위반/모킹 예외) 상황에서 `GET /api/v1/articles/{id}` **200 정상** + 상세의 lazy write(deep 요약 save) **롤백 안 됨** + article_event 0건(실패 삼킴). **검증하는 것 = end-to-end 격리 결과**(상세 200 + 요약 보존 + 기록실패 삼킴). **격리 보장의 핵심 = post-commit 호출 순서(getDetail @Transactional이 컨트롤러 반환 시 커밋 완료된 뒤 recordView 호출) + try-catch(예외 삼킴)**. REQUIRES_NEW는 ambient TX 부재로 여기선 REQUIRED와 동등하나 **방어적 경계로 명시 유지**(미래에 TX 안에서 호출돼도 격리). "REQUIRES_NEW 단독 load-bearing" 과장 금지(005 REQUIRES_NEW 계열·008 AdminAudit REQUIRED와 반대 방향성은 설계 맥락으로만).
- [x] T012 [US1] `ArticleViewRecordIT`(실 PG) in `src/test/java/com/newscurator/integration/ArticleViewRecordIT.java` — **크라운주얼 #2·#5**: 상세 조회 → article_event 1건(event_type=VIEW·source=SERVER·**metric_value=null** 단언=forward-seam) + **디바운스**(같은 account·article 30분내 2회=1행, 30분 경과 후 2행, 다른 기사 독립).

**Checkpoint**: 조회 기록 + 격리 + 디바운스 동작 → US1 독립 배포 가능(MVP).

---

## Phase 4: US2 — 개인 읽은수·조회 이력 조회 (Priority: P2)

**목표**: 사용자가 본인 읽은수(distinct article)·조회 이력(최신순)을 `/api/v1/me/...`로 조회. 본인 데이터만.

**독립 테스트**: US1로 N건 조회 후 read-count=distinct N, read-history=역순, 타인 토큰은 0건.

### 구현

- [x] T013 [P] [US2] DTO `ReadCountResponse`(readCount) in `src/main/java/com/newscurator/dto/response/ReadCountResponse.java` — @Schema.
- [x] T014 [P] [US2] DTO `ReadHistoryItemResponse`(articleId·title·lastViewedAt) + 페이지 래퍼 in `src/main/java/com/newscurator/dto/response/ReadHistoryItemResponse.java` — @Schema.
- [x] T015 [US2] `ReadHistoryService` in `src/main/java/com/newscurator/service/ReadHistoryService.java` — `@Transactional(readOnly=true)` 읽은수(T006b distinct) + 조회 이력(T006c, **article 기준 최신 1건 = DISTINCT ON(article_id), occurred_at DESC**, 같은 기사 다회 조회는 이력 1건, 기사 메타 조인). 본인 accountId 스코프.
- [x] T016 [US2] `ReadHistoryController` in `src/main/java/com/newscurator/controller/ReadHistoryController.java` — `GET /api/v1/me/read-count`·`GET /api/v1/me/read-history?cursor&size`, `@AuthenticationPrincipal`로 본인만, ApiResponse 래퍼, @Tag/@Operation/@ApiResponses/@Parameter.

### 테스트 (크라운주얼)

- [x] T017 [P] [US2] `ReadHistoryServiceTest`(단위) in `src/test/java/com/newscurator/service/ReadHistoryServiceTest.java` — 읽은수 distinct 계산, 이력 역순 매핑.
- [x] T018 [US2] `ReadHistoryIT`(실 PG) in `src/test/java/com/newscurator/integration/ReadHistoryIT.java` — **크라운주얼 #3·#4**: 같은 기사 여러 조회→읽은수=1, 다른 기사 N→N, **같은 기사 여러 조회 → 이력에 1건만**(DISTINCT ON article_id, F3), 이력 occurred_at DESC 정확 + **본인 스코프**(사용자 B 토큰으로 A 데이터 0건).

**Checkpoint**: 읽은수·이력 조회 + 본인 스코프 → US2 완료.

---

## Phase 5: Polish & Cross-Cutting

- [x] T019 [P] best-effort TX 분리 ADR in `.specify/specs/009-read-tracking/adr/ADR-001-best-effort-recording.md` — **격리 보장의 핵심 = post-commit 호출 순서(상세 서비스 @Transactional 커밋 후 컨트롤러에서 recordView 호출) + try-catch(예외 삼킴)**. **REQUIRES_NEW는 ambient TX 부재로 REQUIRED와 동등하나 방어적 경계로 명시 유지**(미래에 TX 안에서 호출돼도 격리) — "REQUIRES_NEW 단독 load-bearing" 과장 제거. 실패 비차단=보장 / 지연 비격리=MVP 수용(예외 O·타임아웃 X), @Async 지연격리 forward-note. 008 AdminAudit(REQUIRED 참여=같이 롤백) 정반대 방향·005(REQUIRES_NEW) 동일 계열은 설계 맥락. 디바운스 단일 조건부 INSERT, 인덱스 2개 결정.
- [x] T020 [P] CHANGELOG 항목 in `CHANGELOG.html` — 009 read-tracking(article_event V17·best-effort 격리·디바운스·읽은수/이력) feature 엔트리 + stats 갱신.
- [x] T021 quickstart 검증 상태 갱신 in `.specify/specs/009-read-tracking/quickstart.md` — 시나리오↔IT 매핑·full suite 결과.
- [x] T022 OpenApiSpecExportTest 통과 확인 → dev push 시 sync-openapi가 `/api/v1/me/read-count`·`/me/read-history` news-pulse-spec 반영.
- [x] T023 009 전체 회귀 + 001~009 full suite(V1~V17 순차 적용) 0 fail 확인(배치 실행, OOM 회피). **+ FR-010 흡수(F2): "개인 조회이력 질의 성립"(account별 article_event 조회로 006/009 인사이트 입력 가능) 1줄 확인**.

---

## Dependencies & 실행 순서

- **Setup(T001)** → **Foundational(T002~T006)**: T002→T005→T006, T003·T004는 T005 선행이며 [P].
- **US1(T007~T012)**: T007→T008(컨트롤러), 테스트 T009·T010 [P] 후 T011·T012. Foundational 완료 후 착수.
- **US2(T013~T018)**: T013·T014 [P]→T015→T016, T017 [P] 후 T018. Foundational 완료 후 착수(US1과 독립 — 병렬 가능하나 데이터는 US1 기록에 의존하므로 IT는 US1 경로 사용).
- **Polish(T019~T023)**: 전 US 후. T022/T023은 구현 완료 후.

## 병렬 기회

- Foundational: T003·T004 동시(다른 파일).
- US1 테스트: T009·T010 동시.
- US2: T013·T014 동시, T017 단위와 구현 분리.
- Polish: T019·T020 동시.

## MVP 범위

**US1(T001~T012)** = MVP. 조회 자동 기록 + best-effort 격리 + 디바운스만으로 006/009 인사이트의 "개인 조회 이력" 원천 데이터 충족. US2는 사용자 노출 소비 경로(후속 증분).

## 크라운주얼 ↔ task 매핑

| # | 크라운주얼 | task | US |
|---|---|---|---|
| 1 | ★ best-effort 격리(recordView 실패→상세 정상+요약 롤백 없음) | **T011** | US1 |
| 2 | 디바운스(30분 1행/경과 2행/다른기사 독립) | **T012**(+ T009 repo) | US1 |
| 3 | 읽은수 distinct + 이력 역순 | **T018**(+ T017) | US2 |
| 4 | 본인 스코프(타 계정 조회 불가) | **T018** | US2 |
| 5 | forward-seam(VIEW·SERVER만·metric_value=null, 빈 구현 없음) | **T012**(단언) + T010 | US1 |
