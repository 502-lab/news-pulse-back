# Implementation Plan: 007 트렌드 집계 엔진

**Branch**: `007-trend-aggregation` | **Date**: 2026-06-22 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `.specify/specs/007-trend-aggregation/spec.md`

---

## Summary

수집 기사(001 시계열)에서 Lucene Nori로 한국어 명사를 추출(`KeywordExtractor` 포트)하여 `article_keyword`에 저장하고, (1시간 슬롯 × 카테고리 × term) 단위로 `trend_keyword_slot`에 멱등 집계한다. 공개 read API(Top5/히트맵/워드클라우드/WoW/이슈)는 저장된 집계를 인덱스 read로 서빙한다(on-the-fly 재계산 없음, Redis 미사용). 이슈는 **re-derive**(OI-4) — co-occurrence 클러스터러(`IssueClusterer` 포트)가 매 집계마다 `issue_snapshot`을 전량 교체한다. WoW는 평활비 `(cur+1)/(prev+1)` 정렬 + prev=0 신규(isNew) 가드. 집계 스케줄러는 default 10분(env), 보존 90일.

---

## Technical Context

**Language/Version**: Java 25 · Spring Boot 4.x · Spring Data JPA(Hibernate 6)

**Primary Dependencies**:
- **Lucene Nori** (`org.apache.lucene:lucene-analysis-nori:9.12.0`) — 신규. 한국어 형태소(명사 NNG/NNP) 추출. 순수 JVM.
- PostgreSQL 17 — 3개 신규 테이블(V14), TEXT[]/BIGINT[] 배열, date_trunc 슬롯
- Flyway — V14
- SpringDoc OpenAPI, Testcontainers(BigmPostgresImage)

**Storage**: `article_keyword`, `trend_keyword_slot`, `issue_snapshot` (V14). 기사 테이블 무변경.

**Testing**: JUnit5, Testcontainers, @WebMvcTest(standalone), @SpringBootTest(RANDOM_PORT, 공개접근 IT)

**Target Platform**: Linux EC2 단일 인스턴스(fixedDelay 스케줄러, scale-out 시 ShedLock)

**Performance Goals**: SC-001 Top5 체감 1초 / SC-002 p95 ≤ 3s [Jace TODO: 측정조건·동시요청 수]

**Constraints**: Redis 미사용(저장 집계 + 인덱스 read, 규모 시 matview) · 저볼륨(하루 ~61건) 캘리브레이션

**Scale/Scope**: 슬롯 = 시간당 1행×카테고리×term. 저볼륨에선 슬롯당 희소.

---

## Constitution Check

| Gate | 원칙 | 상태 | 비고 |
|------|------|------|------|
| I 레이어드 | Controller→Service→Repository | PASS | TrendController→TrendQueryService/TrendAggregationService→Repository |
| II Entity 비노출 | DTO record | PASS | TrendKeyword/Wordcloud/Heatmap/Issue Response |
| III 응답 포맷 | ApiResponse<T> | PASS | |
| IV 테스트 필수 | 단위 + Testcontainers IT | PASS | 추출/집계/클러스터링/공개접근 |
| V Flyway | V14 | PASS | data-model DDL |
| VI 보안 기본값 | 공개 엔드포인트 명시 permitAll + 코멘트 | PASS | `/api/v1/trends/**` permitAll(비민감 전역집계) |
| VII 멱등성 | article_keyword PK / trend_keyword_slot PK + UPSERT | PASS | 재실행·중첩 안전 |
| 구조적 로깅 | FR-014 배치 로그 | PASS | MDC runId |
| 외부 API 복원력 | 외부 호출 없음(Nori 로컬) | N/A | Gemini/네트워크 의존 없음 |
| 신규 의존성 | Lucene Nori | 주의 | Complexity Tracking 기재 |

---

## Project Structure

```text
src/main/java/com/newscurator/
├── client/keyword/
│   ├── KeywordExtractor.java          (포트)
│   └── NoriKeywordExtractor.java       (Lucene Nori 구현)
├── domain/
│   ├── ArticleKeyword.java  (+ ArticleKeywordId 복합키)
│   ├── TrendKeywordSlot.java (+ TrendKeywordSlotId)
│   └── IssueSnapshot.java
├── repository/
│   ├── ArticleKeywordRepository.java
│   ├── TrendKeywordSlotRepository.java
│   └── IssueSnapshotRepository.java
├── service/
│   ├── TrendAggregationService.java    (추출→집계→이슈 재산출, 멱등)
│   ├── TrendQueryService.java          (Top5/heatmap/wordcloud/WoW/issues read)
│   └── trend/
│       ├── IssueClusterer.java         (포트)
│       ├── CoOccurrenceIssueClusterer.java
│       └── DerivedIssue.java / IssueClusterContext.java
├── scheduler/
│   └── TrendAggregationScheduler.java  (@Scheduled interval-ms + cleanup-cron)
├── controller/
│   └── TrendController.java            (public read)
├── config/
│   └── TrendProperties.java
└── dto/response/
    ├── TrendKeywordResponse.java
    ├── WordcloudItemResponse.java
    ├── HeatmapCellResponse.java
    └── IssueResponse.java

src/main/resources/
├── db/migration/V14__add_trend_aggregation.sql
└── trend/stopwords-ko.txt

수정: build.gradle(+Nori), application.yaml/-example(+app.scheduler.trend/app.trend),
      SecurityConfig(+/api/v1/trends/** permitAll + 코멘트), AppConfig(+TrendProperties)
```

**Structure Decision**: Spring Boot 단일 프로젝트, CLAUDE.md 패키지 레이아웃 준수. 포트(KeywordExtractor/IssueClusterer)는 004/005/006 격리 패턴 재사용.

---

## Implementation Details

### 1. V14 Migration / 2. 데이터 모델 / 3. 멱등 흐름
→ [data-model.md](./data-model.md) 참조 (DDL·포트·집계 SQL verbatim).

### 4. KeywordExtractor (Nori)
- `KoreanTokenizer` + `KoreanPartOfSpeechStopFilter`로 NNG/NNP만 유지, 커스텀 불용어(`stopwords-ko.txt`) 추가 필터, term 2자 이상.
- 빈 텍스트/추출 0건은 빈 Set. 분석기 thread-safe 주의(per-call Analyzer 또는 ThreadLocal).
- **summary-race 게이팅**(data-model 추출 흐름 참조): COMPLETED=제목+요약 / FAILED·1h경과 PENDING=제목만 / 1h이내 PENDING=skip(다음 run). NOT EXISTS로 미보유 기사만, article_keyword PK 멱등.

### 4b. CoOccurrenceIssueClusterer — over-merge 방지 임계
- **edge 임계** `app.trend.cooccurrence.min-edge-weight`(default 2): 두 키워드가 **2개 이상 기사에서 함께** 등장해야 edge 생성. 1회 동시출현(우연)은 연결하지 않아 연결성분 over-merge 방지.
- **min 클러스터 크기** `app.trend.cooccurrence.min-cluster-size`(default 2 keywords / 2 articles): 그 미만은 이슈로 승격하지 않음(단발 노이즈 제외).
- 대표 키워드 3개 = 클러스터 내 동시출현 가중치 상위 3. delta = 멤버 키워드 WoW 집계.
- 구현 테스트로 over-merge/under-merge 검증(CoOccurrenceIssueClustererTest): 우연 1회 동시출현이 거대 단일 이슈로 합쳐지지 않음, 강한 동시출현 군집은 묶임.

### 5. 집계 스케줄러
- `@Scheduled(fixedDelayString="${app.scheduler.trend.interval-ms:600000}")` → `aggregate()`. MDC runId, 시작/종료/건수 log.info(FR-014).
- `@Scheduled(cron="${app.scheduler.trend.cleanup-cron}")` → 90일 경과 슬롯 DELETE.
- `@ConditionalOnProperty(app.scheduler.enabled)`.

### 6. 조회 (read, permitAll)
- `TrendQueryService`: trend_keyword_slot 윈도우 집계 쿼리(SUM(article_count) GROUP BY term), deltaPct(raw) + 평활비 정렬, WoW 2주 비교. issue_snapshot 조회.
- 캐싱: Spring 인메모리 `ConcurrentMapCacheManager` 단기 TTL 옵션(R-006). Redis 없음.

### 7. SecurityConfig
- `.requestMatchers(HttpMethod.GET, "/api/v1/trends/**").permitAll()` + 정당화 코멘트(Constitution VI). 기존 `.anyRequest().authenticated()` 앞에 추가.

### 8. 설정 (application.yaml) — research R-007 블록

---

## Open Items (plan 결정 / Jace TODO)

| ID | 항목 | 처리 |
|----|------|------|
| OI-1 | 표시(raw deltaPct) vs 정렬(평활비) | 서버 정렬=평활비, 응답=deltaPct(raw)+isNew. UX 표현 Jace |
| OI-2 | 평활 상수 | `app.trend.smoothing-k` default 1(env). 확정 |
| OI-3 | isNew 노출 위치 | 응답 `isNew` 필드 제공, 화면 배치 Jace/UX |
| OI-4 | 이슈 지속 vs 재산출 | **re-derive 확정** — issue_snapshot 전량 교체, cross-run 안정 ID 없음. persistence는 미래 seam |
| OI-5 | SC-002 p95 수치 | Jace TODO(측정조건·동시요청 수) |
| OI-6 | rate-limit 방식 | 경량 필터(향후), 1순위 인덱스 read |
| OI-7 | Nori 버전 핀 | 9.12.0 가정, 구현 시 Java25/Boot4 동작 검증 |

---

## Complexity Tracking

| 사항 | 내용 |
|------|------|
| 신규 의존성 Lucene Nori | 형태소 분석 자산 부재(코드 확인). 순수 JVM라 네이티브 대비 배포 단순. 버전 핀·동작 검증 OI-7 |
| issue_snapshot 전량 교체 | re-derive(OI-4)의 clean cutover. TRUNCATE+INSERT 단일 TX. 안정 ID 미보장은 의도(005 알림/UI 추적은 범위 밖). **forward-note**: TRUNCATE는 테이블 레벨 락이라 read와 경합 가능 → 규모/동시조회 증가 시 generation-swap(새 generation INSERT 후 active 포인터 원자 교체, 구 generation 삭제)으로 전환. 현 저볼륨·단일 인스턴스에선 짧은 TX로 무해 |
| 집계 잡 단일 인스턴스 | fixedDelay 단일 실행 가정. multi-instance 시 advisory lock/ShedLock 필요(SKIP LOCKED 같은 claim 안전장치 없음). issue_snapshot 전량 교체는 동시 실행 시 경합 |
| 슬롯 저볼륨 희소 | 1시간 슬롯이 forward-compatible(Q1). 현재 슬롯당 2~3건 → Top5는 24h 윈도우 합산으로 동작 |
