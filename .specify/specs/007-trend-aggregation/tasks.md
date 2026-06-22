# Tasks: 트렌드 집계 엔진

**Input**: Design documents from `.specify/specs/007-trend-aggregation/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/openapi-patch.yaml, quickstart.md

**Tests**: 포함 — CLAUDE.md(Service 단위테스트 필수, Repository/통합 Testcontainers `BigmPostgresImage.NAME`, Controller @WebMvcTest) + Constitution IV.

**Build note**: 시스템 기본 JDK가 26으로 변경됨 — Gradle은 JDK 25 명시 지정 필요:
`-Dorg.gradle.java.installations.paths=/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home`

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완료 의존성 없음)
- **[Story]**: US1(Top5) / US2(집계 파이프라인) / US3(히트맵·워드클라우드) / US4(WoW) / US5(이슈)

---

## Phase 1: Setup (Shared Infrastructure)

- [ ] T001 contracts/openapi-patch.yaml의 5개 `/api/v1/trends/**` 경로·스키마를 news-pulse-spec openapi.yaml에 선반영 제안 (CLAUDE.md)
- [X] T002 [P] Lucene Nori 의존성 추가 in `build.gradle` — `implementation 'org.apache.lucene:lucene-analysis-nori:9.12.0'`. 빌드 resolve 확인(JDK25)
- [X] T003 [P] `app.scheduler.trend.*` / `app.trend.*` 설정 추가 in `src/main/resources/application.yaml` + `application-example.yaml` (interval-ms, cleanup-cron, slot-hours, top5-window-hours, retention-days, min-article-count, smoothing-k, extract-window-hours, summary-wait-hours, cooccurrence.min-edge-weight, cooccurrence.min-cluster-size — research R-007)
- [X] T004 [P] `TrendProperties` record 생성 in `src/main/java/com/newscurator/config/TrendProperties.java` (`@ConfigurationProperties(prefix="app.trend")` + 스케줄 분리 키) + AppConfig `@EnableConfigurationProperties` 등록
- [X] T005 [P] 커스텀 한국어 불용어 리소스 in `src/main/resources/trend/stopwords-ko.txt` (조사·일반어 시드)

---

## Phase 2: Foundational (Blocking Prerequisites)

⚠️ 모든 US가 의존. 완료 전 US 시작 불가.

- [X] T006 Flyway 마이그레이션 `V14__add_trend_aggregation.sql` in `src/main/resources/db/migration/` — data-model.md DDL 전체: article_keyword(PK article_id,term) + idx_article_keyword_term, trend_keyword_slot(PK slot_start,category,term) + idx_trend_slot_window + idx_trend_slot_category, issue_snapshot(clustering_method default CO_OCCURRENCE, keywords TEXT[], article_ids BIGINT[])
- [X] T007 [P] `ArticleKeyword` 엔티티 + `ArticleKeywordId` 복합키 in `src/main/java/com/newscurator/domain/` (article_id, term)
- [X] T008 [P] `TrendKeywordSlot` 엔티티 + `TrendKeywordSlotId` 복합키 in `src/main/java/com/newscurator/domain/` (slot_start, category, term, article_count)
- [x] T009 [P] `IssueSnapshot` 엔티티 in `src/main/java/com/newscurator/domain/` (clustering_method, delta, keywords String[], article_ids Long[] @JdbcTypeCode ARRAY)
- [X] T010 [P] `KeywordExtractor` 포트 인터페이스 in `src/main/java/com/newscurator/client/keyword/KeywordExtractor.java` (`Set<String> extractNouns(String)`)
- [X] T011 `NoriKeywordExtractor` 구현 in `src/main/java/com/newscurator/client/keyword/NoriKeywordExtractor.java` — KoreanTokenizer + KoreanPartOfSpeechStopFilter(NNG/NNP) + stopwords-ko.txt 필터 + 2자 이상, thread-safe(per-call/ThreadLocal Analyzer)
- [X] T012 [P] `ArticleKeywordRepository` in `src/main/java/com/newscurator/repository/` — `insertIgnore`(ON CONFLICT DO NOTHING native), 추출 대상 게이팅 SELECT(summary_status 게이트 + NOT EXISTS + 25h)
- [X] T013 [P] `TrendKeywordSlotRepository` in `src/main/java/com/newscurator/repository/` — 슬롯 UPSERT(native, data-model 집계 SQL), 윈도우 합산 조회(Top5/wordcloud/heatmap/WoW), 보존 DELETE
- [x] T014 [P] `IssueSnapshotRepository` in `src/main/java/com/newscurator/repository/` — `truncate`(native) + saveAll, findAll(delta DESC NULLS LAST)
- [X] T015 [US-shared] `NoriKeywordExtractorTest` 단위 in `src/test/java/com/newscurator/client/keyword/NoriKeywordExtractorTest.java` — 명사(NNG/NNP) 추출·불용어 제거·조사 제거·중복 제거 검증(컨테이너 불요)

**Checkpoint**: 마이그레이션·엔티티·repo·추출기 준비 — US 시작 가능

---

## Phase 3: User Story 2 — 트렌드 집계 파이프라인 (Priority: P1) 🎯 데이터 생산

**Goal**: 기사→키워드 추출(summary-race 게이팅)→슬롯 멱등 집계. US1/3/4/5의 데이터 기반.

**Independent Test**: 슬롯 내 기사 추출→trend_keyword_slot 저장, 재집계 시 article_count 불변(멱등) (quickstart Scenario 1·2).

- [X] T016 [US2] `TrendAggregationService` 생성 in `src/main/java/com/newscurator/service/TrendAggregationService.java` — `aggregate()`: ① 추출(게이팅 SELECT: COMPLETED / FAILED·1h경과PENDING / recent-PENDING skip → insertIgnore) ② 슬롯 UPSERT(date_trunc hour, COALESCE category 'OTHER', COUNT DISTINCT) ③ 로그(FR-014).
  - **BALANCED 본문 조회**: `summaryRepository.findByArticleIdAndDepth(articleId, SummaryDepth.BALANCED)`
  - **useSummary** = `summary_status='COMPLETED'` AND BALANCED 행 present AND `content` non-null·non-blank
  - `text = useSummary ? (title + " " + content) : title`  // 불만족 시 제목만 fallback (NPE/null 방어)
  - summaries.content는 NULL 가능(V1 schema) — Optional·blank 가드 필수
- [X] T017 [US2] `TrendAggregationScheduler` 생성 in `src/main/java/com/newscurator/scheduler/TrendAggregationScheduler.java` — `@ConditionalOnProperty(app.scheduler.enabled)` + MDC runId, `@Scheduled(fixedDelayString="${app.scheduler.trend.interval-ms:600000}")` → aggregate()
- [X] T018 [US2] `TrendAggregationServiceTest` 단위 in `src/test/java/com/newscurator/service/TrendAggregationServiceTest.java` (KeywordExtractor mock) — 게이팅 분기(COMPLETED/FAILED/PENDING 1h/recent skip), 제목+요약 vs 제목만 선택. **추가 분기**: summary_status='COMPLETED'이지만 (a) BALANCED 행 부재 (b) content=null (c) content=blank → 각각 **제목만 추출, NPE 없음** 단언
- [X] T019 [US2] 집계 멱등 통합 테스트 in `src/test/java/com/newscurator/service/TrendAggregationIT.java` (`BigmPostgresImage.NAME`) — 추출→슬롯 저장, 재집계 동일 article_count(멱등), summary-race 게이팅(recent-PENDING skip→COMPLETED 후 요약 포함) (quickstart Scenario 1·2)

**Checkpoint**: 트렌드 슬롯 데이터 생산. read US 기반 확보.

---

## Phase 4: User Story 1 — 지금 뜨는 키워드 Top5 (Priority: P1) 🎯 MVP

**Goal**: 최근 24h 급상승 Top5 공개 조회.

**Independent Test**: 슬롯 데이터 존재 시 Top5(term/count/deltaPct/isNew) 내림차순, 노이즈컷 적용, 빈 데이터 빈 목록 (quickstart Scenario 3).

- [X] T020 [P] [US1] `TrendKeywordResponse` record in `src/main/java/com/newscurator/dto/response/TrendKeywordResponse.java` (term, count, deltaPct[Double nullable], isNew) + @Schema
- [X] T021 [US1] `TrendQueryService` 생성 + `getTop5(category)` in `src/main/java/com/newscurator/service/TrendQueryService.java` — 24h 윈도우 SUM(article_count) GROUP BY term, min-article-count 필터, deltaPct=raw((cur-prev)/prev) prev=0→null+isNew, 정렬=평활비 (cur+1)/(prev+1) LIMIT 5
- [X] T022 [US1] `TrendController` 생성 + `GET /api/v1/trends/keywords/top5` in `src/main/java/com/newscurator/controller/TrendController.java` — public, @Tag/@Operation/@Parameter, ApiResponse.success
- [X] T023 [US1] SecurityConfig `/api/v1/trends/**` GET permitAll + 정당화 코멘트(Constitution VI) in `src/main/java/com/newscurator/config/SecurityConfig.java` (`.anyRequest().authenticated()` 앞)
- [X] T024 [P] [US1] `TrendControllerTest` (@WebMvcTest standalone, service mock) in `src/test/java/com/newscurator/controller/TrendControllerTest.java` — Top5 200, 카테고리 필터, 빈 목록
- [X] T025 [US1] Top5 집계 정확성 IT in `src/test/java/com/newscurator/service/TrendTop5IT.java` (`BigmPostgresImage.NAME`) — 노이즈컷(2건 미만 제외), deltaPct/isNew, 정렬

**Checkpoint**: MVP — 사용자가 "지금 뜨는 키워드 Top5" 확인 가능.

---

## Phase 5: User Story 3 — 히트맵·워드클라우드 (Priority: P2)

**Goal**: 시간슬롯×카테고리 히트맵 + 워드클라우드 데이터.

**Independent Test**: 다수 슬롯·카테고리 집계 존재 시 격자/가중치 목록 반환, 빈 윈도우 빈 응답 (quickstart Scenario 5).

- [X] T026 [P] [US3] `WordcloudItemResponse`(term, weight) + `HeatmapCellResponse`(slotStart, category, intensity) records in `src/main/java/com/newscurator/dto/response/` + @Schema
- [X] T027 [US3] `TrendQueryService.getWordcloud(windowHours)` + `getHeatmap(windowHours)` in `TrendQueryService.java` — 워드클라우드 term/weight, 히트맵 slot×category SUM 격자
- [X] T028 [US3] `GET /api/v1/trends/wordcloud` + `GET /api/v1/trends/heatmap` in `TrendController.java` — public, Swagger
- [X] T029 [US3] 컨트롤러 테스트 추가 in `TrendControllerTest` — wordcloud/heatmap 200, 빈 윈도우
- [X] T030 [US3] 히트맵/워드클라우드 IT in `src/test/java/com/newscurator/service/TrendHeatmapWordcloudIT.java` (`BigmPostgresImage.NAME`) — 격자·가중치 정확성

**Checkpoint**: 시각 탐색 데이터 제공.

---

## Phase 6: User Story 4 — WoW 급상승 (Priority: P2)

**Goal**: 주간 대비 급상승 + 작은 분모 가드(평활비, isNew).

**Independent Test**: 두 주간 분포에서 deltaPct 정확·평활비 정렬·prev=0 isNew·cur<2 제외 (quickstart Scenario 4).

- [X] T031 [US4] `TrendQueryService.getWow()` in `TrendQueryService.java` — 이번주 vs 지난주 term SUM 비교, prev=0→deltaPct null+isNew, 정렬 평활비 (cur+1)/(prev+1), cur<min-article-count 제외, smoothing-k env
- [X] T032 [US4] `GET /api/v1/trends/wow` in `TrendController.java` — public, Swagger (TrendKeywordResponse 재사용)
- [X] T033 [US4] WoW 가드 단위 테스트 in `src/test/java/com/newscurator/service/WowGuardTest.java` — prev=0 null+isNew, 평활비 정렬(1→2 vs 50→60 순서), cur<2 제외, 분모0 무에러
- [X] T034 [US4] 컨트롤러 테스트 추가 in `TrendControllerTest` — wow 200, isNew 직렬화

**Checkpoint**: 추세적 급상승 제공.

---

## Phase 7: User Story 5 — 이슈 클러스터링 (Priority: P3)

**Goal**: co-occurrence 이슈 재산출(전량 교체), 대표 키워드 3 + 관련 기사.

**Independent Test**: 동시출현 공유 기사군→이슈 묶음, 재집계 시 스냅샷 전량 교체, over-merge 방지 (quickstart Scenario 6).

- [x] T035 [P] [US5] `IssueClusterer` 포트 + `DerivedIssue`/`IssueClusterContext` records in `src/main/java/com/newscurator/service/trend/`
- [x] T036 [US5] `CoOccurrenceIssueClusterer` 구현 in `src/main/java/com/newscurator/service/trend/CoOccurrenceIssueClusterer.java` — 동시출현 그래프(min-edge-weight≥2), 연결성분 묶음(min-cluster-size≥2), 대표 키워드 3 + article_ids + delta(멤버 WoW)
- [x] T037 [P] [US5] `IssueResponse`(keywords[], articleIds[], delta, clusteringMethod) record in `src/main/java/com/newscurator/dto/response/`
- [x] T038 [US5] `TrendAggregationService`에 이슈 재산출 연결 — aggregate() Phase3: clusterer.cluster() → IssueSnapshotRepository.truncate() + saveAll(clusteringMethod='CO_OCCURRENCE'), 단일 TX clean cutover
- [x] T039 [US5] `TrendQueryService.getIssues()` + `GET /api/v1/trends/issues` in TrendQueryService/TrendController — findAll delta DESC NULLS LAST, public
- [x] T040 [US5] `CoOccurrenceIssueClustererTest` 단위 in `src/test/java/com/newscurator/service/trend/CoOccurrenceIssueClustererTest.java` — over-merge 방지(우연 1회 동시출현 미연결), 강한 군집 묶임, 대표 키워드 3
- [x] T041 [US5] 이슈 재산출 IT in `src/test/java/com/newscurator/service/TrendIssueIT.java` (`BigmPostgresImage.NAME`) — 스냅샷 생성, 재집계 전량 교체(이전 row 소멸), clustering_method=CO_OCCURRENCE

**Checkpoint**: 이슈 단위 트렌드 제공.

---

## Phase 8: Polish & Cross-Cutting

- [ ] T042 [P] 보존 정리 스케줄 — `TrendAggregationScheduler.cleanup()` `@Scheduled(cron="${app.scheduler.trend.cleanup-cron}")` → 90일 경과 슬롯 DELETE (FR-009)
- [ ] T043 [P] 공개 접근 통합 테스트 in `src/test/java/com/newscurator/integration/TrendPublicAccessIT.java` (@SpringBootTest RANDOM_PORT, 실 SecurityConfig) — JWT 없이 5개 trends 엔드포인트 전부 200 (FR-013, quickstart Scenario 7)
- [ ] T044 [P] 트렌드 read 캐싱 — Spring `ConcurrentMapCacheManager` 단기 TTL @Cacheable(R-006, Redis 미사용). 캐시 무효화 주기 = 집계 주기
- [ ] T045 [P] 보존 정리 IT in `src/test/java/com/newscurator/service/TrendRetentionIT.java` — 90일 경과 슬롯 삭제, 최신 영향 없음 (quickstart Scenario 8)
- [ ] T046 CHANGELOG.html 항목 추가 (tag-feature, stats 갱신, 결정 이유 — re-derive·summary-race 게이팅·over-merge 가드)
- [ ] T047 quickstart 8개 시나리오 수동 검증 — Docker 기동, Nori 실추출 1건 확인(또는 "런타임/배포 시 검증" 명시)
- [ ] T048 OpenApiSpecExportTest 통과 확인 → sync-openapi로 news-pulse-spec 반영
- [ ] T049 ADR 작성 in `.specify/specs/007-trend-aggregation/adr/ADR-001-keyword-extraction-clustering.md` — Nori 선택·co-occurrence MVP·re-derive 결정 (CLAUDE.md 분석 로직 결정 기록)

---

## Dependencies & Execution Order

- **Setup(P1)**: 의존 없음
- **Foundational(P2)**: Setup 후. 모든 US 차단. T006(V14)→T007~T009(엔티티), T010→T011(추출기), T012~T014(repo)
- **US2(Phase 3)**: Foundational 후. 데이터 생산 — US1/3/4/5의 선행
- **US1(Phase 4)**: US2 데이터 필요(단위는 mock 독립). MVP
- **US3(Phase 5) / US4(Phase 6)**: US2 데이터 필요, 서로 독립 병렬
- **US5(Phase 7)**: US2 + 추출기 필요. T038은 TrendAggregationService(T016) 수정
- **Polish(Phase 8)**: 전 US 후

### 병렬 기회
- Setup: T002·T003·T004·T005
- Foundational: T007·T008·T009·T010 병렬, T012·T013·T014 병렬(T006 후), T015 병렬
- US1: T020·T024 / US3: T026 / US5: T035·T037
- US3 ↔ US4: US2 후 병렬
- Polish: T042·T043·T044·T045 병렬

---

## Implementation Strategy

### MVP (최소 배포 단위)
**Phase 1+2+US2+US1** = 키워드 추출·슬롯 집계 + Top5 공개 조회. "지금 뜨는 키워드 Top5"(P1 핵심) 달성.

### 증분 전달
1. **1차 MVP**: Setup→Foundational→US2→US1 (Top5)
2. **2차**: US3(히트맵/워드클라우드) + US4(WoW) 병렬
3. **3차**: US5(이슈 클러스터링)
4. **4차**: Polish(보존정리·공개접근IT·캐싱·CHANGELOG·OpenAPI·ADR)

### Open Items 반영
- OI-1(표시/정렬): T021/T031 서버 정렬=평활비, 응답=deltaPct(raw)+isNew. UX는 Jace
- OI-2(평활상수): T003 smoothing-k env
- OI-5(SC-002 p95): Jace TODO — 측정코드는 구현하되 임계 판정 보류
- OI-6(rate-limit): 미구현, 인덱스 read 우선(향후)
- OI-7(Nori 버전): T002에서 9.12.0 resolve·동작 확인
