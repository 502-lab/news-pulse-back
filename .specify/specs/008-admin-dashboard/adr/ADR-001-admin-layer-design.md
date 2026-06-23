# ADR-001: 어드민 레이어 설계 결정 (008)

**Status**: Accepted
**Date**: 2026-06-23
**Feature**: 008 어드민 대시보드
**위치 주의**: CLAUDE.md는 ADR을 `/specs/adr/`에 두도록 안내하나 `specs/`는 수정 금지 submodule이므로
feature-local(`.specify/specs/008-admin-dashboard/adr/`)에 기록한다.

---

## Context

008은 기존 데이터·파이프라인 위에 도는 ADMIN 전용 운영 레이어다. 사용자 관리·모니터링·수집 제어·공지/푸시·
심층 통계를 추가하며, false-schema(007 교훈)를 피하고 기존 인프라(002 인가·005 알림·006 편향·007 트렌드)를
재활용한다. 아래는 구현 중 확정한 비자명 설계 결정이다.

## Decision 1 — 기사 숨김 = 전용 컬럼 `articles.admin_hidden_at` (feed_visible 재활용 금지)

관리자 숨김을 기존 `feed_visible` 재활용이 아닌 **전용 `admin_hidden_at TIMESTAMPTZ NULL`** 컬럼으로 구현.

**Rationale**: `feed_visible`은 `ExpiryService` 만료 라이프사이클 전용이며 2단계가 `feed_visible=false AND
user_saved=false AND updated_at<grace` 기사를 **물리 삭제**한다(`findArticlesToDelete`). 숨김을
`feed_visible=false`로 표현하면 admin이 숨긴 기사가 만료 스케줄러에 비가역 삭제된다. 독립 컬럼으로 분리해
숨김(가역)과 만료(물리삭제)를 직교화했다. 검증: `HiddenExpiryIndependenceIT`(admin-hidden 미만료 기사는
물리삭제 후보 아님, unhide 가역).

**적용 일관성**: hidden은 13개 사용자향 읽기 경로(피드 6·검색 2·기사상세 404·트렌드 추출/히트맵/슬롯·북마크)에서
쿼리 필터(`admin_hidden_at IS NULL`)·코드 가드로 제외하되 `article_keyword` 행은 보존(가역). 어드민 뷰는
hidden 포함(필터 미적용). 알림 딥링크(#14)는 후속.

## Decision 2 — AdminAuditLog 캡처 = 서비스 명시 호출(호출자 TX 참여)

변형 행위 감사를 AOP가 아닌 **각 변형 액션 서비스가 같은 트랜잭션에서 `AdminAuditService.record()` 명시 호출**로
구현. `record()`는 `@Transactional(REQUIRED)`로 호출자 TX에 **참여**(REQUIRES_NEW 아님).

**Rationale**: ① 변경 내용(diff: before/after role·status 등)은 메서드 본문만 알아 AOP로는 빈약. ② 같은 TX →
행위 롤백 시 감사도 롤백(고아 감사 0). ③ 변형 액션이 소수라 명시 호출이 추적 가능. 검증:
`AdminAuditTxParticipationIT`(외부 롤백 시 감사 0, 005 REQUIRES_NEW 격리의 정반대 방향).
감사 대상 타입은 신규 `AuditTargetType`(★ 005 푸시 대상 선택자 `AdminTargetType`과 별개).

## Decision 3 — 스케줄러 런타임 토글 = DB 게이트(SchedulerSetting)

`@ConditionalOnProperty(app.scheduler.enabled)`(빌드/배포 토글)와 별개로, **12개 @Scheduled 메서드 본문 진입
시 `SchedulerControlService.isEnabled(key)` 조회 → disabled면 skip**. `scheduler_setting`에 DB 영속(재기동 후
유지). 토글 대상 = @Scheduled 메서드 12개(클래스 9, Claimer 3 제외) 1:1. `weekly_email`은 전역
@ConditionalOnProperty가 없어 DB 게이트가 유일 차단책이라 반드시 포함.

**주기 동적 변경 제외**: `@Scheduled(fixedDelayString)`는 빈 등록 시 고정이라 런타임 변경 불가 → MVP는 enabled
토글 + 수동 실행만 노출. `interval_override_ms`는 스키마만 보유(API 미노출, 거짓약속 회피).

**수동 실행 = 게이트 우회**: admin 수동 실행은 게이트 없는 작업 진입점(서비스 메서드/`runNow()`)을 직접 호출 →
disabled 스케줄러도 1회 임시 실행("꺼둔 걸 임시로 돌림"). 검증: `SchedulerGateCoverageTest`(12 each),
`SchedulerManualRunIT`(disabled 우회 실행).

## Decision 4 — 어드민 푸시/인앱 멱등 = 결정적 dedup 키

005 알림 파이프라인 재사용. `enqueueSystem`은 매 호출 새 notification→비결정 키라 멱등 불가여서 **결정적 키 전용
경로** `enqueueAdminPush`를 신설. 채널 분리:
- **인앱**(notifications): 토큰 무관 **전원 도달** + `dedup_key`(부분 unique 인덱스 `WHERE dedup_key IS NOT NULL`,
  V16)로 멱등(`ON CONFLICT DO NOTHING`).
- **푸시**(notification_outbox): 디바이스 토큰 보유자만 + `uq_outbox_idempotency`로 멱등.
- 키 패턴: 공지 `ADMIN:NOTICE:{noticeId}:{accountId}`(재발송 멱등) / 캠페인 `ADMIN:CAMPAIGN:{serverUuid}:{accountId}`
  (매 발송 새 UUID → 의도적 재발송 가능).

**Rationale**: FR-042가 "푸시/인앱" + "중복 없이"를 요구. 토큰 없는 사용자도 인앱으로 받아야 하므로 인앱은
무조건 생성·멱등, 푸시는 토큰 조건부. 검증: `AdminInAppPushIT`(토큰 없는 계정 푸시 0·인앱 1), `AdminPushIdempotencyIT`.

## Decision 5 — ErrorLog = 기존 FAILED 집계(신규 저장소 없음)

에러 로그를 전용 캡처 테이블 없이 **기존 FAILED 상태 집계**로 구현: `articles.summary_status='FAILED'` +
`bias_analysis.status='FAILED'` + `notification_outbox.status='FAILED'` 출처별 카운트.

**Rationale**: 전용 에러 캡처는 cross-cutting 인프라(009 트래킹 성격)라 008 비대. 기존 상태로 충분. 검증:
`AdminOpsStatsIT`. 조회는 전부 read-only·감사 비대상(Q3).

## 런타임/배포 시 검증 이연 (spec Deferred 섹션과 동일)

1. 실 corpus 클러스터 품질(007 상속) / SC-002 p95 / Nori 실기사 재평가 / article_keyword 재추출.
2. 알림 딥링크 hidden(#14), 기사 영구 삭제, 스케줄러 주기 동적 변경, in-app 외 채널 — 후속.
