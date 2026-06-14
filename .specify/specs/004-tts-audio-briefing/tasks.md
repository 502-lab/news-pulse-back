# Tasks: TTS 음성 — 기사·브리핑 오디오

**Input**: Design documents from `.specify/specs/004-tts-audio-briefing/`

**Prerequisites**: plan.md ✅ | spec.md ✅ | research.md ✅ | data-model.md ✅ | contracts/ ✅ | quickstart.md ✅

**Tests**: 헌법 IV조(Service 단위 테스트 필수) + CLAUDE.md(@DataJpaTest/@WebMvcTest/WireMock Mock 필수)에 의해 포함.

**Organization**: US1(P1) → US2(P1) → US3(P2) → US4(P3). US1·US2 완료 후 독립 검증 가능.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 동일 단계 내 다른 파일, 병렬 실행 가능
- **[Story]**: 해당 유저 스토리 레이블 (US1~US4)
- 파일 경로는 모두 `src/` 기준 상대 경로

---

## Phase 1: Setup (공유 인프라)

**Purpose**: Flyway 마이그레이션 + 신규 Enum 생성 — 모든 도메인 엔티티의 전제 조건

- [ ] T001 Create Flyway migration `src/main/resources/db/migration/V10__add_tts_tables.sql` — `voices` CREATE TABLE + INSERT [TBD] × 2 (하린/준서), `tts_audios` CREATE TABLE (audio_key TEXT, UNIQUE(owner_type,ref_id,voice_id), idx_tts_audios_status PARTIAL WHERE IN('PENDING','PROCESSING')), `daily_briefs` CREATE TABLE (article_ids BIGINT[], UNIQUE(account_id,brief_date)), `ALTER TABLE reading_preferences ADD COLUMN voice_id VARCHAR(50) REFERENCES voices(id) ON DELETE SET NULL`
- [ ] T002 [P] Create `src/main/java/com/newscurator/domain/enums/TtsOwnerType.java` — `enum TtsOwnerType { ARTICLE }` (BRIEF 없음)
- [ ] T003 [P] Create `src/main/java/com/newscurator/domain/enums/TtsStatus.java` — `enum TtsStatus { PENDING, PROCESSING, READY, FAILED }`

**Checkpoint**: 마이그레이션 + Enum — 엔티티 작성 시작 가능

---

## Phase 2: Foundational (블로킹 전제 조건)

**Purpose**: 전체 유저 스토리가 의존하는 도메인 엔티티, 리포지토리, 외부 클라이언트 뼈대

**⚠️ CRITICAL**: 이 Phase 완료 전까지 유저 스토리 구현 시작 불가

- [ ] T004 Extend `src/main/java/com/newscurator/domain/ReadingPreference.java` — `private String voiceId;` 필드 추가, `update()` 메서드 파라미터에 `String voiceId` 추가하여 저장. 기존 summaryDepth·consumeMode 로직 유지.
- [ ] T005 [P] Create `src/main/java/com/newscurator/domain/Voice.java` — `@Entity @Table("voices")`, `@Id String id`, `String name`, `String gender` (VARCHAR — Gender enum 없음, A7: 기존 enums에 Gender 미존재 확인), `String previewUrl`, `Instant createdAt`. @Getter @NoArgsConstructor @Builder.
- [ ] T006 [P] Create `src/main/java/com/newscurator/domain/TtsAudio.java` — `@Entity @Table(uniqueConstraints = @UniqueConstraint({owner_type,ref_id,voice_id}))`, UUID id, `@Enumerated TtsOwnerType ownerType`, String refId, String voiceId, `@Enumerated TtsStatus status` DEFAULT PENDING, `String audioKey` (S3 key, NOT URL), `Integer durationSec` (nullable), `String errorMsg`, Instant createdAt·updatedAt. `resetToPending()` 메서드: status=PENDING, audioKey=null, errorMsg=null, updatedAt=now(). @Getter @NoArgsConstructor @Builder.
- [ ] T007 [P] Create `src/main/java/com/newscurator/domain/DailyBrief.java` — `@Entity @Table(uniqueConstraints = @UniqueConstraint({account_id,brief_date}))`, UUID id, `@ManyToOne LAZY Account account`, `LocalDate briefDate`, `@Column(columnDefinition="BIGINT[]") Long[] articleIds`, String voiceId, Instant createdAt. @Getter @NoArgsConstructor @Builder.
- [ ] T008 [P] Create `src/main/java/com/newscurator/repository/VoiceRepository.java` — `JpaRepository<Voice, String>`. 추가 메서드 불필요 (findAll, findById 충분).
- [ ] T009 Create `src/main/java/com/newscurator/repository/TtsAudioRepository.java` — `JpaRepository<TtsAudio, UUID>`. 추가 메서드: (1) `Optional<TtsAudio> findByOwnerTypeAndRefIdAndVoiceId(TtsOwnerType, String, String)` (2) `@Query(value="SELECT * FROM tts_audios WHERE status='PENDING' LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery=true) List<TtsAudio> findPendingWithLock(@Param("limit") int limit)` — 멀티 인스턴스 중복 처리 방지 필수.
- [ ] T010 [P] Create `src/main/java/com/newscurator/repository/DailyBriefRepository.java` — `JpaRepository<DailyBrief, UUID>`. 메서드: `Optional<DailyBrief> findByAccountIdAndBriefDate(UUID accountId, LocalDate date)`.
- [ ] T011 Create `src/main/java/com/newscurator/client/ai/NaverClovaVoiceClient.java` — 생성자에서 `@Value("${naver.clova.voice.api-key-id}")` / `@Value("${naver.clova.voice.api-key}")` / `@Value("${naver.clova.voice.base-url}")` 주입. `byte[] generate(String voiceId, String text)` 메서드: RestClient로 `POST {base-url}/tts-premium/v1/tts`, 헤더 `X-NCP-APIGW-API-KEY-ID`·`X-NCP-APIGW-API-KEY`, form body `speaker={voiceId}&text={text}&volume=0&speed=0&pitch=0&format=mp3`, 응답 `byte[]` 반환. 4xx/5xx 응답 시 `AiProviderException` throw.
- [ ] T012 Create `src/main/java/com/newscurator/service/S3AudioUploader.java` — (1) `String upload(byte[] mp3Bytes, String audioKey)`: S3 PutObject(key=audioKey, content-type=audio/mpeg) 후 audioKey 반환. (2) `String generateUrl(String audioKey)`: CloudFront 도메인 + audioKey 조합 → URL 반환. `@Value("${cloud.aws.s3.bucket}")`, `@Value("${cloud.aws.cloudfront.domain}")` 주입.
- [ ] T013 Add TTS config to `src/main/resources/application.yaml` and `src/main/resources/application-example.yaml` — `naver.clova.voice.*`(api-key-id/api-key/base-url placeholder), `app.tts.briefing.article-count: 5`, `app.tts.scheduler.cron: "*/30 * * * * *"`, `app.tts.default-voice-id: "[TBD]"` (A6: BriefingService 기본 음성 결정적 선정에 사용), `cloud.aws.cloudfront.domain` placeholder.

**Checkpoint**: 도메인·리포지토리·클라이언트 뼈대 완성 — 유저 스토리 구현 시작 가능

---

## Phase 3: User Story 1 — 음성 선택 및 청취 설정 (Priority: P1) 🎯

**Goal**: 음성 목록 조회 + 선호 voiceId 프로필 저장. 004 기능의 진입점.

**Independent Test**: `GET /api/v1/voices` → 하린·준서 2건 반환. `PUT /api/v1/me/reading-preference {voiceId}` → GET으로 라운드트립 확인. US2 없이 단독 검증 가능.

### Implementation

- [ ] T014 [P] [US1] Create `src/main/java/com/newscurator/dto/response/VoiceResponse.java` — Java record: `(String id, String name, String gender, String previewUrl)`. gender는 String ("FEMALE"/"MALE", A7: Gender enum 미존재). 각 필드 `@Schema(description, example)`.
- [ ] T015 [P] [US1] Create `src/main/java/com/newscurator/service/VoiceService.java` — `List<VoiceResponse> findAll()`: VoiceRepository.findAll() → VoiceResponse 매핑. `void validateVoiceId(String voiceId)`: VoiceRepository.existsById() → false면 throw `InvalidVoiceIdException` (새 exception 또는 IllegalArgumentException — 422로 매핑).
- [ ] T016 [US1] Create `src/main/java/com/newscurator/controller/VoiceController.java` — `@Tag(name="Voice")`, `GET /api/v1/voices`, `@Operation(summary="음성 목록 조회")`, `@ApiResponses(200, 401, 403)`. `ApiResponse<List<VoiceResponse>>` 반환.
- [ ] T017 [P] [US1] Extend `src/main/java/com/newscurator/dto/request/ReadingPreferenceRequest.java` — 기존 `summaryDepth, consumeMode` 유지. `@Schema(description="선호 음성 ID, optional") String voiceId` 필드 추가 (nullable, @Valid 없음).
- [ ] T018 [P] [US1] Extend `src/main/java/com/newscurator/dto/response/ReadingPreferenceResponse.java` — `String voiceId` 필드 추가 (nullable). 기존 `summaryDepth, consumeMode` 유지.
- [ ] T019 [US1] Extend `src/main/java/com/newscurator/service/ProfileService.java` — `updateReadingPreference()`: req.voiceId() non-null이면 VoiceService.validateVoiceId() 호출 (유효하지 않으면 422). ReadingPreference.update(summaryDepth, consumeMode, voiceId) 갱신. `getReadingPreference()`: voiceId 포함 ReadingPreferenceResponse 반환.

### Tests

- [ ] T020 [US1] Unit test `src/test/java/com/newscurator/service/VoiceServiceTest.java` — (1) findAll: mockRepository.findAll() → 2건 VoiceResponse 반환 검증. (2) validateVoiceId valid → no exception. (3) validateVoiceId invalid → exception 발생.
- [ ] T021 [US1] @WebMvcTest `src/test/java/com/newscurator/controller/VoiceControllerTest.java` — (1) GET /api/v1/voices 인증O → 200 + 배열 반환. (2) 미인증 → 401.
- [ ] T022 [US1] @WebMvcTest `src/test/java/com/newscurator/controller/MeControllerTest.java` (기존 파일 확장) — PUT /api/v1/me/reading-preference `{voiceId: "[TBD]"}` → 200, 응답에 voiceId 포함. PUT with 존재하지 않는 voiceId → 422.
- [ ] T023 [US1] @DataJpaTest `src/test/java/com/newscurator/repository/VoiceRepositoryTest.java` — V10 마이그레이션 시드 적용 후 findAll() 2건 반환 확인.

**Checkpoint**: US1 독립 검증 — 음성 목록 조회·저장 완전 동작

---

## Phase 4: User Story 2 — 기사 TTS 생성 및 재생 (Priority: P1)

**Goal**: 기사 요약 → Naver TTS → S3 → READY. 멱등 캐시·FAILED 재시도·FOR UPDATE SKIP LOCKED 포함.

**Independent Test**: POST /articles/{id}/tts → 202 PENDING. GET 폴링 → READY + audioUrl. 동일 재요청 → 200 즉시 반환(캐시). US1 없이 voiceId를 body로 직접 전달하여 단독 검증 가능.

### Implementation

- [ ] T024 [P] [US2] Create `src/main/java/com/newscurator/exception/SummaryNotReadyException.java` and `NoFeedArticlesException.java` — 둘 다 RuntimeException. GlobalExceptionHandler(`src/main/java/com/newscurator/exception/GlobalExceptionHandler.java`)에 각각 추가: `SummaryNotReadyException` → HTTP 409 (`SUMMARY_NOT_READY`), `NoFeedArticlesException` → HTTP 404 (`NO_FEED_ARTICLES`).
- [ ] T025 [P] [US2] Create `src/main/java/com/newscurator/dto/request/TtsRequest.java` — record: `(@NotBlank String voiceId)`. @Schema 포함.
- [ ] T026 [P] [US2] Create `src/main/java/com/newscurator/dto/response/TtsStatusResponse.java` — record: `(UUID id, TtsOwnerType ownerType, String refId, String voiceId, TtsStatus status, String audioUrl, Integer durationSec, String errorMsg)`. audioUrl은 응답 시 S3AudioUploader.generateUrl(audioKey)로 생성. **audioKey==null이면 generateUrl 미호출, audioUrl=null 반환** (PENDING/PROCESSING/FAILED 상태 대응 — A8). @Schema 포함.
- [ ] T027 [US2] Create `src/main/java/com/newscurator/service/TtsService.java` — `TtsStatusResponse requestArticleTts(Long articleId, String voiceId, Account account)` 멱등 4분기: (1) READY → 즉시 반환 (2) PENDING/PROCESSING → 기존 TtsAudio 반환 (3) FAILED → ttsAudio.resetToPending() + save → 반환 (4) 없음 → NEW TtsAudio(PENDING) INSERT. 검증: voiceId VoiceRepository.existsById() false → 422. Article 조회 후 summaryStatus≠COMPLETED → SummaryNotReadyException. **audioUrl 생성 시 `audioKey != null ? s3AudioUploader.generateUrl(audioKey) : null` null 가드 필수 (A8)**.
- [ ] T028 [US2] Add `TtsStatusResponse getArticleTtsStatus(Long articleId, String voiceId)` to `src/main/java/com/newscurator/service/TtsService.java` — TtsAudioRepository.findByOwnerTypeAndRefIdAndVoiceId() → 없으면 404, 있으면 TtsStatusResponse 반환. **audioUrl = audioKey != null ? generateUrl(audioKey) : null (A8)**.
- [ ] T029 [US2] Create `src/main/java/com/newscurator/controller/TtsController.java` — `@Tag(name="TTS")`. `POST /api/v1/articles/{articleId}/tts` (`@Operation`, `@ApiResponses(200,202,401,403,404,409,422)`). `GET /api/v1/articles/{articleId}/tts?voiceId` (`@Parameter(description)`). 200 vs 202 분기: TtsService가 반환한 TtsAudio.status == READY이면 200, 아니면 202.
- [ ] T030 [US2] Implement `src/main/java/com/newscurator/client/ai/NaverClovaVoiceClient.java` full body — T011 skeleton에 실제 RestClient 로직 구현: form-encoded POST, audio/mpeg binary response body to byte[], 4xx/5xx JSON 파싱 후 AiProviderException(errorCode, errorMessage). @Slf4j 추가, API 키 로그 출력 절대 금지.
- [ ] T031 [US2] Create `src/main/java/com/newscurator/scheduler/TtsProcessingScheduler.java` — `@Scheduled(cron="${app.tts.scheduler.cron}")`. `@Transactional`로 `ttsAudioRepository.findPendingWithLock(batchSize)` 조회(FOR UPDATE SKIP LOCKED). 각 TtsAudio: status→PROCESSING 저장 → naverClovaVoiceClient.generate() → s3AudioUploader.upload() → status=READY, audioKey 저장. 실패 시 status=FAILED, errorMsg 저장. 단순 findByStatus(PENDING) 폴링 절대 사용 금지.

### Tests

- [ ] T032 [US2] Unit test `src/test/java/com/newscurator/service/TtsServiceTest.java` — 멱등 4분기 **행 생성 여부 단언 포함 (A1)**:
  - **(1) READY 분기**: `verify(ttsAudioRepository, never()).save(any())` — 기존 READY 행 재사용, DB 수정 없음 단언.
  - **(2) PENDING/PROCESSING 분기**: `verify(ttsAudioRepository, never()).save(any())` — 중복 생성 없음 단언.
  - **(3) FAILED 분기**: `verify(ttsAudio).resetToPending()` 호출됨 + `verify(ttsAudioRepository, times(1)).save(same(existingTtsAudio))` — 동일 객체 1회 save, 새 객체 INSERT 아님 단언.
  - **(4) 없음 분기**: `verify(ttsAudioRepository, times(1)).save(argThat(a -> a.getId() == null || a.getStatus() == PENDING))` — 신규 행 1회 save 단언.
  - 추가: summaryStatus≠COMPLETED → SummaryNotReadyException. 유효하지 않은 voiceId → exception.
- [ ] T033 [US2] WireMock test `src/test/java/com/newscurator/client/NaverClovaVoiceClientTest.java` — WireMock.stubFor(POST .../tts-premium/v1/tts).willReturn(aResponse().withBody(mp3bytes)) → generate() 반환 byte[] 검증. WireMock 4xx → AiProviderException 검증. X-NCP-* 헤더 존재 확인. API 키 값 로그 미포함 단언.
- [ ] T034 [US2] @DataJpaTest `src/test/java/com/newscurator/repository/TtsAudioRepositoryTest.java` —
  - **(1) UNIQUE 제약**: 동일(ownerType,refId,voiceId) 두 번 save → DataIntegrityViolationException.
  - **(2) findPendingWithLock**: PENDING 2건·PROCESSING 1건·READY 1건 → 2건만 반환 확인.
  - **(3) resetToPending UPDATE 검증 (A2)**: FAILED TtsAudio 1건 INSERT 후 `ttsAudio.resetToPending()` + `repository.save(ttsAudio)` 실행 → DB 재조회: `SELECT COUNT(*) WHERE owner_type=? AND ref_id=? AND voice_id=? == 1` (행 수 불변 — INSERT 아닌 UPDATE), `id` 동일, `status=='PENDING'`, `audioKey IS NULL` 단언.
- [ ] T035 [US2] @WebMvcTest `src/test/java/com/newscurator/controller/TtsControllerTest.java` — (1) POST 신규 → 202 + status=PENDING. (2) POST READY 상태 → 200 + audioUrl. (3) POST summaryNotReady → 409. (4) GET 존재하지 않는 TTS → 404.
- [ ] T036 [US2] Unit test `src/test/java/com/newscurator/scheduler/TtsProcessingSchedulerTest.java` — NaverClovaVoiceClient·S3AudioUploader mock 사용 (A4):
  - **(1) 정상 처리**: mock PENDING TtsAudio → `scheduler.process()` 호출 → `ttsAudio.status == PROCESSING` 저장 확인 → `naverClovaVoiceClient.generate()` 1회 호출 → `s3AudioUploader.upload()` 1회 호출 → DB TtsAudio `status == READY`, `audioKey != null` 단언.
  - **(2) Naver API 실패**: `naverClovaVoiceClient.generate()` → AiProviderException throw → DB TtsAudio `status == FAILED`, `errorMsg != null` 단언. S3 업로드 미호출 `verify(s3AudioUploader, never()).upload(any(), any())` 확인.

**Checkpoint**: US2 독립 검증 — TTS 생성·폴링·캐시·스케줄러 전체 동작

---

## Phase 5: User Story 3 — 데일리 브리핑 TTS (Priority: P2)

**Goal**: 오늘의 브리핑 = 상위 N건 COMPLETED 기사 TTS 재생 큐. 하루 캐시, 재요청 즉시 반환.

**Independent Test**: GET /api/v1/briefing/today → 202 + articleIds[] + ttsItems[]. 재요청 → 200 동일 결과(캐시). FeedService.getTopArticles() 최소 N건 데이터 직접 삽입하여 US3 단독 검증.

### Implementation

- [ ] T037 [P] [US3] Create `src/main/java/com/newscurator/dto/response/BriefingResponse.java` — record: `(LocalDate briefDate, List<Long> articleIds, String voiceId, List<TtsStatusResponse> ttsItems)`. `@Schema(description)` 포함. "브리핑 READY" = ttsItems 전체 status=READY.
- [ ] T038 [US3] Create `src/main/java/com/newscurator/service/BriefingService.java` — `BriefingResponse getOrCreateTodayBrief(Account account)`: (1) DailyBriefRepository.findByAccountIdAndBriefDate(today) 존재 → ttsItems 조합하여 반환. (2) 없으면: FeedService를 통해 `summary_status=COMPLETED` 기사 상위 N건 선정(부족 시 가용한 수 사용, **0건이면 NoFeedArticlesException throw → 404**, A5). DailyBrief(articleIds) 저장, 각 articleId에 대해 TtsService.requestArticleTts() 호출(기존 READY 재사용), ttsItems 조합 반환. **voiceId 결정: account.readingPreferences.voiceId 우선, null이면 `${app.tts.default-voice-id}` config 값 사용 (A6 — "첫 번째 행" 비결정적 방식 금지)**.
- [ ] T039 [US3] Create `src/main/java/com/newscurator/controller/BriefingController.java` — `@Tag(name="Briefing")`. `GET /api/v1/briefing/today`, `@Operation`, `@ApiResponses(200,202,401,403,404)`. ttsItems 전체 READY → 200; 아니면 → 202.

### Tests

- [ ] T040 [US3] Unit test `src/test/java/com/newscurator/service/BriefingServiceTest.java` —
  - **(1) 캐시 히트**: 당일 DailyBrief 존재 → `dailyBriefRepository.save()` 미호출, ttsItems 반환.
  - **(2) 신규 생성**: 당일 없을 때 → N건 COMPLETED 기사 선정·DailyBrief 저장·TtsService 호출 N회.
  - **(3) COMPLETED/비-COMPLETED 혼합 선정 단언 (A3)**: 피드 후보를 [COMPLETED-A, non-COMPLETED-B, COMPLETED-C, non-COMPLETED-D, COMPLETED-E] 순으로 mock. N=3 요청 → 생성된 `DailyBrief.articleIds`에 A·C·E만 포함, B·D 제외 단언. 부족분 채우기: COMPLETED 3건 존재하므로 `articleIds.size() == 3` 단언.
  - **(4) COMPLETED 0건**: COMPLETED 기사 0건 mock → `NoFeedArticlesException` 발생 단언 (A5).
- [ ] T041 [US3] @WebMvcTest `src/test/java/com/newscurator/controller/BriefingControllerTest.java` — (1) 신규 → 202 + `ttsItems` 배열 포함(각 항목 id/status 확인). (2) 캐시(전체 READY) → 200. (3) 미인증 → 401. (4) COMPLETED 0건 → 404.
- [ ] T042 [US3] @DataJpaTest `src/test/java/com/newscurator/repository/DailyBriefRepositoryTest.java` — (1) UNIQUE(account_id, brief_date) 중복 저장 → exception. (2) findByAccountIdAndBriefDate 정상 반환.

**Checkpoint**: US3 독립 검증 — 브리핑 재생 큐 생성·캐시 동작

---

## Phase 6: User Story 4 — 저장 기사 "들을 수 있음" 필터 (Priority: P3)

**Goal**: `listenable=true` 시 TTS READY 기사만 반환. 파라미터 미제공 시 기존 동작 유지.

**Independent Test**: 저장 기사 A(TTS READY)·B(없음) → listenable=true → A만 반환. listenable 미제공 → A·B 모두 반환.

### Implementation

- [ ] T043 [US4] Extend `src/main/java/com/newscurator/repository/SavedArticleRepository.java` — `listenable` 필터 쿼리 추가: JPQL 또는 native query로 `tts_audios` JOIN `WHERE ta.status='READY' AND ta.owner_type='ARTICLE' AND CAST(ta.ref_id AS BIGINT) = sa.article_id AND ta.voice_id = :voiceId`(voiceId null이면 any). 기존 커서 페이지네이션 파라미터 유지.
- [ ] T044 [US4] Extend `src/main/java/com/newscurator/service/SavedArticleService.java` — `getSavedArticles()` 오버로드 또는 파라미터 추가: `boolean listenable, String voiceId`. listenable=false → 기존 쿼리. listenable=true → T043 쿼리 사용.
- [ ] T045 [US4] Extend `src/main/java/com/newscurator/controller/SavedArticleController.java` — GET /api/v1/me/saved-articles에 `@RequestParam(defaultValue="false") boolean listenable`, `@RequestParam(required=false) String voiceId` 추가. `@Parameter(description)` 포함. 기존 파라미터(cursor, size) 유지.

### Tests

- [ ] T046 [US4] @WebMvcTest `src/test/java/com/newscurator/controller/SavedArticleControllerTest.java` (기존 파일 확장) — (1) listenable=true → READY 기사만 반환. (2) listenable=false(또는 미제공) → 전체 반환(기존 동작 회귀 없음). (3) listenable=true + voiceId → 해당 voiceId READY만 반환.

**Checkpoint**: US4 독립 검증 — listenable 필터 동작 + 기존 동작 회귀 없음

---

## Phase 7: Polish & Cross-Cutting

**Purpose**: Swagger 문서화, 설정 파일, CHANGELOG, 빌드 검증

- [ ] T047 Add Swagger annotations to all new controllers and DTOs — VoiceController: `@Tag`, `@Operation`, `@ApiResponses`. TtsController: 동일. BriefingController: 동일. VoiceResponse/TtsRequest/TtsStatusResponse/BriefingResponse/ReadingPreferenceRequest·Response 확장분: `@Schema(description, example)` 각 필드. `@Parameter(description)` for all @RequestParam/@PathVariable.
- [ ] T048 Update `src/main/resources/application-example.yaml` — `naver.clova.voice.*` 섹션(api-key-id/api-key/base-url 환경변수 참조), `app.tts.briefing.article-count: 5`, `app.tts.scheduler.cron: "*/30 * * * * *"`, `app.tts.default-voice-id: "[TBD]"` (A6), `cloud.aws.cloudfront.domain` placeholder 추가.
- [ ] T049 Add CHANGELOG.html entry — `tag-feature` 카테고리, 날짜 그룹(오늘), 기능 설명(US1~US4), 결정 이유(Naver Clova Voice 선택 근거, Model B 선택 근거, FAILED UPDATE 방식), 영향 파일 목록. stats bar 항목 수 갱신.
- [ ] T050 Run full build `./gradlew build` — 기존 테스트 회귀 없음 + 신규 테스트 통과. 실패 시 원인 특정·수정 후 재실행.

---

## Dependencies & Execution Order

### Phase Dependencies

```
Phase 1 (Setup)          → 즉시 시작 가능
Phase 2 (Foundational)   → Phase 1 완료 후
Phase 3 (US1)            → Phase 2 완료 후
Phase 4 (US2)            → Phase 2 완료 후 (Phase 3와 병렬 가능)
Phase 5 (US3)            → Phase 4 T027(TtsService) 완료 후 — BriefingService 의존
Phase 6 (US4)            → Phase 4 T006(TtsAudio 엔티티) 완료 후 — Repository JOIN 의존
Phase 7 (Polish)         → Phase 3~6 완료 후
```

### User Story Dependencies

- **US1 (P1)**: Phase 2 완료 후 독립 시작 — US2에 의존 없음
- **US2 (P1)**: Phase 2 완료 후 독립 시작 — US1과 병렬 가능
- **US3 (P2)**: T027(TtsService) 완료 후 시작 — BriefingService가 TtsService.requestArticleTts() 호출
- **US4 (P3)**: T006(TtsAudio) 완료 후 시작 — SavedArticleRepository TtsAudio JOIN 필요

### Within Each Phase (순서)

```
Phase 2:  T004 → T005·T006·T007[P] → T008·T009·T010[P] → T011 → T012 → T013
Phase 3:  T014·T015·T017·T018[P] → T019 → T016 → T020·T021·T022·T023[P]
Phase 4:  T024·T025·T026[P] → T027 → T028 → T029·T030[P] → T031
          → T032·T033·T034·T035[P] → T036
Phase 5:  T037[P] → T038 → T039 → T040·T041·T042[P]
Phase 6:  T043 → T044 → T045 → T046
Phase 7:  T047·T048[P] → T049 → T050
```

---

## Parallel Example: User Story 2 (US2)

```bash
# 병렬 실행 가능 (T024 완료 후):
Task T025: "Create TtsRequest DTO"
Task T026: "Create TtsStatusResponse DTO"

# 순차 실행:
Task T027: "Create TtsService (T025,T026 의존)"
Task T028: "Add getArticleTtsStatus() to TtsService"
Task T029: "Create TtsController (T027,T028 의존)"

# 병렬 실행 가능 (TtsController 완료 후):
Task T030: "Implement NaverClovaVoiceClient"
Task T031: "Create TtsProcessingScheduler"

# 테스트 병렬 실행 (구현 완료 후):
Task T032: "TtsServiceTest (멱등 save 단언)"
Task T033: "NaverClovaVoiceClientTest (WireMock)"
Task T034: "TtsAudioRepositoryTest (FAILED→resetToPending DB 단언)"
Task T035: "TtsControllerTest"
# 순차:
Task T036: "TtsProcessingSchedulerTest (Scheduler mock 단언)"
```

---

## Implementation Strategy

### MVP Scope (US1 + US2 Only)

1. Phase 1: Setup (T001~T003)
2. Phase 2: Foundational (T004~T013)
3. Phase 3: US1 (T014~T023) — 음성 목록·청취 설정
4. Phase 4: US2 (T024~T036) — 기사 TTS 생성·재생 + 스케줄러 테스트
5. **STOP and VALIDATE**: quickstart.md Scenario 1~4 수동 검증
6. Phase 5~6: US3·US4 순차 추가

### Incremental Delivery

```
Setup + Foundational → 도메인 레디
→ US1 완료 → 음성 선택 가능 (독립 릴리스 가능)
→ US2 완료 → 기사 TTS 재생 (핵심 가치)
→ US3 완료 → 브리핑 재생 큐
→ US4 완료 → saved listenable 필터
→ Polish → 빌드 그린
```

---

## Notes

- `[TBD]` voice ID: 구현 전 Naver Clova Voice 콘솔에서 실제 speaker ID 확인 후 V10 시드·T013 config·코드에 교체
- T004 ReadingPreference.update() 수정 시 기존 002 테스트(ProfileService) 회귀 주의
- T009 FOR UPDATE SKIP LOCKED: Spring Data JPA `@Lock(LockModeType.PESSIMISTIC_WRITE)` + `@QueryHints(@QueryHint(name=AvailableHints.HINT_SPEC_LOCK_TIMEOUT, value="-2"))` 조합으로 SKIP LOCKED 구현 또는 native query 사용
- T043 ref_id(VARCHAR)↔article_id(BIGINT) JOIN: `CAST(tts_audios.ref_id AS BIGINT) = saved_articles.article_id` 명시
- T049 CHANGELOG: API 키 등 민감 정보 절대 미기록
- A7: `com.newscurator.domain.enums`에 Gender enum 미존재 확인 완료 → Voice.gender·VoiceResponse.gender 모두 `String` 타입 사용
