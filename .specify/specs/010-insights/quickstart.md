# Quickstart: 010 인사이트 + 추천 검증

전제: 001~009 머지됨. Docker(Testcontainers `BigmPostgresImage`). **신규 마이그레이션 없음**(V17까지). 인증 USER 토큰 2개(A·B) + 기사/요약/편향/소스/키워드 시드.

## 검증 시나리오

1. **6항목 집계(US1, SC-001)**: A가 여러 카테고리·언론사 기사를 조회(009) + 일부 북마크(003) → `/api/v1/me/insights` = 읽은수(distinct)·북마크수·최다카테고리·카테고리/키워드 분포·주요언론사·편향분포가 정확.
2. **본인 스코프(SC-001)**: B 토큰으로 조회 → A 데이터 0 반영.
3. **★ 표본 부족(D3, SC-004)**: 읽은 고유 기사 < 5인 사용자 → `sampleSufficient=false`, 분포 필드 null, readCount/bookmarkCount는 실제값. NPE·분모0 없음.
4. **숨김 제외**: admin_hidden 기사를 읽은 경우에도 인사이트 집계·추천에서 제외.
5. **편향 중립 기술(D1, SC-005)**: biasDistribution = 진보/중립/보수 %(006 DONE분, [-100,-34]/[-33,33]/[34,100] 버킷), 단정 라벨 없음. 미완료 편향 기사는 제외.
6. **추천 제외(US2, SC-003)**: A가 일부 기사 조회·저장 → `/api/v1/me/recommendations`에 이미 조회·저장·숨김 기사 **0건** 포함.
7. **추천 랭킹(US2)**: 관심사 매칭 + 트렌드 + 최근성 가중 정렬, 후보=최근 14일.
8. **★ 콜드스타트(D2, SC-004)**: 조회이력·관심사 둘 다 없는 신규 사용자 → 추천이 트렌드/최근 fallback으로 **비어있지 않음**(coldStart=true).
9. **쿼리 수 상한(D1)**: 인사이트 1회 = 최대 6 집계 쿼리(표본<5면 분포 skip으로 감소).

## 크라운주얼 테스트

- `InsightAggregationIT`(실 PG): 6항목 본인 스코프 정확 + 숨김 제외 + 편향 버킷.
- `InsightSampleThresholdIT`(실 PG): 읽은 기사 <5 → sampleSufficient=false·분포 null·카운트 정상(NPE 0).
- `RecommendationExclusionIT`(실 PG): 조회·저장·숨김 제외 0건 보장.
- `RecommendationColdStartIT`(실 PG): 조회·관심사 0 → 트렌드 fallback 비어있지 않음.
- `ArticleRelevanceScorerTest`(단위): 추출된 스코어러가 FeedService와 동일 점수(행위 보존) + 추천 트렌드 가중.
- `RuleBasedRecommenderTest`(단위): 가중치 config 반영·콜드스타트 분기.
- 회귀: `FeedService` 003 피드 테스트 GREEN 유지(computeScore 추출 후).

## 런타임/배포 시 검증 이연
- 실 사용자 인사이트 체감 응답성(SC-002 정량)·대량 시 사전집계 필요성.
- 추천 품질(클릭률) — 런타임 관측 후 가중치 튜닝(config).

## 검증 상태 (2026-06-24, US1·US2 구현 완료)
실 PG(BigmPostgresImage) 자동 검증:
- 추출 회귀 → ArticleRelevanceScorerTest(동일점수) + FeedServiceTest 랭킹 단언 불변 GREEN
- 6항목/스코프/숨김/편향버킷 → InsightAggregationIT
- 표본<5 → InsightSampleThresholdIT
- 추천 제외 → RecommendationExclusionIT(조회·저장·숨김 0건 + fresh 포함)
- 콜드스타트 4분기 → RuleBasedRecommenderTest(단위) + RecommendationColdStartIT(조회·관심사 0/관심사만/조회만)
- 가중치 config → RuleBasedRecommenderTest(props 변경→순위 변화)
런타임 이연: 인사이트 p95·추천 클릭률 튜닝.
