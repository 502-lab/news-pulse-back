# Research: 010 인사이트 + 추천 — 설계 결정

모든 결정은 grep 실증(추정 금지). 확인된 표면은 맨 아래.

## D1 — 6항목 집계 쿼리 설계 + 쿼리 수 상한 (성능)

**Decision**: **항목별 분리 집계, 인사이트 1회 호출 = 최대 6 쿼리 상한**. 모두 `@Transactional(readOnly=true)` 단일 TX.
- 공통 기준: **읽은 기사 = `article_event` WHERE account_id=? AND event_type='VIEW'**(distinct article) + JOIN `articles` ON `admin_hidden_at IS NULL`(008 숨김 제외).
- 쿼리 매핑:
  1. **읽은수** = `COUNT(DISTINCT article_id)` (009 `countDistinctArticlesByAccount` 재사용, 숨김 필터는 010용 변형).
  2. **북마크수** = `COUNT(*) FROM saved_articles WHERE account_id=?` (003).
  3. **최다 카테고리 + 카테고리 분포** = 읽은 기사 JOIN `articles.category` GROUP BY category (1 쿼리로 top + 분포 동시).
  4. **주요 언론사** = 읽은 기사 JOIN `article_sources`→`sources` GROUP BY source.name ORDER BY cnt DESC LIMIT k.
  5. **관심 키워드 분포** = 읽은 기사 JOIN `article_keyword` GROUP BY keyword(007).
  6. **내 편향 분포** = 읽은 기사 JOIN `bias_analysis`(status='DONE') 버킷 집계(진보/중립/보수 %).
- **표본<5 단축**: 먼저 읽은수(distinct) 조회 → **5 미만이면 분포 쿼리(3·5·6 일부) skip + "표본 부족" 플래그** → 실제 쿼리 수 감소. 카운트(1·2)는 항상.

**Rationale**:
- per-user 집계라 각 쿼리가 `idx_article_event_debounce(account_id,...)` 적중 소량 → 6쿼리도 저비용. **통합(한 CTE로 읽은 기사 집합 + 모든 집계 JOIN)**은 카테고리·언론사·키워드·편향이 서로 다른 테이블로 FANOUT JOIN되어 행 폭증·집계 혼선(한 기사가 키워드 N개·소스 M개) 위험 → 분리가 명확·정확. "쿼리 수 6 상한"을 명시 계약으로 둔다(항목 추가 시 재평가).
- 모든 분포가 **동일 "읽은 기사" 부분집합** 기준이라, 각 쿼리는 `article_event` distinct 서브쿼리/JOIN을 반복하나 인덱스로 흡수.

**Alternatives considered**:
- 단일 거대 CTE/JSON 집계: 다대다 JOIN FANOUT으로 카운트 왜곡(한 기사가 소스 2·키워드 5면 distinct 처리 복잡) → 기각.
- 사전집계 스냅샷 테이블(주간 배치): 신규 테이블·배치 인프라 → MVP 과대, forward-note.

## D2 — 추천 인터페이스 격리 + FeedService 재활용 (★ 결합도)

**Decision**: `RecommendationEngine` 인터페이스(007 IssueClusterer 패턴) + `RuleBasedRecommender` 구현. FeedService 스코어링은 **공유 컴포넌트로 추출**해 재활용.
- **인터페이스**: `List<RecommendedArticle> recommend(UUID accountId, int limit)`. 임베딩 v2는 같은 인터페이스 다른 구현으로 교체(seam).
- **★ FeedService 재활용 = behavior-preserving 추출(복제 아님)**: `FeedService.computeScore(Article, userCategories, userKeywords, referenceTs)`(순수 함수, 부수효과 0 — grep 확인)를 **`ArticleRelevanceScorer @Component`로 이동**, `FeedRankingProperties` 주입. `FeedService`는 이 컴포넌트에 **순수 위임**.
  - **★ behavior-preserving 명시**: 메서드 본문(카테고리 매칭·키워드 매칭 상한·최근성 ratio 계산)·인자·연산 순서·반환을 **100% 보존**. FeedService의 상태 구성(주입 빈)·호출부 동일. 추출은 "위치 이동 + 위임"일 뿐 계산 변경 0.
  - **회귀 보장**: 기존 **003 피드 랭킹 테스트가 추출 후에도 동일 통과**(FeedService 행위 불변 증거) + 신규 단위 테스트로 `ArticleRelevanceScorer`가 추출 전 computeScore와 동일 점수 산출 확인. → 크라운주얼 #1.
  - **결합도**: RuleBasedRecommender → `ArticleRelevanceScorer`(공유) + 트렌드 소스(007) 의존. FeedService와 **직접 결합 안 함**(공유 컴포넌트 경유). 복제를 피해 003/010 스코어링 발산(DRY 위반) 차단. 트렌드 가중은 추천 고유라 scorer 밖에서 가산(FeedService 행위 불변 유지).
- **후보 풀**: 최근 `candidateDays`(14일) 비숨김 기사 − 이미 조회(`article_event NOT EXISTS`) − 저장(`saved_articles NOT EXISTS`). 후보 쿼리에서 제외 적용.
- **★ 콜드스타트 분기 정밀화** (개인화 데이터를 fallback이 덮지 않게):
  - **조회 0 AND 관심사 0**(둘 다 없음) → **트렌드/최근 인기 fallback**(`coldStart=true`).
  - **조회 0 + 관심사 있음** → **관심사 기반** 스코어링(트렌드 fallback 아님).
  - **조회 있음 + 관심사 0** → **조회 기반**(읽은 카테고리/키워드 프로파일) 스코어링.
  - **둘 다 있음** → 관심사 + 조회 프로파일 결합.
  - 즉 fallback은 **개인화 신호가 전무할 때만** 발동(FR-012, 빈 목록 금지). 관심사·조회 어느 하나라도 있으면 그 신호를 우선.

**Rationale**: 추출은 단일 출처 보장 + FeedService.computeScore가 이미 순수 함수라 추출 저위험. 인터페이스 격리로 임베딩 교체 시 호출부 불변. 트렌드 가중은 추천 고유라 scorer 밖에서 가산(FeedService 행위 불변 유지).

**Alternatives considered**:
- 복제(로직 카피): 003/010 스코어링 발산 위험 → 기각.
- RuleBasedRecommender가 FeedService 직접 호출: 피드 서비스에 추천 책임 누수·순환 위험 → 공유 컴포넌트로 분리.

## D3 — 가중치/임계 config (하드코딩 금지)

**Decision**: 신규 `@ConfigurationProperties(prefix="app.insights.recommendation")` `InsightsRecommendationProperties`:
- **전 항목 env 조정 가능(하드코딩 0)**: `categoryWeight=0.5`, `trendWeight=0.3`, `recencyWeight=0.2`(블렌드 가중), `candidateDays=14`, `minSampleSize=5`, `recommendLimit`(기본 N). 전부 `app.insights.recommendation.*`로 런타임 오버라이드.
- base 카테고리/키워드/최근성 **점수**는 기존 `FeedRankingProperties`(`app.feed.ranking`) 재사용(ArticleRelevanceScorer 경유). 010 props는 그 위의 **블렌드 가중 + 후보일수 + 표본임계**.
- **검증**: `RuleBasedRecommenderTest`가 props 값 변경 시 후보일수·가중치·임계가 반영됨을 단언(하드코딩 부재 증명).

**Rationale**: 007 클러스터링 임계·003 랭킹 점수가 이미 `@ConfigurationProperties`로 런타임 튜닝되는 패턴과 일관. 코드 하드코딩 금지(CLAUDE.md).

## D4 — 표본<5 응답 DTO 표현

**Decision**: `InsightResponse`에 **항상 반환되는 카운트**(readCount·bookmarkCount) + **표본 충분 시에만 채워지는 분포**(nullable) + **`sampleSufficient` boolean** 플래그.
- 읽은 고유 기사 < `minSampleSize`(5) → `sampleSufficient=false`, `topCategory`/`categoryDistribution`/`keywordDistribution`/`biasDistribution`/`topOutlets` = **null**(미산출). `readCount`·`bookmarkCount`는 실제 값.
- 클라이언트는 `sampleSufficient=false`면 "더 읽어보세요" UX 분기(UI는 범위 밖).

**Rationale**: null + 단일 플래그가 "표본 부족"을 명확·단순하게 전달(항목별 플래그 난립 회피). 006도 표본 부족 시 value null 패턴(BiasAnalysisService 최소표본 10건) 선례.

## D5 — "내 편향" 버킷 재사용 (006 정합)

**Decision**: 006 `BiasAnalysisRepository.aggregateSpectrum`의 버킷 경계 **진보[-100,-34]/중립[-33,33]/보수[34,100]**(grep 확인)를 그대로 미러링하되, **읽은 기사(article_event)로 스코프**한 신규 집계 쿼리. `NULLIF(COUNT,0)` division-by-zero 안전. **중립적 기술**(분포 %, 단정 라벨 금지 — D1 clarify).

**Rationale**: 동일 척도(−100~+100)·동일 버킷으로 006 전역 스펙트럼과 일관, 사용자 혼란 방지. status='DONE'만 집계(미완료 제외).

## 기존 표면 확인 결과 (grep verbatim)

- **FeedService.computeScore**: `double computeScore(Article article, List<String> userCategories, List<String> userKeywords, Instant referenceTs)` — `rankingProps.categoryMatchScore()` + 키워드 매칭(`keywordMatchScore`, 상한 5×) + 최근성(`recencyWindowHours`/`recencyMaxScore`). **순수 함수**(부수효과 0). → 추출 가능.
- **FeedRankingProperties**: `@ConfigurationProperties(prefix="app.feed.ranking")`, `categoryMatchScore()`·`recencyMaxScore()` 등. config 패턴 확립.
- **user_interests**(V4): `account_id UUID, category VARCHAR(50), UNIQUE(account_id, category)`. **follow_keywords**(V4): `account_id, keyword VARCHAR(100), type VARCHAR(20)`. → 추천 관심사 입력.
- **009 ArticleEventRepository**: `countDistinctArticlesByAccount(UUID)` + `findHistory(...)` + `insertViewDebounced`. event_type='VIEW'.
- **006 bias_analysis**: `value INTEGER`(−100~+100, 006 spec L15), `status` DONE/PENDING/FAILED. 버킷 진보[-100,-34]/중립[-33,33]/보수[34,100](BiasAnalysisRepository.aggregateSpectrum, NULLIF 안전).
- **003 saved_articles**(V9): `account_id, article_id, UNIQUE`. **001 article_sources**: `article_id, source_id → sources(id)`; `sources.name VARCHAR(255)`.
- **마이그레이션 최고 = V17**(009). → **신규 테이블 0이라 V18 없음**(Entity 변경 없음).
