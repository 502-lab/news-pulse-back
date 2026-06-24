# ADR-001: 조회 기록 best-effort 격리 설계 (009)

**Status**: Accepted
**Date**: 2026-06-24
**Feature**: 009 읽기 추적(Read Tracking) — P1 조회 BE 단독 기록
**위치 주의**: CLAUDE.md는 ADR을 `/specs/adr/`에 두도록 안내하나 `specs/`는 수정 금지 submodule이므로 feature-local에 기록.

---

## Context

기사 상세(`GET /api/v1/articles/{id}`)는 핫패스이며 `ArticleDetailService.getDetail`이 `@Transactional` + **lazy write**(deep 요약 생성·저장)를 수행한다. 여기에 조회 이벤트(`article_event`) 기록을 더하되, 기록의 실패가 상세 조회를 절대 깨면 안 된다(FR-003).

## Decision 1 — 격리 보장의 핵심 = post-commit 호출 순서 + try-catch (REQUIRES_NEW는 방어적)

조회 기록은 **기사 상세가 성공한 뒤** 컨트롤러에서 `readTrackingService.recordView(...)`를 **try-catch로 호출**한다.

- `ArticleDetailController`는 `@Transactional`이 아니고 `getDetail` 서비스가 `@Transactional`이라 **컨트롤러로 반환되는 순간 상세·요약 write가 이미 커밋**된다(OSIV는 세션만, TX 아님). 그 **뒤** recordView가 호출되므로, "요약 롤백 안 됨"은 **호출 순서(post-commit) + try-catch(예외 삼킴)**가 보장한다.
- `recordView`는 `@Transactional(propagation = REQUIRES_NEW)`이나, 호출 시점에 ambient TX가 없어 **사실상 REQUIRED와 동등**하다. REQUIRES_NEW는 "독립 TX 경계"를 코드로 못박는 **방어적 명시**(미래에 TX 안에서 호출돼도 격리)이며, **단독 load-bearing이 아니다**(과장 금지).
- **방향성**: 008 `AdminAuditService`(REQUIRED로 호출자 TX 참여 → 행위와 같이 롤백)와 **정반대 목적**, 005 `NotificationSendService`(REQUIRES_NEW 격리)와 동일 계열.

**검증**: `ReadTrackingBestEffortIT`(T011) — recordView 강제 실패 시 상세 200 + DEEP 요약 행 보존 + article_event 0(예외 삼킴). end-to-end 격리 *결과*를 증명(REQUIRES_NEW 단독이 아님).

## Decision 2 — best-effort = 실패 격리(보장) + 지연 비격리(MVP 수용)

- **try-catch가 잡는 것 = 예외(O)** → 기록 실패 비차단(보장).
- **못 잡는 것 = 타임아웃·지연(X)** → 동기 호출이라 기록 지연은 상세 응답 시간에 전파될 수 있음(MVP 수용). 저볼륨+디바운스로 대부분 인덱스 적중 SELECT라 영향 작음.
- **진짜 지연 격리**(별도 스레드풀 `@Async`) = forward-note. `@Async`/`@EnableAsync` 인프라 전무(grep) + 볼륨 작아 MVP 미도입.
- FR-003은 "실패 비차단"으로 한정("지연 비차단"까지 아님). SC-002도 이에 정렬.

## Decision 3 — 디바운스 = 단일 조건부 INSERT (30분 윈도우)

`INSERT ... SELECT ... WHERE NOT EXISTS ((account, article, VIEW) 30분 내 행)` — 라운드트립 1회·경합창 축소. 미세 경합으로 드물게 2행 가능하나 읽은수가 distinct article이라 영향 0(시간윈도우 유니크는 불가, 과설계 회피). 검증: `ArticleEventRepositoryTest`·`ArticleViewRecordIT`.

## Decision 4 — 인덱스 2개 (디바운스/distinct + 이력 역순)

- `idx_article_event_debounce (account_id, article_id, occurred_at)` — 디바운스 EXISTS + 읽은수 distinct 커버.
- `idx_article_event_history (account_id, occurred_at DESC)` — 이력 최신순. debounce 인덱스는 (account_id, **article_id**, occurred_at) 순서라 account 내 occurred_at 정렬을 못 만족 → 이력 전용 인덱스 별도(실증, research D3).
- 이력은 **article 기준 최신 1건**(GROUP BY article_id, MAX(occurred_at)) — 같은 기사 다회 조회는 1건(F3).

## Decision 5 — forward-seam (P1=VIEW·SERVER만)

`article_event.event_type`/`source`/`metric_value`는 후속 클라이언트 계측 이벤트(체류·완료율·클릭·공유)를 **스키마 변경 없이 수용**하기 위한 forward-seam. **P1 코드는 VIEW·SERVER만 기록, metric_value=null**(빈 구현 금지). 검증: `ArticleViewRecordIT`(VIEW·SERVER·null 단언).

## 런타임/배포 시 검증 이연

- 실 트래픽 핫패스 p95 영향(SC-002 정량) — 배포 환경 측정.
- 대량 성장 시 occurred_at range 파티셔닝·보존정책·읽은수 사전집계 — 후속.
- 체류·완료율·AI클릭·공유(클라이언트 계약 의존) + "평균읽기시간" — read-tracking P2 별도 사이클.
