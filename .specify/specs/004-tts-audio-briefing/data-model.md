# Data Model: 004 TTS 음성 — 기사·브리핑 오디오

**Date**: 2026-06-13 | **Branch**: `004-tts-audio-briefing`

---

## 기존 테이블 변경

### `reading_preferences` — voice_id 컬럼만 추가 (M2)

> **M2 결론**: `consume_mode` 컬럼(READ/LISTEN/BOTH)이 002에서 이미 구현됨 → `read_mode` 신설 취소. `voice_id VARCHAR(50)` 컬럼만 추가.

```sql
-- V10 마이그레이션에서 추가 (nullable, 기존 데이터 영향 없음)
ALTER TABLE reading_preferences
  ADD COLUMN voice_id VARCHAR(50);   -- voices.id FK (nullable = 선호 음성 미설정)

ALTER TABLE reading_preferences
  ADD CONSTRAINT fk_rp_voice FOREIGN KEY (voice_id) REFERENCES voices(id) ON DELETE SET NULL;
```

**값 의미**:
- `voice_id = NULL`: 기본 음성(하린) 사용 또는 TTS 미사용
- 읽기/듣기 방식은 기존 `consume_mode`(READ/LISTEN/BOTH) 컬럼 재사용

---

## 신규 테이블

### `voices` — 음성 캐릭터 목록 (시스템 고정, Flyway 시드)

```sql
CREATE TABLE voices (
    id          VARCHAR(50) PRIMARY KEY,   -- Naver Clova Voice 실제 speaker ID [TBD: 콘솔 확인 필요]
    name        VARCHAR(100) NOT NULL,     -- 표시명 (e.g., '하린', '준서') — 디자인 확정값
    gender      VARCHAR(10) NOT NULL,      -- 'FEMALE' | 'MALE'
    preview_url TEXT,                      -- 미리듣기 정적 샘플 URL (CDN)
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 초기 시드
-- [V1] 화자 ID 미확인: id 값을 Clova Voice 콘솔에서 실제 speaker ID로 교체 필요
-- name(표시명)은 확정, id(speaker 파라미터)는 반드시 콘솔 확인 후 교체
INSERT INTO voices (id, name, gender, preview_url) VALUES
  ('[TBD]', '하린', 'FEMALE', 'https://cdn.news-pulse.app/voices/preview/harin.mp3'),
  ('[TBD]', '준서', 'MALE',   'https://cdn.news-pulse.app/voices/preview/junho.mp3');
```

---

### `tts_audios` — TTS 오디오 작업 (기사 전용, 전 사용자 공유 캐시)

> **브리핑 모델 B**: 브리핑은 단일 합성 오디오가 아닌 기사 TTS 재생 큐. `owner_type`은 `ARTICLE`만 존재. `TtsOwnerType.BRIEF` 제거.

> **S1**: DB에 `audio_url`(presigned URL)을 저장하지 않는다. S3 키(`audio_key`)만 저장하고 응답 시점에 CloudFront URL을 조합하거나 presigned URL을 생성한다.

```sql
CREATE TABLE tts_audios (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type   VARCHAR(20) NOT NULL DEFAULT 'ARTICLE',  -- 현재 'ARTICLE'만 사용
    ref_id       VARCHAR(100) NOT NULL,   -- owner_type=ARTICLE → article_id 문자열
    voice_id     VARCHAR(50) NOT NULL REFERENCES voices(id),
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING|PROCESSING|READY|FAILED
    audio_key    TEXT,                    -- S3 key (예: tts/article/12345/[TBD].mp3). READY 후 설정.
    duration_sec INTEGER,                 -- 재생 시간(초). Naver API 미제공 → MP3 메타 파싱 또는 nullable 유지.
    error_msg    TEXT,                    -- FAILED 상태 오류 메시지
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_tts_owner_voice UNIQUE (owner_type, ref_id, voice_id)
);

CREATE INDEX idx_tts_audios_owner ON tts_audios (owner_type, ref_id, voice_id);
CREATE INDEX idx_tts_audios_status ON tts_audios (status) WHERE status IN ('PENDING', 'PROCESSING');

CREATE TRIGGER trg_tts_audios_updated_at
    BEFORE UPDATE ON tts_audios
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
```

**상태 전이**:
```
PENDING → PROCESSING → READY
              └──────→ FAILED (재시도: 기존 행 UPDATE → status=PENDING, error_msg/audio_key=NULL)
```

**FAILED 재시도 — UPDATE 방식** (M1):
- UNIQUE 제약이 있으므로 FAILED 상태에서 새 PENDING 행 INSERT 불가
- 기존 FAILED 행을 `UPDATE tts_audios SET status='PENDING', error_msg=NULL, audio_key=NULL, updated_at=NOW() WHERE id=?`로 리셋
- 스케줄러가 다음 주기에 PENDING 행으로 처리

**TtsProcessingScheduler — SELECT…FOR UPDATE SKIP LOCKED 필수** (S2):
- 멀티 인스턴스 환경에서 동일 PENDING 행을 중복 처리하면 Naver API 과금 2배 발생
- PENDING 조회 시 반드시 `SELECT … FOR UPDATE SKIP LOCKED` 사용 (001 AiProcessingScheduler와 동일 패턴)

**S3 키 패턴** (통일):
```
tts/{owner_type}/{ref_id}/{voice_id}.mp3
예: tts/article/12345/[TBD].mp3
```

**listenable 필터 캐스팅 주의**:
- `tts_audios.ref_id` 는 VARCHAR(100), `saved_articles.article_id` 는 BIGINT
- JOIN 또는 WHERE 조건 작성 시 `CAST(ref_id AS BIGINT)` 또는 `ref_id = article_id::TEXT` 명시 필요

**캐시 키**: `(owner_type, ref_id, voice_id)` UNIQUE — 기사 TTS는 전 사용자 공유. A 사용자가 기사 101의 하린 TTS를 생성하면 B 사용자도 동일 오디오 재사용.

---

### `daily_briefs` — 일별 사용자 브리핑 (재생 큐)

> 브리핑 모델 B: `tts_audio_id` 컬럼 제거. 브리핑 TTS는 각 기사 `tts_audios` 참조로 구성.

```sql
CREATE TABLE daily_briefs (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id   UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    brief_date   DATE NOT NULL,                 -- UTC 날짜
    article_ids  BIGINT[] NOT NULL DEFAULT '{}', -- 재생 큐 (순서 보존)
    voice_id     VARCHAR(50) NOT NULL REFERENCES voices(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_daily_brief_user_date UNIQUE (account_id, brief_date)
);

CREATE INDEX idx_daily_briefs_account_date ON daily_briefs (account_id, brief_date DESC);
```

**브리핑 생성 흐름**:
1. `daily_briefs` 행 생성 (article_ids = 선정된 기사 ID 순서 배열)
2. 각 articleId에 대해 `tts_audios(ARTICLE, articleId, voiceId)` 존재 확인
   - 없으면 PENDING 생성 (US2 재사용)
   - 있으면 현재 상태 그대로 사용
3. API 응답: `ttsItems[]` 배열로 각 기사 TTS 상태 포함
4. "브리핑 READY" = `ttsItems` 중 모든 항목이 READY 상태

**브리핑 기사 선정**:
- 003 개인화 피드 상위 N건 중 `summary_status = COMPLETED`인 기사만 포함
- COMPLETED 기사가 N 미만이면 다음 순위로 채워 N건 확보 (불가 시 가용한 수만큼)
- 관심사 미설정 시 최신순 fallback

---

## Entity 관계도

```
accounts (002)
  ├── reading_preferences (002, 확장: voice_id 추가)
  │     ├── consume_mode (기존: READ/LISTEN/BOTH)
  │     └── voice_id → voices.id
  └── daily_briefs
        ├── voice_id → voices.id
        └── article_ids[] → (각 article → tts_audios ARTICLE 타입)

articles (001)
  └── tts_audios [owner_type='ARTICLE', ref_id=article_id]

tts_audios
  ├── owner_type + ref_id + voice_id (UNIQUE)
  └── voice_id → voices.id

voices (시드, [TBD] ID 확인 필요)
```

---

## Domain Entity (Java)

### `Voice.java`
```java
@Entity @Table(name = "voices")
public class Voice {
    @Id private String id;          // Naver Clova 화자 ID [TBD: 콘솔 확인 필요]
    private String name;            // '하린', '준서' (디자인 확정)
    @Enumerated(EnumType.STRING)
    private Gender gender;          // FEMALE, MALE
    private String previewUrl;
    private Instant createdAt;
}
```

### `TtsAudio.java`
```java
@Entity @Table(name = "tts_audios",
    uniqueConstraints = @UniqueConstraint(columnNames = {"owner_type","ref_id","voice_id"}))
public class TtsAudio {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Enumerated(EnumType.STRING) @Column(name = "owner_type")
    private TtsOwnerType ownerType;   // ARTICLE only (BRIEF 제거)
    private String refId;
    private String voiceId;
    @Enumerated(EnumType.STRING)
    private TtsStatus status;         // PENDING, PROCESSING, READY, FAILED
    private String audioKey;          // S3 key (presigned URL 아님)
    private Integer durationSec;      // nullable — Naver 미제공, MP3 메타 파싱 또는 추후 백필
    private String errorMsg;
    private Instant createdAt;
    private Instant updatedAt;

    // FAILED 재시도 — 새 행 INSERT 아닌 현재 행 UPDATE
    public void resetToPending() {
        this.status = TtsStatus.PENDING;
        this.audioKey = null;
        this.errorMsg = null;
        this.updatedAt = Instant.now();
    }
}
```

### `DailyBrief.java`
```java
@Entity @Table(name = "daily_briefs",
    uniqueConstraints = @UniqueConstraint(columnNames = {"account_id","brief_date"}))
public class DailyBrief {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    private Account account;
    private LocalDate briefDate;
    @Column(columnDefinition = "BIGINT[]")
    private Long[] articleIds;        // 재생 큐 (순서)
    private String voiceId;
    private Instant createdAt;
    // tts_audio_id 없음 — 브리핑 TTS는 각 기사 tts_audios로 구성
}
```

### `ReadingPreference.java` 확장 (기존 + voice_id 추가)
```java
// 기존 필드 유지:
//   SummaryDepth summaryDepth
//   ConsumeMode consumeMode    ← READ/LISTEN/BOTH (재사용, readMode 신설 없음)
// 추가:
private String voiceId;            // nullable — voices.id FK
```

---

## 새 Enum

```java
enum TtsOwnerType { ARTICLE }          // BRIEF 없음 (브리핑 모델 B)
enum TtsStatus    { PENDING, PROCESSING, READY, FAILED }
// ReadMode 신설 없음 — ConsumeMode 재사용 (M2)
```

---

## Flyway 마이그레이션 계획

| 버전 | 내용 | 비고 |
|------|------|------|
| V10  | `voices` 테이블 + 시드(하린·준서, [TBD] ID), `tts_audios` 테이블, `daily_briefs` 테이블, `reading_preferences`에 `voice_id` 컬럼 추가 | 단일 마이그레이션 파일 |
