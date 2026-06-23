# Research: 008 어드민 대시보드

모든 결정은 grep 실증 기반(007 false-schema 교훈 — 추정 금지). 날짜 2026-06-23.

---

## D1 (OI-A) — 기사 hidden = 전용 컬럼 `articles.admin_hidden_at`

**Decision**: `articles`에 `admin_hidden_at TIMESTAMPTZ NULL` 컬럼 신설. hidden = NOT NULL, unhide = NULL(가역).

**Rationale**: 기존 `feed_visible`(V1:52, BOOLEAN DEFAULT TRUE) 재활용은 **위험**.
- `ExpiryService` 1단계: `expires_at < NOW() AND user_saved=false → feed_visible=false`.
- `ExpiryService` 2단계: `feed_visible=false AND updated_at<grace AND user_saved=false → 물리 삭제`(ArticleRepository.findArticlesToDelete L104).
- → hidden을 `feed_visible=false`로 표현하면, 저장 안 된 hidden 기사가 grace 경과 후 **만료 스케줄러에 물리 삭제**됨(비가역). admin hidden(가역 의도)과 정면 충돌.

**Alternatives rejected**: feed_visible 재활용 → 물리삭제 충돌. boolean `hidden` 컬럼 → 누가/언제 숨겼는지 미보존; `admin_hidden_at` TIMESTAMPTZ가 감사성까지 겸함.

## D2 (OI-B) — hidden 적용 = 읽기 쿼리 제외(article_keyword 보존)

**Decision**: hidden 기사는 **사용자향 읽기 쿼리에서 `AND a.admin_hidden_at IS NULL`로 제외**한다. 이미 추출된
`article_keyword` 행은 삭제하지 않는다. 트렌드 집계는 JOIN 시점에 제외.

**Rationale**: 가역성(unhide 시 즉시 복귀) + 재추출 비용 회피. article_keyword 삭제는 unhide를 비가역으로 만들고
재추출 파이프라인을 다시 태워야 함.

**Alternatives rejected**: 숨김 시 article_keyword 삭제 → unhide 비가역·재추출 필요. trend_keyword_slot에서 제거 →
슬롯은 집계 산물이라 다음 집계에서 자연 반영(hidden JOIN 제외)되므로 직접 삭제 불필요.

### ★ hidden 읽기경로 목록 (admin_hidden_at IS NULL 추가 대상) — 누락 시 "숨겼는데 노출" 버그

grep 실증(`ArticleRepository`, `ArticleKeywordRepository`, `TrendKeywordSlotRepository`, `ArticleDetailService`):

| # | 경로 | 위치(현재) | 조치 | 비고 |
|---|---|---|---|---|
| 1 | 피드 목록 | `ArticleRepository.findFeedPage` (L53) | `+ AND a.adminHiddenAt IS NULL` | feedVisible=true 쿼리 |
| 2 | 피드 커서 | `findFeedPageWithCursor` (L65) | 동일 | |
| 3 | 카테고리 피드 | `findFeedPageByCategory` (L78) | 동일 | |
| 4 | 카테고리 피드 커서 | `findFeedPageByCategoryWithCursor` (L93) | 동일 | |
| 5 | 피드 후보 | `findFeedCandidates` (L145) | 동일 | |
| 6 | 카테고리 피드 후보 | `findFeedCandidatesByCategory` (L158) | 동일 | |
| 7 | 검색 | `searchByQuery` (L195, native) | `+ AND a.admin_hidden_at IS NULL` | 003 |
| 8 | 검색 커서 | `searchByQueryWithCursor` (L234, native) | 동일 | 003 |
| 9 | **기사 상세** | `ArticleDetailService.findById` (L52) | **신규 hidden 가드**(일반 사용자 404/숨김) | ★ 현재 visibility 필터 없음 → 누출 위험 |
| 10 | 트렌드 추출 입력 | `ArticleKeywordRepository.windowArticleKeywords` (JOIN articles) | `+ AND a.admin_hidden_at IS NULL` | 007 |
| 11 | 히트맵 | `ArticleKeywordRepository.heatmap` (JOIN articles) | 동일 | 007 |
| 12 | 슬롯 UPSERT | `TrendKeywordSlotRepository.upsertSlots` (JOIN articles) | 동일 | 007, 다음 집계부터 hidden 제외 |
| 13 | 북마크 노출 | `SavedArticleService` 목록(JOIN articles) | hidden 제외 여부 결정 → tasks서 쿼리 확인 후 제외 | 사용자가 저장한 기사가 hidden되면 목록서 숨김 |
| 14 | 알림 딥링크 | 기사 참조 알림 | (낮은 우선순위) hidden 기사 링크 처리 정책 — 후속/명시 | |

**admin 뷰는 hidden 포함 조회**(별도 admin 기사 조회 쿼리는 필터 미적용). #9·#13은 현재 필터가 없어 **신규 가드 필요**(나머지는 기존 WHERE에 조건 1개 추가).

## D3 (OI-C) — 감사 대상 타입 = 신규 `AuditTargetType` enum

**Decision**: `AuditTargetType { ACCOUNT, ARTICLE, SCHEDULER, NOTICE, PUSH, EXCLUDED_KEYWORD, SUMMARY }` 신규.

**Rationale**: 기존 `AdminTargetType`(enum 값 `ALL`·`ACCOUNT_IDS`·`TOPIC_SUBSCRIBERS`)은 **005 어드민 푸시의 수신
대상 선택자**이지 감사 대상 분류가 아님(grep 확인). spec의 "AdminTargetType 재활용 가능" 표현은 **정정**한다.

**Alternatives rejected**: AdminTargetType 재활용 → 의미 충돌(푸시 대상 vs 감사 대상).

## D4 — AdminAuditLog 캡처 메커니즘 = 서비스 명시 호출(@Transactional 내)

**Decision**: 각 변형 액션 서비스 메서드가 동일 트랜잭션 내에서 `AdminAuditService.record(actor, action, targetType,
targetId, detail)`를 **명시 호출**한다. AOP 전역 인터셉트는 채택하지 않는다.

**Rationale**:
- **변경 내용(diff) 컨텍스트**가 필요하다(예: role USER→ADMIN, 기사 hidden 사유, 스케줄러 enabled false). 메서드 본문만이
  before/after를 안다 — AOP는 인자/반환만 보여 diff 재구성이 빈약.
- **같은 TX 일관성**: 액션과 감사 기록이 한 트랜잭션 → 액션 성공 시에만 감사, 롤백 시 함께 롤백(고아 감사 없음).
- **누락 방지**: 변형 액션이 소수(역할변경·활성화·숨김/해제·스케줄러제어·공지CRUD·푸시발송)로 명시 호출이 충분히
  추적 가능. 단위 테스트로 "각 액션이 audit 1건 남김"을 강제.

**Alternatives rejected**: `@Auditable` AOP 애너테이션 → diff·도메인 컨텍스트 부족, 누락은 줄지만 의미 빈약 + 프록시
경계 함정. 이벤트 기반(ApplicationEvent) → afterCommit 발행 시 TX 분리로 고아 위험·복잡도↑.

**캡처 보장 테스트**: 변형 액션별 서비스 단위 테스트에서 `AdminAuditLogRepository`에 1건 기록(행위자·대상·diff) 단언.

## D5 — SchedulerSetting ↔ @Scheduled 연결 = 실행 시작 시 DB 게이트

**Decision**: `SchedulerSetting`(scheduler_key PK, enabled, interval_override, updated_at)을 DB에 두고, 각 `@Scheduled`
메서드가 **실행 시작 시 `SchedulerControlService.isEnabled(key)`를 조회해 disabled면 즉시 return(skip)**. 토글은
admin이 `SchedulerSetting.enabled` 갱신.

**Rationale**:
- 기존 `@ConditionalOnProperty(app.scheduler.enabled, matchIfMissing=true)`(9개 스케줄러 전부)는 **전역 빈 생성
  여부** 토글이라 런타임 per-scheduler 제어 불가(재배포 필요). 런타임·영속·개별 제어가 필요 → DB 게이트.
- DB 영속 → 재기동 후에도 유지(SC-010). admin이 끈 스케줄러가 재기동으로 살아나지 않음.
- skip 방식(메서드 진입 가드)은 fixedDelay/cron 트리거 자체는 돌되 본문만 건너뛰어 단순·안전(단일 인스턴스 전제).

**주기(interval) 변경 한계 → MVP API 비노출**: `@Scheduled(fixedDelayString=...)`의 주기는 빈 등록 시점 고정이라
런타임 변경이 어렵다. **MVP는 enabled 토글 + 수동 실행만 API로 노출**한다. `interval_override_ms` 컬럼은 스키마에
두되 **API로 노출하지 않는다**(저장만 하고 적용 안 되는 거짓 약속 회피). 완전 동적 재스케줄(ScheduledTaskRegistrar/
TaskScheduler 재등록)은 후속. → FR-031을 "enabled 토글 + 수동 실행"으로 한정(주기 동적 변경은 후속, spec 반영).

### 토글 대상 @Scheduled ↔ scheduler_key 매핑 (grep 권위 확인 — @Scheduled **메서드 12개** / 클래스 9개)

> 토글 게이트는 **메서드 진입 skip**이므로 키 단위 = **@Scheduled 메서드 단위(12개)**. 죽은 키 0, 누락 스케줄러 0
> (직전 11키는 `bias_sla` 누락 오류 — 본 표로 정정).

| scheduler_key | 클래스::메서드 | 트리거 |
|---|---|---|
| `collection` | `CollectionScheduler::run` | fixedDelay 900s |
| `ai_processing` | `AiProcessingScheduler::run` | fixedDelay 60s |
| `bias_analysis` | `BiasAnalysisScheduler::run` | fixedDelay 60s |
| `bias_recovery` | `BiasAnalysisScheduler::recover` | fixedDelay 3600s |
| `bias_sla` | `BiasAnalysisScheduler::emitSla` | cron 0 0 0 * * * |
| `trend_aggregation` | `TrendAggregationScheduler::run` | fixedDelay 600s |
| `trend_cleanup` | `TrendAggregationScheduler::cleanup` | cron 0 30 3 * * * |
| `tts_processing` | `TtsProcessingScheduler::process` | cron |
| `notification_outbox` | `NotificationOutboxProcessor::process` | fixedDelay |
| `notification_expiry` | `NotificationExpiryScheduler::deleteExpiredNotifications` | cron |
| `weekly_email` | `WeeklyEmailScheduler::scheduleWeeklyEmail` | cron |
| `expiry` | `ExpiryScheduler::run` | cron 0 0 3 * * * |

**합계 12키.** seed: 마이그레이션에서 12키 전부 `enabled=true` 시드. 행 부재 시에도 기본 enabled=true(matchIfMissing 의미 유지)로 안전.

## D6 — 기존 표면 확인 결과 (재활용, grep 실증)

**001 모니터링**:
- `PipelineStatsService.getStats() → PipelineStatsResponse` 존재. `AdminPipelineController @RequestMapping("/api/v1/admin")` `GET /pipeline/stats`. → US2에서 재활용·확장(KPI 추가).
- KPI/파이프라인 소스 컬럼 실재: `articles.summary_status`·`category_status`(V1:47-49), `summaries.status`(V1:115, DEFAULT 'NOT_GENERATED'), `bias_analysis.status`(V13:9). 수집량 `source_daily_usage.call_count`(V1).

**005 어드민 푸시**:
- `AdminNotificationController POST /send → AdminNotificationService.sendNotification(request) → NotificationSendService.enqueueSystem(accountId, title, body)`.
- 멱등: `notification_outbox.uq_outbox_idempotency UNIQUE(idempotency_key)`(V12:70), enqueue 시 중복 키 무시(`NotificationSendService` L154 "중복 enqueue 무시").

**어드민 푸시 dedup 키 규칙(SC-008)** — 발송 단위로 결정적 키 부여, 수신자별 1행:
- **공지 푸시**(특정 Notice 연계): `ADMIN:NOTICE:{noticeId}:{accountId}`. 같은 공지를 같은 사용자에게 재요청해도
  중복 0(noticeId가 안정 식별자). 공지 내용을 바꿔 다시 알리려면 새 공지(noticeId)로.
- **일반 캠페인 푸시**(Notice 무관 단발 공지성 발송): `ADMIN:CAMPAIGN:{campaignId}:{accountId}`. **campaignId = 서버가
  발송 시점에 생성하는 UUID**(매 발송 고유). → **의도적 재발송 = 새 campaignId(= 새 발송, 의도된 중복 허용)**,
  **같은 발송 내 다중 수신자 fan-out·재시도만 dedup**(우발 중복 0). 클라이언트가 campaignId를 보내지 않음(서버 생성)이라
  재요청이 무한 새 발송이 되지 않도록 컨트롤러는 발송 1회당 1 campaignId 발급.
- 현재 `enqueueSystem(accountId,title,body)`는 내부적으로 idempotency_key를 만든다 → admin 발송 경로에 위 키 규칙을
  주입하도록 확장(tasks에서 enqueueSystem 키 생성 규칙 확인 후 admin 전용 enqueue 추가/오버로드).

**002 인가**:
- `AccountRole { USER, ADMIN }`(EDITOR 없음). `SecurityConfig` `/api/v1/admin/** → hasRole("ADMIN")`(L78). 신규 role/규칙 추가 없음.
- admin **사용자 관리** 컨트롤러 부재 → 008 신규(`AdminUserController`).

**에러 로그(FR-051)**: 전용 저장소 없음 → 기존 FAILED 집계로 구현. 소스: `articles.summary_status='FAILED'`(또는
summaries.status FAILED), `bias_analysis.status='FAILED'`, `notification_outbox` 실패 상태. 신규 테이블 없음.

## 미해결/검토 포인트 (plan→tasks 전 사용자 확인)

1. **FR-031 주기 동적 변경 범위**: enabled 토글 + 값 저장까지(MVP) vs 완전 동적 재스케줄(후속). 위 D5 권장 = 토글+수동실행 보장, interval은 저장·차기 적용.
2. **북마크(#13)·알림 딥링크(#14) hidden 처리 깊이**: 북마크 목록 제외는 포함, 알림 딥링크는 후속/명시.
3. **어드민 푸시 idempotency_key 규칙 확장**: enqueueSystem 현 키 규칙 확인 후 admin 발송용 결정적 키 도입.
