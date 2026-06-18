# Tasks: 개인화 피드 · 검색 · 저장

**Input**: `.specify/specs/003-personalized-feed-search-save/`

**Prerequisites**: plan.md ✅ · spec.md ✅ · research.md ✅ · data-model.md ✅ · quickstart.md ✅

**Tests**: 포함 — plan.md Phase 2~4에 단위·통합 테스트 명세 포함됨

**Organization**: Phase 1 Setup → Phase 2 Foundational → US1(P1) → US2(P2) → US3(P3) → Polish

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: 병렬 실행 가능 (다른 파일, 선행 의존 없음)
- **[US1/US2/US3]**: 해당 User Story 귀속
- 각 태스크에 정확한 파일 경로 포함

---

## Phase 1: Setup (공유 인프라)

**Purpose**: Flyway 마이그레이션, 테스트 인프라, 설정 클래스 준비

- [x] T001 V9 Flyway 마이그레이션 파일 작성 (pg_bigm 확장 + saved_articles 테이블 + GIN 인덱스) in `src/main/resources/db/migration/V9__saved_articles_search_indexes.sql`
- [x] T002 [P] Testcontainers 커스텀 Dockerfile 작성 (postgres:16 + postgresql-16-pg-bigm 설치 + `CMD ["postgres", "-c", "shared_preload_libraries=pg_bigm"]` 지정; 미설정 시 gin_bigm_ops 연산자 미로드로 V9 GIN 인덱스 사용 불가) in `src/test/resources/postgres-bigm/Dockerfile`
- [x] T003 [P] `FeedRankingProperties` @ConfigurationProperties 클래스 작성 (`feed.ranking.*` 바인딩) + `AppConfig.java`의 `@EnableConfigurationProperties` 목록에 `FeedRankingProperties.class` 추가 (기존 패턴 — `@SpringBootApplication`에 `@ConfigurationPropertiesScan` 없으므로 수동 등록 필수) in `src/main/java/com/newscurator/config/FeedRankingProperties.java`, `src/main/java/com/newscurator/config/AppConfig.java`
- [x] T004 `application.yaml` 에 `feed.ranking` 기본값 추가 (category-match-score: 50, keyword-match-score: 30, recency-window-hours: 30, recency-max-score: 20, feed-window-days: 7)

**Checkpoint**: `./gradlew bootRun` 기동 시 Flyway V9 통과, `ddl-auto=validate` 성공

---

## Phase 2: Foundational (블로킹 전제조건)

**Purpose**: 모든 User Story가 공유하는 Entity·공통 DTO·예외 처리 준비

**⚠️ CRITICAL**: 이 Phase 완료 전 US1~US3 구현 착수 불가

- [x] T005 `SavedArticle` Entity 작성 (`@Table(name = "saved_articles")`, id/accountId/articleId/savedAt 필드, `@PrePersist` savedAt 자동 설정) in `src/main/java/com/newscurator/domain/SavedArticle.java`
- [x] T006 `SavedArticleRepository` JPA Repository 작성 (`findByAccountIdOrderBySavedAtDesc(Pageable)`, `existsByAccountIdAndArticleId(UUID, Long)`, `countByAccountId(UUID)`, `deleteByAccountIdAndArticleId(UUID, Long)`, `findSavedArticleIdsByAccountIdAndArticleIdIn(UUID accountId, Collection<Long> articleIds)` — 피드·검색 ArticleItem.saved 배치 조회용, N+1 방지) in `src/main/java/com/newscurator/repository/SavedArticleRepository.java`
- [x] T007 `SaveLimitExceededException` 예외 클래스 신규 작성 + `GlobalExceptionHandler` 에 409 `SAVE_LIMIT_EXCEEDED` 핸들러 추가 (`ArticleNotFoundException`→404는 이미 등록됨, 추가 불필요) in `src/main/java/com/newscurator/exception/SaveLimitExceededException.java`, `src/main/java/com/newscurator/exception/GlobalExceptionHandler.java`
- [x] T008 [P] `ArticleItem` 공통 응답 DTO 작성 (피드·검색 공용, id/title/category/publishedAt/sourceName/summary/rankScore/saved 필드; sourceName은 `article_sources → sources.name` JOIN — Article이 복수 출처를 가질 수 있으므로 `article.getSources().get(0).getSource().getName()` 또는 native 쿼리 서브셀렉트로 첫 수집 출처명 취득, N+1 금지) in `src/main/java/com/newscurator/dto/response/ArticleItem.java`
- [x] T009 [P] `SummarySlot` 응답 DTO 작성 (text/depth/isFallback 필드; depth는 실제 반환된 슬롯, isFallback은 reading_preferences.summary_depth 불일치 여부) in `src/main/java/com/newscurator/dto/response/SummarySlot.java`

**Checkpoint**: `./gradlew compileJava` 성공 — Foundational 코드 컴파일 오류 없음

---

## Phase 3: User Story 1 — 개인화 피드 (Priority: P1) 🎯 MVP

**Goal**: `GET /api/v1/feed` — 관심 카테고리·키워드·최신성 3요소 규칙 기반 랭킹, category 필터, fallback

**Independent Test**: `./gradlew test --tests "*FeedServiceTest*" --tests "*FeedIntegrationTest*"`

### Tests for User Story 1

- [x] T010 `FeedServiceTest` 단위 테스트 작성 (7 시나리오: 관심 카테고리 기사 점수 우위, 키워드 매칭 +30점, recency 가산점 검증, 관심사·키워드 모두 없는 사용자 → personalized=false+published_at DESC, category 파라미터 필터링, DEEP 미생성·BALANCED 존재 시 isFallback=true+depth=balanced, 요약 슬롯 전혀 없음 시 summary.text=null+isFallback=false) in `src/test/java/com/newscurator/service/FeedServiceTest.java`
- [x] T011 `FeedIntegrationTest` Testcontainers 통합 테스트 작성 (커스텀 postgres-bigm 이미지 사용; 실 DB 삽입 후 피드 API 랭킹 순서 단언, fallback 최신순 200, **커서 전달 시 2페이지 결과가 1페이지와 중복 없음 단언(AS1.6)**, 미인증 401, 이메일 미인증 403) in `src/test/java/com/newscurator/integration/FeedIntegrationTest.java`

### Implementation for User Story 1

- [x] T012 [P] `FeedRequest` 요청 DTO 작성 (`?category` Category enum, `?cursor` Base64 커서, `?size` int 기본 20, @Parameter Swagger 어노테이션 포함) in `src/main/java/com/newscurator/dto/request/FeedRequest.java`
- [x] T013 [P] `FeedResponse` 응답 DTO 작성 (`articles: List<ArticleItem>`, `personalized: boolean`, `nextCursor: String`, `hasNext: boolean`) in `src/main/java/com/newscurator/dto/response/FeedResponse.java`
- [x] T014 `FeedService` 구현 (후보 기사 조회 7일·Java 내 점수 계산·정렬·슬라이스; rank_score = category_score(50) + keyword_score(30×n, 최대 5개 = 150) + recency_score(최대 20, 30h 선형 감쇠); **fallback 분기(런타임 상태 기준, category 미지정 피드 한정)**: `UserInterestsRepository.findByAccountId` + `FollowKeywordRepository.findByAccountId` 결과가 모두 비어있으면 personalized=false+published_at DESC, 하나라도 있으면 rank_score 내림차순+personalized=true. `account.personalizationActive` 는 참조하지 않음 — `PUT /me/interests`가 동 플래그를 재계산하지 않아 stale 가능, 런타임 interests/keywords가 단일 진실 출처임. category 지정 피드는 fallback 없음(결과 0건 시 빈 목록); **saved 배치 조회**: 결과 articleId 목록으로 `SavedArticleRepository.findSavedArticleIdsByAccountIdAndArticleIdIn` 단일 쿼리 → Set 변환 후 ArticleItem.saved 설정(N+1 금지); 요약 fallback 매트릭스 DEEP→BALANCED→BRIEF; 슬롯 없음 시 text=null; 커서: Base64(rank_score|published_at|article_id|reference_ts) 4-component 인코딩; @Transactional(readOnly=true)) in `src/main/java/com/newscurator/service/FeedService.java`
- [x] T015 `FeedController` 구현 (`GET /api/v1/feed` → 응답 `ApiResponse.success(feedResponse)`로 래핑(FR-020), JWT+이메일인증 게이팅, @Tag/@Operation/@ApiResponses 포함) in `src/main/java/com/newscurator/controller/FeedController.java`

**Checkpoint**: T010·T011 전체 통과 — 랭킹 순서, fallback, 인증 오류 응답 단언 완료

---

## Phase 4: User Story 2 — 기사 검색 (Priority: P2)

**Goal**: `GET /api/v1/articles/search?q=` — pg_bigm GIN 인덱스 활용 한국어 전문 검색, 90일 범위, relevance 정렬

**Independent Test**: `./gradlew test --tests "*ArticleRepositorySearchTest*" --tests "*SearchServiceTest*" --tests "*SearchIntegrationTest*"`

### Tests for User Story 2

- [x] T016 `ArticleRepositorySearchTest` @DataJpaTest + Testcontainers 테스트 작성 (커스텀 postgres-bigm 이미지 사용; "경제" 검색 → "경제성장" 기사 포함 단언, 무관 기사 미포함, 90일 초과 기사 제외, GREATEST(bigm_similarity) 정렬 확인) in `src/test/java/com/newscurator/repository/ArticleRepositorySearchTest.java`
- [x] T017 [P] `SearchServiceTest` 단위 테스트 작성 (쿼리 정규화, 결과 없음 빈 목록 200, 검색 커서 Base64 인코딩/디코딩, 빈 쿼리 422 검증) in `src/test/java/com/newscurator/service/SearchServiceTest.java`
- [x] T018 `SearchIntegrationTest` Testcontainers 통합 테스트 작성 (검색 API relevance 순 결과, 0건 응답 200, 빈 쿼리 422, 101자 초과 422, **커서 전달 시 2페이지 결과가 1페이지와 중복 없음 단언(AS2.6)**, 미인증 401) in `src/test/java/com/newscurator/integration/SearchIntegrationTest.java`

### Implementation for User Story 2

- [x] T019 [P] `ArticleSearchRequest` 요청 DTO 작성 (`?q` @Size(min=2, max=100) @NotBlank, `?cursor` Base64, `?size` int 기본 20) in `src/main/java/com/newscurator/dto/request/ArticleSearchRequest.java`
- [x] T020 [P] `ArticleSearchResponse` 응답 DTO 작성 (`articles: List<ArticleItem>`, `nextCursor: String`, `hasNext: boolean`) in `src/main/java/com/newscurator/dto/response/ArticleSearchResponse.java`
- [x] T021 `ArticleRepository` 에 `searchByQuery` 네이티브 쿼리 추가 (DISTINCT + `a.title LIKE '%' || :query || '%'` + `a.content LIKE '%' || :query || '%'` + `EXISTS (SELECT 1 FROM summaries s WHERE s.article_id=a.id AND s.text LIKE '%' || :query || '%')` + GREATEST(bigm_similarity) 내림차순 + published_at >= 90일 필터 + 커서 조건 + `(SELECT s2.name FROM article_sources asrc JOIN sources s2 ON s2.id=asrc.source_id WHERE asrc.article_id=a.id ORDER BY asrc.collected_at ASC LIMIT 1) AS source_name` 서브셀렉트; nativeQuery=true. 주의: `LIKE '%:query%'`는 JPA 네이티브에서 ':query'를 리터럴로 처리해 빈 결과 유발 — 반드시 `'%' || :query || '%'` 패턴 사용) in `src/main/java/com/newscurator/repository/ArticleRepository.java`
- [x] T022 `SearchService` 구현 (쿼리 공백 trim·2자 미만 검증, `ArticleRepository.searchByQuery` 호출, `ArticleItem` 변환; **개인화 가중치 미적용 — relevance(bigm_similarity) 정렬만 수행(FR-013)**, user_interests/follow_keywords 참조 금지; **saved 배치 조회**: resultIds로 `SavedArticleRepository.findSavedArticleIdsByAccountIdAndArticleIdIn` 단일 쿼리 → Set 변환 후 ArticleItem.saved 설정(N+1 금지); 커서 Base64(similarity|published_at|article_id) 인코딩; @Transactional(readOnly=true)) in `src/main/java/com/newscurator/service/SearchService.java`
- [x] T023 `ArticleSearchController` 구현 (`GET /api/v1/articles/search` → 응답 `ApiResponse.success(articleSearchResponse)`로 래핑(FR-020), JWT+이메일인증 게이팅, @Tag/@Operation/@ApiResponses 포함) in `src/main/java/com/newscurator/controller/ArticleSearchController.java`

**Checkpoint**: T016·T017·T018 전체 통과 — 한국어 bigram 검색, relevance 정렬, 입력 검증 단언 완료

---

## Phase 5: User Story 3 — 기사 저장 (Priority: P3)

**Goal**: `POST/DELETE /api/v1/articles/{articleId}/save` + `GET /api/v1/me/saved-articles` — 멱등 저장/해제, 1,000건 상한, cursor 목록

**Independent Test**: `./gradlew test --tests "*SavedArticleServiceTest*" --tests "*SavedArticleIntegrationTest*"`

### Tests for User Story 3

- [x] T024 `SavedArticleServiceTest` 단위 테스트 작성 (5 시나리오: 재저장 멱등 200·DB 중복 없음, 미저장 해제 멱등 204, 1001번째 저장 409 SAVE_LIMIT_EXCEEDED, 미존재 articleId 404 ARTICLE_NOT_FOUND, **count=1000 + 기존 저장 기사 재저장 → 200(exists 단락, 상한 검사 도달 안 함)**) in `src/test/java/com/newscurator/service/SavedArticleServiceTest.java`
- [x] T025 `SavedArticleIntegrationTest` Testcontainers 통합 테스트 작성 (저장→목록→포함 단언, 해제→재조회→미포함 단언, 1000건 초과→409, savedAt 역순 정렬, 미인증 401) in `src/test/java/com/newscurator/integration/SavedArticleIntegrationTest.java`

### Implementation for User Story 3

- [x] T026 [P] `SavedArticleItem` record 신규 작성 (savedAt: Instant, article: ArticleItem) + `SavedArticleListResponse` 응답 DTO 작성 (`articles: List<SavedArticleItem>`, `nextCursor: String`, `hasNext: boolean`; openapi SavedArticleItem 래퍼 구조와 일치) in `src/main/java/com/newscurator/dto/response/SavedArticleItem.java`, `src/main/java/com/newscurator/dto/response/SavedArticleListResponse.java`
- [x] T027 `SavedArticleService` 구현 (save 순서: ①`existsByAccountIdAndArticleId` 먼저 확인 → 있으면 상한 검사 없이 멱등 200 즉시 반환; ②없으면 `countByAccountId >= 1000`이면 SaveLimitExceededException(409); ③아니면 신규 저장 201; unsave: `deleteByAccountIdAndArticleId`(미저장 시 no-op, 204); list: cursor 기반 savedAt DESC 페이지네이션; @Transactional) in `src/main/java/com/newscurator/service/SavedArticleService.java`
- [x] T028 `SavedArticleController` 구현 (`POST /api/v1/articles/{articleId}/save` 201/200, `DELETE /api/v1/articles/{articleId}/save` 204, `GET /api/v1/me/saved-articles` 200 → 목록 응답은 `ApiResponse.success(savedArticleListResponse)`로 래핑(FR-020); JWT+이메일인증 게이팅, @Tag/@Operation/@ApiResponses 포함) in `src/main/java/com/newscurator/controller/SavedArticleController.java`

**Checkpoint**: T024·T025 전체 통과 — 저장/해제 멱등성, 1000건 상한, 목록 정렬 단언 완료

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Swagger 문서, 설정 예시, CHANGELOG 업데이트, 빌드 검증

- [x] T029 [P] `FeedController` Swagger 완성 (@Tag, @Operation summary/description, @ApiResponses 200/400/401/403/422, @Parameter on ?category/?cursor/?size) in `src/main/java/com/newscurator/controller/FeedController.java`
- [x] T030 [P] `ArticleSearchController` Swagger 완성 (@Tag, @Operation, @ApiResponses 200/401/403/422, @Parameter on ?q/?cursor/?size) in `src/main/java/com/newscurator/controller/ArticleSearchController.java`
- [x] T031 [P] `SavedArticleController` Swagger 완성 (@Tag, @Operation, @ApiResponses 200/201/204/401/403/404/409, @PathVariable articleId @Parameter) in `src/main/java/com/newscurator/controller/SavedArticleController.java`
- [x] T032 [P] `application-example.yaml` 에 `feed.ranking.*` 예시 항목 추가 (application.yaml 기본값과 동일 구조) in `src/main/resources/application-example.yaml`
- [x] T033 `CHANGELOG.html` 에 003 feature 항목 추가 (tag-feature, 피드·검색·저장 구현 내용, 영향 파일 목록)
- [x] T034 `./gradlew build` 실행 — 기존 테스트 회귀 없음 + 003 신규 테스트 전체 통과 확인 (Testcontainers Docker 필요)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 즉시 착수 가능
- **Foundational (Phase 2)**: Phase 1 완료 후 착수 — 모든 US 블로킹
- **US1 (Phase 3)**: Phase 2 완료 후 착수 가능
- **US2 (Phase 4)**: Phase 2 완료 후 착수 가능 — US1과 독립적으로 병렬 진행 가능
- **US3 (Phase 5)**: Phase 2 완료 후 착수 가능 — US1·US2와 독립적으로 병렬 진행 가능
- **Polish (Phase 6)**: US1~US3 원하는 스토리 완료 후

### User Story Dependencies

- **US1 (P1)**: Phase 2 완료 후 독립 착수 가능
- **US2 (P2)**: Phase 2 완료 후 독립 착수 가능 — `ArticleRepository.searchByQuery` 신규 추가이므로 기존 US1 코드와 충돌 없음
- **US3 (P3)**: Phase 2 완료 후 독립 착수 가능 — `SavedArticleRepository`·`SavedArticle` Entity는 Phase 2에서 이미 생성

### Within Each User Story

- 테스트를 먼저 작성하고 실패 확인 후 구현
- DTO → Service → Controller 순서
- 같은 파일을 건드리는 태스크는 반드시 순차 실행 (예: T021은 기존 ArticleRepository 수정)

---

## Parallel Opportunities

### Phase 1 병렬 실행

```bash
# 동시 착수 가능 (다른 파일):
T001: V9 마이그레이션 SQL
T002: postgres-bigm Dockerfile
T003: FeedRankingProperties.java
# T004는 T003 완료 후 (application.yaml 연동 확인)
```

### Phase 3 (US1) 병렬 실행

```bash
# 테스트 먼저 작성 (동시):
T010: FeedServiceTest.java
T011: FeedIntegrationTest.java

# DTO는 동시 작성:
T012: FeedRequest.java
T013: FeedResponse.java

# Service·Controller는 DTO 후 순차:
T014: FeedService.java → T015: FeedController.java
```

### Phase 4 (US2) 병렬 실행

```bash
# 테스트 동시 착수:
T016: ArticleRepositorySearchTest.java
T017: SearchServiceTest.java
T018: SearchIntegrationTest.java (T016 후)

# DTO 동시 착수:
T019: ArticleSearchRequest.java
T020: ArticleSearchResponse.java

# 구현 순차:
T021 (ArticleRepository 수정) → T022 (SearchService) → T023 (Controller)
```

### Phase 6 병렬 실행

```bash
# 컨트롤러 Swagger 어노테이션 동시:
T029: FeedController
T030: ArticleSearchController
T031: SavedArticleController
T032: application-example.yaml
```

---

## Implementation Strategy

### MVP First (US1만)

1. Phase 1 완료 (마이그레이션·설정)
2. Phase 2 완료 (SavedArticle Entity·공통 DTO·예외 핸들러)
3. Phase 3 완료 (개인화 피드 US1)
4. **검증**: `./gradlew test --tests "*FeedServiceTest*" --tests "*FeedIntegrationTest*"` → 7+3 시나리오 통과
5. MVP 배포/데모 가능

### Incremental Delivery

1. Setup + Foundational → 기반 완성
2. US1(피드) 완성 → 배포 가능 (관심사 기반 피드)
3. US2(검색) 추가 → 배포 가능 (검색 추가)
4. US3(저장) 추가 → 전체 기능 완성

### Task Counts

| Phase | 태스크 수 | 병렬 가능 |
|-------|----------|---------|
| Phase 1 (Setup) | 4 | T002·T003 병렬 |
| Phase 2 (Foundational) | 5 | T008·T009 병렬 |
| Phase 3 (US1) | 6 | T010~T013 병렬 |
| Phase 4 (US2) | 8 | T016·T017·T019·T020 병렬 |
| Phase 5 (US3) | 5 | T024·T025·T026 병렬 |
| Phase 6 (Polish) | 6 | T029~T032 병렬 |
| **합계** | **34** | — |

---

## Notes

- `[P]` 태스크 = 다른 파일, 선행 의존 없음
- T021(ArticleRepository 수정)은 기존 파일 확장 — 기존 메서드 삭제 금지
- pg_bigm 테스트는 반드시 커스텀 Docker 이미지 사용 (standard postgres:16-alpine에는 pg_bigm 없음)
- `ddl-auto=validate` 유지 — Hibernate가 SavedArticle Entity와 V9 스키마 정합성 런타임 검증
- 커서 인코딩: Base64(URL-safe) 사용, 디코딩 실패 시 첫 페이지 반환으로 graceful fallback
- quickstart.md 7개 시나리오를 구현 완료 후 수동 검증 가이드로 사용
- SC-001(피드 p95<1s)·SC-002(검색 p95<2s)·SC-004(저장 p95<500ms) 성능 목표는 부하 테스트 태스크 미포함 — 004 배포 준비(k6 SLO 테스트)로 위임
