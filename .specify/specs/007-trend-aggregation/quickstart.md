# Quickstart Validation Guide: 007 트렌드 집계 엔진

**Date**: 2026-06-22

구현 검증 시나리오. 구현/테스트 전문은 포함하지 않음.

## Prerequisites

1. `docker compose up -d postgres`
2. V14 적용 확인: `./gradlew flywayInfo` → V14 PENDING/SUCCESS
3. `application-local.yaml`에 `app.scheduler.trend.*` / `app.trend.*` 설정(R-007)
4. JDK 25 toolchain (`-Dorg.gradle.java.installations.paths=/opt/homebrew/Cellar/openjdk/25.0.2/...`)

## Scenario 1 — 키워드 추출(Nori) (FR-001)

기사 제목+요약으로 KeywordExtractor 실행 → article_keyword에 명사만 저장.
```sql
SELECT article_id, term FROM article_keyword ORDER BY article_id LIMIT 20;
```
기대: 조사·일반어 제거된 NNG/NNP만, 기사당 term 중복 없음.

## Scenario 2 — 슬롯 집계 멱등 (FR-002/003)

집계 스케줄러 1회 실행 → trend_keyword_slot 채워짐. 재실행해도 article_count 불변(멱등).
```sql
SELECT slot_start, category, term, article_count FROM trend_keyword_slot ORDER BY slot_start DESC LIMIT 20;
-- 재집계 후 동일 슬롯 article_count 동일 검증
```

## Scenario 3 — Top5 (FR-004, US1)

```bash
curl http://localhost:8080/api/v1/trends/keywords/top5            # 인증 없이 200
curl http://localhost:8080/api/v1/trends/keywords/top5?category=POLITICS
```
기대: term/count/deltaPct/isNew 5개 이하, 노이즈컷(기사 2건 미만) 제외, 데이터 없으면 빈 목록.

## Scenario 4 — WoW 작은 분모 가드 (FR-007, US4)

지난주 0건/1건 → 이번주 2건 키워드 주입 후:
```bash
curl http://localhost:8080/api/v1/trends/wow
```
기대: prev=0은 `deltaPct=null, isNew=true`. 정렬은 평활비 `(cur+1)/(prev+1)`. cur<2 제외(분모 0 에러 없음).

## Scenario 5 — 히트맵/워드클라우드 (FR-006, US3)

```bash
curl http://localhost:8080/api/v1/trends/heatmap?windowHours=24
curl http://localhost:8080/api/v1/trends/wordcloud?windowHours=24
```
기대: 히트맵=slotStart×category×intensity 격자, 워드클라우드=term/weight. 빈 윈도우면 빈 배열.

## Scenario 6 — 이슈 재산출 clean cutover (FR-010/012, US5)

동시출현 키워드 공유 기사군 주입 → 집계 → issue_snapshot 생성. 재집계 시 스냅샷 전량 교체(이전 row 사라짐).
```bash
curl http://localhost:8080/api/v1/trends/issues   # keywords[3], articleIds[], delta, clusteringMethod=CO_OCCURRENCE
```

## Scenario 7 — 공개 인증 (FR-013)

모든 `/api/v1/trends/**`가 JWT 없이 200. (006 bias는 401이었던 것과 대조 — 명시 permitAll)

## Scenario 8 — 보존 정리 (FR-009)

90일 경과 slot_start 행 주입 후 cleanup 실행 → 해당 행 삭제, 최신 영향 없음.

## 통합 테스트 구조 (참고)

- `NoriKeywordExtractorTest`: 명사 추출·불용어 단위(컨테이너 불요)
- `TrendAggregationServiceTest`/IT(`BigmPostgresImage`): 멱등 집계·재집계 동일성
- `CoOccurrenceIssueClustererTest`: 동시출현 묶음·대표 키워드 3
- `TrendControllerTest`(@WebMvc standalone) + `TrendPublicAccessIT`(@SpringBootTest, 실 SecurityConfig permitAll 200)
- `WowGuardTest`: prev=0 null+isNew, 평활비 정렬, cur<2 제외
