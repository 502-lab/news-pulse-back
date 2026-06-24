# Data Model: 009 읽기 추적

신규 엔티티 1(`ArticleEvent`) + enum 2(`ArticleEventType`·`ArticleEventSource`). 단일 마이그레이션 **`V17__read_tracking.sql`**(현재 최신 V16 다음).

---

## 엔티티

### ArticleEvent (신규 — "안 A" 단일 이벤트 테이블)

사용자의 기사 행동 이벤트 1건. **P1=조회(VIEW)·서버기록(SERVER)만 생성**. 나머지 컬럼은 후속(체류·완료율·클릭·공유) **forward-seam**(P1 미사용).

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGSERIAL | PK | |
| account_id | UUID | NOT NULL, FK→accounts(id) ON DELETE CASCADE | 행동 주체(인증 사용자). 비로그인 미기록(D3). 계정 파기 시 함께 삭제(개인정보) |
| article_id | BIGINT | NOT NULL, FK→articles(id) ON DELETE CASCADE | 대상 기사. 기사 물리삭제(만료 2단계) 시 함께 삭제 |
| event_type | VARCHAR(16) | NOT NULL DEFAULT 'VIEW' | `ArticleEventType`. **P1=VIEW만**. (forward-seam: DWELL·COMPLETE·AI_CLICK·SHARE) |
| metric_value | INTEGER | NULL | 수치 측정값(체류ms·완료율 등). **P1=null**. (forward-seam; P2에서 의미·타입 확정) |
| source | VARCHAR(8) | NOT NULL DEFAULT 'SERVER' | `ArticleEventSource`. **P1=SERVER만**(서버기록=클라 위조 방지). (forward-seam: CLIENT) |
| occurred_at | TIMESTAMPTZ | NOT NULL DEFAULT now() | 발생 시각 |

- 도메인 캡슐화: `ArticleEvent.view(accountId, articleId)` 정적 팩토리(event_type=VIEW·source=SERVER·occurred_at=now). Lombok `@Getter @Builder`.
- **상태 전이 없음**(append-only 이벤트). 수정·삭제는 보존정책/계정파기 CASCADE만.

## Enum

### ArticleEventType (신규)
`VIEW`(P1) — forward-seam: `DWELL`, `COMPLETE`, `AI_CLICK`, `SHARE`(후속). P1 코드에서는 VIEW만 기록·사용.

### ArticleEventSource (신규)
`SERVER`(P1) — forward-seam: `CLIENT`(후속 클라이언트 계측 이벤트). P1 코드에서는 SERVER만.

## 마이그레이션 — `V17__read_tracking.sql`

```sql
-- 009 읽기 추적: 기사 조회 이벤트(append-only). P1=VIEW·SERVER만 사용, 나머지 컬럼은 forward-seam.
CREATE TABLE article_event (
    id           BIGSERIAL    PRIMARY KEY,
    account_id   UUID         NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    article_id   BIGINT       NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    event_type   VARCHAR(16)  NOT NULL DEFAULT 'VIEW',
    metric_value INTEGER      NULL,
    source       VARCHAR(8)   NOT NULL DEFAULT 'SERVER',
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 디바운스 EXISTS(account,article,30분 윈도우) + 읽은수 distinct(account별 article_id)
CREATE INDEX idx_article_event_debounce
    ON article_event (account_id, article_id, occurred_at);

-- 조회 이력 최신순(account별 occurred_at DESC) — debounce 인덱스가 정렬 미커버라 별도(research D3)
CREATE INDEX idx_article_event_history
    ON article_event (account_id, occurred_at DESC);
```

## 쿼리 경로 (ArticleEventRepository)

- **디바운스 조건부 INSERT**(native): `INSERT ... SELECT ... WHERE NOT EXISTS (30분 윈도우 VIEW)` — research D2. `@Modifying`, 반환=영향행수(1=기록, 0=디바운스 skip).
- **읽은수**: `SELECT COUNT(DISTINCT article_id) FROM article_event WHERE account_id=? AND event_type='VIEW'`.
- **조회 이력**: `WHERE account_id=? AND event_type='VIEW' ORDER BY occurred_at DESC` 페이지네이션(`idx_history`). 기사 메타 조인은 서비스에서(또는 fetch). 동일 기사 다회 조회 시 이력 표현은 distinct article 최신 1건 권장(tasks에서 확정).

## forward-note (성장/확장)

- `article_event`는 조회당 최대 1행으로 최고 성장률. 대량 시 occurred_at range 파티셔닝·보존정책·읽은수 사전집계 캐시 후속.
- event_type/metric_value/source forward-seam으로 **스키마 변경 없이** 후속 클라이언트 이벤트(체류·완료율·클릭·공유) 수용(SC-005). 단 P1 코드는 VIEW·SERVER 외 기록 금지(빈 구현 회피).
