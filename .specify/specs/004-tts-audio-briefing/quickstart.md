# Quickstart Validation Guide: 004 TTS 음성 — 기사·브리핑 오디오

**Branch**: `004-tts-audio-briefing` | **Date**: 2026-06-13

이 가이드는 spec 004 구현 완료 후 기능이 end-to-end로 동작하는지 검증하는 시나리오를 기술한다. 구현 코드 자체는 포함하지 않는다.

---

## Prerequisites

1. Docker 실행 중 (Testcontainers용 PostgreSQL)
2. `application-local.yaml` 설정:
   - `naver.clova.voice.api-key-id`, `naver.clova.voice.api-key` (실제 테스트 시 필요, 통합 테스트는 WireMock)
   - `cloud.aws.s3.bucket` (실제 S3 또는 LocalStack)
   - `app.tts.briefing.article-count: 5`
3. Flyway V10 마이그레이션 정상 적용 (`voices` 시드 포함 — 화자 ID [TBD] 확인 필요)
4. 003 개인화 피드 API 동작 중 (브리핑 테스트 전제)

---

## Scenario 1: 음성 목록 조회 (US1)

**목적**: `voices` 테이블 시드가 정상 적용되고 API가 반환하는지 확인

**Command**:
```
GET /api/v1/voices
Authorization: Bearer {jwt_token}
```

**Expected**:
- HTTP 200
- `data` 배열에 2개 항목 (하린, 준서)
- 각 항목: `id`, `name`, `gender`, `previewUrl` 포함
- `previewUrl` null 아님 (시드 데이터에 샘플 URL 설정)

**Failure Indicators**:
- 401 → JWT 토큰 만료 또는 이메일 미인증
- 빈 배열 → V10 마이그레이션 시드 미적용

---

## Scenario 2: 청취 설정 저장 (US1 확장)

**목적**: `reading_preferences.voice_id` 컬럼이 정상 추가되었는지 확인 (M2: readMode 미검증, consumeMode 기존 동작)

**Command**:
```
PUT /api/v1/me/reading-preference
Authorization: Bearer {jwt_token}
Content-Type: application/json

{ "summaryDepth": "BALANCED", "consumeMode": "LISTEN", "voiceId": "[TBD-하린-ID]" }
```

**Expected**:
- HTTP 200
- 응답 `data.consumeMode = "LISTEN"`, `data.voiceId = "[TBD-하린-ID]"`

**Follow-up (라운드트립)**:
```
GET /api/v1/me/reading-preference
```
→ 동일 consumeMode·voiceId 반환 확인

---

## Scenario 3: 기사 TTS 생성 → 상태 폴링 → READY 확인 (US2 핵심)

**목적**: TTS 비동기 파이프라인 전체 흐름 검증

**Step 1 — TTS 생성 요청**:
```
POST /api/v1/articles/{articleId}/tts
Authorization: Bearer {jwt_token}
Content-Type: application/json

{ "voiceId": "[TBD-하린-ID]" }
```

**Expected Step 1**: HTTP 202, `data.status = "PENDING"`, `data.id` (jobId) 포함

> 전제: articleId의 기사가 `summary_status = COMPLETED` 상태여야 함.
> 미완료 기사의 경우 HTTP 409 (`SUMMARY_NOT_READY`) 반환 확인.

**Step 2 — 상태 폴링**:
```
GET /api/v1/articles/{articleId}/tts?voiceId=[TBD-하린-ID]
Authorization: Bearer {jwt_token}
```

**Expected Step 2**: 처음엔 PENDING/PROCESSING, 약 30초 이내 READY 전이

**Step 3 — READY 상태 확인**:
- `data.status = "READY"`
- `data.audioUrl` 값 있음 (CloudFront URL — S3 audio_key에서 생성)
- `data.durationSec` null 허용 (Naver API 미제공, MP3 메타 파싱 시 채워짐)

---

## Scenario 4: 멱등 캐시 확인 (US2 핵심)

**목적**: 동일 조합 재요청 시 새 행 생성 없이 즉시 반환하는지 확인

**Command** (Scenario 3 READY 후 동일 조합 재요청):
```
POST /api/v1/articles/{articleId}/tts
Authorization: Bearer {jwt_token}
Content-Type: application/json

{ "voiceId": "[TBD-하린-ID]" }
```

**Expected**:
- HTTP 200 (202 아님)
- `data.status = "READY"`
- `data.audioUrl` = Scenario 3과 동일 URL
- DB `tts_audios` 행 수 변화 없음 (신규 행 미생성)

---

## Scenario 5: 데일리 브리핑 재생 큐 생성 → 폴링 → READY 확인 (US3 핵심)

**목적**: 브리핑 재생 큐 모델(Model B) 검증 — 단일 합성 오디오 아닌 기사별 TTS 배열

**Step 1 — 브리핑 초회 요청**:
```
GET /api/v1/briefing/today
Authorization: Bearer {jwt_token}
```

**Expected Step 1**: HTTP 202
- `data.articleIds` → 5건 이하 기사 ID 목록 (순서 있음)
- `data.ttsItems` → 각 기사의 TTS 상태 배열 (초기엔 대부분 PENDING)
- `data.ttsItems` 길이 = `data.articleIds` 길이 (1:1 대응)

> 주의: `data` 최상위에 단일 `tts` 객체 없음. `ttsItems[]` 배열임.

**Step 2 — 개별 기사 TTS 폴링** (각 ttsItem.status가 READY 될 때까지):
```
GET /api/v1/articles/{articleId}/tts?voiceId=[TBD]
```
→ 각 기사 READY 후 약 30초 이내. 브리핑 5건 전체 완료 ≈ 60초 이내(SC-004).

**Step 3 — 전체 READY 후 브리핑 재조회**:
```
GET /api/v1/briefing/today
```
- HTTP 200 (모든 ttsItems READY)
- `data.ttsItems` 전체 `status = "READY"` + `audioUrl` 포함

**Step 4 — 당일 캐시 확인** (Step 3 직후 재요청):
- HTTP 200 즉시 반환 (< 1초)
- 동일 `articleIds`, 동일 `ttsItems` 배열

---

## Scenario 6: 저장 기사 listenable 필터 (US4)

**목적**: TTS READY 기사만 필터링하는지 확인

**Precondition**: 저장 기사 중 일부는 TTS READY, 일부는 없음

**Command**:
```
GET /api/v1/me/saved-articles?listenable=true
Authorization: Bearer {jwt_token}
```

**Expected**:
- TTS READY 기사만 반환
- listenable 미제공 시 전체 목록 반환 (하위 호환)

---

## Integration Test Checklist

구현 완료 후 아래 항목을 실제 테스트 코드로 검증:

**TtsAudio 상태·멱등**:
- [ ] READY 상태 재요청 → HTTP 200, 동일 jobId, DB 행 수 불변
- [ ] PENDING/PROCESSING 재요청 → HTTP 202, 동일 jobId 반환 (중복 행 없음)
- [ ] FAILED 상태 기존 행 → `resetToPending()` UPDATE 후 status=PENDING, audio_key=NULL, error_msg=NULL (INSERT 아님)
- [ ] FAILED UPDATE 후 스케줄러 재처리 → READY 전이 확인

**TtsService 예외**:
- [ ] 요약 PENDING 기사 TTS 요청 → `SummaryNotReadyException` / 409

**TtsProcessingScheduler**:
- [ ] PENDING 조회 쿼리에 `FOR UPDATE SKIP LOCKED` 포함 확인 (Repository 쿼리 검증)
- [ ] 멀티 스레드 동시 실행 시 동일 행 중복 처리 없음 (동시성 테스트)

**BriefingService**:
- [ ] 당일 브리핑 존재 → 캐시 반환 (Naver API 미호출)
- [ ] 브리핑 생성 시 summary_status=PENDING 기사 제외, COMPLETED만 포함
- [ ] ttsItems 배열 = articleIds 배열과 1:1 대응

**NaverClovaVoiceClientTest**:
- [ ] WireMock으로 API 응답 mock → audioBytes 정상 처리 → S3 업로드 → audio_key 저장
- [ ] WireMock으로 4xx 응답 → `AiProviderException` 발생 → TtsAudio FAILED 갱신

**Controller**:
- [ ] `POST /articles/{id}/tts` 신규 → 202 + PENDING
- [ ] `GET /briefing/today` 신규 → 202, `ttsItems` 배열 포함
- [ ] `GET /briefing/today` 재요청 (전체 READY) → 200, ttsItems 전체 status=READY
- [ ] `GET /me/saved-articles?listenable=true` → READY 기사만 포함

**M2 검증**:
- [ ] `PUT /me/reading-preference` voiceId 포함 → 200, 응답에 voiceId 반영
- [ ] `readMode` 필드 미존재 확인 (consumeMode만 존재)

---

## Build Verification

```bash
./gradlew build
```

기대 결과: 기존 테스트 회귀 없음 + 신규 테스트 통과.

---

## Configuration Reference

`application-example.yaml` 추가 항목 (V10 구현 시 반영):

```yaml
app:
  tts:
    briefing:
      article-count: 5       # 브리핑 기사 수 (최대 10)
    scheduler:
      interval: "*/30 * * * * *"  # TTS 처리 스케줄러 주기 (cron)

naver:
  clova:
    voice:
      api-key-id: ${NAVER_CLOVA_VOICE_API_KEY_ID}    # NCP 인증 (Naver Developers와 별개)
      api-key: ${NAVER_CLOVA_VOICE_API_KEY}
      base-url: https://naveropenapi.apigw.ntruss.com
```
