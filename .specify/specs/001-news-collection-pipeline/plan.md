# Implementation Plan: 뉴스 수집·큐레이션 파이프라인과 카테고리 피드

**Branch**: `001-news-collection-pipeline` | **Date**: 2026-06-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/.specify/specs/001-news-collection-pipeline/spec.md`

---

## Summary

뉴스 출처(RSS + 네이버 검색 API)에서 기사를 주기적으로 수집·중복 제거하고,
별도 AI 처리 스케줄러가 Gemini를 통해 카테고리 분류와 balanced 요약을 생성한다.
인증된 사용자는 커서 기반 피드 API로 최신순/카테고리별 기사를 조회하며,
상세 조회 시 brief(트런케이션)/balanced(eager)/deep(lazy) 3슬롯 요약을 받는다.
관리자는 파이프라인 통계 API로 수집·처리 현황을 모니터링한다.

---

## Technical Context

**Language/Version**: Java 25 (Virtual Threads 지원, record 패턴, Sealed classes)

**Primary Dependencies**:
- Spring Boot 4.0.x (Spring Framework 7)
- Spring Data JPA + Hibernate 7
- Spring WebMVC (RestClient for external HTTP)
- com.rometools:rome 2.x (RSS 파싱)
- Flyway (스키마 마이그레이션)
- Testcontainers + PostgreSQL (통합 테스트)
- net.javacrumbs.shedlock:shedlock-spring (스케줄러 단일 실행, 현재 비활성화)
- Logback JSON encoder (구조적 로깅)

**Storage**: PostgreSQL 16 (온박스, t3.medium 공유)

**Testing**: JUnit 5 + Mockito + @DataJpaTest + @WebMvcTest + @SpringBootTest + Testcontainers

**Target Platform**: Linux (EC2 t3.medium), 단일 인스턴스 배포

**Performance Goals**:
- 피드 조회 서버측 P95 < 1초 (100~200 concurrent users)
- AI 처리: 500건/일 × 2 AI 호출 ≈ 1,000 Gemini calls/일 (Gemini Flash 유료 tier)

**Constraints**:
- Redis 미도입 (t3.medium 메모리 제약, 인덱스로 충분)
- 원문 본문 저장 금지 (저작권)
- 시크릿은 환경변수·외부 설정으로만
- Flyway 마이그레이션 외 스키마 변경 없음

**Scale/Scope**: 하루 10,000건 설계 헤드룸, 동시 100~200명 SLO 기준

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 상태 | 근거 |
|------|------|------|
| I. 레이어드 아키텍처 | ✅ PASS | Controller→Service→Repository 단방향. 트랜잭션은 Service. |
| II. Entity 비노출·입력 검증 | ✅ PASS | 모든 API 응답은 DTO(record). Bean Validation 적용. |
| III. 일관된 응답 포맷 | ✅ PASS | `ErrorResponse(code, message, timestamp)`. @RestControllerAdvice. |
| IV. 테스트 없는 비즈니스 로직 금지 | ✅ PASS | Service 단위 테스트, Repository @DataJpaTest, Controller @WebMvcTest. |
| V. 스키마 변경은 마이그레이션으로만 | ✅ PASS | Flyway V1__, V2__. Hibernate ddl-auto=validate. |
| VI. 보안 기본값·시크릿 외부화 | ✅ PASS | 인증은 spec 002(범위 밖). 관리자 경로 분리(/admin/). 시크릿은 환경변수. |
| VII. 멱등성·중복 방지 | ✅ PASS | URL dedup + INSERT ON CONFLICT. AI 처리 DB 상태 기반 멱등. |
| 추가: 시간대 관리 | ✅ PASS | 모든 시각 UTC 저장. published_at / first_collected_at 구분. |
| 추가: API 버저닝 | ✅ PASS | /api/v1 버저닝 적용. |
| 추가: 외부 API 복원력 | ✅ PASS | timeout + 재시도 + backoff. 출처별 장애 격리. |
| 추가: 구조적 로깅 | ✅ PASS | SLF4J + Logback JSON. 시크릿 로그 제외. 스케줄러 runId 추적. |

**Complexity Tracking**: 위반 없음.

---

## Architecture Overview

```
[RSS 피드]    [네이버 검색 API]
     │               │
     └─── SourceAdapter (interface) ───┐
                                       ▼
                              CollectionScheduler
                              (@Scheduled fixedDelay)
                                       │
                         URL 정규화 + dedup (normalized_url)
                                       │
                              ArticleRepository
                              INSERT or MERGE
                              (category_status=PENDING)
                                       │
                              PostgreSQL [articles]
                                       │
                              AiProcessingScheduler
                              (@Scheduled fixedDelay 1min)
                              PENDING 배치 폴링 (10건/회)
                                       │
                          ┌────────────┴────────────┐
                          ▼                         ▼
                    AiProvider                AiProvider
                    .classify()              .summarize(BALANCED)
                          │                         │
                   category_status=COMPLETED  summary_status=COMPLETED
                   + category 값 설정         + BRIEF 트런케이션 저장
                                             DEEP: NOT_GENERATED (lazy)
                                       │
                              ExpiryScheduler (daily)
                              feed_visible=false → 물리삭제

[클라이언트 요청]
  GET /api/v1/articles          → ArticleFeedController → FeedService
  GET /api/v1/articles/{id}     → ArticleDetailController → DetailService
                                  (DEEP lazy: AiProvider.summarize(DEEP) on first request)
  GET /api/v1/admin/pipeline/stats → AdminPipelineController → PipelineStatsService
```

---

## Project Structure

### Documentation (this feature)

```text
.specify/specs/001-news-collection-pipeline/
├── spec.md
├── plan.md              ← 이 파일
├── research.md          ← 13개 항목 결정 완료
├── data-model.md        ← 엔티티·인덱스·마이그레이션 계획
├── quickstart.md        ← 검증 시나리오·테스트 전략
├── contracts/
│   └── openapi.yaml     ← 피드 목록·기사 상세·관리자 통계 API
└── tasks.md             ← /speckit-tasks 실행 후 생성
```

### Source Code

```text
src/main/java/com/newscurator/
├── controller/
│   ├── ArticleFeedController.java        ← GET /api/v1/articles
│   ├── ArticleDetailController.java      ← GET /api/v1/articles/{id}
│   └── AdminPipelineController.java      ← GET /api/v1/admin/pipeline/stats
│   └── InternalTriggerController.java    ← 개발용 트리거 (local 프로파일만)
├── service/
│   ├── ArticleFeedService.java           ← 피드 조회, 커서 페이지네이션
│   ├── ArticleDetailService.java         ← 상세 조회, lazy 요약 생성
│   ├── CollectionService.java            ← URL 정규화, dedup, 병합 처리
│   ├── AiProcessingService.java          ← PENDING 큐 폴링, 분류·요약
│   ├── PipelineStatsService.java         ← 관리자 통계 집계
│   └── ExpiryService.java               ← 만료 기사 2단계 처리
├── scheduler/
│   ├── CollectionScheduler.java          ← @Scheduled fixedDelay
│   ├── AiProcessingScheduler.java        ← @Scheduled fixedDelay 1min
│   └── ExpiryScheduler.java             ← @Scheduled daily
├── repository/
│   ├── ArticleRepository.java
│   ├── SourceRepository.java
│   ├── ArticleSourceRepository.java
│   └── SummaryRepository.java
├── domain/
│   ├── Article.java
│   ├── Source.java
│   ├── ArticleSource.java
│   ├── Summary.java
│   └── enums/
│       ├── Category.java                 ← displayName() 포함
│       ├── ProcessingStatus.java
│       ├── SummaryDepth.java
│       └── SummarySlotStatus.java
├── dto/
│   ├── request/
│   │   └── FeedRequest.java             ← cursor, size, category
│   └── response/
│       ├── ArticleFeedResponse.java
│       ├── ArticleFeedItem.java
│       ├── ArticleDetailResponse.java
│       ├── SummarySlot.java
│       ├── ArticleSourceRef.java
│       ├── PipelineStatsResponse.java
│       └── ErrorResponse.java
├── exception/
│   ├── GlobalExceptionHandler.java       ← @RestControllerAdvice
│   ├── ArticleNotFoundException.java
│   └── AiProviderException.java
├── client/
│   ├── ai/
│   │   ├── AiProvider.java              ← interface: classify(), summarize()
│   │   └── GeminiAiProvider.java        ← Gemini Flash 구현체
│   └── source/
│       ├── SourceAdapter.java           ← interface: fetch(Source)
│       ├── ArticleCandidate.java        ← 수집 결과 VO
│       ├── RssSourceAdapter.java        ← ROME 기반 RSS
│       └── NaverSourceAdapter.java      ← 네이버 검색 API
└── util/
    └── UrlNormalizer.java               ← URL 정규화 7단계

src/main/java/com/newscurator/config/
├── CollectionProperties.java            ← @ConfigurationProperties
├── RetentionProperties.java
├── FeedProperties.java
└── AiProperties.java

src/main/resources/
├── application.yaml                     ← 공통 기본값
├── application-local.yaml              ← 로컬 설정 (gitignore)
├── application-dev.yaml                ← dev 설정 (gitignore)
├── application-prod.yaml               ← prod 설정 (gitignore)
├── application-example.yaml            ← 템플릿 (커밋)
└── db/migration/
    ├── V1__create_core_tables.sql
    └── V2__seed_initial_sources.sql

src/test/java/com/newscurator/
├── controller/
│   ├── ArticleFeedControllerTest.java   ← @WebMvcTest
│   ├── ArticleDetailControllerTest.java
│   └── AdminPipelineControllerTest.java
├── service/
│   ├── ArticleFeedServiceTest.java      ← JUnit 5 + Mockito
│   ├── CollectionServiceTest.java
│   ├── AiProcessingServiceTest.java
│   └── ExpiryServiceTest.java
├── repository/
│   └── ArticleRepositoryTest.java      ← @DataJpaTest + Testcontainers
└── client/
    ├── GeminiAiProviderTest.java        ← Mock HTTP (MockServer or WireMock)
    ├── RssSourceAdapterTest.java
    └── NaverSourceAdapterTest.java
```

---

## Key Design Decisions (research.md 요약)

| 항목 | 결정 | 참조 |
|------|------|------|
| RSS 파싱 | ROME 2.x 라이브러리 | research #1 |
| Redis | 미도입 (인덱스로 충분) | research #2 |
| 비동기 처리 | @Scheduled + DB 상태 | research #3 |
| brief 요약 | balanced 트런케이션 (~200자) | research #4 |
| FAILED 카테고리 | 상태 유지, API에서 OTHER 매핑 | research #5 |
| PostgreSQL | 온박스 (메모리 배분 엄격 관리) | research #6 |
| 카테고리 세트 | 10개 고정 enum | research #7 |
| URL 정규화 | 7단계 (tracking param 제거 등) | research #8 |
| Gemini | Flash 유료 tier, rate limit 간격 제어 | research #9 |
| 소스 관리 | DB 테이블 + Flyway seed | research #10 |
| 인덱스 | 6개 partial index + unique | research #11 |
| 관측 | DB 집계 + 구조적 로깅 | research #12 |
| 스케줄러 단일 실행 | fixedDelay + ShedLock 준비 | research #13 |

---

## Outstanding Items

없음. 모든 TBD 항목이 research.md에서 결론 도출됨.

단, 다음 항목은 구현 시작 전 확인 권장:
- Gemini Flash API 유료 tier 활성화 및 키 발급
- 초기 RSS 소스 목록 확정 (V2__seed_initial_sources.sql에 반영)
- 인증 spec(002) 연동 일정 (현재는 admin 엔드포인트 무보호 상태로 배포)

---

## Implementation Phases

### Phase A: 기반 인프라 (모든 User Story 블로킹)

- Flyway 마이그레이션 (V1: 테이블+인덱스, V2: 시드 데이터)
- 도메인 Entity + enum 클래스
- 전역 예외 처리 (GlobalExceptionHandler + ErrorResponse)
- @ConfigurationProperties 설정 바인딩
- UrlNormalizer 유틸 + 단위 테스트

### Phase B: US1 — 수집 파이프라인

- SourceAdapter 인터페이스 + RssSourceAdapter + NaverSourceAdapter
- CollectionService (URL 정규화, dedup, merge 로직)
- CollectionScheduler (@Scheduled)
- 통합 테스트: 수집→dedup→provenance
- **트랜잭션 격리**: 출처별(가능하면 기사별) 독립 트랜잭션. 전체 배치를 단일 트랜잭션으로 묶지 않으며, 특정 출처·기사의 실패는 해당 단위로만 롤백되고 나머지는 독립 커밋된다(FR-003 원칙).

### Phase C: US2 — AI 처리 파이프라인

- AiProvider 인터페이스 + GeminiAiProvider
- AiProcessingService (분류, balanced 요약, brief 트런케이션)
- AiProcessingScheduler (@Scheduled 1분)
- ExpiryService + ExpiryScheduler (만료 2단계 처리)
- **호출 예산 추적**: `calls_today` 컬럼 일괄 리셋 방식 대신 `SourceDailyUsage(source_id, usage_date, call_count)` 테이블로 (출처, 날짜) 키 기반 사용량을 기록한다. 날짜가 바뀌면 자연히 새 레코드를 사용하므로 별도 리셋 잡이 불필요하고 멱등하다. 날짜 기준은 각 공급자의 쿼터 리셋 시각(네이버: KST 자정 = UTC 15:00)에 맞춘다.
- **AI 응답 검증**: Gemini 분류 결과가 정의된 Category enum 외 값이면 OTHER로 폴백하고 WARN 로그(category_raw_value 포함)를 기록한다. 요약·분류 응답은 파싱·검증 후 저장한다.
- **AI Gemini 일일 cap 없음**: research #9 결론 — 960콜/일 ≪ 2,000 RPM, 별도 daily cap 불필요. HTTP 429 응답 시 exponential backoff(최대 3회, 1→2→4→8초)으로 충분.
- **스케줄러 단일 실행 보장**: research #13 결론 — 단일 EC2 인스턴스 전제(fixedDelay로 JVM 내 중첩 없음). PENDING 배치는 `SELECT ... FOR UPDATE SKIP LOCKED`로 row-level claim하여 scale-out 시에도 중복 처리 없음. ShedLock 의존성 준비, 활성화는 scale-out 시.

### Phase D: US3 — 피드 API

- ArticleRepository 커서 페이지네이션 쿼리
- ArticleFeedService + ArticleFeedController
- ArticleDetailService (lazy deep 생성) + ArticleDetailController
- Controller 테스트 (@WebMvcTest)

### Phase E: US4 — 관리자 통계 API

- PipelineStatsService (DB 집계 쿼리)
- AdminPipelineController + DTO
- 개발용 InternalTriggerController (local 프로파일)

---

## Test Strategy Summary

(상세 시나리오는 quickstart.md 참조)

- **단위**: Service 계층 핵심 로직 (dedup, 상태 전이, URL 정규화, 커서 생성)
- **리포지토리**: @DataJpaTest + Testcontainers PostgreSQL (쿼리, 인덱스, 제약)
- **컨트롤러**: @WebMvcTest (요청 직렬화, 에러 포맷, HTTP 상태)
- **클라이언트**: MockServer/WireMock으로 RSS 피드 및 API 응답 스텁
- **AI 공급자**: @MockBean으로 모킹, 응답 내용 단위 검증
- **통합**: @SpringBootTest + Testcontainers (수집→AI처리→피드 전체 흐름)
