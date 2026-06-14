# Implementation Plan: 개인화 피드 · 검색 · 저장

**Branch**: `003-personalized-feed-search-save` | **Date**: 2026-06-12 | **Spec**: [spec.md](spec.md)

**Input**: `.specify/specs/003-personalized-feed-search-save/spec.md`

---

## Summary

001(뉴스 수집)의 기사 풀과 002(계정)의 관심사 데이터를 조합하여 규칙 기반 가중치 개인화 피드(`GET /api/v1/feed`)를 제공하고, pg_bigm 기반 한국어 전문 검색(`GET /api/v1/articles/search`), 기사 저장/목록/해제를 구현한다. 새 수집 로직은 없으며 기존 테이블은 인덱스 추가 외 수정하지 않는다.

---

## Technical Context

**Language/Version**: Java 25  
**Primary Dependencies**: Spring Boot 4.0.x, Spring Data JPA, Flyway, PostgreSQL pg_bigm extension  
**Storage**: PostgreSQL — `V9__saved_articles_search_indexes.sql` (001/002 테이블 수정 없음, 인덱스 추가 + 신규 테이블)  
**Testing**: JUnit 5, Testcontainers (커스텀 postgres:16+pg_bigm 이미지), Mockito  
**Target Platform**: Linux server (Spring Boot 임베디드)  
**Project Type**: REST API (web-service)  
**Performance Goals**: 피드 p95 < 1s (SC-001), 검색 p95 < 2s (SC-002), 저장 p95 < 500ms (SC-004)  
**Constraints**: 001/002 테이블 수정 금지(인덱스 추가만), `ddl-auto=validate`, 랭킹 가중치 환경변수 외부화  
**Scale/Scope**: 단일 인스턴스, 수천 명 사용자, 최근 7일 피드 후보 (일반적으로 수백~수천 건)

---

## Constitution Check

*GATE: 구현 착수 전 통과 필수.*

| # | Principle | Status | Evidence |
|---|-----------|--------|---------|
| I | 레이어드 아키텍처와 단방향 의존 | ✅ PASS | `FeedController` → `FeedService` → `ArticleRepository`/`UserInterestsRepository`. 랭킹 로직은 `FeedService`에만. 트랜잭션은 Service. |
| II | API 경계에서 Entity 비노출과 입력 검증 | ✅ PASS | `Article` Entity 미노출. `FeedResponse`, `ArticleSearchResponse`, `SavedArticleListResponse` record DTO. `?q=` 파라미터 `@Size(min=1, max=100)` 검증. |
| III | 일관된 응답 포맷과 전역 예외 처리 | ✅ PASS | 기존 `GlobalExceptionHandler` 확장. `SAVE_LIMIT_EXCEEDED`(409), `ARTICLE_NOT_FOUND`(404) 등록. |
| IV | 테스트 없는 비즈니스 로직 금지 | ✅ PASS | `FeedService` 단위 테스트(랭킹 점수, fallback 조건). Testcontainers 통합(커스텀 postgres:16+pg_bigm 이미지): 피드 랭킹 E2E, 검색 한국어 bigram(pg_bigm), 저장 멱등성. |
| V | 스키마 변경은 마이그레이션으로만 | ✅ PASS | `V9__saved_articles_search_indexes.sql`. 001/002 테이블 DDL 수정 없음(GIN 인덱스 추가만). |
| VI | 보안 기본값과 시크릿 외부화 | ✅ PASS | 모든 엔드포인트 JWT + 이메일인증 게이팅(002 `JwtAuthenticationFilter` 재사용). 랭킹 가중치는 `application.yaml`에 기본값, 환경변수로 오버라이드 가능. |
| VII | 수집·알림의 멱등성과 중복 방지 | ✅ PASS | 저장 `POST /save`는 멱등(이미 저장 시 200). 저장 해제 `DELETE /save`는 멱등(미저장 시 204). `(account_id, article_id)` 복합 UNIQUE 제약으로 DB 레벨 보장. |

---

## Project Structure

### Documentation (this feature)

```text
.specify/specs/003-personalized-feed-search-save/
├── plan.md              ← 이 파일
├── spec.md
├── research.md          ← Phase 0 출력
├── data-model.md        ← Phase 1 출력
├── quickstart.md        ← Phase 1 출력
├── contracts/
│   └── openapi.yaml     ← Phase 1 출력
└── checklists/
    └── requirements.md
```

### Source Code

```text
src/main/java/com/newscurator/
├── controller/
│   ├── FeedController.java               # GET /api/v1/feed
│   ├── ArticleSearchController.java      # GET /api/v1/articles/search
│   └── SavedArticleController.java       # POST/DELETE /api/v1/articles/{id}/save
│                                         # GET /api/v1/me/saved-articles
│
├── service/
│   ├── FeedService.java                  # 개인화 랭킹 계산, fallback 로직
│   ├── SearchService.java                # pg_bigm 검색 쿼리 조합, cursor 처리
│   └── SavedArticleService.java          # 저장/해제/목록, 상한 검사
│
├── repository/
│   ├── SavedArticleRepository.java       # 신규
│   └── ArticleRepository.java            # 기존 확장: searchByQuery(Native Query)
│
├── domain/
│   └── SavedArticle.java                 # 신규 Entity
│
├── dto/
│   ├── request/
│   │   ├── FeedRequest.java              # ?category, ?cursor, ?size
│   │   └── ArticleSearchRequest.java     # ?q, ?cursor, ?size
│   └── response/
│       ├── FeedResponse.java             # articles[], personalized, nextCursor
│       ├── ArticleItem.java              # 피드/검색 공통 기사 응답 DTO
│       ├── SummarySlot.java              # text, depth, isFallback
│       ├── ArticleSearchResponse.java    # articles[], nextCursor
│       └── SavedArticleListResponse.java # articles[], nextCursor
│
└── config/
    └── FeedRankingProperties.java        # @ConfigurationProperties("feed.ranking")

src/main/resources/
├── db/migration/
│   └── V9__saved_articles_search_indexes.sql
└── application.yaml                      # feed.ranking.* 기본값 추가

src/test/java/com/newscurator/
├── service/
│   ├── FeedServiceTest.java
│   ├── SearchServiceTest.java
│   └── SavedArticleServiceTest.java
├── repository/
│   └── ArticleRepositorySearchTest.java  # @DataJpaTest + Testcontainers
└── integration/
    ├── FeedIntegrationTest.java
    ├── SearchIntegrationTest.java
    └── SavedArticleIntegrationTest.java
```

---

## Implementation Phases

### Phase 1: DB Migration & Domain

**목표**: `saved_articles` 테이블 + pg_bigm 인덱스 활성화

1. `V9__saved_articles_search_indexes.sql`
   - `CREATE EXTENSION IF NOT EXISTS pg_bigm`
   - `saved_articles` 테이블 + `(account_id, article_id)` UNIQUE
   - `articles.title`, `articles.content` GIN bigram(pg_bigm) 인덱스 추가
   - `summaries.text` GIN bigram(pg_bigm) 인덱스 추가
2. `SavedArticle.java` Entity
3. `SavedArticleRepository.java`
4. `FeedRankingProperties.java` + `application.yaml` 가중치 기본값

**완료 기준**: Flyway V9 통과, `ddl-auto=validate` 성공

---

### Phase 2: 개인화 피드 (P1 — US1)

**목표**: `GET /api/v1/feed` — 규칙 기반 랭킹, category 필터, fallback

1. `FeedService` — 후보 조회(7일), rank_score 계산, fallback 판단, cursor 슬라이스
2. `FeedController` — `?category` 파라미터, Swagger 어노테이션
3. `FeedResponse`, `ArticleItem`, `SummarySlot` DTO
4. 요약 fallback 로직 (research.md R4 매트릭스)

**단위 테스트** (`FeedServiceTest`):
- 관심 카테고리 기사 > 비관심 기사 점수
- 키워드 포함 기사 +30점
- 최신 기사 recency 가산점
- 관심사 없는 사용자 → `personalized: false` + 최신순
- category 지정 → 해당 카테고리만 필터링
- DEEP 미생성·BALANCED 존재 → `isFallback=true`, `depth=balanced`

**통합 테스트** (`FeedIntegrationTest`):
- 실 DB에 관심사·기사 삽입 → 피드 API → 랭킹 순서
- fallback: 관심사 없는 사용자 → 최신순 200
- 미인증 401 / 이메일 미인증 403

---

### Phase 3: 기사 검색 (P2 — US2)

**목표**: `GET /api/v1/articles/search?q=` — pg_bigm 전문 검색

1. `ArticleRepository.searchByQuery` — Native Query (LIKE + GREATEST(bigm_similarity), EXISTS summaries, 90일 필터)
2. `SearchService` — 쿼리 정규화, cursor 처리
3. `ArticleSearchController`

**@DataJpaTest** (`ArticleRepositorySearchTest`, Testcontainers):
- 한국어 "경제" 검색 → "경제성장" 기사 포함
- 무관한 기사 미포함
- 90일 초과 기사 제외

**통합 테스트** (`SearchIntegrationTest`):
- 검색 API → relevance 순 결과
- 0건 → 빈 목록 200
- 422(빈 쿼리, 101자 초과)

---

### Phase 4: 기사 저장 (P3 — US3)

**목표**: 저장 CRUD

1. `SavedArticleService` — 1,000건 상한, 멱등 저장/해제, 목록 cursor 페이지네이션
2. `SavedArticleController`
3. `GlobalExceptionHandler` — `SAVE_LIMIT_EXCEEDED`(409) 추가

**단위 테스트** (`SavedArticleServiceTest`):
- 재저장 멱등 → 200, DB 중복 없음
- 미저장 해제 멱등 → 204
- 1,001번째 저장 → 409
- 미존재 articleId → 404

**통합 테스트** (`SavedArticleIntegrationTest`):
- 저장 → 목록 → 포함 단언
- 해제 → 목록 → 제외 단언
- 1,000건 초과 → 409

---

### Phase 5: Polish

1. SpringDoc 어노테이션 전체 적용 (모든 컨트롤러)
2. `application-example.yaml` — `feed.ranking.*` 예시 추가
3. `CHANGELOG.html` 업데이트
4. `./gradlew build` 전체 빌드 + 테스트 통과 확인
