-- blast 시각 전용 신호: reuse 감지 경로(family blast)에서만 set된다.
-- 로그아웃(revoke) 경로는 이 컬럼을 set하지 않아 false-escalation을 방지한다.
ALTER TABLE refresh_tokens ADD COLUMN blasted_at TIMESTAMPTZ;

CREATE INDEX idx_refresh_tokens_blasted ON refresh_tokens(account_id, blasted_at)
    WHERE blasted_at IS NOT NULL;
