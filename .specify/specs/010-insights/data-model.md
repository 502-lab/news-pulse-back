# Data Model: 010 인사이트 + 추천

## ★ 신규 영속 엔티티/테이블 = 0 (마이그레이션 없음)

010은 **기존 데이터의 온디맨드 집계**다. 신규 테이블·컬럼·마이그레이션이 없다(V18 없음). 아래는 **응답 표현(비영속 projection/DTO)** + 집계 쿼리 경로일 뿐 저장하지 않는다.

## 응답 표현 (비영속)

### InsightResponse (US1)
| 필드 | 표본 무관/조건 | 소스 |
|---|---|---|
| readCount | **항상** | 009 `article_event` distinct(VIEW, 숨김 제외) |
| bookmarkCount | **항상** | 003 `saved_articles` COUNT |
| sampleSufficient | **항상**(boolean) | readCount ≥ `minSampleSize`(5) |
| topCategory | 표본≥5만(else null) | 읽은 기사 `articles.category` 최빈 |
| categoryDistribution | 표본≥5만 | category별 비중 % |
| keywordDistribution | 표본≥5만 | 007 `article_keyword` GROUP BY |
| topOutlets | 표본≥5만 | `article_sources`→`sources.name` top-k |
| biasDistribution | 표본≥5만 | 006 `bias_analysis`(DONE) 진보/중립/보수 % |

- **표본 부족(readCount<5)**: `sampleSufficient=false`, 분포 필드 전부 null. 카운트는 실제 값.

### RecommendationResponse / RecommendedArticle (US2)
| 필드 | 설명 |
|---|---|
| items[] | 추천 기사 목록(미열람·미저장·비숨김) |
| RecommendedArticle.articleId·title·category·publishedAt | 기사 메타 |
| RecommendedArticle.reason | 추천 사유(관심사 매칭 / 트렌드 / 최근, 콜드스타트=트렌드) |
| coldStart | boolean(조회·관심사 0 → 트렌드 fallback 여부) |

## 집계 쿼리 경로 (읽기 전용, 신규 쿼리 메서드)

공통: 읽은 기사 = `article_event ae JOIN articles a ON a.id=ae.article_id AND a.admin_hidden_at IS NULL WHERE ae.account_id=:acc AND ae.event_type='VIEW'`.

1. `readCount` = `COUNT(DISTINCT ae.article_id)` (숨김 제외 변형 — 009 countDistinct에 JOIN 추가).
2. `bookmarkCount` = `COUNT(*) FROM saved_articles WHERE account_id=:acc`.
3. 카테고리 분포/최다 = `... GROUP BY a.category ORDER BY COUNT(DISTINCT a.id) DESC`.
4. 주요 언론사 = `... JOIN article_sources s ON s.article_id=a.id JOIN sources src ON src.id=s.source_id GROUP BY src.name ORDER BY ... LIMIT :k`.
5. 키워드 분포 = `... JOIN article_keyword ak ON ak.article_id=a.id GROUP BY ak.keyword ...`(007).
6. 편향 분포 = `... JOIN bias_analysis ba ON ba.article_id=a.id AND ba.status='DONE'` 버킷: `COUNT(*) FILTER (WHERE value BETWEEN -100 AND -34)`(진보)·`-33..33`(중립)·`34..100`(보수), `NULLIF(COUNT,0)`(006 미러).

**추천 후보 쿼리**: `articles WHERE published_at >= now()-INTERVAL ':days days' AND admin_hidden_at IS NULL AND NOT EXISTS(article_event 본인 VIEW) AND NOT EXISTS(saved_articles 본인)` → 후보를 `ArticleRelevanceScorer`(카테고리·키워드·최근성) + 트렌드 가중으로 정렬 → top N.
- **콜드스타트**: 조회·관심사 0 → 트렌드(`trend_keyword_slot` 상위) / 최근성 정렬 fallback.

## forward-note
- 대량 사용자 시 인사이트 **사전집계 스냅샷 테이블**(주간 배치) 후속. MVP는 온디맨드 0테이블.
- 임베딩 추천: `RecommendationEngine` 인터페이스 교체로 도입(스키마 변경 없음).
