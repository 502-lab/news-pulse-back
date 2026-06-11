# 체크리스트 평가 결과: 파이프라인 요구사항 품질

**평가 기준 문서**: spec.md · plan.md · data-model.md · quickstart.md · contracts/openapi.yaml  
**평가일**: 2026-06-09  
**평가 결과 요약**: 충족 15 / 미충족 16 / 해당없음 9

---

## 평가 범례

| 마커 | 의미 |
|------|------|
| ✅ 충족 | 세 문서에서 일관되게 명시됨 |
| 🔴 미충족(HIGH) | 구현 전 반드시 보완 — 런타임 오류·계약 위반 직결 |
| 🟡 미충족(MEDIUM) | 구현 중 보완 권장 — 모호성·계약 불완전 |
| ⬜ 해당없음 | 001 범위 밖 또는 구현 세부사항으로 적절히 위임됨 |

---

## 1. 요구사항 완성도·일관성 (CHK001–CHK008)

### CHK001 ✅ 충족
`category_status=FAILED → category=OTHER` 변환 규칙이 세 곳 모두에 일관되게 명시됨.
- spec FR-011: 명시 ✓
- plan Key Design Decisions: "FAILED 카테고리 — 상태 유지, API에서 OTHER 매핑" ✓
- contracts ArticleFeedItem.category: "category_status=FAILED인 경우 OTHER로 반환" ✓

---

### CHK002 🟡 미충족(MEDIUM)
`article.summary_status=COMPLETED`가 balanced 슬롯 완료를 의미함이 **contracts에서 불명확**.
- spec FR-007 + data-model Generation Strategy: "article.summary_status = 이 슬롯(BALANCED)의 완료 여부를 추적" ✓
- contracts `ArticleFeedItem.summaryStatus` / `ArticleDetailResponse.summaryStatus`: 필드 description이 없어 balanced 완료를 의미함이 암시만 됨 ✗

**보완 위치**: `contracts/openapi.yaml` — `ArticleFeedItem.summaryStatus`와 `ArticleDetailResponse` 스키마에 `description: "article-level 요약 상태. COMPLETED는 balanced 슬롯 생성 완료를 의미한다."` 추가.

---

### CHK003 ⬜ 해당없음
URL 정규화 7단계 규칙은 서버 내부 dedup 로직의 구현 세부사항으로, API 계약에 노출되지 않음. research.md #8로 충분히 문서화됨. 클라이언트가 정규화 규칙을 알 필요 없음.

---

### CHK004 🟡 미충족(MEDIUM)
`user_saved BOOLEAN`이 001 사이클에서 "시스템 수준 플래그"로만 사용됨이 **spec에 명시적으로 가정되어 있지 않음**.
- spec FR-019: "user_saved 여부를 추적할 수 있는 구조" — 의도는 알 수 있으나 단일 boolean이 다중 사용자 환경에서 사용자 구분이 불가함을 인정하는 가정 문서가 없음
- data-model: `user_saved BOOLEAN NOT NULL DEFAULT FALSE`로 구현

**보완 위치**: `spec.md` Assumptions 섹션 — "user_saved는 001 사이클에서 시스템 수준 플래그(전체 저장 여부)로만 사용하며, 사용자별 저장 관계 테이블은 별도 명세에서 설계한다." 추가.

---

### CHK005 🟡 미충족(MEDIUM)
spec Key Entities의 `PipelineStats "집계 뷰"` 용어와 plan/data-model 결정이 **불일치**.
- spec Key Entities: "PipelineStats (파이프라인 통계): 관리자 대시보드를 위한 집계 뷰" ← DB 뷰 암시
- plan research #12: 실시간 DB 집계 쿼리로 결정 (별도 뷰/테이블 없음)
- data-model.md: PipelineStats 테이블/뷰 DDL 없음

**보완 위치**: `spec.md` Key Entities — PipelineStats 설명을 "실시간 DB 집계 쿼리로 산출되는 파이프라인 현황 (별도 테이블·뷰 없음)"으로 수정.

---

### CHK006 ⬜ 해당없음
spec Assumptions에서 "트렌드 집계 저장소는 별도 명세에서 설계된다"로 명시적으로 범위 밖 처리됨. 001 scope 이탈 없음.

---

### CHK007 ✅ 충족
`InternalTriggerController`가 plan.md에 "(local 프로파일만)"으로 명시됨. `@Profile("local")` 비활성화 요건이 문서화됨.

---

### CHK008 🔴 미충족(HIGH)
`sources.calls_today` 매일 자정 리셋 담당 작업이 **미결정 상태**.
- data-model Notes: "매일 자정 0으로 리셋 (ExpiryScheduler 또는 별도 리셋 작업)" — "또는"으로 미결정
- plan Phase C: ExpiryService + ExpiryScheduler 언급만, calls_today 리셋 책임 없음
- 리셋 실패 시 call_budget이 영구 소진되는 리스크 존재

**보완 위치**: `plan.md` Phase C — ExpiryScheduler 담당 작업에 "calls_today 일일 리셋 (UTC 자정 기준)" 명시. 또는 별도 DailyResetScheduler 신설 여부 결정.

---

## 2. 실패·엣지 경로 커버리지 (CHK009–CHK016)

### CHK009 🟡 미충족(MEDIUM)
RSS malformed XML 처리가 spec 엣지 케이스에 **명시되어 있지 않음**.
- spec FR-003: "특정 출처 수집이 실패해도 나머지 출처 수집을 계속 진행" — 파싱 실패가 "실패"에 포함됨을 구현자가 추론해야 함
- spec 엣지 케이스: "출처 응답 없음·타임아웃"만 명시

**보완 위치**: `spec.md` 엣지 케이스 — "출처 응답이 malformed XML 또는 파싱 불가 포맷인 경우 해당 출처 수집 실패로 처리하고(연속 실패 카운트 증가) 나머지 출처는 계속 진행한다." 추가.

---

### CHK010 🔴 미충족(HIGH)
`call_budget_daily` 초과 시 동작이 **요구사항으로 정의되어 있지 않음**.
- spec FR-001: "rate limit 준수, call budget 관리" 요구 — 초과 시 처리 없음
- 네이버 검색 API 25,000콜/일 초과 → API 오류 반환 → 시스템 동작 미정의

**보완 위치**: `spec.md` FR-001 또는 엣지 케이스 — "call_budget_daily 초과 시 해당 출처의 당일 수집을 중단하고 'BUDGET_EXCEEDED' 사유로 기록한다. 다음 자정(UTC)에 calls_today 리셋 후 재개한다." 추가.

---

### CHK011 ⬜ 해당없음
`consecutive_failure_count` 임계값 기반 자동 비활성화는 data-model Notes에서 "비기능 운영 정책"으로 명시적으로 위임됨. 001 범위 밖.

---

### CHK012 ✅ 충족
AI 서비스 장기 중단 시나리오가 spec 엣지 케이스 3에서 커버됨: "AI 서비스 복구 후 retry_limit 미초과 기사는 자동으로 재처리됨". retry_limit 소진 후 영구 FAILED 전이도 정의됨.

---

### CHK013 🟡 미충족(MEDIUM)
Gemini API가 10개 카테고리 **enum 외 문자열을 반환**할 때의 처리가 spec에 명시적이지 않음.
- spec FR-006: "분류 불가 시 기타(OTHER)로 처리" — "분류 불가"(AI 판단)와 "예상 외 응답값"(파싱 실패)은 다른 케이스

**보완 위치**: `spec.md` FR-006 — "Gemini API가 정의된 카테고리 enum 외 값을 반환하거나 응답 파싱이 실패할 경우 분류 불가로 간주하여 기타(OTHER)로 처리한다." 추가.

---

### CHK014 🟡 미충족(MEDIUM)
brief 트런케이션 기준(2문장 또는 200자)이 **contracts 수준에서 미명시**.
- research.md #4: "balanced 앞 2문장 또는 최대 200자" — 구현 세부사항으로만 존재
- contracts SummarySlot.content: description이 없어 brief 길이 보장이 없음

**보완 위치**: `contracts/openapi.yaml` — brief SummarySlot의 `content` description 또는 `/articles/{id}` 설명에 "brief 슬롯의 content는 balanced 요약의 앞 2문장 또는 200자 이내 트런케이션이다." 추가.

---

### CHK015 🔴 미충족(HIGH)
만료됐지만 미삭제(`feed_visible=false`) 기사와 **동일 URL 재수집 시 동작이 미정의**.
- data-model: `unique index on normalized_url` → INSERT 시 UNIQUE VIOLATION 발생
- 기존 기사 feed_visible 복원 vs 새 기사 무시 중 어느 것인지 spec/plan 어디에도 없음

**보완 위치**: `spec.md` FR-015 또는 엣지 케이스 — "feed_visible=false 상태의 기사와 동일 URL을 재수집할 경우, 기존 기사의 feed_visible을 true로 복원하고 expires_at을 현재 기준으로 갱신한다(재활성화). 물리 삭제된 기사는 신규 기사로 삽입한다." (또는 무시 정책 명시).

---

### CHK016 ⬜ 해당없음
보존 기간 설정값 변경 시 기존 expires_at 재계산은 운영 마이그레이션 스크립트 영역. 새 수집 기사부터 적용이 합리적 기본값. 001 정의 불필요.

---

## 3. 비동기 파이프라인·멱등성 (CHK017–CHK023)

### CHK017 ✅ 충족
data-model의 `UNIQUE INDEX idx_articles_normalized_url` + plan Constitution Check VII의 "URL dedup + INSERT ON CONFLICT"로 DB 수준 race condition dedup이 문서화됨.

---

### CHK018 ✅ 충족
spec FR-008: "분류와 요약의 실패는 독립적으로 처리된다" — 처리 순서 선행 조건 없이 병렬 진행 가능함이 명확히 정의됨.

---

### CHK019 ✅ 충족
단일 인스턴스 + fixedDelay 스케줄러로 중복 실행 없음. plan research #13에서 ShedLock 준비(비활성화 상태) 명시. 항목 락 메커니즘은 구현 세부사항으로 적절히 위임됨.

---

### CHK020 🔴 미충족(HIGH)
수집 트랜잭션이 출처별인지 전체 배치인지 **plan에 명시되어 있지 않음**.
- spec FR-003: "출처 A 실패 시 나머지 계속" — 출처 간 격리 암시 (출처별 독립 트랜잭션)
- plan: 트랜잭션 경계 미명시

**보완 위치**: `plan.md` Phase B 또는 Architecture Overview — "각 출처 수집은 독립 트랜잭션으로 처리한다. 출처 A 수집 실패는 출처 B 수집 결과에 영향을 주지 않는다." 추가.

---

### CHK021 ✅ 충족
data-model State Transitions: "FAILED → PENDING (retry_count < retry_limit: 재시도 대상)" — FAILED→PENDING 조건이 명시됨. spec 엣지 케이스 3도 "AI 복구 후 재처리"로 지원.

---

### CHK022 🔴 미충족(HIGH)
deep lazy 생성 중 AI 오류 발생 시 **API 응답 동작이 contracts에 미명시**.
- contracts `/articles/{id}` 응답: 200, 400, 401, 404만 정의
- "슬롯 상태: NOT_GENERATED / PENDING / COMPLETED / FAILED"로 FAILED 상태가 암시되지만 "200 + summaries.deep.status=FAILED로 반환한다"는 계약이 없음

**보완 위치**: `contracts/openapi.yaml` `/articles/{id}` description — "DEEP 슬롯 생성 중 AI 오류 시 요청은 200으로 응답하며, `summaries.deep.status=FAILED`로 반환된다. 다음 상세 조회 시 재생성을 시도하지 않는다(retry_limit 정책 적용)." 추가.

---

### CHK023 ✅ 충족
spec FR-009 멱등성 요구가 JVM 재시작 케이스를 커버. DB 상태 기반 처리이므로 재시작 후 PENDING 기사가 자동 재처리됨.

---

## 4. API 계약 완전성 (CHK024–CHK032)

### CHK024 ✅ 충족
contracts info.description: "커서는 `published_at + id`를 Base64 인코딩한 **불투명 토큰**이다." — 불투명성이 명시됨.

---

### CHK025 🟡 미충족(MEDIUM)
커서 만료 조건 또는 **참조 기사 삭제 시 페이지네이션 동작이 contracts에 미명시**.
- 커서는 published_at+id 위치 기반이므로 기사 삭제에 영향받지 않으나, 이 특성이 계약에 없음

**보완 위치**: `contracts/openapi.yaml` cursor parameter description — "커서는 특정 기사가 아닌 정렬 위치(published_at+id)를 기준으로 하므로, 이전 페이지 기사가 만료·삭제되어도 페이지네이션은 계속 동작한다. 커서의 별도 만료 기간은 없다." 추가.

---

### CHK026 ✅ 충족
contracts info.description: "현재 모든 엔드포인트는 인증 미구현 상태로 배포 가능" + plan Outstanding Items: "admin 엔드포인트 무보호 상태로 배포"로 임시 동작이 문서화됨.

---

### CHK027 ✅ 충족
size 파라미터 `minimum: 1` + '400' `$ref: BadRequest`로 경계값 오류 처리가 정의됨.

---

### CHK028 🟡 미충족(MEDIUM)
category enum 외 값 전달 시 400 반환이 **contracts에 명시적이지 않음**.
- category 파라미터가 `$ref: Category` enum으로 정의되어 OpenAPI 도구가 추론 가능하지만, HTTP 수준에서 명시적 에러 설명이 없음

**보완 위치**: `contracts/openapi.yaml` category parameter description — "유효하지 않은 카테고리 값 전달 시 400 VALIDATION_ERROR 반환." 추가.

---

### CHK029 🔴 미충족(HIGH)
admin 통계 `categoryBreakdown`에서 **FAILED 기사 집계 기준이 미명시**.
- contracts categoryBreakdown: "피드 노출 기사의 카테고리별 건수" — FAILED→OTHER 포함 여부 없음
- 피드 API에서는 FAILED→OTHER 매핑이 정의됐으나 통계 집계 기준과의 일관성 미확인

**보완 위치**: `contracts/openapi.yaml` PipelineStatsResponse.categoryBreakdown description — "`category_status=FAILED` 기사는 OTHER로 집계한다." 추가.

---

### CHK030 🟡 미충족(MEDIUM)
피드 결과 0건인 경우 응답 구조가 **contracts 예시에 없음**.
- contracts ArticleFeedResponse 스키마: data(array), nextCursor(nullable), hasMore, size 정의는 있음
- 0건 케이스의 `data: [], nextCursor: null, hasMore: false` 예시 없음

**보완 위치**: `contracts/openapi.yaml` GET /articles 200 응답 — 0건 케이스 예시 추가 또는 description에 "결과가 없으면 data: [], nextCursor: null, hasMore: false를 반환한다." 명시.

---

### CHK031 ⬜ 해당없음
aiDisclaimer 문구 설정 가능 여부는 운영 결정 사항. 001 범위에서 상수로 처리하고 추후 설정화 가능. 다국어·법적 검토는 별도 사이클.

---

### CHK032 🟡 미충족(MEDIUM)
deep 생성 실패 시 에러 코드가 CHK022와 연계해 **명시가 필요**.
- ARTICLE_NOT_FOUND(404), VALIDATION_ERROR(400), UNAUTHORIZED(401), FORBIDDEN(403)은 정의됨
- AI 처리 실패(summary/category FAILED)는 200 응답 내 status 필드로 표현되므로 별도 에러 코드 불필요
- 단, deep lazy 실패 시 200 처리임을 CHK022 보완으로 명확히 해야 에러 코드 목록이 완결됨

**보완 위치**: CHK022 보완 완료 시 자동 해소.

---

## 5. 관측성·운영 (CHK033–CHK040)

### CHK033 ✅ 충족
contracts PipelineStatsResponse.date: `description: "통계 기준일 (오늘, UTC)"` — UTC 기준이 명시됨.

---

### CHK034 ✅ 충족
contracts articlesCollectedToday: `description: "오늘 신규 수집된 기사 수"` — 신규 기사(first_collected_at 기준)만 집계함이 명시됨. research #12 SQL과 일치.

---

### CHK035 ✅ 충족
spec FR-016이 결과 요건을 정의하고, research #12가 구조적 로그 포맷(runId, sourceName, articlesFound, duplicatesSkipped 등)을 구체적으로 정의함. 적절한 책임 분리.

---

### CHK036 ⬜ 해당없음
스케줄러 lastCollectionAt은 spec FR-014의 명시적 요구 항목이 아님. pipelineStatus(categoryPending/Failed, summaryPending/Failed)로 현재 운영 확인 가능. 001 범위 밖.

---

### CHK037 ⬜ 해당없음
PENDING 적체 자동 알림은 001 범위 밖. pipelineStatus 노출로 운영자 수동 확인 가능한 수준으로 충분.

---

### CHK038 ⬜ 해당없음
AI 분류 품질 메트릭(정확도, FAILED 비율 추이)은 001 범위 밖. 카테고리별 건수 제공이 현재 수준.

---

### CHK039 ✅ 충족
spec SC-007이 "단계별 처리 결과 추적 가능성"을 요구하고, research #12가 단계(수집·분류·요약) + 필드(runId, sourceName, articlesFound, errors)를 구체적으로 정의함.

---

### CHK040 ⬜ 해당없음
Gemini API 호출 비용·건수 런타임 추적은 001 기능 요구사항에 없음. GCP Console 외부 도구 또는 향후 기능으로 처리.

---

## 종합 결과표

| ID | 상태 | 보완 위치 (미충족만) |
|----|------|----------------------|
| CHK001 | ✅ 충족 | — |
| CHK002 | 🟡 미충족(MEDIUM) | `contracts/openapi.yaml` summaryStatus description 추가 |
| CHK003 | ⬜ 해당없음 | — |
| CHK004 | 🟡 미충족(MEDIUM) | `spec.md` Assumptions — user_saved 001 설계 제약 명시 |
| CHK005 | 🟡 미충족(MEDIUM) | `spec.md` Key Entities — PipelineStats "뷰" → "집계 쿼리"로 수정 |
| CHK006 | ⬜ 해당없음 | — |
| CHK007 | ✅ 충족 | — |
| CHK008 | 🔴 미충족(HIGH) | `plan.md` Phase C — calls_today 리셋 담당 스케줄러 결정 |
| CHK009 | 🟡 미충족(MEDIUM) | `spec.md` 엣지 케이스 — malformed XML = 수집 실패 명시 |
| CHK010 | 🔴 미충족(HIGH) | `spec.md` FR-001 — call_budget 초과 시 동작 추가 |
| CHK011 | ⬜ 해당없음 | — |
| CHK012 | ✅ 충족 | — |
| CHK013 | 🟡 미충족(MEDIUM) | `spec.md` FR-006 — enum 외 응답 → OTHER 처리 명시 |
| CHK014 | 🟡 미충족(MEDIUM) | `contracts/openapi.yaml` brief content description에 길이 기준 추가 |
| CHK015 | 🔴 미충족(HIGH) | `spec.md` 엣지 케이스 — 만료 기사 재수집 시 재활성화 또는 무시 정책 결정 |
| CHK016 | ⬜ 해당없음 | — |
| CHK017 | ✅ 충족 | — |
| CHK018 | ✅ 충족 | — |
| CHK019 | ✅ 충족 | — |
| CHK020 | 🔴 미충족(HIGH) | `plan.md` Phase B — 출처별 독립 트랜잭션 명시 |
| CHK021 | ✅ 충족 | — |
| CHK022 | 🔴 미충족(HIGH) | `contracts/openapi.yaml` `/articles/{id}` — deep FAILED 시 200 응답 명시 |
| CHK023 | ✅ 충족 | — |
| CHK024 | ✅ 충족 | — |
| CHK025 | 🟡 미충족(MEDIUM) | `contracts/openapi.yaml` cursor description — 위치 기반 커서 특성 명시 |
| CHK026 | ✅ 충족 | — |
| CHK027 | ✅ 충족 | — |
| CHK028 | 🟡 미충족(MEDIUM) | `contracts/openapi.yaml` category parameter — 400 반환 명시 |
| CHK029 | 🔴 미충족(HIGH) | `contracts/openapi.yaml` categoryBreakdown — FAILED→OTHER 집계 기준 추가 |
| CHK030 | 🟡 미충족(MEDIUM) | `contracts/openapi.yaml` GET /articles — 0건 응답 예시 추가 |
| CHK031 | ⬜ 해당없음 | — |
| CHK032 | 🟡 미충족(MEDIUM) | CHK022 보완 시 자동 해소 |
| CHK033 | ✅ 충족 | — |
| CHK034 | ✅ 충족 | — |
| CHK035 | ✅ 충족 | — |
| CHK036 | ⬜ 해당없음 | — |
| CHK037 | ⬜ 해당없음 | — |
| CHK038 | ⬜ 해당없음 | — |
| CHK039 | ✅ 충족 | — |
| CHK040 | ⬜ 해당없음 | — |

---

## 우선순위별 보완 목록

### 🔴 HIGH — 구현 전 반드시 보완 (6건)

| ID | 보완 내용 | 파일 |
|----|-----------|------|
| CHK008 | calls_today 리셋 담당 스케줄러 확정 | plan.md Phase C |
| CHK010 | call_budget_daily 초과 시 동작 정의 | spec.md FR-001 |
| CHK015 | 만료 기사 재수집 시 재활성화·무시 정책 결정 | spec.md 엣지 케이스 |
| CHK020 | 출처별 독립 트랜잭션 경계 명시 | plan.md Phase B |
| CHK022 | deep FAILED 시 200 응답 계약 명시 | contracts/openapi.yaml |
| CHK029 | categoryBreakdown FAILED→OTHER 집계 기준 | contracts/openapi.yaml |

### 🟡 MEDIUM — 구현 중 보완 권장 (9건)

| ID | 보완 내용 | 파일 |
|----|-----------|------|
| CHK002 | summaryStatus가 balanced 완료임을 명시 | contracts/openapi.yaml |
| CHK004 | user_saved 001 설계 제약 가정 명시 | spec.md |
| CHK005 | PipelineStats "뷰" → "집계 쿼리" 용어 정정 | spec.md |
| CHK009 | malformed XML = 수집 실패 케이스 추가 | spec.md |
| CHK013 | Gemini enum 외 응답 → OTHER 처리 명시 | spec.md |
| CHK014 | brief 최대 길이 기준 계약 수준 명시 | contracts/openapi.yaml |
| CHK025 | 커서 위치 기반 특성·만료 없음 명시 | contracts/openapi.yaml |
| CHK028 | category 유효하지 않은 값 → 400 명시 | contracts/openapi.yaml |
| CHK030 | 0건 피드 응답 구조 예시 추가 | contracts/openapi.yaml |
