# Quickstart: 008 어드민 대시보드 검증

전제: 001·002·005·006·007 머지됨. Docker(Testcontainers `BigmPostgresImage`). V15 마이그레이션 적용.
관리자 계정(ROLE=ADMIN) + 일반 USER 계정 토큰 준비.

## 검증 시나리오

1. **인가(SC-002)**: 비인증 → 모든 `/api/v1/admin/**` 401. USER 토큰 → 403. ADMIN 토큰 → 200. 실 SecurityConfig 로딩(@SpringBootTest, standalone 아님).
2. **사용자 관리(US1)**: ADMIN이 사용자 목록 조회 → role USER→ADMIN→USER 왕복 → 한 사용자 비활성→로그인 차단 확인→재활성.
3. **자기보호 가드(SC-004)**: ADMIN이 (a) 자기 자신 강등/비활성 시도 → 거부, (b) 마지막 ADMIN 강등/비활성 시도 → 거부.
4. **모니터링 빈데이터 안전(SC-007)**: 신규 환경에서 KPI·pipeline·schedulers·collection 조회 → 오류 없이 0/빈값.
5. **★ hidden 일관성(SC-005)**: ADMIN이 기사 1건 hide → (a) 피드·검색·기사상세·트렌드 재집계 입력에서 0건 노출, (b) admin 기사 조회엔 포함, (c) unhide 시 즉시 복귀. research D2 표 #1~#13 경로 전부 확인.
6. **스케줄러 토글 영속(SC-010)**: trend_aggregation 비활성화 → 다음 트리거 skip → 앱 재기동 → 여전히 비활성. 수동 실행 트리거는 동작.
7. **제외 키워드(US3)**: 키워드 추가 → 다음 트렌드 집계서 해당 term 슬롯 배제.
8. **요약 재시도(US3)**: summary_status=FAILED 기사 1건 재시도 트리거 → 재처리 대상 전환.
9. **공지(US4)**: 공지 생성(초안) → 공개 `/api/v1/notices`에 미노출 → 게시 → 노출 → 비게시 → 미노출(SC-006).
10. **어드민 푸시 멱등(SC-008)**: 동일 발송 2회 → outbox 중복 0(uq_outbox_idempotency).
11. **감사(SC-009)**: 위 변형 액션(role변경·비활성·hide·스케줄러토글·공지CRUD·푸시) 각각 → AdminAuditLog 1건(행위자·대상·diff) 기록. 단순 조회는 미기록.
12. **에러 로그(US5)**: 처리 실패(summary/bias/outbox FAILED) → `/api/v1/admin/ops/errors`에 시간역순 노출(신규 테이블 없이 집계).

## 크라운주얼 테스트(통합)
- `AdminAuthorizationIT`: 5개 영역 대표 엔드포인트 × {비인증401·USER403·ADMIN200}.
- `ArticleHiddenConsistencyIT`(실 PG): hide 후 피드·검색·트렌드 추출·상세 0건, admin 포함, unhide 복귀.
- `LastAdminGuardIT`: 자기/마지막 ADMIN 강등·비활성 거부.
- `SchedulerTogglePersistenceIT`: 토글→skip→재기동 유지.
- `AdminAuditCaptureTest`(단위): 변형 액션별 audit 1건 + diff.
- `AdminPushIdempotencyIT`: 동일 발송 중복 0.
