# Research: 007 트렌드 집계 엔진

**Date**: 2026-06-22

---

## R-001 이슈 모델 — re-derive (OI-4 확정)

**Decision**: 이슈는 매 집계 재산출(re-derive)한다. 지속 엔티티(안정 cross-run ID) 아님.

**Rationale**:
- 이슈 증감은 멤버 키워드의 WoW delta 집계로 파생(키워드 `term`이 안정 식별자) → 이슈 persistence 불필요.
- FR-012 "재산출 가능"과 정합.
- 안정 이슈 ID가 필요한 005 rising 알림 참조·UI 이슈 추적은 007 범위 밖 → persistence는 **미래 seam**으로 문서화(필요 시 추가).
- `clusteringMethod`는 정보성(감사/A·B) + 메서드 교체 시 전량 클린 컷오버.

**구현 형태**: 이슈는 스케줄러가 현재 윈도우에서 산출해 `issue_snapshot` 테이블을 **매 실행 전량 교체**(clean cutover)한다. read는 최신 스냅샷을 서빙(FR-013 "on-the-fly 재계산 안 함"과 정합). 스냅샷 행은 cross-run 안정 ID를 보장하지 않는다.

**Q5 reconcile**: `clustering_method`는 `issue_snapshot` 컬럼으로 둔다(default `'CO_OCCURRENCE'`). 지속 엔티티가 아니므로 "마이그레이션 없는 v2 전환"은 issue_snapshot 재작성 로직 교체로 달성. 키워드/슬롯은 영향 없음.

---

## R-002 키워드 추출 — Lucene Nori

**Decision**: `org.apache.lucene:lucene-analysis-nori` 신규 도입. `KeywordExtractor` 포트 뒤에 `NoriKeywordExtractor` 구현.

- 추출 대상: 기사 제목 + AI 요약(`summaries.content`, BALANCED 슬롯 우선), 요약 없으면 제목만.
- 명사 추출: Nori `KoreanTokenizer` + `KoreanPartOfSpeechStopFilter`로 NNG(일반명사)/NNP(고유명사)만 유지. 나머지 품사 제거.
- 불용어: Nori 기본 + 커스텀 한국어 불용어 리소스(`stopwords-ko.txt`) 추가 필터.
- 기사당 1회(article-level dedup): 한 기사에서 같은 term은 1회만(Set).

**Version pin**: `lucene-analysis-nori:9.12.0`(Lucene 9 안정 라인, Java 21+ 호환). 구현 시 Spring Boot 4 / Java 25 환경에서 토크나이저 동작 검증. (대안 10.x는 추후 업그레이드.)

**Rationale**: 순수 JVM(네이티브 없음) → EC2 단일 인스턴스 배포 단순. 사전 내장. 기존 스택에 형태소 자산 없음(코드 확인 — pg_bigm은 substring, FollowKeyword/rationale_keywords는 목적 다름).

**Alternatives**: KOMORAN(사전 파일·메모리 부담), Mecab-ko(네이티브 바이너리 → 배포 복잡) 기각.

---

## R-003 데이터 모델 — 슬롯 집계 테이블 + 스냅샷

**Decision**: 3개 신규 테이블(V14), 이슈는 스냅샷.

1. `article_keyword` (article_id, term) PK(article_id, term) — 기사당 추출 키워드(durable, article-level dedup). 재추출 멱등(PK 충돌 무시).
2. `trend_keyword_slot` (slot_start, category, term, article_count) PK(slot_start, category, term) — (시간슬롯×카테고리×term) 집계. article_keyword JOIN articles로 멱등 UPSERT.
3. `issue_snapshot` (id, derived_at, clustering_method, delta, keywords text[], article_ids bigint[]) — 최신 co-occurrence 이슈, 매 실행 전량 교체.

**Rationale**: 슬롯 집계를 실체 테이블로 저장 → read는 인덱스 스캔(FR-015 "저장 집계 + 인덱스 read 1순위, Redis 미사용"). 모두 article_keyword(→articles)에서 재산출 가능(FR-003/012). 규모 증가 시 trend_keyword_slot을 materialized view로 전환.

---

## R-004 집계 멱등 흐름

**Decision**: 스케줄러(default 10분, `app.scheduler.trend.interval-ms`)가 다음을 멱등 수행:
1. **추출**: 최근 윈도우(예: 최근 25h, 24h 윈도우 + 버퍼) 기사 중 article_keyword 미보유 기사에 대해 KeywordExtractor 실행 → `INSERT ... ON CONFLICT DO NOTHING`.
2. **슬롯 집계**: 영향 슬롯(최근 윈도우 + WoW 2주 경계)에 대해 `trend_keyword_slot` UPSERT — `slot_start = date_trunc('hour', first_collected_at)`, category별 GROUP BY, `article_count = COUNT(DISTINCT article_id)`. 동일 슬롯 재계산은 동일 결과(멱등).
3. **이슈 재산출**: 현재 윈도우에서 co-occurrence 클러스터링 → `issue_snapshot` 전량 교체(TRUNCATE + INSERT, 단일 TX).
4. 시작/종료/처리 건수/실패 건수 log.info (FR-014).

**멱등 키**: article_keyword PK, trend_keyword_slot PK. 재실행/중첩 실행 안전(Constitution VII). 단일 EC2 fixedDelay 전제(004/005/006 동일), scale-out 시 ShedLock.

---

## R-005 조회 API (public, permitAll)

| 경로 | 설명 | 소스 |
|------|------|------|
| `GET /api/v1/trends/keywords/top5?category=` | 급상승 Top5 (term/count/deltaPct/isNew) | trend_keyword_slot, 최근 24h |
| `GET /api/v1/trends/wordcloud?window=` | (term, weight) 목록 | trend_keyword_slot |
| `GET /api/v1/trends/heatmap?window=` | 슬롯×카테고리 강도 격자 | trend_keyword_slot |
| `GET /api/v1/trends/wow` | WoW 급상승(평활비 정렬, isNew) | trend_keyword_slot 2주 |
| `GET /api/v1/trends/issues` | 최신 이슈 스냅샷 | issue_snapshot |

**인증**: 전부 permitAll. SecurityConfig에 `/api/v1/trends/**` 명시 permitAll + 코멘트(Constitution VI: 공개 엔드포인트 명시 선언). 비민감 전역 집계.

**deltaPct/정렬(Q3)**: 기존(prev≥1) deltaPct = raw `(cur−prev)/prev`(표시), 정렬 = 평활비 `(cur+1)/(prev+1)`. prev=0 → deltaPct=null + isNew=true. cur<2(노이즈컷, `app.trend.min-article-count`) 제외. 평활 상수 `app.trend.smoothing-k`(default 1).

---

## R-006 캐싱·rate-limit (FR-015, no Redis)

**Decision**: 1순위 = 인덱스 기반 저장 집계 read(추가 인프라 없음). 캐싱은 Spring 인메모리(`ConcurrentMapCacheManager`) 단기 TTL을 옵션으로 적용(트렌드는 집계 주기마다 갱신되므로 수십 초 캐시 안전). Redis 미도입. rate-limit은 공개 read 보호용 경량 필터(향후, plan 결정/Jace).

**Rationale**: 저볼륨·저빈도 변경 데이터 → 인메모리 캐시로 충분. 규모 증가 시 matview.

---

## R-007 Configuration

```yaml
app:
  scheduler:
    trend:
      interval-ms: 600000        # 집계 주기 default 10분
      cleanup-cron: "0 30 3 * * *"   # 90일 경과 슬롯 정리
  trend:
    slot-hours: 1               # 슬롯 단위(시간)
    top5-window-hours: 24       # 실시간 Top5 윈도우
    retention-days: 90          # 슬롯 보존
    min-article-count: 2        # 노이즈 컷(윈도우 내 기사 수)
    smoothing-k: 1              # WoW 평활 상수(add-one)
    extract-window-hours: 25    # 추출/집계 대상 윈도우(24h+버퍼)
    summary-wait-hours: 1       # PENDING 요약 대기 한도(초과 시 제목만 fallback)
    cooccurrence:
      min-edge-weight: 2        # 두 키워드 동시출현 최소 기사 수(over-merge 방지)
      min-cluster-size: 2       # 이슈 승격 최소 키워드/기사 수
```

---

## R-008 Open Items → plan 처리 / Jace TODO

- **OI-1 표시 vs 정렬**: deltaPct(raw) 표시 + 평활비 정렬. 응답에 둘 다 노출하지 않고 정렬만 평활 기반(서버 정렬). UX 표현(배지/문구)은 Jace/UX. → plan 데이터 계약에 반영, UX는 TODO.
- **OI-2 평활 상수**: `smoothing-k` default 1, 환경변수. plan 확정.
- **OI-3 isNew 노출**: 응답에 `isNew` 필드 제공, 화면 배치(혼합 vs 별도 섹션)는 Jace/UX TODO.
- **SC-002 p95**: 측정조건·동시요청 수 Jace TODO(저볼륨·인덱스 read라 여유 예상).
