# Feature Specification: 편향 분석 엔진

**Feature Branch**: `006-bias-analysis-engine`

**Created**: 2026-06-21

**Status**: Draft

**Input**: User description: "006 편향 분석 엔진"

## User Scenarios & Testing *(mandatory)*

### User Story 1 — 기사 편향 점수·근거 키워드 조회 (Priority: P1)

독자가 기사 상세 또는 피드에서 해당 기사의 편향 점수(−100 ~ +100)와 점수를 뒷받침하는 근거 키워드를 확인한다. 점수만 표시되는 것이 아니라 "왜 이 점수인지" 2~5개의 키워드로 설명이 함께 제공되어 정보 수용 판단에 도움을 준다.

**Why this priority**: 편향 분석의 핵심 가치(설명가능성)를 직접 전달하는 기능. 점수 없이는 후속 기능(스펙트럼, 비교)도 성립하지 않는다.

**Independent Test**: 특정 기사의 편향 점수와 근거 키워드가 피드 응답 및 상세 API에 포함되는지 확인. AI 분석 미완료 기사는 null을 반환하고 클라이언트가 처리 중 상태를 표시할 수 있다.

**Acceptance Scenarios**:

1. **Given** AI 편향 분석이 완료된 기사, **When** 피드 또는 상세 조회, **Then** `biasScore.value`(−100~+100 정수)와 `biasScore.rationaleKeywords`(2~5개 문자열 배열) 반환
2. **Given** AI 분석이 아직 미완료(PENDING/PROCESSING)인 기사, **When** 피드 또는 상세 조회, **Then** `biasScore: null` 반환 (기사 자체는 정상 노출)
3. **Given** AI 분석이 완료된 기사, **When** 편향 칩(bias chip) 클릭/탭, **Then** 점수와 근거 키워드 상세 노출 (W-04 / A-05)
4. **Given** AI 분석이 실패한 기사, **When** 피드 조회, **Then** `biasScore: null` 반환, 분석 실패 상태를 편향 칩 미표시로 처리

---

### User Story 2 — 비동기 편향 분석 파이프라인 (Priority: P1)

시스템이 새 기사 수집 후 자동으로 AI 편향 분석을 비동기로 수행한다. 001의 AI 요약 처리와 동일한 PENDING→PROCESSING→DONE 상태 패턴을 재사용하여, 분석 중인 기사도 피드에 정상 노출되고 분석 완료 시 점수가 갱신된다.

**Why this priority**: 편향 점수 데이터를 생산하는 인프라. US1의 선행 조건.

**Independent Test**: 기사 수집 후 편향 분석 작업이 PENDING 상태로 생성되고, 처리 후 해당 기사의 `bias_score`와 `rationale_keywords`가 DB에 저장되는지 확인.

**Acceptance Scenarios**:

1. **Given** 새 기사가 수집됨, **When** AI 파이프라인이 실행, **Then** 해당 기사에 대한 편향 분석 작업이 PENDING 상태로 생성됨
2. **Given** PENDING 편향 분석 작업, **When** 스케줄러가 처리, **Then** Gemini AI 호출 → 점수(-100~+100)와 근거 키워드 저장 → 상태 DONE
3. **Given** AI 호출 실패, **When** 재시도 상한(3회) 미도달, **Then** +5m/+30m 딜레이 백오프 후 재시도(총 3회 한도 내)
4. **Given** AI 호출이 3회 모두 실패, **When** 재시도 상한 도달, **Then** 상태 FAILED(`bias_score` null), 기사 노출 영향 없음. failed_at + 6h에 one-shot 복구 시도 예약
5. **Given** 동일 기사에 대해 분석 작업 중복 트리거, **When** 이미 PENDING/PROCESSING/DONE 상태, **Then** 중복 생성 없음 (멱등성 보장)
6. **Given** 3회 소진 FAILED 기사, **When** failed_at + 6h 도달, **Then** one-shot 복구(attempt_count +1). 성공→DONE, 실패→terminal FAILED

---

### User Story 3 — 출처(Outlet) 편향 집계 조회 (Priority: P2)

독자가 특정 뉴스 출처의 편향 성향을 숫자로 확인한다. 시스템이 해당 출처에서 수집된 기사들의 편향 점수를 집계하여 출처 단위 `biasValue`(가중평균)와 분석된 `articleCount`를 제공한다.

**Why this priority**: 개별 기사 편향이 누적되어 출처 성향을 드러내는 상위 뷰. US2 파이프라인 완료 후 의미 있는 데이터가 쌓인다.

**Independent Test**: 분석 완료 기사가 n건 이상 존재하는 출처의 Outlet 편향 조회 API가 `biasValue`와 `articleCount`를 반환하는지 확인.

**Acceptance Scenarios**:

1. **Given** 편향 분석 완료 기사가 존재하는 출처, **When** 출처 편향 조회, **Then** `biasValue`(−100~+100 실수, 가중평균), `articleCount`(분석 완료 기사 수) 반환
2. **Given** 분석 완료 기사가 없는 출처, **When** 출처 편향 조회, **Then** `biasValue: null`, `articleCount: 0` 반환
3. **Given** 새 기사의 편향 분석이 완료됨, **When** 출처 편향 재조회, **Then** `biasValue`와 `articleCount`가 갱신됨 (실시간 또는 배치 집계)

---

### User Story 4 — 전체 편향 스펙트럼 조회 (Priority: P2)

독자가 전체 서비스 분석완료 기사의 편향 분포를 스펙트럼으로 확인한다. 분석된 기사들의 점수를 집계하여 진보 % / 중립 % / 보수 % 및 전체 가중평균을 제공한다. (W-03 화면)

**Why this priority**: 개인화 인사이트(009)의 선행 데이터. 단독으로도 "내가 얼마나 편향된 뉴스를 읽나" 인식 제고 가치가 있다.

**Independent Test**: 다수의 편향 점수가 다른 기사를 조회 이력에 가진 상태에서 스펙트럼 API가 가중평균과 진보/중립/보수 % 세 값을 반환하는지 확인.

**Acceptance Scenarios**:

1. **Given** 편향 분석 완료 기사가 존재, **When** 스펙트럼 조회, **Then** `spectrum.weightedAverage`(−100~+100), `spectrum.liberalPercent`, `spectrum.neutralPercent`, `spectrum.conservativePercent`(합산 100%) 반환
2. **Given** 점수 −100~−34는 진보, −33~+33은 중립, +34~+100은 보수로 분류, **When** 각 버킷 비율 계산, **Then** 백분율 정확히 산출
3. **Given** 분석 완료 기사가 없음, **When** 스펙트럼 조회, **Then** 빈 스펙트럼 응답(모든 값 null 또는 0)

---

### User Story 5 — 기사 비교: 006 범위 외, 별도 스펙으로 분리(추후).

---

### Edge Cases

- AI가 편향 분류 불가능한 기사(순수 사실 보도, 날씨 등)에 `value: 0`, `rationaleKeywords: ["사실 보도"]` 반환
- 동일 기사에 편향 분석 재요청 시 기존 DONE 결과 유지(재분석 덮어쓰기 없음)
- FAILED 기사는 failed_at + 6h에 one-shot 자동 복구(attempt_count +1). 복구 실패 시 terminal FAILED 확정, 이후 재시도 없음. 수동 재큐잉은 008 관리자 대시보드.
- 출처 편향 집계 시 분석 완료(DONE) 기사만 포함, PENDING/FAILED 제외
- value는 정수. 버킷은 inclusive 정수범위 — 진보[−100,−34]/중립[−33,+33]/보수[+34,+100] — 로 무중복·무공백 분할. −34→진보, +34→보수. 경계 모호성 없음.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 새 기사 수집 후 해당 기사에 대한 편향 분석 작업을 자동으로 PENDING 상태로 생성해야 한다.
- **FR-002**: 시스템은 편향 분석 작업을 비동기로 처리하고, AI로부터 −100~+100 범위의 정수 편향 점수와 2~5개의 근거 키워드를 수신하여 기사에 저장해야 한다.
- **FR-003**: 편향 분석 실패 시 총 3회 시도(attempt_count 1→2→3; 재시도 딜레이 +5m/+30m, 005 outbox threshold `attemptCount >= 3` 동일 적용)하고, 3회 소진 시 FAILED 전환. FAILED 후 6시간(failed_at + 6h)에 one-shot 자동 복구 1회 수행; 그것도 실패하면 terminal FAILED 확정. 기사는 편향 점수 없이 정상 노출. 수동 재큐잉은 008.
- **FR-004**: 동일 기사의 편향 분석 작업은 중복 생성되지 않아야 한다(멱등성).
- **FR-005**: 피드 및 기사 상세 API는 `biasScore.value`와 `biasScore.rationaleKeywords`를 포함해야 하며, 분석 미완료 시 `biasScore: null`을 반환해야 한다. (인증: 001 기존 피드/상세 API 설정 상속)
- **FR-006**: 시스템은 출처(Source/Outlet)별 편향 점수 가중평균(`biasValue`)과 분석 완료 기사 수(`articleCount`)를 제공하는 API를 제공해야 한다. (인증 필수 — JWT)
- **FR-007**: 시스템은 분석 완료 기사들의 편향 스펙트럼(전체 가중평균, 진보/중립/보수 %)을 제공하는 API를 제공해야 한다. (집계 범위: 전체 분석완료 기사 기준. 개인 조회 이력 기반은 009.) (인증 필수 — JWT)
- **FR-008**: 편향 점수는 진보(−100~−34) / 중립(−33~+33) / 보수(+34~+100) 세 버킷으로 분류된다.
- **FR-009**: 기사 상세 화면에서 편향 칩(bias chip) 인터랙션 시, 점수와 근거 키워드 상세 정보를 반환하는 API를 제공해야 한다. (인증 필수 — JWT; 001 기사 상세 API와 동일 컨텍스트)
- **FR-010**: 편향 분석 결과(점수, 키워드, 상태)는 기사 데이터와 분리하여 저장하고, 기존 기사 테이블을 오염시키지 않아야 한다.
- **FR-011**: 편향 분석 스케줄러는 배치 실행마다 시작·종료·처리 건수·실패 건수를 log.info(구조적 로그)로 기록해야 한다. 개별 분석 실패 시 article_id·attempt_count·에러 종류를 log.warn으로 기록한다. API 키·토큰 등 민감 정보는 로그에 포함하지 않는다.
- **FR-012**: SLA 지표('수집 후 24h 경과 기사 중 status=DONE 비율'과 '당일 FAILED 전환 건수')는 일 1회(자정 또는 별도 일일 스케줄) 구조적 로그로 emit한다(SC-001 측정치 산출 근거). 배치 실행별 처리·실패 건수 로그는 FR-011이 담당한다.

### Key Entities

- **BiasAnalysis**: 기사별 편향 분석 결과 — `articleId`, `value`(−100~+100 정수), `rationaleKeywords`(문자열 배열), `status`(PENDING/PROCESSING/DONE/FAILED), `attemptCount`, `analyzedAt`, `failedAt`(3회 소진 시 기록, one-shot 복구 스케줄 기준)
- **OutletBiasSummary**: 출처 단위 집계 뷰 — `sourceId`, `biasValue`(단순평균, 롤링 90일, 최소 10건), `articleCount`. 인덱스 기반 쿼리 집계; 규모 증가 시 materialized view 전환.
- **BiasSpectrum**: 스펙트럼 집계 뷰 — `weightedAverage`, `liberalPercent`, `neutralPercent`, `conservativePercent`
- BiasAnalysis 행 자체가 작업 단위이며(1 기사 1 행, status로 추적), 별도 Job/큐 엔티티는 두지 않는다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 기사 수집 후 24시간 이내 95% 이상의 기사에 편향 점수가 부여된다. (전제: Gemini 정상가동. 재시도 소진 FAILED ≤5% 허용. Gemini 장기 장애는 본 SLA 범위 외.)
  - 측정: 최근 7일 롤링, '수집 후 24h 경과' 기사 모집단 중 status=DONE 비율, 일 1회 산출 — 스케줄러 구조적 로그로 emit(FR-012). (TODO: 7일/주기 Jace 확정)
- **SC-002**: 기사 조회 응답에 `biasScore` 필드가 항상 포함된다(필드 누락 0%). status≠DONE 기사는 값 null로 반환한다.
- **SC-003**: 편향 분석 실패 시에도 해당 기사가 피드에 정상 노출되어 사용자 경험이 단절되지 않는다.
- **SC-004**: 출처 편향 집계 조회가 분석 완료 기사 10건 이상 존재하는 출처에 대해 p95 ≤ 3s, p99 ≤ 5s로 응답한다. 1순위: 인덱스 기반 집계 쿼리(Redis 미사용). 규모 증가 시 materialized view + 기존 스케줄러 REFRESH로 전환. 측정조건: 분석완료 10건+ 출처, 동시요청 수 명시. (TODO: 수치 Jace 확정)
- **SC-005**: 동일 기사에 대한 편향 분석 작업 중복 실행이 0건이다.

## Assumptions

- AI 편향 분석 프롬프트는 Gemini Flash 모델(001 AI 요약과 동일 모델)을 사용한다.
- 편향 스펙트럼(US4)은 서비스 전체 기사 기준으로 집계한다 (개인 조회 이력 기반은 009 인사이트에서 담당).
- 출처 편향 집계(US3)는 단순평균 + 롤링 90일 + 최소 10건 기준으로 집계한다.
- 기사 비교(US5)는 006 범위 외로 확정, 별도 스펙으로 분리한다.
- 편향 점수 −100은 "극진보", +100은 "극보수"를 의미하며, 한국 뉴스 생태계 기준으로 프롬프트를 설계한다.
- 편향 분석 결과·상태(status, attemptCount 포함)는 전부 별도 BiasAnalysis 테이블에 저장한다(FR-010 분리저장 준수). 기사 테이블에 bias 컬럼을 추가하지 않는다. 004 TTS·005 알림과 동일하게 전용 테이블 + FOR UPDATE SKIP LOCKED claim 패턴을 재사용하고, 기사 수집 시 bias_analysis PENDING 행을 생성한다. 재시도/백오프는 005 outbox threshold(`attemptCount >= 3`)와 동일. 006 딜레이: attempt_count 1→+5m, 2→+30m, 3→FAILED(005는 +1m/+5m이나 006은 독자 값 적용).
- 기존 수집 기사 소급 편향 분석(backfill)은 최근 90일 이내 기사로 범위를 한정한다. backfill은 별도 버스트가 아니라 bias_analysis PENDING 행 일괄 생성 후 정상 claimer로 rate-safe하게 드레인하며, live 신규 기사 처리를 굶기지 않는다. backfill 기사는 SC-001(수집 후 24h) 측정 대상이 아니다(일회성 소급).
- 편향 기반 피드 재랭킹은 003과의 통합 시점에 결정하며, 006 범위에서 제외한다.
- 편향 집계(FR-006·FR-007) 성능은 Redis 없이 인덱스 기반 쿼리로 SC-004를 충족한다. 규모 증가(출처 수 급증, 집계 지연 발생) 시 BiasAnalysis 테이블의 materialized view를 기존 스케줄러 REFRESH 패턴으로 전환한다.
- 메트릭 수집(Micrometer Counter/Gauge)은 006 범위 외. 현재 스택에 Prometheus 등 스크래핑 레지스트리 미연결(actuator만 존재). 실패율·처리량 집계는 구조적 로그(FR-011/012)로 대체하며, Micrometer 연동은 008 운영 대시보드 스펙에서 결정한다.

## Clarifications

### Session 2026-06-21

- Q: FR-006/007/009 신규 API 인증 요구사항 → A: FR-005는 001 기존 설정 상속, FR-006/007/009는 인증 필수(JWT)
- Q: FAILED 재분석 정책 → A: 3회 fast 재시도(+5m/+30m) → FAILED 후 6h one-shot 자동복구, 그것도 실패 시 terminal FAILED. 수동 재큐잉은 008.
- Q: Redis 캐싱 대상/TTL → A: Redis 미도입. 인덱스 기반 집계 쿼리 1순위, 규모 증가 시 materialized view + 스케줄러 REFRESH로 대응.
- Q: 재시도 기산점·백오프 구체값 → A: 총 3회 시도(attempt_count 1→2→3), 재시도 딜레이 +5m/+30m, 005 threshold(`attemptCount >= 3`) 재사용. 코드 검증: NotificationOutbox.java:103 `if (attemptCount >= 3)`.
- Q: 관측가능성 요구사항 → A: Micrometer 스크래핑 백엔드 미연결(actuator만). B 채택 — 배치 시작·종료·처리/실패 건수 log.info + 개별 실패 log.warn(FR-011). SC-001 측정치(DONE 비율·FAILED 건수) 일 1회 구조적 로그 emit 필수(FR-012). Micrometer Counter는 008 Deferred.
