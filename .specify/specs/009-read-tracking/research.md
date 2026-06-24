# Research: 009 읽기 추적 — 설계 결정

모든 결정은 **grep 실증**(추정 금지) 기반. 확인된 표면은 맨 아래 "기존 표면 확인 결과" 참조.

## D1 — best-effort 기록 위치 + TX 경계 (★ 핫패스 비차단)

**Decision**: 조회 기록은 **기사 상세 조회가 성공한 뒤**, **별개 트랜잭션**에서 수행한다.
- `ReadTrackingService.recordView(UUID accountId, Long articleId)` = `@Transactional(propagation = REQUIRES_NEW)`.
- 호출 위치 = **`ArticleDetailController.getDetail`에서 `articleDetailService.getDetail(id)`가 반환된 직후**, `try-catch (Exception)`로 감싸 **예외는 로깅만**(`log.warn`)하고 상세 응답은 그대로 반환.
- **Spring 이벤트(@TransactionalEventListener) 미사용**.

**Rationale**:
- ★ `getDetail`은 `@Transactional`이며 **lazy write**(deep 요약 `summaryRepository.save` + `generateDeepSummary`)를 한다(grep 확인). 기록 INSERT를 같은 TX에 묶으면 **기록 실패가 요약 write·상세를 함께 롤백** → 핫패스 파괴. 따라서 **반드시 분리**.
- 이는 **008 AdminAuditService(`REQUIRED`로 호출자 TX 참여 → 같이 롤백)와 정반대 방향**이다. 008은 "행위와 감사를 원자적으로" 묶는 게 목적이었고, 009 조회 기록은 "부가 기록이 본 기능을 절대 못 깨게" 분리하는 게 목적. → **005 NotificationSendService의 `REQUIRES_NEW` 격리 패턴과 동일 계열**(grep 확인).
- 상세 조회는 이미 반환되어 TX 커밋 완료 → `REQUIRES_NEW`로 연 새 TX는 상세에 영향 불가(물리 분리). `REQUIRES_NEW`를 명시해 "독립 TX 경계"를 코드로 못박고 향후 호출맥락 변화에도 안전.
- try-catch를 **컨트롤러**에 둔 이유: 코드베이스에 이벤트/AOP 관행이 없고(grep: ApplicationEventPublisher·@TransactionalEventListener·@Async **전무**), 컨트롤러의 역할은 "상세 성공 후 fire-and-forget 디스패치 + 예외 삼킴·로깅"이라는 **요청 오케스트레이션**뿐이다. 디바운스 판정·INSERT 등 **모든 비즈니스 로직은 `ReadTrackingService`에 위치**(CLAUDE.md "컨트롤러 비즈니스 로직 금지" 정합 — 컨트롤러엔 도메인 로직 없음).

**★ best-effort 정밀 정의 (보장/비보장 구분)**:
- **REQUIRES_NEW + try-catch가 보장하는 것** = 기록 **실패(예외: DB 오류·제약 위반)**가 상세 조회를 **롤백·중단시키지 않음**(실패 격리). try-catch가 잡는 것 = **예외(O)**.
- **MVP가 보장하지 않는 것** = 기록 **지연**(슬로우쿼리·DB 일시지연)은 **동기 호출이라 상세 응답 시간에 전파됨**. try-catch는 예외만 잡고 **타임아웃·지연은 못 막음(X)**. 저볼륨+디바운스로 대부분 SELECT-only(EXISTS 적중)라 실무 영향은 작음 → MVP 수용.
- **진짜 지연 격리**(별도 스레드풀 `@Async`로 상세 응답 후 기록) = **forward-note**. 현재 `@Async`/`@EnableAsync` 인프라 전무(grep)라 신설 비용 + 볼륨이 작아 MVP 미도입. 볼륨 증가 시 도입.
- 한 줄 요약: **실패 비차단(보장) + 지연 비격리(MVP 수용)**. FR-003은 "실패 비차단"으로 한정한다("지연 비차단"까지는 아님).

**Alternatives considered**:
- **AOP 어드바이스**(@AfterReturning around getDetail): 코드베이스에 AOP 관행 없음 + 핫패스에 암묵 부작용 숨김 → 가시성 낮아 기각.
- **@TransactionalEventListener(AFTER_COMMIT) 동기 리스너**: 격리·post-commit 보장 측면 우수하나 **코드베이스 이벤트 패턴 전무** → 일관성 위반, 신규 인프라 비용. 기각(단 향후 async 전환 시 재검토 — forward-note).
- **별도 Facade 레이어**: 단일 호출에 레이어 신설은 과설계, 코드베이스 패턴 아님. 기각.

## D2 — 디바운스 30분 판정 + 핫패스 비용

**Decision**: `recordView` 내에서 **조건부 단일 INSERT**로 처리.
```
INSERT INTO article_event (account_id, article_id, event_type, source, occurred_at)
SELECT :acc, :art, 'VIEW', 'SERVER', now()
WHERE NOT EXISTS (
  SELECT 1 FROM article_event
  WHERE account_id = :acc AND article_id = :art AND event_type = 'VIEW'
    AND occurred_at > now() - INTERVAL '30 minutes'
);
```
- 디바운스 판정 = (account, article, VIEW)의 **30분 내 행 존재 시 INSERT skip**. EXISTS는 인덱스 `(account_id, article_id, occurred_at)`로 즉시 판정.

**Rationale**:
- 별도 SELECT-then-INSERT(2 round-trip) 대신 **단일 `INSERT ... WHERE NOT EXISTS`**로 라운드트립 1회·경합 창 축소. 이 SELECT/EXISTS 비용은 인덱스 적중으로 소형이며, 전체가 **best-effort try-catch 안**에서 실행되어 실패·지연이 상세를 안 깸(D1).
- 단일 인스턴스·동기 가정(007/008 상속)에서 동시 더블탭 등 미세 경합으로 30분 창에 2행이 드물게 들어갈 수 있으나, **조회 추적은 best-effort 지표**라 정확성 임계가 아님(읽은수는 distinct article이라 중복 행이 있어도 읽은수엔 영향 0). 유니크 제약으로 막지 않음(시간윈도우 유니크는 불가) — 과설계 회피.

**Alternatives considered**:
- 애플리케이션단 SELECT 후 분기 INSERT: 라운드트립 2회 + TOCTOU 경합 동일 → 단일 statement가 우수.
- 부분 유니크 인덱스: 시간 윈도우(30분) 기반 유니크는 표현 불가 → 부적합.

## D3 — article_event 인덱스 (디바운스 + 읽은수 + 이력 커버리지 실증)

**Decision**: **인덱스 2개**.
- `idx_article_event_debounce (account_id, article_id, occurred_at)` — 디바운스 EXISTS + 읽은수 distinct.
- `idx_article_event_history (account_id, occurred_at DESC)` — 조회 이력 최신순.

**Rationale (사용자 제안 "단일 (account_id, article_id, occurred_at)가 다 커버하는지" 검증)**:
- **디바운스** `WHERE account_id=? AND article_id=? AND occurred_at > ?`: 선두 (account_id, article_id) 등치 + occurred_at 범위 → `idx_debounce`가 **완전 커버**. ✅
- **읽은수** `COUNT(DISTINCT article_id) WHERE account_id=?`: 선두 account_id로 범위 한정 후 article_id(2번째 컬럼) distinct → `idx_debounce`로 효율적(인덱스 스캔). ✅
- **조회 이력** `WHERE account_id=? ORDER BY occurred_at DESC`: `idx_debounce`는 컬럼 순서가 (account_id, **article_id**, occurred_at)라 account 내에서 occurred_at이 **정렬되지 않음**(중간에 article_id) → **시간 역순 정렬을 인덱스로 못 만족, sort 필요**. ⚠️ 따라서 이력 전용 `idx_history (account_id, occurred_at DESC)`를 **추가**해야 정렬까지 커버. → 단일 인덱스로는 **부분 커버**(이력 미흡)임을 실증, 인덱스 2개로 결정.
- **성장/파티셔닝 forward-note**: 조회당 최대 1행으로 가장 빠르게 성장. 대량 시 (a) occurred_at 기준 range 파티셔닝, (b) 오래된 이벤트 보존정책(롤업/아카이브), (c) 읽은수 사전집계 캐시를 후속 고려. MVP는 인덱스 2개로 시작.

**Alternatives considered**:
- 단일 인덱스 (account_id, article_id, occurred_at): 디바운스·distinct는 커버하나 **이력 역순 정렬 미커버** → 이력 쿼리 sort 비용. 기각(이력 전용 인덱스 추가).
- (account_id, occurred_at) 단일: 이력·distinct는 되나 디바운스의 article_id 등치 필터 비효율 → 둘 다 필요.

## 기존 표면 확인 결과 (grep verbatim, 추정 금지)

- **ArticleDetailController** `GET /api/v1/articles/{id}` — 현재 `getDetail(@PathVariable Long id)` **principal 없음**. 009에서 `@AuthenticationPrincipal CustomUserDetails userDetails` 주입(002 일관 패턴).
- **002 principal 패턴**: 다수 컨트롤러가 `@AuthenticationPrincipal CustomUserDetails userDetails` → `userDetails.getAccountId()` 사용. 같은 `/api/v1/articles` 경로의 `SavedArticleController`가 동일 패턴(`save(userDetails.getAccountId(), articleId)`). `CustomUserDetails.getAccountId()` → **UUID** 반환.
- **마이그레이션 최고 번호 = V16**(008 dedup) → **009 = V17**.
- **articles.id = BIGSERIAL(BIGINT)** → `article_event.article_id BIGINT REFERENCES articles(id)`. **accounts.id = UUID** → `article_event.account_id UUID REFERENCES accounts(id)`.
- **getDetail은 read-write**: `@Transactional` + deep 요약 lazy 생성(`summaryRepository.save`·`generateDeepSummary`) → 기록 INSERT 격리 필요의 근거(D1).
- **이벤트/Async 인프라 전무**: `ApplicationEventPublisher`·`@TransactionalEventListener`·`@EventListener`·`@Async`·`@EnableAsync` 코드베이스 **0건** → 명시 호출·동기 시작이 관행, async/event는 forward-note.
- **REQUIRES_NEW 선례**: `NotificationSendService`(005)·`AdminAuditService`(008) → 격리 TX 패턴 확립.
