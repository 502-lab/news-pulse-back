# Implementation Plan: 010 인사이트 + 추천 — 개인 소비 리포트 & 놓친 기사 추천

**Branch**: `feat/010-insights` | **Date**: 2026-06-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `.specify/specs/010-insights/spec.md`

## Summary

기존 데이터(009 조회·003 북마크·006 편향·007 키워드/트렌드·001 언론사) 위에서 **온디맨드 집계**로 개인 소비 리포트 6항목(US1, P1)과 **룰베이스 놓친 기사 추천**(US2, P2)을 제공한다. **신규 테이블 0**(V18 없음). 추천은 교체 가능한 `RecommendationEngine` 인터페이스 뒤로 격리(007 IssueClusterer 패턴), `FeedService.computeScore`를 공유 컴포넌트로 **추출**해 재활용 + 트렌드 가중. 가중치·후보일수·표본임계는 config. 평균읽기시간(P2 dwell)·임베딩·사전집계 배치는 제외.

## Technical Context

**Language/Version**: Java 25 / Spring Boot 4.x

**Primary Dependencies**: Spring Data JPA, Spring Security(JWT), PostgreSQL, SpringDoc OpenAPI. **신규 외부 의존 없음**(BE 집계만).

**Storage**: PostgreSQL — **신규 테이블 없음**. 읽기 전용 집계: 009 `article_event`·003 `saved_articles`·001 `articles/sources/article_sources`·006 `bias_analysis`·007 `article_keyword`/`trend_keyword_slot`.

**Testing**: Mockito 단위(서비스/추천), Testcontainers `BigmPostgresImage`(집계 쿼리·통합/인가)

**Project Type**: 단일 백엔드 web-service, 3계층

**Performance Goals**: 인사이트 1회 호출 = **최대 6 집계 쿼리 상한**(per-user, 인덱스 적중 저볼륨). 추천 = 후보 14일 비숨김 - 조회 - 저장 스코어링 1회.

**Constraints**: 모든 집계 `article_event`(VIEW) distinct 기준 + `admin_hidden_at IS NULL` 제외 + 편향은 `bias_analysis.status='DONE'`만. 본인 스코프. 표본<5 분포 미산출(카운트는 항상).

**Scale/Scope**: per-user 소량 집계. 신규 테이블 0이라 대량 사용자 시 사전집계 스냅샷은 forward-note.

## Constitution Check

- **3계층**: Controller(`InsightController`)·Service(`InsightService` 집계, `RecommendationEngine`/`RuleBasedRecommender`)·Repository(기존 + 신규 집계 쿼리). ✅
- **ApiResponse 래퍼·RFC7807·Swagger·openapi 선반영**: 신규 `/me/insights`·`/me/recommendations`에 적용. ✅
- **신규 테이블 0 → migration 없음**(Entity 변경 없음, 읽기 전용 집계만). ✅
- **외부 API 無**: fallback 불필요. ✅
- **config 하드코딩 금지**: 가중치·후보일수·표본임계 = `@ConfigurationProperties`(007 트렌드 임계 패턴). ✅
- **테스트**: 서비스 단위 + 집계 Testcontainers + 인가/본인스코프 IT + 콜드스타트/표본부족 IT. ✅
- **성능 선보고**: 인사이트 6쿼리 상한을 research D1에 명시(통합 vs 분리 트레이드오프). ✅

**위반 없음** → Phase 0 진행.

## Project Structure

```text
.specify/specs/010-insights/
├── plan.md · research.md · data-model.md · quickstart.md
├── contracts/openapi-patch.yaml   # /me/insights · /me/recommendations
└── tasks.md (/speckit-tasks)

src/main/java/com/newscurator/
├── controller/InsightController.java              # 신규: GET /api/v1/me/insights · /me/recommendations
├── service/
│   ├── InsightService.java                        # 신규: 6항목 온디맨드 집계 + 표본<5 분기
│   ├── ArticleRelevanceScorer.java                # ★ FeedService.computeScore 추출(공유 컴포넌트)
│   └── recommendation/
│       ├── RecommendationEngine.java              # 신규 인터페이스(seam)
│       └── RuleBasedRecommender.java              # 신규: scorer + 트렌드 + 콜드스타트
├── service/FeedService.java                       # 변경: computeScore → ArticleRelevanceScorer 위임(행위 보존)
├── config/InsightsRecommendationProperties.java   # 신규 @ConfigurationProperties(가중치·14일·임계5)
├── repository/InsightAggregationRepository.java    # 신규: 6항목 집계 + 추천 후보 쿼리
└── dto/response/                                  # InsightResponse·RecommendationResponse(비영속)
```

**Structure Decision**: 기존 3계층 편입. `FeedService.computeScore` 추출만 기존 코드 변경 = **behavior-preserving refactor**(시그니처·계산·순서 100% 보존, FeedService는 순수 위임). 003 피드 랭킹 테스트가 추출 전후 동일 통과로 회귀 가드(크라운주얼 #1). 나머지 신규. **신규 테이블·마이그레이션 없음**. 콜드스타트는 개인화 신호(조회/관심사) 전무 시에만 트렌드 fallback — 하나라도 있으면 그 신호 우선(research D2). 추천 가중치·candidateDays·minSampleSize는 전부 `@ConfigurationProperties`(env 조정, 하드코딩 0).

## Complexity Tracking

> 위반 없음 — 비움.
