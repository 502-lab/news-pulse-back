# Tasks: 010 인사이트 + 추천 — 개인 소비 리포트 & 놓친 기사 추천

**Feature**: `feat/010-insights` | **Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

**범위**: P1(US1 소비 리포트 6항목) + P2(US2 놓친 기사 추천). ★ **신규 테이블 0 = 마이그레이션 task 없음**. US3(평균읽기시간 P2 dwell·임베딩·사전집계 배치)은 후속 — 제외.

크라운주얼(실 PG `BigmPostgresImage` / 인가 @SpringBootTest): ①추출 회귀(T010 동일점수 + T022 003 불변)·②6항목 집계(T013)·③표본<5(T014)·④추천 제외(T020)·⑤콜드스타트(T021).

---

## Phase 1: Setup

- [ ] T001 OpenAPI 선반영 확인 — `.specify/specs/010-insights/contracts/openapi-patch.yaml`(`/me/insights`·`/me/recommendations`)가 구현 대상과 일치 점검(news-pulse-spec 반영은 dev push 시 sync-openapi). **마이그레이션 없음(테이블 0) 확인**.

---

## Phase 2: Foundational (차단 선행)

- [ ] T002 `InsightsRecommendationProperties` in `src/main/java/com/newscurator/config/InsightsRecommendationProperties.java` — `@ConfigurationProperties(prefix="app.insights.recommendation")`: categoryWeight=0.5·trendWeight=0.3·recencyWeight=0.2·candidateDays=14·minSampleSize=5·recommendLimit. application.yaml 기본값 추가. **하드코딩 0**.
- [ ] T003 ★ `ArticleRelevanceScorer` 추출 in `src/main/java/com/newscurator/service/ArticleRelevanceScorer.java` — `FeedService.computeScore(Article, List<String> userCategories, List<String> userKeywords, Instant referenceTs)`를 **behavior-preserving 이동**(본문·인자·연산순서·반환 100% 보존), `FeedRankingProperties` 주입. @Component.
- [ ] T004 `FeedService` 위임 변경 in `src/main/java/com/newscurator/service/FeedService.java` — computeScore 제거하고 `ArticleRelevanceScorer` 주입·위임(순수 위임, 상태 구성·호출부 동일). 003 피드 행위 불변.
- [ ] T005 `InsightAggregationRepository` in `src/main/java/com/newscurator/repository/InsightAggregationRepository.java` — 읽은 기사(article_event VIEW distinct, `JOIN articles ON admin_hidden_at IS NULL`) 기준 6항목 집계 native 쿼리: (1)readCount distinct, (2)카테고리 분포/최다, (3)언론사 top-k(article_sources→sources.name), (4)키워드 분포(article_keyword), (5)편향 분포(bias_analysis DONE, 버킷 진보[-100,-34]·중립[-33,33]·보수[34,100], NULLIF 안전). + 추천 후보 쿼리(최근 candidateDays 비숨김 − article_event NOT EXISTS − saved_articles NOT EXISTS).

**Checkpoint**: config·scorer·집계 리포지토리 준비 → US1/US2 착수.

---

## Phase 3: US1 — 개인 소비 리포트 (Priority: P1) 🎯 MVP

**목표**: `/api/v1/me/insights` 6항목 온디맨드 집계 + 표본<5 분기. 본인 스코프·숨김 제외·편향 DONE만.

**독립 테스트**: 조회·저장 이력 있는 사용자 → 6항목 정확, 타인 0, 표본<5면 분포 null·카운트 정상.

### 구현

- [ ] T006 [P] [US1] DTO `InsightResponse` in `src/main/java/com/newscurator/dto/response/InsightResponse.java` — readCount·bookmarkCount(항상) + sampleSufficient + topCategory·categoryDistribution·keywordDistribution·topOutlets·biasDistribution(표본<5면 null). @Schema.
- [ ] T007 [P] [US1] DTO `BiasDistributionResponse`(liberalPercent·neutralPercent·conservativePercent·total) in `src/main/java/com/newscurator/dto/response/BiasDistributionResponse.java` — 중립 기술(단정 라벨 없음). @Schema.
- [ ] T008 [US1] `InsightService` in `src/main/java/com/newscurator/service/InsightService.java` — `@Transactional(readOnly=true)` getInsights(accountId): readCount 먼저 → **minSampleSize(5) 미만이면 분포 쿼리 skip + sampleSufficient=false + 분포 null**(카운트만), 이상이면 6항목 집계(쿼리 6 상한). 003 saved count. NPE·분모0 없음.
- [ ] T009 [US1] `InsightController` in `src/main/java/com/newscurator/controller/InsightController.java` — `GET /api/v1/me/insights`, `@AuthenticationPrincipal`로 본인만, ApiResponse 래퍼, @Tag/@Operation/@ApiResponses.

### 테스트 (크라운주얼)

- [ ] T010 [P] [US1] ★ `ArticleRelevanceScorerTest`(단위) in `src/test/java/com/newscurator/service/ArticleRelevanceScorerTest.java` — **크라운주얼 #1(추출 동일점수)**: 추출된 scorer가 카테고리 매칭·키워드 상한·최근성 ratio를 기존 computeScore와 동일하게 산출(대표 케이스 점수 단언).
- [ ] T011 [P] [US1] `InsightServiceTest`(단위, Mockito) in `src/test/java/com/newscurator/service/InsightServiceTest.java` — 표본<5 분기(분포 null·카운트 반환), 표본≥5 6항목 매핑.
- [ ] T012 [US1] `InsightAggregationRepositoryTest`(Testcontainers) in `src/test/java/com/newscurator/repository/InsightAggregationRepositoryTest.java` — 6항목 쿼리 정확(distinct·숨김 제외·편향 DONE만·버킷 경계).
- [ ] T013 [US1] ★ `InsightAggregationIT`(@SpringBootTest RANDOM_PORT, 실 PG) in `src/test/java/com/newscurator/integration/InsightAggregationIT.java` — **크라운주얼 #2·#4·#5**: 6항목 본인 스코프(B토큰→A 0) + admin_hidden 기사 집계 제외 + 편향 중립 분포(진보/중립/보수 % 버킷, DONE만).
- [ ] T014 [US1] ★ `InsightSampleThresholdIT`(실 PG) in `src/test/java/com/newscurator/integration/InsightSampleThresholdIT.java` — **크라운주얼 #3**: 읽은 고유 기사 <5 → sampleSufficient=false·분포 null·readCount/bookmarkCount 정상(NPE·분모0 없음). 읽은 기사 0 사용자 안전.

**Checkpoint**: 6항목 리포트 + 표본 분기 → US1 독립 배포(MVP).

---

## Phase 4: US2 — 놓친 기사 추천 (Priority: P2)

**목표**: `/api/v1/me/recommendations` 룰베이스 추천 — 조회·저장·숨김 제외 + 관심사/조회/트렌드/최근성 가중 + 콜드스타트 fallback.

**독립 테스트**: 일부 조회·저장한 사용자 → 추천에 그 기사 0건. 조회·관심사 0 사용자 → 트렌드 fallback 비어있지 않음.

### 구현

- [ ] T015 [P] [US2] DTO `RecommendationResponse`(items·coldStart) + `RecommendedArticle`(articleId·title·category·publishedAt·reason) in `src/main/java/com/newscurator/dto/response/RecommendationResponse.java` — @Schema.
- [ ] T016 [US2] `RecommendationEngine` 인터페이스 in `src/main/java/com/newscurator/service/recommendation/RecommendationEngine.java` — `RecommendationResponse recommend(UUID accountId, int limit)`. 임베딩 v2 교체 seam(007 IssueClusterer 패턴).
- [ ] T017 [US2] `RuleBasedRecommender` in `src/main/java/com/newscurator/service/recommendation/RuleBasedRecommender.java` — 후보(14일 비숨김 − 조회 − 저장, T005) → `ArticleRelevanceScorer`(카테고리·키워드·최근성) + **트렌드 가중**(007) 블렌드(config 가중치) → top N. **★ 콜드스타트 분기**: 조회(009 distinct=0) AND 관심사(user_interests+follow_keywords=0) → 트렌드/최근 fallback(coldStart=true) / 조회0+관심사有→관심사 / 조회有+관심사0→조회 프로파일. 빈 목록 금지.
- [ ] T018 [US2] `InsightController` 확장 in `src/main/java/com/newscurator/controller/InsightController.java` — `GET /api/v1/me/recommendations?limit`, 본인만, ApiResponse, Swagger.

### 테스트 (크라운주얼)

- [ ] T019 [P] [US2] `RuleBasedRecommenderTest`(단위) in `src/test/java/com/newscurator/service/recommendation/RuleBasedRecommenderTest.java` — 가중치 config 반영(props 변경→결과 변화, 하드코딩 0), 콜드스타트 분기 4경우(조회·관심사 조합).
- [ ] T020 [US2] ★ `RecommendationExclusionIT`(실 PG) in `src/test/java/com/newscurator/integration/RecommendationExclusionIT.java` — **크라운주얼 #4**: 이미 조회(009)·저장(003)·숨김(admin_hidden) 기사가 추천에 **0건**.
- [ ] T021 [US2] ★ `RecommendationColdStartIT`(실 PG) in `src/test/java/com/newscurator/integration/RecommendationColdStartIT.java` — **크라운주얼 #5**: 조회·관심사 0 신규 사용자 → 트렌드/최근 fallback 추천 **비어있지 않음**(coldStart=true). + 관심사만 있는 사용자는 관심사 기반(fallback 아님) 대조.

**Checkpoint**: 추천 + 제외 + 콜드스타트 → US2 완료.

---

## Phase 5: Polish & Cross-Cutting

- [ ] T022 [P] ★ `FeedService` 003 피드 랭킹 회귀 확인 — **크라운주얼 #1(추출 회귀)**: 기존 003 피드/랭킹 테스트(`FeedService`·피드 IT)가 ArticleRelevanceScorer 추출 후 **동일 통과**(행위 보존 증거). 별도 테스트 추가 없이 기존 스위트 GREEN 확인(또는 추출 전후 동일성 명시 단언).
- [ ] T023 [P] ADR in `.specify/specs/010-insights/adr/ADR-001-aggregation-recommendation.md` — 온디맨드 집계(테이블 0·쿼리 6 상한·FANOUT 회피), behavior-preserving 추출(공유 scorer), RecommendationEngine seam, 콜드스타트 분기, 편향 버킷 006 미러, 표본<5 처리.
- [ ] T024 [P] CHANGELOG 항목 in `CHANGELOG.html` — 010 insights+추천(온디맨드 6항목·룰베이스 추천·테이블 0) feature 엔트리 + stats 갱신.
- [ ] T025 quickstart 검증 상태 갱신 in `.specify/specs/010-insights/quickstart.md` — 시나리오↔IT 매핑·full suite 결과.
- [ ] T026 OpenApiSpecExportTest 통과 확인 → dev push 시 sync-openapi가 `/api/v1/me/insights`·`/me/recommendations` news-pulse-spec 반영.
- [ ] T027 010 전체 회귀 + 001~010 full suite(V1~V17, **신규 마이그레이션 0**) 0 fail 확인(forkEvery=1).

---

## Dependencies & 실행 순서

- **Setup(T001)** → **Foundational(T002~T005)**: T002·T003 [P], T003→T004(FeedService 위임), T005.
- **US1(T006~T014)**: T006·T007 [P]→T008→T009, 테스트 T010·T011 [P] 후 T012·T013·T014. Foundational 후.
- **US2(T015~T021)**: T015 [P]→T016→T017→T018, T019 [P] 후 T020·T021. Foundational 후(US1과 독립이나 scorer·후보쿼리 공유).
- **Polish(T022~T027)**: 전 US 후. T022(003 회귀)는 T004 직후에도 조기 확인 가능.

## 병렬 기회
- Foundational: T002·T003 [P]. US1: T006·T007·T010·T011 [P]. US2: T015·T019 [P]. Polish: T023·T024 [P].

## MVP 범위
**US1(T001~T014)** = MVP. 개인 소비 리포트 6항목만으로 "데이터 되돌려주기" 핵심 가치. US2 추천은 증분.

## 범위 제외 (후속)
- ★ 평균읽기시간(read-tracking P2 dwell, metric_value P1 null) / 임베딩·ML 추천(RecommendationEngine 교체 seam) / 인사이트 사전집계 배치(온디맨드 MVP). **신규 테이블 0 — 마이그레이션 task 없음**.

## 크라운주얼 ↔ task 매핑
| # | 크라운주얼 | task |
|---|---|---|
| 1 | ★ ArticleRelevanceScorer 추출 회귀(003 불변 + 동일점수) | **T010**(동일점수) + **T022**(003 불변) |
| 2 | 6항목 집계·본인스코프·숨김제외·편향버킷 | **T013**(+T012) |
| 3 | 표본<5 sampleSufficient=false·분포null·카운트정상 | **T014**(+T011) |
| 4 | 추천 조회·저장·숨김 제외 0건 | **T020** |
| 5 | 콜드스타트 트렌드 fallback 비어있지 않음 | **T021**(+T019 분기) |
