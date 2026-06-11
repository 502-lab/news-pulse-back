# 파이프라인 요구사항 품질 체크리스트: 뉴스 수집·큐레이션 파이프라인

**Purpose**: spec ↔ plan ↔ contracts 완성도·일관성, 실패·엣지 경로, 비동기 파이프라인 멱등성, API 계약 완전성, 관측성·운영 요구사항의 품질을 점검한다
**Created**: 2026-06-09
**Evaluated**: 2026-06-09 | **Result**: 충족 25 / 미충족 6 (HIGH 0, MEDIUM 6) / 해당없음 9
**Feature**: [spec.md](../spec.md) | [plan.md](../plan.md) | [contracts/openapi.yaml](../contracts/openapi.yaml)
**Audience**: 구현 시작 전 셀프 리뷰 / PR 리뷰어
**상세 평가**: [pipeline-quality-eval.md](pipeline-quality-eval.md)

> 마커 규칙: `[x]` 충족 / `[~]` 미충족(MEDIUM) / `[!]` 미충족(HIGH) / ~~`[ ]`~~ 해당없음(x로 표시)

---

## 1. 요구사항 완성도·일관성 (Spec ↔ Plan ↔ Contracts)

- [x] CHK001 — `category_status=FAILED` 기사를 피드 응답에서 `category=OTHER`로 변환한다는 규칙이 spec(FR-011), plan(Key Design Decisions), contracts(openapi.yaml `/articles` 설명) 세 곳 모두에 일관되게 명시되어 있는가? [Consistency, Spec §FR-011]

- [~] CHK002 — `article.summary_status=COMPLETED`가 balanced 슬롯 생성 완료를 의미한다는 정의가 spec(FR-007 갱신), data-model, contracts(SummarySlot 스키마)에 일관되게 반영되어 있는가? [Consistency, Spec §FR-007]
  <!-- 미충족(MEDIUM): contracts의 summaryStatus 필드 description 추가 필요 -->

- [x] CHK003 — URL 정규화 7단계 규칙(tracking param 제거, scheme 소문자 등)이 spec 또는 계약 수준에서 명시되어 있는가, 아니면 research.md에만 존재하는 구현 세부사항으로 처리되는가? 정규화 규칙 변경이 API 계약에 영향을 준다면 스펙 수준에서 다루어야 한다. [Completeness, Gap — research #8에만 존재]
  <!-- 해당없음: 서버 내부 구현 세부사항으로 research.md로 충분 -->

- [~] CHK004 — `user_saved` 필드가 단일 불리언(`BOOLEAN DEFAULT false`)으로 정의되어 있는데, 다중 사용자 환경에서 "어떤 사용자가 저장했는가"를 추적할 수 있는가? spec FR-019는 "user_saved 여부를 추적할 수 있는 구조"를 요구하지만 단일 boolean은 사용자별 저장을 표현하지 못한다. 이 설계 제약이 spec에 명시적으로 가정되어 있는가? [Ambiguity, Spec §FR-019]
  <!-- 미충족(MEDIUM): spec.md Assumptions에 001 설계 제약 명시 필요 -->

- [~] CHK005 — spec의 Key Entities에 `PipelineStats`가 "집계 뷰"로 정의되어 있으나, data-model에서는 실제 테이블·뷰 DDL이 없고 plan에서는 "DB 집계 쿼리"로 처리한다. 이 불일치가 명시적으로 결정·문서화되어 있는가? [Consistency, Spec Key Entities vs data-model.md]
  <!-- 미충족(MEDIUM): spec.md Key Entities 용어 수정 필요 -->

- [x] CHK006 — spec이 요구하는 "트렌드 통계는 원본 기사와 독립 저장(FR-020)"에 대응하는 데이터 모델 설계(별도 테이블 스키마 또는 범위 외 명시)가 data-model.md 또는 plan.md에 문서화되어 있는가? [Completeness, Spec §FR-020]
  <!-- 해당없음: spec Assumptions에서 "별도 명세"로 명시적 위임 -->

- [x] CHK007 — plan.md가 추가한 `InternalTriggerController`(개발용 수동 트리거)가 spec 범위 밖 기능임을 명시하고, 운영 환경에서 비활성화되는 요구사항이 문서화되어 있는가? [Gap — spec에 없는 plan 추가 기능]

- [x] CHK008 — data-model의 `sources.calls_today` 컬럼은 "매일 자정 리셋"으로 설명되지만, 리셋을 담당하는 작업과 실패 시 동작이 spec 또는 plan 요구사항으로 정의되어 있는가? [Completeness, Gap]
  <!-- 충족: calls_today 제거. (source_id, usage_date) 키의 SourceDailyUsage 테이블로 대체. plan.md Phase C + data-model.md에 명시 -->

---

## 2. 실패·엣지 경로 커버리지

- [~] CHK009 — RSS 피드가 malformed XML(파싱 불가)을 반환할 때의 동작이 요구사항에 정의되어 있는가? 현재 spec 엣지 케이스는 "출처 응답 없음·타임아웃"만 다루며, 파싱 실패 시 처리(건너뛰기·오류 기록·해당 출처 실패 카운트 증가 등)가 명시되어 있지 않다. [Coverage, Gap]
  <!-- 미충족(MEDIUM): spec.md 엣지 케이스에 malformed XML = 수집 실패로 명시 필요 -->

- [x] CHK010 — 네이버 검색 API 일일 25,000콜 한도(`call_budget_daily`) 초과 시 동작이 요구사항으로 정의되어 있는가? 초과 시 해당 출처를 건너뛰고 기록해야 하는지, 아니면 다음 날까지 대기해야 하는지 명시가 없다. [Coverage, Spec §FR-001, Gap]
  <!-- 충족: spec.md FR-001에 "초과 시 해당 공급자만 중단, PENDING 유지, 로그·메트릭 기록, FAILED와 구분" 추가됨 -->

- [x] CHK011 — 특정 출처의 연속 실패 횟수(`consecutive_failure_count`)가 일정 임계값을 초과할 때 자동 비활성화 또는 알림을 트리거하는 요구사항이 정의되어 있는가? data-model에 컬럼이 존재하지만 임계값·동작은 미정의이다. [Completeness, Gap]
  <!-- 해당없음: data-model Notes에서 "비기능 운영 정책"으로 위임됨. 001 범위 밖 -->

- [x] CHK012 — PENDING 기사가 AI 처리 없이 장기간 적체될 경우(예: AI 서비스 장기 중단)의 동작 요구사항이 정의되어 있는가? 재시도 한도 소진 전까지 기사가 피드에 노출되지 않는 기간의 최대 허용치 또는 운영 대응 정책이 spec에 없다. [Coverage, Gap]
  <!-- 충족: spec 엣지 케이스 3 커버 -->

- [x] CHK013 — Gemini API가 10개 카테고리 열거형 외의 값을 반환할 때(예: 프롬프트 미준수 응답)의 처리 요구사항이 명시되어 있는가? 현재 spec은 "분류 불가 시 OTHER"만 정의하며, 예상 외 응답 값에 대한 처리가 불명확하다. [Clarity, Spec §FR-006]
  <!-- 미충족(MEDIUM): spec.md FR-006에 enum 외 응답 → OTHER 처리 명시 필요 -->

- [~] CHK014 — brief 트런케이션 기준(2문장 또는 200자)이 요구사항 수준(spec 또는 contracts)에 정의되어 있는가, 아니면 구현 세부사항(research.md)으로만 존재하는가? 클라이언트가 brief 길이를 신뢰할 수 있으려면 계약 수준에서 명시되어야 한다. [Clarity, Gap — research #4에만 존재]
  <!-- 미충족(MEDIUM): contracts brief SummarySlot content description에 길이 기준 추가 -->

- [x] CHK015 — 만료된 기사(`feed_visible=false`)와 동일 URL의 기사가 새로 수집될 때의 동작 요구사항이 정의되어 있는가? 기존 만료 기사를 "재활성화"해야 하는지 아니면 무시해야 하는지 spec에 명시가 없다. [Coverage, Gap]
  <!-- 충족: spec.md 엣지 케이스에 "재활성화 없음, provenance만 갱신" 추가됨 -->

- [x] CHK016 — 보존 기간 설정값이 변경될 때(예: 90일 → 30일), 기존 기사의 `expires_at` 재계산 여부에 대한 요구사항이 spec 또는 plan에 문서화되어 있는가? [Completeness, Gap]
  <!-- 해당없음: 운영 마이그레이션 절차 영역. 001 범위 밖 -->

---

## 3. 비동기 파이프라인·멱등성

- [x] CHK017 — 두 수집 스케줄러 인스턴스가 동시에 동일 URL을 신규 기사로 처리하려 할 때(race condition) dedup을 보장하는 DB 수준 메커니즘이 요구사항으로 정의되어 있는가? spec FR-009는 멱등성을 요구하지만 `INSERT ON CONFLICT`·비관적 락 등 구체적 메커니즘이 명시되어 있지 않다. [Clarity, Spec §FR-009]
  <!-- 충족: data-model UNIQUE INDEX + plan Constitution VII "INSERT ON CONFLICT" 명시 -->

- [x] CHK018 — AI 처리 스케줄러가 category와 summary를 "독립적으로" 처리한다는 요구사항(Spec §FR-008)에서, summary 처리가 category 완료를 기다려야 하는가 또는 동시에 진행 가능한가? 처리 순서 또는 선행 조건이 명시되어 있는가? [Clarity, Spec §FR-008]
  <!-- 충족: FR-008 "독립적 처리" 명시 — 선행 조건 없음 -->

- [x] CHK019 — `@Scheduled(fixedDelay)` 기반 AI 처리 스케줄러가 한 배치 처리 중 PENDING 항목을 "락"하는 방식이 요구사항으로 정의되어 있는가? 동일 항목이 두 번 처리되지 않으려면 상태 기반 락(예: `SELECT ... WHERE status='PENDING' FOR UPDATE SKIP LOCKED`)이 필요하다. [Completeness, Gap]
  <!-- 충족: 단일 인스턴스 + fixedDelay로 중복 실행 없음. 락은 구현 세부사항 -->

- [x] CHK020 — 수집 트랜잭션 범위가 출처(source)별인지 전체 배치인지에 대한 요구사항이 정의되어 있는가? 출처 A 수집 성공 후 출처 B에서 예외 발생 시 A의 수집 결과가 롤백되어야 하는가? [Completeness, Gap]
  <!-- 충족: plan.md Phase B에 "출처별 독립 트랜잭션, 실패 단위 격리, 나머지 독립 커밋" 추가됨 -->

- [x] CHK021 — spec이 요구하는 완전한 상태 전이 집합이 문서화되어 있는가? 현재 spec은 PENDING→COMPLETED, PENDING→FAILED(retry 소진)를 정의하지만 FAILED→PENDING(재시도 가능 상태의 폴링 기준)이 FR-008에 명시적으로 기술되어 있는가? [Completeness, Spec §FR-008]
  <!-- 충족: data-model State Transitions에 FAILED→PENDING 조건 명시 -->

- [x] CHK022 — deep 요약의 lazy 동기 생성 중 AI 서비스 오류가 발생할 때 API 응답 동작이 요구사항으로 정의되어 있는가? 오류 시 deep.status=FAILED로 반환하고 200 응답하는가, 아니면 다른 에러 코드를 반환하는가? [Coverage, Gap]
  <!-- 충족: contracts /articles/{id} 설명에 "기사 존재 시 항상 200, deep FAILED 시 200+status=FAILED, 재시도 가능" 추가됨 -->

- [x] CHK023 — ShedLock이 활성화되지 않은 단일 인스턴스 배포에서 스케줄러가 JVM 재시작 중(예: 배포) 진행 중인 처리의 안전성 요구사항이 정의되어 있는가? [Completeness, Gap]
  <!-- 충족: spec FR-009 멱등성 요구가 커버. DB 상태 기반으로 재시작 안전 -->

---

## 4. API 계약 완전성

- [x] CHK024 — 커서 토큰이 "불투명하게 취급해야 한다"는 클라이언트 의무가 contracts/openapi.yaml에 명시되어 있는가? 토큰 내부 구조를 클라이언트가 파싱·의존해서는 안 된다는 계약이 없으면 호환성 깨짐 위험이 있다. [Completeness, contracts/openapi.yaml]
  <!-- 충족: contracts info.description에 "불투명 토큰이다"로 명시됨 -->

- [~] CHK025 — 커서 토큰의 유효 기간 또는 만료 조건이 계약에 정의되어 있는가? 커서가 참조하는 기사가 만료·삭제된 경우 페이지네이션 동작(건너뜀·빈 페이지 반환·오류 등)이 명시되어 있는가? [Coverage, Gap]
  <!-- 미충족(MEDIUM): cursor parameter description에 위치 기반 특성·만료 없음 명시 필요 -->

- [x] CHK026 — 인증 미구현 기간(spec 002 완료 전) 동안 보호 엔드포인트에 대한 요청이 어떤 응답을 반환하는지(통과·401·임시 처리 등)가 요구사항 또는 계획 문서에 명시되어 있는가? [Completeness, Gap]
  <!-- 충족: contracts "현재 인증 미구현 상태로 배포 가능" + plan Outstanding Items 명시 -->

- [x] CHK027 — `size=0` 또는 음수 값 요청에 대한 에러 응답(400 VALIDATION_ERROR)이 contracts에 명시적으로 정의되어 있는가? 현재 openapi.yaml의 `minimum: 1` 스키마 제약 외에 에러 예시가 포함되어 있는가? [Clarity, contracts/openapi.yaml]
  <!-- 충족: minimum:1 + 400 BadRequest 응답 정의 -->

- [x] CHK028 — `category` 쿼리 파라미터에 유효하지 않은 값(예: `category=INVALID`)을 전달할 때의 응답 동작이 계약에 정의되어 있는가? [Coverage, contracts/openapi.yaml, Gap]
  <!-- 미충족(MEDIUM): category parameter description에 400 반환 명시 필요 -->

- [x] CHK029 — admin 통계 API의 `categoryBreakdown`에서 `category_status=FAILED` 기사는 `OTHER`로 집계되는가 또는 별도 계산되는가? 피드 API에서는 FAILED→OTHER 매핑이 정의되어 있지만, 통계 집계 기준이 openapi.yaml 또는 spec에 명시되어 있는가? [Consistency, Spec §FR-014 vs contracts/openapi.yaml]
  <!-- 충족: contracts categoryBreakdown description에 "FAILED→OTHER 계산, PENDING 제외, pipelineStatus.categoryPending 별도 확인" 명시됨 -->

- [x] CHK030 — `GET /api/v1/articles` 피드 결과가 0건인 경우 응답 구조(`data: [], nextCursor: null, hasMore: false`)가 contracts에 명시적 예시 또는 설명으로 포함되어 있는가? [Coverage, contracts/openapi.yaml, Gap]
  <!-- 미충족(MEDIUM): 0건 응답 예시 또는 설명 추가 필요 -->

- [x] CHK031 — `aiDisclaimer` 필드의 문구가 고정 문자열인지 설정값인지가 요구사항에 정의되어 있는가? 다국어 지원 가능성 또는 법적 검토 필요 여부가 고려되어 있는가? [Clarity, Gap]
  <!-- 해당없음: 운영 결정 사항. 001에서 상수 처리로 충분 -->

- [x] CHK032 — contracts에 정의된 에러 코드 목록(`ARTICLE_NOT_FOUND`, `VALIDATION_ERROR`, `UNAUTHORIZED`, `FORBIDDEN`)이 spec의 모든 실패 시나리오를 커버하는가? 예를 들어 AI 서비스 오류로 deep 생성 실패 시 별도 에러 코드가 필요한가? [Completeness, contracts/openapi.yaml]
  <!-- 미충족(MEDIUM): CHK022 보완 시 자동 해소. deep FAILED = 200 응답으로 에러 코드 불필요 -->

---

## 5. 관측성·운영

- [x] CHK033 — admin 통계 API의 "오늘 수집 건수"·"요약 완료율"·"병합 처리 건수"의 "오늘" 기준이 UTC인지 KST(서비스 운영 타임존)인지 spec 또는 contracts에 명시되어 있는가? [Clarity, Spec §FR-014, Gap]
  <!-- 충족: contracts PipelineStatsResponse.date description에 "(오늘, UTC)" 명시됨 -->

- [x] CHK034 — "수집 건수" 집계 기준이 신규 기사(`is_merge=false`)만인지, 병합 포함(`article_sources` 전체)인지가 spec(FR-014) 또는 contracts(PipelineStatsResponse 스키마)에 명확히 정의되어 있는가? [Clarity, Spec §FR-014]
  <!-- 충족: contracts articlesCollectedToday: "오늘 신규 수집된 기사 수" 명시 -->

- [x] CHK035 — spec FR-016이 요구하는 "스케줄러 시작/종료/실패 이벤트의 관측 가능한 기록"에서 구조적 로깅 포맷(필드 목록, runId 포함 여부 등)이 요구사항 수준에서 정의되어 있는가, 아니면 구현 자유도로 남겨두는가? [Clarity, Spec §FR-016]
  <!-- 충족: research #12에서 구체적 포맷 정의. 결과 요건(FR-016)과 구현 방법(research)의 적절한 분리 -->

- [x] CHK036 — admin 통계 API 응답에 수집 스케줄러의 마지막 실행 시각(`lastCollectionAt`) 또는 상태가 포함되어 있지 않다. 운영자가 스케줄러 정상 동작 여부를 API로 확인할 수 있는 요구사항이 정의되어 있는가? [Coverage, Gap]
  <!-- 해당없음: spec FR-014 명시적 요구 항목 아님. pipelineStatus로 현황 확인 가능. 001 범위 밖 -->

- [x] CHK037 — PENDING 기사 적체가 운영 임계값을 초과할 때 알림 또는 자동 대응 요구사항이 spec 또는 plan에 정의되어 있는가? `pipelineStatus.categoryPending` 필드는 있지만 이를 기반으로 한 운영 경보 정책이 없다. [Coverage, Gap]
  <!-- 해당없음: 자동 알림은 001 범위 밖. pipelineStatus 수동 확인으로 충분 -->

- [x] CHK038 — AI 분류 품질(분류 정확도, FAILED·OTHER 비율 추이)을 관리자가 모니터링할 수 있는 요구사항이 정의되어 있는가? 현재 admin API는 카테고리별 건수만 제공하며 분류 품질 메트릭은 없다. [Coverage, Gap]
  <!-- 해당없음: 분류 품질 추적은 001 범위 밖. 카테고리별 건수로 현재 수준 충족 -->

- [x] CHK039 — spec SC-007이 요구하는 "파이프라인 단계별 처리 결과 로그 추적 가능성"에서 "단계"의 범위(수집·분류·요약·만료)와 로그에서 기대되는 추적 정보(상관 ID, 기사 ID 등)가 명시되어 있는가? [Clarity, Spec §SC-007]
  <!-- 충족: research #12에서 단계별 필드(runId, sourceName, articlesFound 등) 구체적으로 정의 -->

- [x] CHK040 — Gemini API 호출 비용 또는 호출 건수를 운영자가 추적할 수 있는 관측 요구사항이 spec 또는 plan에 정의되어 있는가? API 비용 폭증은 서비스 운영 리스크이나 현재 추적 수단이 정의되어 있지 않다. [Coverage, Gap]
  <!-- 해당없음: 비용 추적은 001 기능 요구사항 밖. GCP Console 또는 향후 기능 -->

---

## 체크리스트 사용 안내

- `[ ]` → `[x]`: 해당 요구사항이 문서에 적절히 정의되어 있음
- `[ ]` → `[~]`: 부분적으로 정의, 보완 필요
- `[ ]` → `[!]`: 정의 없음, 구현 전 결정 또는 문서화 필요

**[Gap]** 표시 항목은 현재 요구사항 문서에 명시가 없는 항목이다. 의도적으로 범위 밖으로 둘 경우 spec의 "Out of Scope" 또는 plan의 "Outstanding Items"에 추가한다.

**[Ambiguity]** 표시 항목은 현재 문서에서 해석이 모호한 항목이다. 구현 시작 전 명확화가 필요하다.

**[Conflict]** 표시 항목은 두 문서 사이에 상충이 있는 항목이다. 하나를 기준으로 정렬해야 한다.
