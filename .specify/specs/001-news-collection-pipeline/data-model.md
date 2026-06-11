# Data Model: 뉴스 수집·큐레이션 파이프라인과 카테고리 피드

**Feature**: 001-news-collection-pipeline  
**Date**: 2026-06-09

---

## Entity Relationship Overview

```
Source (1) ──────────────── (N) ArticleSource (N) ──────── (1) Article
                                                                    │
                                                              (1:3) Summary
                                                              [BRIEF, BALANCED, DEEP]
```

---

## Enums

### ProcessingStatus
```
PENDING     — 처리 대기 (신규 수집 또는 재시도 예정)
COMPLETED   — 처리 완료
FAILED      — 처리 실패 (retry_limit 도달 또는 기술적 장애)
```
*category_status 및 summary_status 공통 사용*

### SummaryDepth
```
BRIEF       — 핵심 요약 (balanced 트런케이션, ~200자)
BALANCED    — 균형 요약 (AI eager 생성)
DEEP        — 심층 요약 (AI lazy 생성, 최초 상세 조회 시)
```

### SummarySlotStatus
```
NOT_GENERATED  — 아직 생성 요청 없음 (최초 상태)
PENDING        — 생성 중 (요청됨, 비동기 처리 중 아님 - 동기 생성 도중 장애 회복 목적)
COMPLETED      — 생성 완료, content 존재
FAILED         — 생성 실패
```

### Category
```
ECONOMY_FINANCE         — 경제·금융
SCIENCE                 — 과학
POLITICS                — 정치
SPORTS                  — 스포츠
WORLD                   — 세계
ENTERTAINMENT_CULTURE   — 연예·문화
HEALTH_MEDICINE         — 건강·의학
AUTOMOTIVE              — 자동차
IT                      — IT
OTHER                   — 기타 (분류 불가 또는 FAILED 피드 표시)
```

### SourceAdapterType
```
RSS     — RSS/Atom 피드
NAVER   — 네이버 검색 API
```

---

## Entities

### Source (뉴스 출처)

```sql
CREATE TABLE sources (
    id                          BIGSERIAL PRIMARY KEY,
    name                        VARCHAR(255)    NOT NULL,
    feed_url                    VARCHAR(1024)   NOT NULL UNIQUE,
    adapter_type                VARCHAR(32)     NOT NULL,           -- RSS | NAVER
    active                      BOOLEAN         NOT NULL DEFAULT TRUE,
    collection_interval_minutes INTEGER         NULL,               -- NULL = 전역 기본값 사용
    call_budget_daily           INTEGER         NOT NULL DEFAULT 1000,
    consecutive_failure_count   INTEGER         NOT NULL DEFAULT 0,
    last_collected_at           TIMESTAMPTZ     NULL,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);
```

**Notes**:
- `feed_url`: RSS는 피드 URL, NAVER는 검색 쿼리 문자열 (e.g. `뉴스 경제`)
- `collection_interval_minutes NULL` → 전역 `app.scheduler.collection.interval-ms` / 60 값 사용
- `call_budget_daily`: 일일 호출 예산. 실제 사용량은 `SourceDailyUsage` 테이블에서 (source_id, usage_date) 키로 별도 추적하여 별도 리셋 잡 없이 날짜 변경만으로 갱신된다.
- `consecutive_failure_count`: 3회 초과 시 알림 또는 일시 중단 (비기능 운영 정책)

---

### SourceDailyUsage (출처별 일일 호출 사용량)

```sql
CREATE TABLE source_daily_usage (
    source_id   BIGINT  NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
    usage_date  DATE    NOT NULL,           -- 공급자 쿼터 기준 날짜 (네이버: KST 날짜)
    call_count  INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (source_id, usage_date)
);
```

**Notes**:
- (source_id, usage_date) 복합 기본키로 날짜가 바뀌면 자연히 새 레코드를 사용한다. 별도 리셋 잡 불필요.
- `usage_date`: 각 공급자의 쿼터 리셋 기준 날짜. 네이버는 KST 자정 기준이므로 UTC 15:00에 날짜가 전환된다.
- `call_budget_daily`(sources 테이블) 대비 `call_count` 초과 시 해당 출처의 당일 수집을 중단한다.

---

### Article (기사)

```sql
CREATE TABLE articles (
    id                      BIGSERIAL       PRIMARY KEY,
    normalized_url          VARCHAR(2048)   NOT NULL,           -- dedup 기준 키
    original_url            VARCHAR(2048)   NOT NULL,           -- 최초 수집 원본 URL
    title                   VARCHAR(1024)   NOT NULL,
    author                  VARCHAR(512)    NULL,
    published_at            TIMESTAMPTZ     NOT NULL,           -- 원 출처 발행 시각
    first_collected_at      TIMESTAMPTZ     NOT NULL,           -- 최초 수집 시각 (불변)
    category                VARCHAR(32)     NULL,               -- Category enum 값
    category_status         VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    category_retry_count    INTEGER         NOT NULL DEFAULT 0,
    summary_status          VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    summary_retry_count     INTEGER         NOT NULL DEFAULT 0,
    expires_at              TIMESTAMPTZ     NOT NULL,           -- first_collected_at + retention
    feed_visible            BOOLEAN         NOT NULL DEFAULT TRUE,
    user_saved              BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- 인덱스 (research.md 항목 11 결정)
CREATE UNIQUE INDEX idx_articles_normalized_url
    ON articles (normalized_url);

CREATE INDEX idx_articles_feed
    ON articles (published_at DESC, id DESC)
    WHERE feed_visible = TRUE AND category_status IN ('COMPLETED', 'FAILED');

CREATE INDEX idx_articles_category_feed
    ON articles (category, published_at DESC, id DESC)
    WHERE feed_visible = TRUE AND category_status IN ('COMPLETED', 'FAILED');

CREATE INDEX idx_articles_category_queue
    ON articles (first_collected_at ASC)
    WHERE category_status = 'PENDING';

CREATE INDEX idx_articles_summary_queue
    ON articles (first_collected_at ASC)
    WHERE summary_status = 'PENDING';

CREATE INDEX idx_articles_expiry
    ON articles (expires_at ASC)
    WHERE feed_visible = TRUE AND user_saved = FALSE;
```

**State Transitions**:
```
category_status:
  PENDING → COMPLETED  (AI 분류 성공, category 값 설정)
  PENDING → FAILED     (retry_count >= retry_limit 도달 후 영구 실패)
  FAILED  → PENDING    (retry_count < retry_limit: AI 처리 스케줄러가 재시도 대상으로 간주)

summary_status:
  PENDING → COMPLETED  (balanced AI 요약 생성 완료)
  PENDING → FAILED     (retry_count >= retry_limit 도달 후 영구 실패)
  FAILED  → PENDING    (retry_count < retry_limit: 재시도 대상)
```

**Feed Visibility Rules**:
- `feed_visible = TRUE AND category_status IN ('COMPLETED', 'FAILED')` → 피드 노출 조건
- `category_status = 'FAILED'` → API 응답에서 category = 'OTHER' 로 매핑
- `expires_at < NOW() AND user_saved = FALSE` → `feed_visible = FALSE` 설정 (2단계 만료 1단계)
- `feed_visible = FALSE AND updated_at < NOW() - INTERVAL '7 days' AND user_saved = FALSE` → 물리 삭제 대상

**Notes**:
- `published_at`: 소스 제공값. 누락 시 `first_collected_at` 대체.
- `normalized_url`: `UrlNormalizer`를 통해 수집 시 정규화. 이후 불변.
- `original_url`: 최초 수집 시 원본 URL 그대로 저장.
- `category` NULL: category_status = 'PENDING' 또는 'FAILED' (영구) 상태에서만 가능.

---

### ArticleSource (수집 출처 이력 / Provenance)

```sql
CREATE TABLE article_sources (
    id          BIGSERIAL   PRIMARY KEY,
    article_id  BIGINT      NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    source_id   BIGINT      NOT NULL REFERENCES sources(id),
    collected_at TIMESTAMPTZ NOT NULL,
    is_merge    BOOLEAN     NOT NULL DEFAULT FALSE,   -- TRUE: 병합(두 번째 이상 수집)
    UNIQUE (article_id, source_id)
);

CREATE INDEX idx_article_sources_article ON article_sources (article_id);
CREATE INDEX idx_article_sources_merge_today
    ON article_sources (collected_at)
    WHERE is_merge = TRUE;
```

**Notes**:
- 기사 최초 수집: `is_merge = FALSE`
- 동일 URL을 다른 출처에서 재수집: `is_merge = TRUE`로 새 레코드 추가, 기사 본체 불변
- `UNIQUE (article_id, source_id)`: 동일 출처에서 동일 기사를 두 번 수집해도 1건
- Admin 통계의 "병합 처리 건수" = `COUNT(*) WHERE is_merge = TRUE AND DATE(collected_at) = TODAY`

---

### Summary (AI 생성 요약)

```sql
CREATE TABLE summaries (
    id              BIGSERIAL   PRIMARY KEY,
    article_id      BIGINT      NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    depth           VARCHAR(32) NOT NULL,           -- BRIEF | BALANCED | DEEP
    status          VARCHAR(32) NOT NULL DEFAULT 'NOT_GENERATED',
    content         TEXT        NULL,
    generated_at    TIMESTAMPTZ NULL,
    last_attempt_at TIMESTAMPTZ NULL,               -- DEEP 슬롯 마지막 AI 시도 시각
    retry_count     INTEGER     NOT NULL DEFAULT 0, -- DEEP 슬롯 누적 AI 시도 횟수
    ai_generated    BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (article_id, depth)
);

CREATE INDEX idx_summaries_article ON summaries (article_id);
```

**Generation Strategy** (research.md 항목 4):
- `BALANCED`: AI 처리 스케줄러가 기사 수집 직후 eager 생성. `article.summary_status` = 이 슬롯의 완료 여부를 추적.
- `BRIEF`: `BALANCED` 생성 완료 후 즉시 트런케이션으로 생성 (별도 AI 호출 없음). `SummaryService.truncateForBrief()`.
- `DEEP`: 기사 상세 최초 조회 시 동기 AI 호출로 생성 후 저장.

**Status Flow**:
```
NOT_GENERATED → PENDING (생성 요청 중, 동기 처리 중 장애 회복용)
             → COMPLETED (생성 완료, content ≠ null)
             → FAILED    (생성 실패)
FAILED        → PENDING  (DEEP 슬롯 전용: last_attempt_at + cooldown-minutes 경과 AND retry_count < deep-retry.limit 충족 시에만 재시도. BALANCED/BRIEF는 article.summary_status 경로로만 재시도)
```

**Notes**:
- `last_attempt_at`, `retry_count`: DEEP 슬롯에서만 유의미. BALANCED·BRIEF는 NULL/0 유지.
- `ai_generated = FALSE`: 미래 확장 (사람이 편집한 요약 등). 현재는 항상 TRUE.
- 기사 삭제 시 (`ON DELETE CASCADE`) 요약도 삭제.
- `user_saved = TRUE` 기사의 요약은 기사 물리 삭제 시에도 보존 → 기사 삭제 전 요약 내용 별도 보관 로직 필요 (ExpiryService에서 처리).

---

## Flyway Migration Plan

```
V1__create_core_tables.sql          — sources, articles, article_sources, summaries, source_daily_usage + 인덱스
V2__seed_initial_sources.sql        — 초기 RSS 소스 데이터 삽입
V3__add_constraints.sql             — CHECK constraints (enum 값 검증) — 선택적
```

**V1 포함 내용**:
- 위 5개 테이블 CREATE (sources, articles, article_sources, summaries, source_daily_usage)
- 위 인덱스 CREATE
- `updated_at` 자동 갱신 trigger (PostgreSQL function + trigger)

**V2 포함 내용**:
```sql
INSERT INTO sources (name, feed_url, adapter_type, active, collection_interval_minutes, call_budget_daily)
VALUES
  ('연합뉴스', 'https://www.yna.co.kr/rss/news.xml', 'RSS', true, NULL, 1000),
  ('네이버-경제', '경제 뉴스', 'NAVER', true, 30, 100),
  -- 추가 소스...
```

---

## Java Entity Mapping Overview

```java
// domain/Article.java
@Entity @Table(name = "articles")
public class Article {
    @Id @GeneratedValue(strategy = IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 2048) private String normalizedUrl;
    @Column(nullable = false, length = 2048) private String originalUrl;
    @Column(nullable = false, length = 1024) private String title;
    private String author;
    @Column(nullable = false) private OffsetDateTime publishedAt;
    @Column(nullable = false, updatable = false) private OffsetDateTime firstCollectedAt;
    @Enumerated(EnumType.STRING) private Category category;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private ProcessingStatus categoryStatus = ProcessingStatus.PENDING;
    private int categoryRetryCount;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private ProcessingStatus summaryStatus = ProcessingStatus.PENDING;
    private int summaryRetryCount;
    @Column(nullable = false) private OffsetDateTime expiresAt;
    private boolean feedVisible = true;
    private boolean userSaved = false;
    // created_at, updated_at via @CreationTimestamp, @UpdateTimestamp
    
    @OneToMany(mappedBy = "article", cascade = ALL) private List<ArticleSource> sources;
    @OneToMany(mappedBy = "article", cascade = ALL) private List<Summary> summaries;
}

// domain/Source.java — Source entity (collection 설정 포함)
// domain/ArticleSource.java — provenance 레코드
// domain/Summary.java — depth별 요약 슬롯
// domain/enums/Category.java — 10개 카테고리 enum + displayName()
// domain/enums/ProcessingStatus.java — PENDING / COMPLETED / FAILED
// domain/enums/SummaryDepth.java — BRIEF / BALANCED / DEEP
// domain/enums/SummarySlotStatus.java — NOT_GENERATED / PENDING / COMPLETED / FAILED
```

---

## Configuration Properties

```yaml
# application.yaml (공통 기본값)
app:
  scheduler:
    enabled: true
    collection:
      interval-ms: 900000          # 15분
  ai:
    batch-size: 10                 # 스케줄러 1회 처리 기사 수
    retry-limit: 3                 # AI 처리 최대 재시도 횟수 (BALANCED/BRIEF 슬롯)
    delay-between-calls-ms: 200    # AI API 호출 간 최소 대기
    deep-retry:
      cooldown-minutes: 60         # DEEP 슬롯 실패 후 재시도 쿨다운 (분)
      limit: 5                     # DEEP 슬롯 최대 AI 시도 횟수
  feed:
    default-page-size: 20
    max-page-size: 100
  retention:
    days: 90                       # 기사 보존 기간
    grace-period-days: 7           # feed_visible=false 후 물리 삭제 대기
```
