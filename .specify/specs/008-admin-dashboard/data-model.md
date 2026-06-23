# Data Model: 008 어드민 대시보드

신규 엔티티 4 + enum 1 + 기존 `articles` 컬럼 1개 추가. 단일 마이그레이션 `V15__admin_dashboard.sql`(현재 최신 V14 다음).

---

## 엔티티

### Notice (신규 — US4)
서비스 공지. 게시 상태인 것만 공개 노출.

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGSERIAL | PK | |
| title | VARCHAR(200) | NOT NULL | 제목(검증: 비어있지 않음·≤200) |
| content | TEXT | NOT NULL | 본문 |
| published | BOOLEAN | NOT NULL DEFAULT false | 게시 여부(초안/게시) |
| author_account_id | UUID | NOT NULL FK→accounts(id) | 작성 관리자 |
| created_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |
| updated_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |

- 상태 전이: 초안(published=false) ↔ 게시(published=true). 공개 조회는 published=true만(FR-041).

### AdminAuditLog (신규 — FR-060)
변형 어드민 행위 감사. 단순 조회 제외.

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGSERIAL | PK | |
| actor_account_id | UUID | NOT NULL FK→accounts(id) | 행위자(관리자) |
| action | VARCHAR(64) | NOT NULL | 행위(예: ROLE_CHANGE·ACCOUNT_DEACTIVATE·ARTICLE_HIDE·SCHEDULER_TOGGLE·NOTICE_CREATE·PUSH_SEND) |
| target_type | VARCHAR(32) | NOT NULL | `AuditTargetType` |
| target_id | VARCHAR(64) | NULL | 대상 식별자(계정 UUID·기사 id·scheduler_key 등 문자열화) |
| detail | JSONB | NULL | 변경 내용(before/after diff·사유) |
| created_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | 시각 |

- 조회: 시간 역순, target_type·action·기간 필터(US5 ErrorLog와 별개의 감사 조회).

### SchedulerSetting (신규 — FR-031)
스케줄러별 런타임 토글·주기의 DB 영속.

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| scheduler_key | VARCHAR(64) | PK | 예: collection·trend_aggregation (research D5 매핑) |
| enabled | BOOLEAN | NOT NULL DEFAULT true | 런타임 활성 여부(토글 — API 노출) |
| interval_override_ms | BIGINT | NULL | 주기 오버라이드 — **MVP API 미노출**(스키마만, 적용 후속) |
| updated_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |
| updated_by | UUID | NULL FK→accounts(id) | 마지막 변경 관리자 |

- 각 `@Scheduled` 본문 진입 시 `enabled` 조회 → false면 skip(D5). 행 부재 = enabled 기본 true(matchIfMissing 의미).

### ExcludedKeyword (신규 — FR-032)
수집/집계 배제 키워드.

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGSERIAL | PK | |
| keyword | VARCHAR(100) | NOT NULL UNIQUE | 배제 키워드(중복 방지) |
| created_by | UUID | NOT NULL FK→accounts(id) | 등록 관리자 |
| created_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | |

- 적용: 트렌드 집계(슬롯 UPSERT/추출)에서 `term NOT IN (SELECT keyword FROM excluded_keyword)`로 배제.

### Article (기존 변경 — FR-034)
`admin_hidden_at` 컬럼 추가. **feed_visible과 독립**(research D1).

| 추가 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| admin_hidden_at | TIMESTAMPTZ | NULL | 관리자 숨김 시각. NULL=노출, NOT NULL=숨김(가역) |

- 도메인: `Article.hideByAdmin(Instant)`·`unhideByAdmin()` 캡슐화. 영구 삭제 없음.

## Enum

### AuditTargetType (신규 — research D3)
`ACCOUNT, ARTICLE, SCHEDULER, NOTICE, PUSH, EXCLUDED_KEYWORD, SUMMARY`
(★ 기존 `AdminTargetType`=푸시 대상 선택자와 별개)

## 마이그레이션 — `V15__admin_dashboard.sql`

```sql
-- 008 어드민 대시보드: 공지·감사·스케줄러설정·제외키워드 + 기사 admin 숨김 컬럼

-- 1) 기사 관리자 숨김(가역, feed_visible과 독립 — 만료 물리삭제와 분리)
ALTER TABLE articles ADD COLUMN admin_hidden_at TIMESTAMPTZ NULL;
-- 사용자향 읽기 제외용 부분 인덱스(노출 대상만)
CREATE INDEX idx_articles_admin_visible
    ON articles (published_at DESC, id DESC)
    WHERE admin_hidden_at IS NULL;

-- 2) 공지
CREATE TABLE notice (
    id                  BIGSERIAL    PRIMARY KEY,
    title               VARCHAR(200) NOT NULL,
    content             TEXT         NOT NULL,
    published           BOOLEAN      NOT NULL DEFAULT FALSE,
    author_account_id   UUID         NOT NULL REFERENCES accounts(id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_notice_published ON notice (published, created_at DESC);

-- 3) 어드민 감사 로그(변형 액션)
CREATE TABLE admin_audit_log (
    id                BIGSERIAL    PRIMARY KEY,
    actor_account_id  UUID         NOT NULL REFERENCES accounts(id),
    action            VARCHAR(64)  NOT NULL,
    target_type       VARCHAR(32)  NOT NULL,
    target_id         VARCHAR(64)  NULL,
    detail            JSONB        NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_created ON admin_audit_log (created_at DESC);
CREATE INDEX idx_audit_target  ON admin_audit_log (target_type, target_id);

-- 4) 스케줄러 설정(런타임 토글·영속)
CREATE TABLE scheduler_setting (
    scheduler_key        VARCHAR(64) PRIMARY KEY,
    enabled              BOOLEAN     NOT NULL DEFAULT TRUE,
    interval_override_ms BIGINT      NULL,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by           UUID        NULL REFERENCES accounts(id)
);
-- 알려진 스케줄러 키 시드(enabled=true) — @Scheduled 메서드 12개 1:1 (grep 권위 확인)
INSERT INTO scheduler_setting (scheduler_key) VALUES
    ('collection'), ('ai_processing'), ('bias_analysis'), ('bias_recovery'), ('bias_sla'),
    ('trend_aggregation'), ('trend_cleanup'), ('tts_processing'),
    ('notification_outbox'), ('notification_expiry'), ('weekly_email'), ('expiry');

-- 5) 제외 키워드
CREATE TABLE excluded_keyword (
    id          BIGSERIAL    PRIMARY KEY,
    keyword     VARCHAR(100) NOT NULL UNIQUE,
    created_by  UUID         NOT NULL REFERENCES accounts(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
```

## 읽기경로 변경(코드, hidden 제외) — research D2 표 #1~#14

기존 쿼리에 `AND a.admin_hidden_at IS NULL`(또는 JPQL `a.adminHiddenAt IS NULL`) 추가:
`ArticleRepository`의 findFeedPage·findFeedPageWithCursor·findFeedPageByCategory·findFeedPageByCategoryWithCursor·
findFeedCandidates·findFeedCandidatesByCategory·searchByQuery·searchByQueryWithCursor;
`ArticleKeywordRepository`의 windowArticleKeywords·heatmap; `TrendKeywordSlotRepository`의 upsertSlots(JOIN articles).
신규 가드: `ArticleDetailService.findById`(일반 사용자 hidden 404), `SavedArticleService` 북마크 목록.
admin 기사 조회는 필터 미적용(hidden 포함).
