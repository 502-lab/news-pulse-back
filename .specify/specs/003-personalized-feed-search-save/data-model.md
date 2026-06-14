# Data Model: 개인화 피드 · 검색 · 저장

**Feature**: 003-personalized-feed-search-save  
**Date**: 2026-06-12  
**Migration**: `V9__saved_articles_search_indexes.sql`

---

## 마이그레이션 전략

- Flyway 파일: `src/main/resources/db/migration/V9__saved_articles_search_indexes.sql`
- `ddl-auto=validate` 유지 — Hibernate가 런타임에 스키마 정합성 검증
- 001 테이블(`articles`, `sources`, `summaries` 등) DDL 수정 없음 — GIN 인덱스만 추가
- 002 테이블(`accounts`, `user_interests`, `follow_keywords`, `reading_preferences`) 수정 없음

---

## 신규 엔티티

### 1. saved_articles

```sql
CREATE TABLE saved_articles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    article_id  UUID NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    saved_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_saved_articles_account_article UNIQUE (account_id, article_id)
);

CREATE INDEX idx_saved_articles_account ON saved_articles(account_id, saved_at DESC);
```

**비즈니스 규칙**:
- `(account_id, article_id)` 복합 UNIQUE — DB 레벨 멱등성 보장
- `account_id`별 최대 1,000건 (애플리케이션 레이어에서 검사)
- 기사 삭제(`articles` ON DELETE CASCADE) 시 자동 제거
- 계정 삭제(`accounts` ON DELETE CASCADE) 시 자동 제거
- `saved_at` 역순 정렬이 기본 (인덱스에 `DESC` 포함)

**Entity (`SavedArticle.java`)**:
```java
@Entity @Table(name = "saved_articles")
public class SavedArticle {
    @Id @GeneratedValue UUID id;
    @Column(nullable = false) UUID accountId;
    @Column(nullable = false) UUID articleId;
    @Column(nullable = false) Instant savedAt;
}
```

---

## 기존 테이블 — 인덱스 추가만

### pg_bigm Extension + GIN 인덱스

```sql
-- 검색 성능을 위한 pg_bigm 확장 활성화 (apt install postgresql-16-pg-bigm 선행 필요)
CREATE EXTENSION IF NOT EXISTS pg_bigm;

-- articles 전문 검색 인덱스 (title, content)
-- 데이터가 찬 테이블에 생성 시 CREATE INDEX CONCURRENTLY 고려 (락 최소화)
-- 초기 투입 시점에 테이블이 작으면 CONCURRENTLY 생략 허용
CREATE INDEX IF NOT EXISTS idx_articles_title_bigm
    ON articles USING gin(title gin_bigm_ops);

CREATE INDEX IF NOT EXISTS idx_articles_content_bigm
    ON articles USING gin(content gin_bigm_ops);

-- summaries 전문 검색 인덱스 (text) — 전 depth 슬롯 커버
CREATE INDEX IF NOT EXISTS idx_summaries_text_bigm
    ON summaries USING gin(text gin_bigm_ops);
```

**영향 범위**: 기존 DDL/데이터 변경 없음. 인덱스 추가만이므로 롤백 시 `DROP INDEX`로 복원 가능.  
**주의**: `gin_bigm_ops`는 `postgresql.conf`에 `shared_preload_libraries = 'pg_bigm'` 설정 및 PG 재시작 후에만 활성화. 배포 절차에 포함 (→ 004 배포 준비 항목).

---

## 참조 엔티티 (001/002 기존 — 수정 없음)

### articles (001)
| 필드 | 타입 | 용도 |
|------|------|------|
| id | UUID PK | 기사 식별자 |
| title | VARCHAR | 검색 대상, 랭킹 키워드 매칭 |
| content | TEXT | 검색 대상 |
| published_at | TIMESTAMPTZ | 최신성 가중치, 90일 검색 범위 |
| category | VARCHAR | 카테고리 일치 가중치, 피드 필터 |
| source_id | UUID FK | 출처 |

### summaries (001)
| 필드 | 타입 | 용도 |
|------|------|------|
| article_id | UUID FK | 기사 참조 |
| depth | VARCHAR | `brief` / `balanced` / `deep` |
| text | TEXT | 검색 대상, 요약 표시, 키워드 매칭 |

### user_interests (002)
| 필드 | 타입 | 용도 |
|------|------|------|
| account_id | UUID FK | 계정 참조 |
| category | VARCHAR | 카테고리 일치 가중치 인풋 |

### follow_keywords (002)
| 필드 | 타입 | 용도 |
|------|------|------|
| account_id | UUID FK | 계정 참조 |
| keyword | VARCHAR | 키워드 일치 가중치 인풋 (title/summary 매칭) |

### reading_preferences (002)
| 필드 | 타입 | 용도 |
|------|------|------|
| account_id | UUID FK | 계정 참조 |
| summary_depth | VARCHAR | `brief` / `balanced` / `deep` — 응답 요약 슬롯 결정 |

---

## 랭킹 점수 계산 참조

```
rank_score = category_score + keyword_score + recency_score

category_score:
  +50 if articles.category IN (SELECT category FROM user_interests WHERE account_id = ?)

keyword_score:
  +30 per follow_keyword WHERE keyword LIKE ANY(articles.title, summaries.text)
  (최대 키워드 5개 매칭, 상한 150점)

recency_score:
  max(0, 30 - hours_since_published) / 30.0 * 20
  (feed.ranking.recency-window-hours=30, feed.ranking.recency-max-score=20)
```

→ 상세 근거: [research.md R2](research.md)

---

## 완전한 V9 마이그레이션 스크립트

```sql
-- V9__saved_articles_search_indexes.sql

-- 1. pg_bigm 확장
CREATE EXTENSION IF NOT EXISTS pg_bigm;

-- 2. saved_articles 테이블
CREATE TABLE saved_articles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    article_id  UUID NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    saved_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_saved_articles_account_article UNIQUE (account_id, article_id)
);

CREATE INDEX idx_saved_articles_account
    ON saved_articles(account_id, saved_at DESC);

-- 3. 전문 검색 GIN bigm 인덱스 (기존 테이블에 추가)
-- 데이터가 많을 경우 CONCURRENTLY 고려; 초기 투입 시 생략 허용
CREATE INDEX IF NOT EXISTS idx_articles_title_bigm
    ON articles USING gin(title gin_bigm_ops);

CREATE INDEX IF NOT EXISTS idx_articles_content_bigm
    ON articles USING gin(content gin_bigm_ops);

CREATE INDEX IF NOT EXISTS idx_summaries_text_bigm
    ON summaries USING gin(text gin_bigm_ops);
```
