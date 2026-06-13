# Research: 004 TTS 음성 — 기사·브리핑 오디오

**Date**: 2026-06-13
**Branch**: `004-tts-audio-briefing`

---

## Decision 1: TTS 제공자 — AWS Polly (Seoyeon, Neural)

> **2026-06-13 업데이트**: 최초 결정(Naver Clova Voice)에서 AWS Polly로 교체. 아래는 최종 결정값.

**Decision**: AWS Polly Neural TTS 사용 — VoiceId: `Seoyeon` (한국어 여성, Neural 엔진)

**Rationale**:
- 기존 S3 연동과 동일한 `DefaultCredentialsProvider`(EC2 인스턴스 프로파일)로 인증 통합 — 별도 키 관리 불필요
- build.gradle에 이미 AWS SDK v2 BOM 선언 → `software.amazon.awssdk:polly` 한 줄 추가만으로 충족
- Polly 한도 ≈3,000자(Naver 2,000자 대비 상향), Neural 엔진으로 자연스러운 한국어 뉴스 낭독체
- Seoyeon: AWS Polly 공식 한국어(ko-KR) Neural 음성, 여성

**API 계약 요약**:
```
AWS SDK v2: pollyClient.synthesizeSpeech(SynthesizeSpeechRequest)
  .voiceId(VoiceId.SEOYEON)
  .engine(Engine.NEURAL)
  .outputFormat(OutputFormat.MP3)
  .text(truncatedText)
→ ResponseInputStream<SynthesizeSpeechResponse> → byte[]
인증: DefaultCredentialsProvider (환경변수 AWS_ACCESS_KEY_ID 또는 EC2 인스턴스 프로파일)
```

**Alternatives Considered**:
- Naver Clova Voice Premium: NCP 별도 인증 키 필요, 화자 ID 미확인([TBD]), 한도 2,000자
- Google Cloud TTS: GCP 별도 인증 필요, Google SDK 의존성 추가
- Kakao i Voice: B2B 위주, 독립 개발자 API 미공개

---

## Decision 2: 비동기 생성 패턴 — 001 AiProcessingScheduler 재사용

**Decision**: 001의 PENDING→PROCESSING→READY/FAILED 상태 머신 + **SELECT … FOR UPDATE SKIP LOCKED** 스케줄러 패턴 그대로 재사용.

**Rationale**:
- 001에서 이미 검증된 패턴(AiProcessingScheduler, 상태 폴링)
- Naver TTS API는 동기 HTTP 호출(응답에 오디오 bytes 포함)이지만, S3 업로드 포함 전체 파이프라인을 비동기로 분리해야 타임아웃 회피 가능
- TTS 생성 + S3 업로드 전체 시간: ≈5~15초 → HTTP timeout 위험 → 비동기 필수

**TtsProcessingScheduler — claim-with-lock 필수**:
- PENDING 항목 조회 시 반드시 `SELECT … FOR UPDATE SKIP LOCKED`로 행 단위 락 획득
- 멀티 인스턴스(수평 확장) 환경에서 동일 TTS 작업을 여러 파드가 동시에 처리하면 Naver API 과금이 2배 발생
- 단순 `findByStatus(PENDING)` 폴링 방식 **금지**

**FAILED 재시도 — UPDATE 방식**:
- FAILED 상태인 기존 행에 새 PENDING 행을 INSERT하지 않는다
- UNIQUE 제약 `(owner_type, ref_id, voice_id)` 때문에 INSERT 시도 시 `DataIntegrityViolationException` 발생
- 올바른 재시도 방법: **기존 FAILED 행 UPDATE** — `status=PENDING, error_msg=NULL, audio_key=NULL, updated_at=NOW()`

**멱등 분기 로직** (서비스 레이어):
- READY → 200 + audioKey → URL 즉시 생성 반환
- PENDING/PROCESSING → 202 + 기존 jobId 반환 (중복 생성 없음)
- FAILED → 기존 행 UPDATE(PENDING 리셋) → 202 반환
- 없음 → 새 PENDING 행 INSERT → 202 반환

**Alternatives Considered**:
- 동기 처리: Naver API 응답(≈2~5초) + S3 업로드(≈1~3초) = 최대 8초 → HTTP 클라이언트 타임아웃 위험
- Kafka/RabbitMQ 메시지 큐: 현재 스택에 MQ 없음, 단순 DB 폴링으로 충분한 규모

---

## Decision 3: 오디오 파일 저장소 — S3 + CloudFront (audio_key 저장)

**Decision**: AWS S3 버킷에 MP3 파일 저장, CloudFront를 통해 URL 제공. DB에는 S3 key(`audio_key`)만 저장하고, API 응답 시점에 URL을 생성한다.

**Rationale**:
- 001에서 이미 S3 인프라 사용 결정(재사용)
- **presigned URL을 DB에 저장하지 않는다**: TTL 만료 후 DB 값이 무효화되고 관리 복잡도 증가
- 응답 시 CloudFront URL 조합(`https://{cdn-domain}/{audio_key}`) 또는 S3 presigned URL 생성으로 항상 유효한 URL 반환
- 기사 TTS는 기사 단위 공유 캐시 — 동일 기사를 여러 사용자가 요청해도 S3에 1개 파일만 존재

**S3 키 패턴** (통일):
```
tts/{owner_type}/{ref_id}/{voice_id}.mp3
예: tts/article/12345/[TBD-harin].mp3
```
- `owner_type`: 소문자 (`article`)
- `ref_id`: articleId 숫자값
- `voice_id`: Clova 화자 ID ([TBD])

**durationSec**:
- Naver Clova Voice API는 재생 시간을 응답 헤더에 미포함
- MP3 메타데이터(ID3 태그 또는 VBR header) 파싱으로 추출하거나 nullable 유지
- 초기 구현에서는 nullable 허용, 추후 백필 가능

**Alternatives Considered**:
- 직접 DB BLOB 저장: 대용량 바이너리 DB 저장은 성능 저하, 배제
- Google Cloud Storage: 스택 일관성을 위해 S3 유지

---

## Decision 4: 브리핑 생성 타이밍 — 온디맨드 (첫 요청 시)

**Decision**: 사용자가 당일 처음 브리핑 요청 시 온디맨드 생성, 이후 같은 날 재요청은 캐시 반환

**Rationale**:
- 비활성 사용자에 대한 불필요한 TTS API 호출·비용 없음
- briefTime 스케줄러 방식은 모든 활성 사용자 수만큼 매일 TTS 생성 → 비용 선형 증가
- SC-004(브리핑 초회 60초 이내)는 온디맨드로도 충분히 달성 가능
- 구현 단순성: 별도 스케줄러 없이 `DailyBrief` 캐시 조회만으로 처리

**Tradeoff**: 매일 첫 브리핑 요청에 초기 응답 지연 발생. 202(PENDING) 반환 후 폴링으로 해결.

**Alternatives Considered**:
- briefTime 사전 생성: 스케줄러 복잡도 증가, 비활성 사용자 비용 낭비
- 백그라운드 사전 생성(비활성 사용자 제외): 구현 복잡, MVP 불필요

---

## Decision 5: 브리핑 모델 — 단일 합성 오디오 아닌 기사 TTS 재생 큐 (Model B)

**Decision**: 브리핑은 N건 요약을 하나의 MP3로 합성(concat)하지 않는다. 대신 **순서 있는 기사 TTS 재생 큐**로 제공한다.

**Rationale**:
- MP3 concat은 ffmpeg 등 서버 사이드 오디오 처리 의존성 추가 → 복잡도·비용 증가
- 기사 TTS를 기사 단위로 캐싱(US2 재사용)하면 브리핑은 별도 TTS 생성 없이 큐 조합만으로 완성
- 클라이언트 재생기가 기사 간 전환을 제어(다음 기사 스킵, 일시정지 등) → UX 유연성 증가
- "브리핑 READY" = 큐에 포함된 모든 기사 TTS가 READY 상태

**브리핑 기사 선정 기준**:
1. 003 개인화 피드 상위 N건 조회
2. 그 중 `summary_status = COMPLETED`인 기사만 큐에 포함 (요약 미완료는 제외)
3. COMPLETED 기사가 N건 미만이면 다음 순위 기사로 채워 N건 확보 (불가 시 가용한 수만큼)
4. 각 기사에 대해 `(ARTICLE, articleId, voiceId)` TtsAudio 없으면 PENDING 생성 (US2 재사용)
5. 기사 TTS는 전체 사용자 공유 캐시 — 다른 사용자가 동일 기사 TTS를 이미 생성했다면 즉시 READY

**BriefingResponse 구조**:
```json
{
  "briefDate": "2026-06-13",
  "articleIds": [101, 203, 87, 45, 312],   // 재생 순서
  "voiceId": "[TBD-harin]",
  "ttsItems": [                              // 각 기사 TTS 상태
    { "id": "...", "refId": "101", "status": "READY", "audioKey": "tts/article/101/[TBD].mp3", ... },
    { "id": "...", "refId": "203", "status": "PENDING", ... },
    ...
  ]
}
```

**N=5 기본값**: 5건 × 평균 90초 ≈ 7.5분 → 출퇴근 청취 적합 길이. 환경변수 조정 가능(최대 10건).

**Alternatives Considered**:
- MP3 concat(Model A): ffmpeg 서버 사이드 처리, 추가 인프라 의존성, 기사 단위 스킵 불가

---

## Decision 6: reading_preferences 확장 — voice_id만 추가 (consume_mode 재사용)

**Decision**: `reading_preferences.consume_mode`(READ/LISTEN/BOTH)가 이미 존재 → `read_mode` 신설 취소. V10에서 `voice_id VARCHAR(50)` 컬럼만 추가.

**Rationale**:
- 002에서 이미 `consume_mode` 컬럼(ENUM: READ/LISTEN/BOTH)과 `ConsumeMode` Java Enum 구현 완료
- 동일 의미의 `read_mode` 컬럼 추가는 중복이자 데이터 불일치 위험
- 기존 `ReadingPreference` 엔티티에 `voiceId` 필드만 추가하면 됨

---

## Decision 7: Voices — DB 시드 테이블

**Decision**: `voices` 테이블을 생성하고 Flyway V10 마이그레이션에서 초기 2건 시드

**Rationale**:
- 코드 하드코딩 대비: 향후 음성 추가 시 DB insert만으로 반영, 코드 변경 없음
- preview_url은 CDN 배포 후 업데이트 가능

> **[V1] 화자 ID 미확인**: 아래 `id` 값은 Clova Voice 콘솔에서 실제 화자 ID를 확인한 후 교체해야 한다. `name`(표시명: 하린/준서)은 디자인 확정값이지만, `id`(Naver `speaker` 파라미터)는 실제 콘솔 값으로 대체 필요. 구현 전 반드시 확인할 것.

| id | name | gender | 비고 |
|----|------|--------|------|
| [TBD] | 하린 | FEMALE | Clova 콘솔에서 실제 화자 ID 확인 필요 |
| [TBD] | 준서 | MALE | Clova 콘솔에서 실제 화자 ID 확인 필요 |
