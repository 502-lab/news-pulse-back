# Quickstart: 009 읽기 추적 검증

전제: 001~008 머지됨. Docker(Testcontainers `BigmPostgresImage`). V17 마이그레이션 적용. 인증 USER 토큰 2개(A·B) + 기사 시드.

## 검증 시나리오

1. **조회 기록(US1, SC-001)**: 사용자 A가 `GET /api/v1/articles/{id}` 호출 → `article_event`에 (A, id, VIEW, SERVER, now) 1건. 상세 응답 정상.
2. **★ best-effort 격리(US1, SC-002)**: 기록 경로가 예외를 던지는 상황에서도 `GET /articles/{id}`는 **200 정상 반환**(기록 실패가 상세를 안 깸). 별개 TX이라 getDetail의 lazy 요약 write도 영향 없음.
3. **디바운스 30분(D1)**: A가 같은 기사를 30분 내 3회 조회 → `article_event` VIEW 1건만(2·3회차 skip). 30분 경과 후 조회 → 추가 1건.
4. **비로그인 미기록(D3)**: 인증 없이(또는 토큰 없이) 상세 접근 → `account_id` NOT NULL이라 기록 0건(애초에 상세가 인증 필요).
5. **읽은수 distinct(US2, FR-006)**: A가 서로 다른 기사 3건 조회(+ 같은 기사 재조회) → `GET /api/v1/me/read-count` = **3**(distinct article).
6. **조회 이력 역순(US2, FR-007)**: A가 기사 여러 건 조회 → `GET /api/v1/me/read-history` = 최신 조회순 페이지(동일 기사 다회는 최신 1건). 커서 페이지네이션.
7. **본인 스코프(US2, SC-003)**: 사용자 B 토큰으로 read-count/history 호출 → B 데이터만(A 데이터 0건 노출).
8. **forward-seam(SC-005)**: `event_type`/`metric_value`/`source` 컬럼이 존재하되 P1은 VIEW·SERVER·null만 기록 — 후속 클라 이벤트가 스키마 변경 없이 수용 가능함을 확인(빈 구현 없음).

## 크라운주얼 테스트(통합/단위)

- `ReadTrackingServiceTest`(단위): 디바운스 판정(30분 내 skip / 경과 후 insert), VIEW·SERVER만 기록.
- `ReadTrackingBestEffortIT`(★ 실 PG): 기록 INSERT 강제 실패(예: 제약 위반 모킹/잘못된 입력) 시에도 `GET /articles/{id}` 200 + getDetail TX(요약 write) 정상 — **별개 TX 격리 증명**.
- `ArticleViewRecordIT`(실 PG): 상세 조회 → article_event 1건 + 디바운스 30분 윈도우.
- `ReadHistoryIT`(실 PG): 읽은수 distinct + 이력 역순 + 본인 스코프(타인 0).
- `ArticleEventRepositoryTest`(Testcontainers): 조건부 INSERT 영향행수(1/0), distinct count, history page.

## 런타임/배포 시 검증 이연

- 실 트래픽에서 핫패스 p95 영향(SC-002 정량) — 배포 환경 측정.
- 대량 성장 시 파티셔닝/보존정책 — 후속.

## 검증 상태 (2026-06-24, US1·US2 구현 완료)
실 PG(BigmPostgresImage) 테스트로 자동 검증됨:
- #1 조회 기록 → `ArticleViewRecordIT`·`ArticleEventRepositoryTest`
- #2 ★ best-effort 격리 → `ReadTrackingBestEffortIT`(recordView 실패 → 상세 200 + DEEP 요약 보존 + article_event 0)
- #3 디바운스 30분 → `ArticleViewRecordIT`(30분내 1행/경과 2행/다른기사 독립)·`ArticleEventRepositoryTest`
- #5 읽은수 distinct → `ReadHistoryIT`·`ArticleEventRepositoryTest`
- #6 이력 역순·article 1건 → `ReadHistoryIT`·`ReadHistoryServiceTest`
- #7 본인 스코프 → `ReadHistoryIT`(B 토큰 → A 데이터 0)
- #8 forward-seam → `ArticleViewRecordIT`(VIEW·SERVER·metric_value=null 단언)
- #4 비로그인 미기록 → 상세가 인증 필요(SecurityConfig authenticated), account_id NOT NULL로 구조적 보장.

런타임/배포 시 검증 이연: 실 트래픽 p95(SC-002 정량)·대량 성장 파티셔닝.
