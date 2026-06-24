# ADR-001: 인사이트 온디맨드 집계 & 룰베이스 추천 설계 (010)

**Status**: Accepted
**Date**: 2026-06-24
**Feature**: 010 인사이트 + 추천

---

## Decision 1 — 온디맨드 집계, 신규 테이블 0
인사이트 6항목은 기존 데이터(009 article_event·003 saved_articles·001 articles/sources·006 bias_analysis·007 article_keyword) 위에서 **온디맨드 GROUP BY**로 산출. 신규 영속 테이블·마이그레이션 없음(V18 없음).

**Rationale**: per-user 소량 집계라 인덱스 적중 저비용. 008 ErrorLog(기존 FAILED 집계 재활용)와 동일 철학. 대량 사용자 시 주간 스냅샷 사전집계는 forward-note.

## Decision 2 — 항목별 분리 쿼리, 6 상한 (FANOUT 회피)
각 항목 분리 native 쿼리, 인사이트 1회 = 최대 6쿼리. **단일 CTE 통합을 기각**: 카테고리·언론사·키워드·편향이 서로 다른 테이블 다대다 JOIN → FANOUT 행 폭증/카운트 왜곡. 언론사·카테고리는 `COUNT(DISTINCT a.id)`로 기사 단위 distinct(부풀림 0). 공통 기준 = 읽은 기사(article_event VIEW) + `admin_hidden_at IS NULL`.

## Decision 3 — behavior-preserving 추출(ArticleRelevanceScorer)
`FeedService.computeScore`(순수 함수)를 공유 `ArticleRelevanceScorer @Component`로 **이동(복제 아님)**, FeedService는 위임. **증거**: FeedServiceTest 랭킹 단언(T1·T3·T4) 수정 없이 GREEN(생성자 배선 1줄만 조정). 복제 회피로 003/010 스코어링 발산 차단.

## Decision 4 — RecommendationEngine seam + 콜드스타트 4분기
추천을 `RecommendationEngine` 인터페이스(007 IssueClusterer 패턴) 뒤로 격리. 임베딩 v2는 교체로 도입. RuleBasedRecommender = ArticleRelevanceScorer(카테고리·키워드·최근성) + 트렌드(007) 가중. **★ 콜드스타트는 조회0 AND 관심사0일 때만 트렌드 fallback** — 조회0+관심사有→관심사 / 조회有+관심사0→조회 프로파일 / 둘다→결합. 개인화 신호가 fallback에 덮이지 않음(F3).

## Decision 5 — 가중치 config(하드코딩 0)
가중치 0.5/0.3/0.2·candidateDays 14·minSampleSize 5 = `@ConfigurationProperties(app.insights.recommendation)`(007 임계 패턴). 검증: RuleBasedRecommenderTest가 props 변경→순위 변화 단언.

## Decision 6 — 편향 중립 분포 + 표본<5
"내 편향" = 006 `aggregateSpectrum` 버킷(진보[-100,-34]/중립[-33,33]/보수[34,100]) 미러, 읽은 기사 status='DONE'만 스코프, NULLIF 안전. **중립 기술**(단정 라벨 없음). 읽은 고유 기사 <5면 sampleSufficient=false + 분포 null(카운트는 항상). NPE·분모0 없음.

## 런타임/배포 시 검증 이연
- 실 사용자 인사이트 p95(SC-002)·대량 시 사전집계. 추천 클릭률 관측 후 가중치 튜닝(config).
- 후속: 평균읽기시간(read-tracking P2 dwell), 임베딩 추천, 사전집계 배치.
