# ADR-001: 키워드 추출(Nori) · 이슈 클러스터링(co-occurrence) · 재산출(re-derive)

**Status**: Accepted
**Date**: 2026-06-23
**Feature**: 007 트렌드 집계
**위치 주의**: CLAUDE.md는 ADR을 `/specs/adr/`에 두도록 안내하나 `specs/`는 수정 금지 submodule이므로
feature-local(`.specify/specs/007-trend-aggregation/adr/`)에 기록한다.

---

## Context

007 트렌드 집계는 (1) 기사 본문에서 키워드(명사)를 추출하고, (2) 키워드 시계열을 슬롯으로 집계해
Top5/WoW/히트맵/워드클라우드를 서빙하며, (3) 키워드 동시출현으로 "이슈"(관련 기사 묶음 + 증감 + 대표
키워드 3)를 도출한다. 추출 품질·클러스터 품질·이슈 영속성 모델에 대한 결정이 필요했다.

## Decision 1 — 키워드 추출: Lucene Nori (형태소 분석)

한국어 명사 추출에 **Apache Lucene Nori**(`nori-9.12.0`)를 채택한다.

- **DecompoundMode.NONE**: 복합명사를 분해하지 않고 통째로 유지한다. DISCARD는 `대한민국→[대한,민국]`,
  `월드컵`·`백혈병` 등 의미 단위를 과분할해 트렌드 키워드로 부적합했다(진단에서 확인).
- **UserDictionary**: Nori 사전에 없는 OOV 고유명사(`연준`, `홍명보` 등)를 사용자 사전으로 보강.
- **stopwords**: 조사·일반어 잡음 제거.

근거: 형태소 분석기 없이 공백 토큰화하면 조사 결합형("금리가","금리는")이 별도 키워드로 흩어진다.
Nori는 명사 원형을 안정적으로 뽑아 슬롯 집계의 식별자(term) 일관성을 보장한다.

대안: (a) 공백/정규식 토큰화 — 조사 처리 불가로 기각. (b) 외부 NLP API — 비용·지연·100req/day 한도와
충돌로 기각. Nori는 인프로세스라 비용 0.

## Decision 2 — 이슈 클러스터링 MVP: 키워드 동시출현(co-occurrence) 그래프

`IssueClusterer` 포트 뒤에 **`CoOccurrenceIssueClusterer`**를 둔다(TtsProvider/PushNotificationPort와
동일 격리 패턴 — 트렌드 집계·데이터모델·API 불변으로 구현 교체 가능).

알고리즘:
1. 기사별 키워드 쌍의 동시출현 빈도(edge weight = 함께 등장한 기사 수)를 센다.
2. `edge weight ≥ min-edge-weight(=2)`인 쌍만 그래프 간선으로 채택 — **우연 1회 동시출현 차단**.
3. union-find로 연결성분을 구한다.
4. 키워드 수 `≥ min-cluster-size(=2)`인 성분만 이슈로 승격.
5. 대표 키워드 = 성분 내 동시출현 가중치 상위 3, article_ids = 성분 키워드 포함 기사,
   delta = 멤버 키워드 WoW delta(non-null) 평균.

근거: 임베딩·외부 호출 없이 인프로세스로 즉시 동작하고, 이미 추출된 `article_keyword`만으로 재현 가능.

### ⚠️ 알려진 한계 — transitive bridge over-merge (가리지 않음)

연결성분(transitive closure) 방식은 **단 하나의 약한 다리에도 별개 토픽이 전이적으로 합쳐진다.**
검증(`CoOccurrenceIssueClustererTest` case (c)): 토픽 A{금리,인상,경제}(3기사 내부응집) + 토픽
B{부동산,대출,규제}(3기사) + 다리(금리·부동산 2기사 동시출현, weight 2 = 임계) → **A·B가 키워드 6개
짜리 단일 blob 이슈 1개로 over-merge됨**(대표 top3 = [금리,부동산,경제]). 테스트는 이 실제 동작을
그대로 단언한다(합쳐짐을 숨기지 않음).

**완화책(현재)**: `min-edge-weight=2`로 우연 1회 동시출현은 차단. 단 weight≥2 다리는 여전히 병합.

### v2 업그레이드 경로 (범위 밖, 미래 참조)

over-merge 품질이 실사용에서 문제로 확인되면 `IssueClusterer` 구현만 교체:
1. **임계 상향** — `min-edge-weight` 3+ (우발 다리 추가 차단, 단 약한 진짜 군집 손실 트레이드오프).
2. **cohesion/bridge 분할** — 성분 내부 밀도 대비 약한 다리(edge betweenness)를 컷.
3. **community detection** — Louvain/Leiden 모듈러리티로 다리를 경계로 인식해 토픽 분리.
4. **임베딩 클러스터링**(spec v2 경로) — Gemini embeddings + 코사인 유사도(DBSCAN 등). 비용·지연 고려.
   `EmbeddingIssueClusterer` 추가 형태. 상세는 별도 후속 스펙/ADR.

`clustering_method` 컬럼(현재 `CO_OCCURRENCE` 고정)으로 산출 방식을 추적해 전환기 A/C 혼재에 대비.

## Decision 3 — 이슈 영속성: re-derive (OI-4, 안정 ID 없음)

이슈는 **매 집계마다 재산출**하고 `issue_snapshot`을 **TRUNCATE+INSERT 단일 TX로 전량 교체**한다
(clean cutover). cross-run 안정 이슈 ID는 두지 않는다.

근거:
- 이슈 증감(delta)은 멤버 키워드의 WoW delta 집계로 파생되고, 키워드(term)가 안정 식별자다 →
  이슈 자체의 persistence 불필요.
- FR-012 "재산출 가능"과 정합. 클러스터러 교체 시 재클러스터링만으로 전환.
- 안정 이슈 ID가 필요한 005 rising 알림 참조·UI 이슈 추적은 007 범위 밖 → 미래 seam으로 문서화
  (필요해질 때 추가). 검증: `TrendIssueRederiveIT`가 RESTART IDENTITY로 id 재시작 + 스테일 0 확인.

## Decision 4 — 트렌드 read 캐싱: 인메모리, 집계 주기 정렬 (R-006)

`ConcurrentMapCacheManager`(Redis 미사용) + `@Cacheable`로 5개 read를 캐싱한다. TTL은 없고 무효화는
`aggregate()`의 **TX 커밋 후(afterCommit) evict**로만 한다 → **캐시 신선도 = 집계 주기**(기본 10분).

`@CacheEvict`를 쓰지 않은 이유: `@CacheEvict`(기본 `beforeInvocation=false`)는 캐시/트랜잭션 advisor
순서가 고정되지 않아 evict가 **커밋 전**에 실행될 수 있고, 그 사이 동시 read가 커밋 전 데이터를 재적재해
최대 1주기 stale이 남는 race가 있다. 대신 `TransactionSynchronizationManager.registerSynchronization`으로
`afterCommit`에 evict를 등록해 그 창을 제거한다(`aggregate()`는 항상 `@Transactional` 경계 안에서 외부
호출되므로 동기화 활성; 비활성 시 즉시 evict 폴백). 롤백 시엔 evict 미발생 → 데이터·캐시 모두 불변(정합).
검증: `TrendCacheFreshnessIT`(커밋 후 즉시 반영 + 롤백 시 캐시 유지). 단일 인스턴스 전제(multi-instance 시
분산 캐시 필요 — 미래 seam).

---

## 런타임/배포 시 검증으로 이연한 게이트 (Deferred)

아래는 코드만으로 닫을 수 없고 실 데이터/실 환경에서 평가해야 하는 항목이다. 구현 시점에 미검증임을 명시한다.

1. **실 corpus 클러스터 품질 평가** — over-merge의 *실 심각도*는 실제 기사 corpus에서만 판단 가능.
   배포 후 이슈 결과를 표본 평가해 over-merge가 사용자 체감 문제면 Decision 2의 v2 경로로 포트 교체.
2. **SC-002 p95 응답 수치 (Jace)** — 트렌드 조회 p95 목표 수치는 plan 미확정. 실 부하/모니터링으로 측정·확정.
3. **Nori 키워드 품질 실기사 재평가** — DecompoundMode.NONE + UserDictionary 튜닝은 진단 표본 기준.
   실 수집 기사로 재평가해 사전/스톱워드 보강 여부 결정.
4. **배포 환경 article_keyword 재추출** — dev/prd DB에 기존 article_keyword가 존재하면 Nori 튜닝
   반영을 위해 재추출 필요(현재 dev는 V14 미적용·데이터 없음 → 최초 추출이 튜닝 후라 불필요).
