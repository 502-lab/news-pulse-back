# Implementation Plan: TTS 음성 — 기사·브리핑 오디오

**Branch**: `004-tts-audio-briefing` | **Date**: 2026-06-13 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `.specify/specs/004-tts-audio-briefing/spec.md`

---

## Summary

기사 요약 텍스트를 AWS Polly Neural TTS(Seoyeon 스피커)로 음성 변환하고, S3+CloudFront로 오디오를 저장·배포한다. *(2026-06-13: TTS 제공자를 Naver Clova Voice → AWS Polly로 교체)* **브리핑은 단일 합성 오디오 아닌 기사 TTS 재생 큐(Model B)**로 구현한다. TTS 생성은 001의 비동기 PENDING→PROCESSING→READY/FAILED 상태 머신(SELECT…FOR UPDATE SKIP LOCKED 포함)을 그대로 재사용하고, `(owner_type, ref_id, voice_id)` UNIQUE 제약으로 멱등 캐시를 구현한다. FAILED 재시도는 새 행 INSERT가 아닌 기존 행 UPDATE(PENDING 리셋)로 처리한다. 신규 테이블 `voices`, `tts_audios`, `daily_briefs`와 `reading_preferences.voice_id` 컬럼 추가를 Flyway V10 단일 마이그레이션으로 처리한다(read_mode 신설 없음 — 기존 consume_mode 재사용).

---

## Technical Context

**Language/Version**: Java 25

**Primary Dependencies**:
- Spring Boot 4.0.5 (WebMVC, Security, Data JPA, Scheduler)
- Spring Data JPA + Hibernate (ORM)
- Flyway (DB 마이그레이션)
- SpringDoc OpenAPI 3 (Swagger UI)
- AWS SDK v2 (S3 업로드)
- Spring Web (RestClient — Naver Clova Voice HTTP 호출)
- Testcontainers + PostgreSQL (통합 테스트)
- WireMock (Naver Clova Voice API mock)

> **인증 구분**: Naver Clova Voice는 NCP 인증(`X-NCP-APIGW-API-KEY-*`)을 사용. 뉴스 수집 NaverSourceAdapter의 Naver Developers 인증(`X-Naver-Client-*`)과 완전히 별개 — 재사용 불가, 별도 NCP 키 발급 필요.

**Storage**:
- PostgreSQL — `voices`, `tts_audios`, `daily_briefs`, `reading_preferences` (확장)
- AWS S3 — MP3 파일 저장. 키 패턴: `tts/{owner_type}/{ref_id}/{voice_id}.mp3`
- Redis — **TTS 캐싱 미도입**: `tts_audios` DB 단순 조회가 SC-003(< 1초) 충족에 충분. Redis 도입은 향후 scale-out 시 재평가.

**Testing**:
- 단위 테스트: JUnit 5 + Mockito (Service 레이어)
- 통합 테스트: @DataJpaTest (Repository), Testcontainers (PostgreSQL)
- Controller: @WebMvcTest + MockMvc
- Naver API Client: WireMock mock
- 빌드: `./gradlew test`

**Target Platform**: Linux server (JVM 25)

**Project Type**: REST API web-service (Spring Boot 백엔드)

**Performance Goals**:
- SC-001: 음성 목록 응답 < 1초 (DB 단순 조회, Redis 캐싱 선택적)
- SC-002: 기사 단건 TTS READY < 30초 (Naver API ≈2~5s + S3 업로드 ≈1~3s + 처리 마진)
- SC-003: READY 캐시 재요청 응답 < 1초
- SC-004: 브리핑(N=5건) 초회 — 재생 큐 생성 즉시 반환(202), 각 기사 TTS READY 완료 < 60초
- SC-005: 브리핑 캐시 재요청 < 1초

**Constraints**:
- Naver Clova Voice API 키는 환경변수에서만 주입 (코드 하드코딩 금지)
- TTS 생성 + S3 업로드 전체가 비동기 — HTTP timeout 회피
- **FAILED 재시도**: 새 PENDING 행 INSERT 금지 (UNIQUE 제약 위반). 기존 FAILED 행 UPDATE(status→PENDING, audio_key/error_msg 초기화)로만 처리
- **TtsProcessingScheduler**: 반드시 `SELECT … FOR UPDATE SKIP LOCKED` 사용 — 단순 status=PENDING 폴링 금지 (멀티 인스턴스 중복 과금 방지)
- `daily_briefs` UNIQUE(account_id, brief_date) — 하루 1건 온디맨드 생성
- 브리핑 N=5 기본값, 환경변수(`app.tts.briefing.article-count`)로 조정 가능 (최대 10)
- **audio_key 저장**: `tts_audios.audio_key`에 S3 키(`tts/article/{id}/{voiceId}.mp3`) 저장. presigned URL을 DB에 저장하지 않음. 응답 시 URL 생성.
- **durationSec**: Naver Clova Voice API는 재생 시간 미제공. MP3 메타 파싱 또는 nullable 유지.
- **listenable 필터**: `tts_audios.ref_id`(VARCHAR) ↔ `saved_articles.article_id`(BIGINT) JOIN 시 캐스팅 명시 필요
- **[V1] 화자 ID**: Clova Voice 콘솔에서 실제 speaker ID 확인 후 `voices` 시드 교체 필요. 현재 [TBD].

**Scale/Scope**:
- 음성 2종 (하린/준서 [TBD]), 향후 DB insert로 추가 가능
- TTS 생성 스케줄러: 001 AiProcessingScheduler 패턴 재사용 + SELECT…FOR UPDATE SKIP LOCKED

---

## Constitution Check

*GATE: 설계 전·후 모두 통과 확인*

| 원칙 | 상태 | 비고 |
|------|------|------|
| I. 레이어드 아키텍처 (Controller→Service→Repository) | ✅ PASS | VoiceController, TtsController, BriefingController → 각 Service → Repository |
| II. Entity 비노출 + DTO 검증 | ✅ PASS | VoiceResponse, TtsStatusResponse, BriefingResponse DTO 사용; Entity 직접 반환 없음 |
| III. 일관된 응답 포맷 + 전역 예외 처리 | ✅ PASS | 기존 ApiResponse<T> 래퍼 + GlobalExceptionHandler 재사용 |
| IV. 테스트 없는 비즈니스 로직 금지 | ✅ PASS | TtsService 단위 테스트 + BriefingService 단위 테스트 + Testcontainers 통합 테스트 필수 |
| V. 스키마 변경은 마이그레이션으로만 | ✅ PASS | Flyway V10 단일 파일 (`V10__add_tts_tables.sql`) |
| VI. 보안 기본값 + 시크릿 외부화 | ✅ PASS | /api/v1/voices 이상 전 엔드포인트 인증 필요; Naver API 키 환경변수 주입; Secure-by-default |
| VII. TTS 멱등성 + 중복 방지 | ✅ PASS | UNIQUE(owner_type, ref_id, voice_id) 제약; PROCESSING 재요청 → 기존 jobId 반환; DailyBrief UNIQUE(account_id, brief_date) |

**헌법 위반 없음 — Complexity Tracking 불필요**

---

## Project Structure

### Documentation (this feature)

```text
.specify/specs/004-tts-audio-briefing/
├── plan.md              # This file (speckit-plan output)
├── spec.md              # Feature specification
├── research.md          # Phase 0: 7가지 설계 결정 (TTS 제공자, 비동기 패턴, S3 등)
├── data-model.md        # Phase 1: DB 스키마 + Java Entity 스케치
├── quickstart.md        # Phase 1: 검증 시나리오 가이드
├── contracts/
│   └── openapi.yaml     # Phase 1: API 계약 (6개 엔드포인트)
├── checklists/
│   └── requirements.md  # 스펙 품질 체크리스트 (16/16 통과)
└── tasks.md             # Phase 2 출력 (speckit-tasks 명령으로 생성)
```

### Source Code (repository root)

```text
src/main/java/com/newscurator/
├── controller/
│   ├── VoiceController.java              # GET /api/v1/voices
│   ├── TtsController.java                # POST/GET /api/v1/articles/{id}/tts
│   └── BriefingController.java           # GET /api/v1/briefing/today
├── service/
│   ├── VoiceService.java                 # 음성 목록 조회
│   ├── TtsService.java                   # TTS 생성 요청·상태 조회·멱등 캐시
│   └── BriefingService.java              # 데일리 브리핑 생성·캐시
├── repository/
│   ├── VoiceRepository.java
│   ├── TtsAudioRepository.java
│   └── DailyBriefRepository.java
├── domain/
│   ├── Voice.java
│   ├── TtsAudio.java
│   └── DailyBrief.java
├── domain/enums/
│   ├── TtsOwnerType.java                 # ARTICLE only (BRIEF 제거)
│   └── TtsStatus.java                    # PENDING, PROCESSING, READY, FAILED
│   # ReadMode 신설 없음 — 기존 ConsumeMode(READ/LISTEN/BOTH) 재사용
├── dto/
│   ├── request/
│   │   └── TtsRequest.java               # voiceId
│   └── response/
│       ├── VoiceResponse.java
│       ├── TtsStatusResponse.java        # ownerType=ARTICLE only
│       └── BriefingResponse.java         # ttsItems[] 배열 (단일 tts 객체 아님)
├── client/
│   └── ai/
│       └── NaverClovaVoiceClient.java    # Naver TTS REST API 호출 (기존 client/ai/ 하위)
└── scheduler/
    └── TtsProcessingScheduler.java       # PENDING TTS 항목 주기 처리 (001 패턴 재사용)

src/main/resources/db/migration/
└── V10__add_tts_tables.sql               # voices + tts_audios + daily_briefs + reading_preferences 확장

src/test/java/com/newscurator/
├── controller/
│   ├── VoiceControllerTest.java
│   ├── TtsControllerTest.java
│   └── BriefingControllerTest.java
├── service/
│   ├── VoiceServiceTest.java
│   ├── TtsServiceTest.java
│   └── BriefingServiceTest.java
├── repository/
│   ├── VoiceRepositoryTest.java
│   ├── TtsAudioRepositoryTest.java
│   └── DailyBriefRepositoryTest.java
├── scheduler/
│   └── TtsProcessingSchedulerTest.java
└── client/
    └── NaverClovaVoiceClientTest.java    # WireMock mock
```

**Structure Decision**: 기존 com.newscurator.* 3계층 구조를 그대로 확장한다. `client/ai/` 하위에 NaverClovaVoiceClient 추가(GeminiClient와 동일 위치). `domain/enums/` 하위에 신규 Enum 3종 추가.

---

## Implementation Phases

### Phase 0 — Research ✅ 완료

산출물: [`research.md`](./research.md)

| 결정 | 결과 |
|------|------|
| TTS 제공자 | Naver Clova Voice Premium API (화자 ID [TBD] — 콘솔 확인 필요) |
| 비동기 패턴 | 001 AiProcessingScheduler 패턴 재사용 + SELECT…FOR UPDATE SKIP LOCKED |
| FAILED 재시도 | 기존 행 UPDATE(PENDING 리셋) — INSERT 아님 |
| 오디오 저장 | AWS S3 (audio_key 저장) + CloudFront URL 응답 시 생성 |
| 브리핑 모델 | Model B: 재생 큐 (단일 합성 오디오 아님) |
| 브리핑 타이밍 | 온디맨드 (첫 요청 시 생성, 당일 캐시) |
| 브리핑 N | 기본값 5건, 환경변수 조정 가능 |
| reading_preferences | 기존 테이블 ALTER — voice_id만 추가 (consume_mode 재사용, read_mode 없음) |
| 음성 목록 | voices DB 시드 테이블 |

### Phase 1 — Design & Contracts ✅ 완료

산출물: [`data-model.md`](./data-model.md), [`contracts/openapi.yaml`](./contracts/openapi.yaml), [`quickstart.md`](./quickstart.md)

- **데이터 모델**: `voices`, `tts_audios`, `daily_briefs` 신규 + `reading_preferences.voice_id` 추가
  - `TtsOwnerType`: ARTICLE만 (BRIEF 제거)
  - `daily_briefs`: `tts_audio_id` 컬럼 없음 (브리핑 TTS = 기사 tts_audios 배열)
  - `tts_audios`: `audio_key` 컬럼 (presigned URL 아닌 S3 key)
- **API 계약**: 6개 엔드포인트 (음성 목록, reading-preference[voiceId 추가], 기사 TTS POST/GET, 브리핑[ttsItems 배열], 저장 기사 listenable 필터)
  - `BriefingResponse`: `tts` 단일 객체 → `ttsItems[]` 배열
  - `ReadingPreferenceRequest`: `readMode` 제거, `consumeMode` 재사용
- **Flyway V10**: 단일 마이그레이션 파일

### Phase 2 — Task Generation (다음 단계)

`/speckit-tasks` 실행으로 tasks.md 생성.

**예상 Phase 구성**:
- **Phase 1 (Setup)**: Flyway V10 마이그레이션, Enum 추가, Entity 생성, NaverClovaVoiceClient 스켈레톤, S3 설정 확장
- **Phase 2 (Foundational)**: ReadingPreference 엔티티 확장(voice_id/read_mode), application-example.yaml TTS 설정 추가
- **Phase 3 (US1)**: VoiceRepository/Service/Controller + reading-preference 확장 + @WebMvcTest
- **Phase 4 (US2)**: TtsAudioRepository/TtsService/TtsController + TtsProcessingScheduler + 멱등 캐시 + 단위+통합 테스트
- **Phase 5 (US3)**: DailyBriefRepository/BriefingService/BriefingController + 온디맨드 생성 + 캐시 + 단위+통합 테스트
- **Phase 6 (US4)**: SavedArticleRepository listenable 필터 확장
- **Phase 7 (Polish)**: Swagger 문서화, CHANGELOG 업데이트, 전체 빌드 검증

---

## Key Design Decisions

### Naver Clova Voice — API 호출 방식

```
POST https://naveropenapi.apigw.ntruss.com/tts-premium/v1/tts
Headers: X-NCP-APIGW-API-KEY-ID: {key-id}   ← NCP 인증 (Naver Developers와 별개)
         X-NCP-APIGW-API-KEY: {key}
Body (form): speaker={voiceId}&text={summaryText}&volume=0&speed=0&pitch=0&format=mp3
Response: audio/mpeg binary
```

NaverClovaVoiceClient는 응답 bytes를 S3에 업로드하고 `audio_key`(S3 키)를 TtsAudio에 저장한다. API 응답 시 CloudFront URL 조합 또는 presigned URL 생성. presigned URL을 DB에 저장하지 않는다.

**[V1]**: `speaker` 파라미터값(voiceId)은 Clova Voice 콘솔에서 실제 화자 ID 확인 후 사용.

### TTS 비동기 파이프라인

```
Client POST → TtsService.requestTts() → TtsAudio(PENDING) 저장 → 즉시 202 반환
                                                    ↓
                              TtsProcessingScheduler (SELECT…FOR UPDATE SKIP LOCKED — 001 패턴)
                                                    ↓
                              NaverClovaVoiceClient.generate() → MP3 bytes
                                                    ↓
                              S3Upload(audio_key=tts/article/{id}/{voiceId}.mp3)
                                                    ↓
                              TtsAudio.update(status=READY, audioKey=...) 갱신
```

실패 시 TtsAudio FAILED 갱신.  
**FAILED 재시도**: `TtsAudio.resetToPending()` UPDATE — status=PENDING, audioKey=NULL, errorMsg=NULL. INSERT 아님(UNIQUE 위반).

### 브리핑 생성 흐름 (Model B: 재생 큐)

```
GET /api/v1/briefing/today
→ DailyBriefRepository.findByAccountIdAndBriefDate(today) 조회
  → 없으면:
      1. FeedService.getTopArticles() → summary_status=COMPLETED 기사 N건 선정
      2. DailyBrief(articleIds=[...]) 저장
      3. 각 articleId → TtsAudio(ARTICLE, articleId, voiceId) 없으면 PENDING 생성
      → 202 + { articleIds, ttsItems[] }
  → 있으면:
      각 articleId의 TtsAudio 상태 조회 → { articleIds, ttsItems[] }
      전체 READY → 200 / 일부 PENDING → 202
```

단일 합성 오디오, MP3 concat, ffmpeg 사용 없음. 기사 TTS는 전 사용자 공유 캐시.

### UNIQUE 멱등 캐시

`tts_audios(owner_type, ref_id, voice_id)` UNIQUE → 서비스 레이어에서 상태별 분기:
- READY → 200 + audioUrl(S3 key → URL 생성) 즉시
- PENDING/PROCESSING → 202 + 기존 jobId
- FAILED → `resetToPending()` UPDATE → 202 반환
- 없음 → 새 PENDING 행 INSERT → 202 반환

---

## Complexity Tracking

*헌법 위반 없음 — 해당 없음*
