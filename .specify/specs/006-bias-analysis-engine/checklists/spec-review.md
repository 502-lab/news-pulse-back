# Spec Review Checklist: 006 편향 분석 엔진

**Purpose**: spec.md의 요구사항 품질(완전성·명확성·일관성·측정가능성)을 검증하는 유닛 테스트
**Created**: 2026-06-21
**Feature**: [spec.md](../spec.md)
**Scope decision**: US5 기사 비교 → **Option B(별도 스펙 분리)** 확정. 아래 CHK003 참조.

---

## Requirement Completeness — 필수 요구사항 누락 여부

- [x] CHK001 - US5(기사 비교)가 확정 제외됐으나 spec.md US5 섹션에 `[NEEDS CLARIFICATION]` 마커가 그대로 남아 있음. "범위 외(별도 스펙)" 문구로 업데이트되어 있는가? [Gap, Spec §US5]
- [x] CHK002 - 기존 수집 기사(006 배포 시점 이전 기사)에 대한 소급 편향 분석 요구사항이 명시되어 있는가? FR-001은 "새 기사 수집 후"만 다루고 있어 레거시 기사 처리 정책이 누락된 상태다. [Gap, Spec §FR-001]
- [x] CHK003 - Redis 캐싱 요구사항이 명시되어 있는가? CLAUDE.md 코딩 규칙은 "Gemini 요약 결과는 Redis에 캐싱"을 강제하며, 편향 분석 결과 역시 동일 정책 적용 여부가 spec에서 다뤄지지 않는다. [Gap]
- [ ] CHK004 - Gemini API rate limit에 대한 처리 요구사항이 존재하는가? 배치 처리 중 429(Rate Limit) 응답 시 동작이 FR-003의 재시도 정책에 포함되는지 명시되지 않았다. [Gap, Spec §FR-003]
- [ ] CHK005 - `openapi.yaml` 선반영 요구사항이 명시되어 있는가? CLAUDE.md는 "모든 엔드포인트 변경 시 openapi.yaml 선반영"을 필수로 하며, 006에서 신규 추가되는 API 목록과 경로가 spec에 열거되어 있는지 확인이 필요하다. [Gap]
- [ ] CHK006 - Source(출처)가 없는 기사(출처 정보 미수집)에 대해 FR-006의 OutletBiasSummary 처리 정책이 명시되어 있는가? [Gap, Spec §FR-006]
- [ ] CHK007 - Flyway 마이그레이션 요구사항이 언급되어 있는가? BiasAnalysis·BiasAnalysisJob 신규 테이블 생성에 대한 DDL 마이그레이션 작성 의무가 spec 또는 가정(Assumptions)에 명시되어야 한다. [Gap, Constitution §V]

---

## Requirement Clarity — 모호한 표현·정의 미확정

- [x] CHK008 - FR-003의 "3회 재시도" 의미가 명확한가? "최대 3회 재시도"는 총 시도 횟수 3회(초회 포함)인지, 실패 후 추가 3회인지(총 4회) 불명확하다. US2 시나리오 3·4의 "재시도 상한(3회)"과 일치하는가? [Ambiguity, Spec §FR-003]
- [x] CHK009 - FR-003의 지수 백오프 구체값이 정의되어 있는가? "지수 백오프" 초기 대기 시간·최대 대기 시간이 명시되지 않아 구현자마다 다르게 해석될 수 있다. [Clarity, Spec §FR-003]
- [ ] CHK010 - FR-009 "편향 칩 인터랙션 시 API"가 신규 엔드포인트인지, 기존 기사 상세 API 응답에 포함되는 것인지 명확한가? 별도 엔드포인트라면 경로와 응답 스키마가 명시되어야 한다. [Ambiguity, Spec §FR-009]
- [x] CHK011 - FR-006의 OutletBiasSummary 업데이트 방식이 명시되어 있는가? Assumptions에서 "실시간 집계 또는 배치" 중 결정을 plan 단계로 미뤘는데, SC-004("3초 이내 응답")와 이 결정이 상호 영향을 줌. 결정을 spec에서 내려야 하는가? [Ambiguity, Spec §Assumptions]
- [x] CHK012 - BiasAnalysis와 BiasAnalysisJob 두 엔티티의 역할 분리가 명확한가? Key Entities 섹션에서 둘 다 "기사별 편향 분석"과 "비동기 처리 작업"으로 나뉘지만, 어느 시점에 BiasAnalysis 레코드가 생성되는지(PENDING 시점 vs DONE 시점) 정의되어 있지 않다. [Clarity, Spec §Key Entities]
- [ ] CHK013 - FR-002의 Gemini 프롬프트 설계 주체와 위치가 명시되어 있는가? CLAUDE.md는 "Gemini API 프롬프트 변경 시 /specs/adr/ 에 결정 기록"을 요구하며, 초기 프롬프트 설계 ADR 작성 요구사항이 spec에 포함되어야 한다. [Gap, Spec §FR-002]
- [ ] CHK014 - Assumptions의 "편향 점수 −100은 극진보, +100은 극보수"가 AI 출력과 저장 포맷 모두에 적용되는 기준인가? Gemini가 반환하는 값의 정규화 과정(AI 출력 범위 → −100~+100 변환)이 필요한지 불명확하다. [Clarity, Spec §Assumptions]

---

## Requirement Consistency — 요구사항 간 충돌·불일치

- [x] CHK015 - US4(스펙트럼) 범위와 FR-007·Assumptions 간 불일치: US4는 "자신이 읽은 기사(또는 전체 서비스)"라고 표현했으나 Assumptions는 "서비스 전체 기사 기준"으로 확정. US4 시나리오 1의 "편향 분석 완료 기사가 존재"는 누구 기준인지 수정이 필요하다. [Conflict, Spec §US4 vs §Assumptions]
- [x] CHK016 - 버킷 경계값(−34, +34)이 FR-008과 Edge Cases 간 일치하는가? Edge Cases는 "스펙트럼 경계값(−34, +34) 처리"를 별도 명시했으나, FR-008은 "진보(−100~−34)"라고 적어 −34가 진보 버킷에 포함됨을 나타낸다. US4 시나리오 2의 "−100~−34는 진보, −33~+33은 중립"과 비교하면 −34 귀속이 다르게 읽힌다. [Conflict, Spec §FR-008 vs §US4 vs §Edge Cases]
- [x] CHK017 - SC-001("24시간 이내 95% 이상")과 US2 시나리오 구성이 정합하는가? US2는 3회 재시도 후 FAILED를 허용하므로 최대 5%는 점수 없이 유지될 수 있다. SC-001의 "95%" 기준이 이 실패 허용치와 의도적으로 정렬된 값인지 명시되어야 한다. [Consistency, Spec §SC-001 vs §US2]
- [x] CHK018 - FR-010("기사 테이블 오염 금지")과 Key Entities의 BiasAnalysis 설계가 일치하는가? BiasAnalysis의 `articleId` FK 방향이 정의되어 있는가? 분리 저장 원칙이 Article 엔티티에 `biasScore` 컬럼을 절대 추가하지 않음을 의미하는지 명확해야 한다. [Consistency, Spec §FR-010 vs §Key Entities]

---

## Acceptance Criteria Quality — 성공 기준의 측정가능성

- [x] CHK019 - SC-001("24시간 이내 95% 이상")을 어떻게 측정할 것인지 정의되어 있는가? 측정 대상(수집된 기사 전체? 특정 기간?), 측정 주기, 모니터링 방법이 명시되지 않아 객관적 검증이 어렵다. [Measurability, Spec §SC-001]
- [x] CHK020 - SC-004("3초 이내 응답")가 p50/p95/p99 중 어떤 기준인지 명시되어 있는가? "3초"만으로는 성능 SLA를 객관적으로 판단할 수 없다. [Measurability, Spec §SC-004]
- [x] CHK021 - SC-002("biasScore 필드 100% 포함")가 null 응답을 포함하는지 명시되어 있는가? 분석 미완료 기사의 `biasScore: null` 반환이 "100% 포함"에 해당하는지, 아니면 SC-002는 분석 완료 기사만 대상으로 하는지 불명확하다. [Clarity, Spec §SC-002]

---

## Scenario Coverage — 흐름 및 예외 시나리오 누락

- [x] CHK022 - 편향 분석 PROCESSING 중 동일 기사에 대한 중복 트리거 방지(FR-004)가 DONE 상태에서도 적용되는가? US2 시나리오 5에서 "PENDING/PROCESSING/DONE" 모두 중복 방지 대상으로 명시되어 있으나, Edge Cases의 "기존 DONE 결과 유지"와 정합하는지 확인 필요. [Coverage, Spec §US2 vs §Edge Cases]
- [x] CHK023 - 편향 분석 상태가 FAILED인 기사에 대한 재분석 트리거 요구사항이 정의되어 있는가? 운영자가 수동으로 재분석을 요청할 수 있는지, 아니면 FAILED는 영구 상태인지 명시되지 않았다. [Gap, Coverage]
- [x] CHK024 - 동시 다수 기사 수집(스케줄러 배치) 시 편향 분석 작업 생성의 동시성 안전 요구사항이 있는가? FR-004는 멱등성을 요구하지만 DB UNIQUE 제약 또는 낙관적 잠금 등 구현 제약 조건이 spec 수준에서 힌트를 제공해야 할지 검토 필요. [Coverage, Constitution §VII]
- [x] CHK025 - Gemini API 자체가 장기간 불가(서비스 다운)인 경우의 graceful degradation 요구사항이 있는가? 단순 재시도를 넘어 파이프라인 전체가 queue 누적되는 시나리오가 edge cases에 포함되어야 한다. [Gap, Coverage, Constitution §외부 API 복원력]
- [x] CHK026 - BiasSpectrum(US4) 조회 시 캐싱 전략이 명시되어 있는가? 전체 서비스 집계는 조회마다 전체 테이블 스캔이 발생할 수 있으며, 캐시 TTL 또는 집계 방식이 비기능 요구사항에 포함되어야 한다. [Gap, Coverage]

---

## Non-Functional Requirements — 비기능 요구사항 명세

- [x] CHK027 - 편향 분석 API에 대한 인증 요구사항이 명시되어 있는가? FR-005~007이 인증 필요 엔드포인트인지, 공개 엔드포인트인지 spec에서 명확히 해야 한다. Constitution §VI는 "인증 필요가 기본값"임을 요구한다. [Gap, Constitution §VI]
- [x] CHK028 - 편향 분석 비동기 파이프라인의 관측가능성 요구사항(로깅·추적)이 명시되어 있는가? Constitution은 "스케줄·배치 작업은 시작/종료/실패를 관측 가능하게 기록"을 요구하며, 이를 FR 또는 비기능 요구사항으로 포함해야 한다. [Gap, Constitution §구조적 로깅]
- [ ] CHK029 - OutletBiasSummary 집계 쿼리에 대한 성능 요구사항이 SC-004 외에 추가로 필요한가? 출처 수·기사 수가 증가할수록 집계 비용이 달라지므로, 스케일 기준(예: 출처 1000개, 기사 100만 건)이 명시되면 더 측정 가능해진다. [Measurability, Spec §SC-004]

---

## Dependencies & Assumptions — 의존성 및 가정의 유효성

- [x] CHK030 - 001의 `bias_status` PENDING 패턴 재사용 여부가 구체적으로 명시되어 있는가? Assumptions는 "상태 컬럼 확장 또는 별도 테이블로 처리"를 plan 단계로 미뤘는데, 이 결정이 FR-010("기사 테이블 오염 금지")과 충돌하지 않는지 사전 확인 필요. [Assumption, Spec §Assumptions]
- [ ] CHK031 - 편향 스펙트럼(US4)이 009 인사이트의 선행 데이터임이 Assumptions에 명시되어 있으나, 009 미구현 시 US4 API의 scope(서비스 전체 집계만)가 향후 개인화 집계로 확장될 때 하위 호환성 정책이 정의되어 있는가? [Assumption, Constitution §API 버저닝]

---

## Ambiguities & Conflicts — 즉시 결정 필요 항목

- [x] CHK032 - US5(기사 비교) 제거 결정이 spec.md에 반영되어 있는가? 현재 US5 섹션이 `[NEEDS CLARIFICATION]` 마커 상태로 남아 있어, plan/tasks 작성 전 "범위 외 — 별도 스펙으로 분리" 문구로 업데이트가 필요하다. [Gap]
- [x] CHK033 - FR-006 OutletBiasSummary 집계 방식(실시간 vs 배치)이 plan 착수 전 결정이 필요한 사항으로 spec에 명시적으로 표시되어 있는가? SC-004 SLA에 직접 영향을 주는 결정임을 강조해야 한다. [Ambiguity, Spec §Assumptions]

---

## Notes

- `[Gap]` — 요구사항이 누락된 항목
- `[Ambiguity]` — 모호하거나 다중 해석이 가능한 항목
- `[Conflict]` — 스펙 내 두 곳 이상에서 충돌하는 항목
- `[Assumption]` — 검증이 필요한 전제 조건
- 항목 완료 시 `- [x]`로 표시하고 해소 근거를 인라인 주석으로 추가
