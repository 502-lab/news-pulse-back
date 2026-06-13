-- V10: spec 004 — TTS 오디오 브리핑
-- voices / tts_audios / daily_briefs + reading_preferences.voice_id 추가

-- ================================================================
-- voices: 음성 캐릭터 목록 (시스템 고정, Flyway 시드)
-- ================================================================
CREATE TABLE IF NOT EXISTS voices (
    id          VARCHAR(50)  PRIMARY KEY,       -- AWS Polly VoiceId
    name        VARCHAR(100) NOT NULL,           -- 표시명 ('서연')
    gender      VARCHAR(10)  NOT NULL,           -- 'FEMALE' | 'MALE'
    preview_url TEXT,                            -- 미리듣기 정적 샘플 URL (CDN)
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

INSERT INTO voices (id, name, gender, preview_url) VALUES
    ('Seoyeon', '서연', 'FEMALE', NULL);

-- ================================================================
-- tts_audios: TTS 오디오 작업 (기사 전용, 전 사용자 공유 캐시)
-- ================================================================
CREATE TABLE IF NOT EXISTS tts_audios (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type   VARCHAR(20) NOT NULL DEFAULT 'ARTICLE',  -- 현재 'ARTICLE'만 사용 (BRIEF 없음)
    ref_id       VARCHAR(100) NOT NULL,                   -- ARTICLE → article_id 문자열
    voice_id     VARCHAR(50) NOT NULL REFERENCES voices(id),
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING|PROCESSING|READY|FAILED
    audio_key    TEXT,                 -- S3 key (tts/article/{ref_id}/{voice_id}.mp3). READY 후 설정.
    duration_sec INTEGER,             -- 재생 시간(초). Naver API 미제공 → nullable 유지.
    error_msg    TEXT,                -- FAILED 상태 오류 메시지
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_tts_owner_voice UNIQUE (owner_type, ref_id, voice_id)
);

CREATE INDEX IF NOT EXISTS idx_tts_audios_owner
    ON tts_audios (owner_type, ref_id, voice_id);

-- PARTIAL INDEX: PENDING/PROCESSING 상태 행만 인덱싱 (스케줄러 쿼리 최적화)
CREATE INDEX IF NOT EXISTS idx_tts_audios_status
    ON tts_audios (status)
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE OR REPLACE FUNCTION update_tts_audios_updated_at()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_tts_audios_updated_at
    BEFORE UPDATE ON tts_audios
    FOR EACH ROW EXECUTE FUNCTION update_tts_audios_updated_at();

-- ================================================================
-- daily_briefs: 일별 사용자 브리핑 재생 큐
-- ================================================================
CREATE TABLE IF NOT EXISTS daily_briefs (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id   UUID        NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    brief_date   DATE        NOT NULL,                      -- UTC 날짜
    article_ids  BIGINT[]    NOT NULL DEFAULT '{}',         -- 재생 큐 (순서 보존)
    voice_id     VARCHAR(50) NOT NULL REFERENCES voices(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_daily_brief_user_date UNIQUE (account_id, brief_date)
);

CREATE INDEX IF NOT EXISTS idx_daily_briefs_account_date
    ON daily_briefs (account_id, brief_date DESC);

-- ================================================================
-- reading_preferences 확장: voice_id 컬럼만 추가 (read_mode 없음 — consume_mode 재사용)
-- ================================================================
ALTER TABLE reading_preferences
    ADD COLUMN IF NOT EXISTS voice_id VARCHAR(50) REFERENCES voices(id) ON DELETE SET NULL;
