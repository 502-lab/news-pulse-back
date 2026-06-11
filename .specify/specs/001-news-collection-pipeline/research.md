# Research: 뉴스 수집·큐레이션 파이프라인과 카테고리 피드

**Feature**: 001-news-collection-pipeline  
**Date**: 2026-06-09  
**Status**: ✅ All 13 items resolved — no TBD remaining

---

## 1. 소스 메커니즘

**Decision**:
- RSS 파싱: `com.rometools:rome` 라이브러리 (ROME 2.x). RSS 2.0 / Atom 1.0 / RDF 1.0 모두 지원, 한국어 인코딩 처리 안정적.
- 네이버 검색 API: Spring `RestClient` 로 직접 호출. 엔드포인트 `https://openapi.naver.com/v1/search/news.json`, 하루 25,000콜 무료.
- 공통 인터페이스: `SourceAdapter.fetch(Source) → List<ArticleCandidate>`. RssSourceAdapter / NaverSourceAdapter 구현체. 추후 확장(커스텀 크롤러 등)은 구현체 추가로만.
- 본문 추출 전략: **RSS `<description>` / 네이버 `description` 필드에서 메타 텍스트만 사용.** Jsoup 등으로 원문 본문 전체 추출 안 함. 추출 실패 시 title만으로 진행.
- 저작권: 제목·원문 링크·발행 메타·AI 생성 요약만 저장. 원문 HTML 본문 저장 없음. AI 요약은 RSS description(통상 100~300자)을 context로만 사용.

**Rationale**: ROME은 사실상 Java RSS 표준 라이브러리. 네이버 검색 API는 한국 주요 언론사 기사를 25,000콜/일에 무료 제공. 본문 비저장은 한국 저작권법 준수 및 DB 용량 절감 동시 달성.

**Alternatives rejected**:
- Jsoup 원문 크롤링: 저작권 리스크(원문 전문 저장), 사이트별 구조 변경 취약.
- Mercury/Diffbot 외부 서비스: 추가 비용, 외부 의존성.
- Spring Batch ItemReader 기반 RSS: 단순 @Scheduled 대비 불필요한 복잡성.

---

## 2. Redis 캐시 도입 여부

**Decision**: **1차 배포 Redis 미도입.** PostgreSQL + partial index로 P95 1s 목표 충분히 달성 가능.

**근거**:
- t3.medium (2 vCPU, 4GB RAM): PostgreSQL shared_buffers 1GB + Spring JVM -Xmx 1.5GB = 2.5GB → 나머지 1.5GB OS buffer. Redis 추가 시 메모리 압박.
- 10,000건 기사 × 커서 페이지네이션 쿼리: partial index (feed_visible=true AND category_status IN ...) + published_at+id 복합 인덱스 스캔 레이턴시 추정 5~20ms. P95 1초 목표에 충분한 여유.
- 100~200 concurrent users: HikariCP connection pool 기본(10~20)으로 감당 가능. 쿼리 20ms × 20 동시 커넥션 = 충분.

**Redis 도입 트리거 조건** (plan에 명시):
- 피드 쿼리 P95 > 200ms 지속 시
- 동시 사용자 500명 이상 도달 시
- t3.medium → t3.large 업그레이드 후 여유 RAM 확보 시

**Alternatives rejected**:
- Redis (지금): 메모리 부족, 캐시 무효화 복잡성, 현 규모 불필요.
- Caffeine (애플리케이션 인메모리): 피드 데이터가 자주 바뀌어 캐시 효과 낮음, 워커 분리 시 무효화 불가.

---

## 3. 비동기 처리 구현 방식

**Decision**: **@Scheduled + DB 상태 폴링. 단일 JVM 내 2개 독립 스케줄러.**

- `CollectionScheduler`: `@Scheduled(fixedDelayString = "${app.scheduler.collection.interval-ms}", initialDelayString = "10000")`. 기본 15분.
- `AiProcessingScheduler`: `@Scheduled(fixedDelay = 60_000)`. 1분마다 PENDING 배치 처리. 배치 크기 `app.ai.batch-size` (기본 10).
- `fixedDelay` (이전 실행 완료 후 N분 대기): 자연적 단일 실행 보장, `fixedRate`(중첩 실행 가능)는 사용하지 않음.
- DB 상태 기반(category_status, summary_status): 재시작·크래시 후 PENDING 항목 자동 재처리 → 멱등성 달성.

**Alternatives rejected**:
- Spring Batch: 단일 인스턴스 규모에서 오버엔지니어링. 설정 복잡도 증가.
- SQS / Kafka: 추가 인프라 비용 및 운영 부담. 현 단계 불필요.
- Virtual threads(Java 25): rate limit 병목(외부 AI API)은 thread 수가 아니라 호출 간격 제어로 해결해야 함.

---

## 4. brief 요약

**Decision**: **brief = balanced 앞부분 트런케이션. 별도 AI 호출 없음. Deep만 진짜 lazy AI 생성.**

- brief: balanced 요약 앞 2문장 또는 최대 200자 trunc. `SummaryService.truncateForBrief(String balanced)`.
- balanced: AI 스케줄러가 eager 생성 (기사당 1 AI call).
- deep: 기사 상세 최초 조회 시 동기 생성 후 캐시 (기사당 최대 1 AI call, on-demand).
- 결과: 기사당 AI 호출 = 2 (classify + balanced). deep은 수요 기반.

**Rationale**: 피드 목록에서 balanced를 미리보기로 쓰기로 결정(Q10)했으므로 brief 별도 생성 불필요. brief는 balanced의 트런케이션으로 즉시 제공 가능. AI 비용 33% 절감(3콜→2콜).

**Alternatives rejected**:
- 별도 AI brief 생성: AI 비용 증가, 품질 차이 미미.
- deep도 eager: 10,000건/일 × 3콜 = 30,000 AI 호출, rate limit 압박.

---

## 5. category 상태 모델

**Decision**: **FAILED 상태 유지. API 응답에서 FAILED → OTHER 매핑.**

- category_status = FAILED: AI 분류 서비스 기술적 장애(타임아웃·5xx 등)로 retry_limit 도달 시.
- category_status = COMPLETED + category = OTHER: AI가 "분류 불가" 판정 시 (정상 처리 경로, FR-006).
- 피드 API: category_status=FAILED는 응답에서 `category = "OTHER"` 로 매핑 (DTO 변환 계층).
- 두 경우 모두 피드에 노출 (category_status ∈ {COMPLETED, FAILED}).

**Rationale**: FAILED와 OTHER를 구분하면 AI 서비스 장애 감지 가능. FAILED 기사는 서비스 복구 후 재처리 가능 (retry_count < retry_limit인 경우). 운영 투명성 확보.

**Alternatives rejected**:
- FAILED → 즉시 category=OTHER로 전환: 장애 감지 불가, 재처리 기회 소실, 통계 왜곡.

---

## 6. PostgreSQL 배치

**Decision**: **t3.medium 온박스 PostgreSQL. 엄격한 메모리 배분 + S3 백업.**

메모리 배분:
- PostgreSQL `shared_buffers = 1GB`, `effective_cache_size = 2GB`
- Spring Boot JVM `-Xmx 1.5GB`, `-Xms 512MB`
- OS / 나머지: ~1.5GB (버퍼 풀 포함)

백업: `pg_dump` daily → S3 버킷, systemd timer or cron. WAL archiving은 1차 생략.

**Upgrade path**: 트래픽 증가 시 RDS t3.small ($30/month)으로 분리. 이 경우 앱 -Xmx 2.5GB로 확대 가능.

**Alternatives rejected**:
- RDS t3.micro ($13-15/month): 비용 절감 우선, 초기에는 온박스 충분.
- Neon 무료 티어: 5분 비활성 sleep → 스케줄러 웨이크업 지연 (수집 SLO 위반 가능). 부적합.
- PlanetScale / Supabase free: PostgreSQL 마이그레이션 복잡성, 무료 티어 제한.

---

## 7. 카테고리 세트 확정

**Decision**: **10개 고정 enum.**

| Java/DB 값 | 표시명 |
|---|---|
| `ECONOMY_FINANCE` | 경제·금융 |
| `SCIENCE` | 과학 |
| `POLITICS` | 정치 |
| `SPORTS` | 스포츠 |
| `WORLD` | 세계 |
| `ENTERTAINMENT_CULTURE` | 연예·문화 |
| `HEALTH_MEDICINE` | 건강·의학 |
| `AUTOMOTIVE` | 자동차 |
| `IT` | IT |
| `OTHER` | 기타 |

- DB 컬럼: `VARCHAR(32)`, Java enum `Category`.
- 표시명 매핑: `Category.displayName()` 메서드 또는 별도 `CategoryDisplayName` enum.
- 네이버 검색 API 카테고리와 최대한 일치. AI 분류 프롬프트에서 이 10개 값만 반환하도록 강제.

**Alternatives rejected**:
- 별도 DB 테이블: 카테고리 추가 시 API 계약 변경 필요(어차피), 복잡성 추가.
- 앱/백오피스 별도 매핑 테이블: 동일 카테고리 세트를 두 곳에서 관리 → 불일치 위험.

---

## 8. URL 정규화 규칙

**Decision**: **다음 7단계 정규화를 `UrlNormalizer` 유틸 클래스에 구현.**

```
1. scheme 소문자, http → https 변환
2. host 소문자, 불필요한 'www.' 제거는 선택적 (뉴스 사이트는 유지)
3. 표준 포트 제거 (https:443, http:80)
4. path trailing slash 제거 (단 root "/" 는 유지)
5. URL fragment (#...) 제거
6. 다음 쿼리 파라미터 제거 (tracking params):
   utm_*, fbclid, gclid, ref, source, medium, campaign, content, term,
   _ga, _gl, mc_*, yclid, navts, cid
7. 나머지 쿼리 파라미터: key 기준 알파벳 오름차순 정렬 (비결정론적 순서 제거)
```

- 구현: `java.net.URI` 기반 파싱. 서드파티 없이 표준 라이브러리만 사용.
- 정규화된 URL은 `Article.normalizedUrl`에 저장, 원본은 `Article.originalUrl`에 별도 보관.
- 단위 테스트로 엣지케이스(앵커, 빈 path, 한국어 인코딩) 커버.

**Alternatives rejected**:
- Apache Commons Validator: 커스텀 tracking param 제거 불가.
- 완전 URL 동일 매칭만: 동일 기사가 tracking param 차이로 dedup 실패.

---

## 9. AI 비용·rate limit 대응

**Decision**: **Gemini 1.5 Flash 유료 tier 사용. Rate limit 제어는 처리 간격 + 배치 크기로.**

처리량 분석:
- 실제 예상 신규 기사: 24개 출처 × ~20건/일 = ~480건/일 (보수적 추정)
- AI 호출: 분류 1 + balanced 요약 1 = 기사당 2콜 → 480 × 2 = 960콜/일
- 분당 처리 목표: AiProcessingScheduler 배치 10건/분 → 분당 20콜 (분류+요약)

Gemini 1.5 Flash 유료 tier: 2,000 RPM, 4M tokens/분. 960콜/일은 무제한 여유.

비용: Gemini Flash 입력 $0.075/1M tokens, 출력 $0.30/1M tokens.
- 추정 기사당 토큰: 입력 ~500 + 출력 ~300 = 800 tokens
- 960건/일 × 800 tokens = 768K tokens → 입력 $0.038/일 + 출력 $0.23/일 ≈ **$0.27/일 이하**

Rate limit 대응:
- `fixedDelay` 스케줄러 + 배치 크기 제한 (기본 10건/회)으로 자연 제어
- HTTP 429 응답 시 exponential backoff (1s → 2s → 4s → 8s, max 3회 재시도)
- 배치 크기 및 대기 간격은 `app.ai.batch-size`, `app.ai.delay-between-calls-ms` 환경변수로 조정

**Alternatives rejected**:
- Gemini 무료 tier (15 RPM): 피크 처리량에서 병목 가능, 운영 불안정.
- OpenAI GPT-4o: 비용 약 10배, 한국어 성능은 유사.
- 자체 LLM: 인프라 비용 및 운영 부담 과다.

---

## 10. 소스 등록·관리

**Decision**: **DB 테이블 (`sources`) 기반 관리. 초기 데이터는 Flyway seed migration.**

- `sources` 테이블에 RSS URL 또는 네이버 검색 쿼리 저장.
- `adapter_type` 컬럼 (RSS | NAVER)으로 SourceAdapter 구현체 선택.
- `active` 컬럼: DB UPDATE로 런타임 비활성화 (재배포 불필요).
- `collection_interval_minutes` NULL = 전역 기본값(15분) 사용.
- `call_budget_daily` 컬럼: 소스별 일일 호출 한도.
- 초기 RSS 피드 목록은 `V2__seed_sources.sql` Flyway 마이그레이션으로 투입.
- 관리 API(추가·수정·삭제)는 spec 008에서 구현. 이번 사이클은 읽기 전용 접근.

초기 RSS 소스 선정 기준:
- 네이버 뉴스 제공 언론사 RSS (공식 제공 피드 우선)
- 연합뉴스 (`https://www.yna.co.kr/rss/news.xml`)
- YTN, MBC, SBS, KBS 등 방송사 RSS
- 조선일보, 중앙일보, 한겨레, 경향신문 등 신문사 RSS

**Alternatives rejected**:
- YAML 설정 파일: per-source 설정(interval, call_budget) 변경 시 재배포 필요. 운영 유연성 저하.
- 환경변수 목록: 수십 개 소스를 환경변수로 관리 불가.

---

## 11. 인덱스 전략

**Decision**: **5종 partial index + 1 unique index. Flyway 마이그레이션에 포함.**

```sql
-- 피드 커서 페이지네이션 (메인 쿼리 경로)
CREATE INDEX idx_articles_feed
  ON articles (published_at DESC, id DESC)
  WHERE feed_visible = true AND category_status IN ('COMPLETED', 'FAILED');

-- 카테고리 필터 피드
CREATE INDEX idx_articles_category_feed
  ON articles (category, published_at DESC, id DESC)
  WHERE feed_visible = true AND category_status IN ('COMPLETED', 'FAILED');

-- URL dedup (INSERT 시 conflict check)
CREATE UNIQUE INDEX idx_articles_normalized_url
  ON articles (normalized_url);

-- AI 분류 PENDING 큐 폴링
CREATE INDEX idx_articles_category_queue
  ON articles (first_collected_at ASC)
  WHERE category_status = 'PENDING';

-- AI 요약 PENDING 큐 폴링
CREATE INDEX idx_articles_summary_queue
  ON articles (first_collected_at ASC)
  WHERE summary_status = 'PENDING';

-- 만료 정리 작업
CREATE INDEX idx_articles_expiry
  ON articles (expires_at ASC)
  WHERE feed_visible = true AND user_saved = false;
```

**Rationale**: Partial index는 조건에 해당하지 않는 행(PENDING 기사, 만료 기사 등)을 인덱스에서 제외해 크기 최소화 및 갱신 오버헤드 감소. PostgreSQL 14+ partial index on expression 지원.

**Alternatives rejected**:
- 단순 B-tree 인덱스 (partial 없이): 전체 행 인덱싱 → 스캔 범위 증가.
- GIN/GiST: 전문 검색 불필요 (검색은 spec 범위 밖).
- 커버링 인덱스 (INCLUDE): 추가 컬럼 포함 시 인덱스 크기 증가 → 우선 기본 구성 후 프로파일링 결과에 따라 추가.

---

## 12. 파이프라인 관측·지표

**Decision**: **DB 집계 쿼리 기반 Admin API + SLF4J 구조적 로깅. 별도 메트릭 저장소 없음.**

Admin API (`GET /api/v1/admin/pipeline/stats`) 집계 쿼리:
```sql
-- 오늘 수집 건수
SELECT COUNT(*) FROM articles WHERE DATE(first_collected_at) = CURRENT_DATE

-- 요약 완료율
SELECT
  ROUND(100.0 * SUM(CASE WHEN summary_status = 'COMPLETED' THEN 1 ELSE 0 END) / COUNT(*), 1)
FROM articles WHERE DATE(first_collected_at) = CURRENT_DATE

-- 병합 처리 건수 (오늘 is_merge=true인 ArticleSource)
SELECT COUNT(*) FROM article_sources
WHERE is_merge = true AND DATE(collected_at) = CURRENT_DATE

-- 카테고리별 기사 수 (전체 피드 기준)
SELECT category, COUNT(*) FROM articles
WHERE feed_visible = true AND category_status = 'COMPLETED'
GROUP BY category
```

스케줄러 구조적 로깅 포맷 (Logback JSON):
```json
{"timestamp":"...", "level":"INFO", "scheduler":"CollectionScheduler",
 "runId":"uuid", "source":"연합뉴스", "articlesFound":42,
 "articlesNew":38, "articlesMerged":4, "durationMs":1234}
```

Spring Boot Actuator (`/actuator/health`, `/actuator/info`) 활성화 → 헬스체크 엔드포인트.

**Upgrade path**: Micrometer + Prometheus + Grafana는 규모 성장 시 추가. 현재는 DB 집계로 충분.

**Alternatives rejected**:
- Prometheus/Grafana: 단일 인스턴스 초기 단계에서 인프라 오버헤드.
- PipelineStats 별도 테이블: 실시간 집계와 동기화 복잡. DB 집계 쿼리가 충분히 빠름.

---

## 13. 스케줄러 단일 실행 보장

**Decision**: **fixedDelay + 단일 인스턴스. ShedLock 의존성 준비, 활성화는 scale-out 시.**

현재:
- `@Scheduled(fixedDelay=...)`: 이전 실행이 완료된 후 delay 시작 → 동일 JVM 내 중첩 실행 없음.
- 단일 EC2 인스턴스 배포 → 분산 락 불필요.

미래 대비:
- `net.javacrumbs.shedlock:shedlock-spring` + `shedlock-provider-jdbc-template` 의존성 추가.
- `@SchedulerLock(name="collectionScheduler", lockAtMostFor="14m")` 어노테이션 준비 (비활성화 상태).
- `@ConditionalOnProperty("app.scheduler.enabled", havingValue="true", matchIfMissing=true)` 로 스케줄러 ON/OFF.
- scale-out 시: ShedLock 활성화로 leader 선출 없이 단일 실행 보장.

**Alternatives rejected**:
- ShedLock 즉시 활성화: 단일 인스턴스에서 DB 락 테이블 불필요, 복잡성 증가.
- Quartz 클러스터: 무거운 스케줄링 프레임워크, 현 요구사항 대비 과도.
- Redis-based lock: Redis 미도입 결정(항목 2)과 일관성 없음.
