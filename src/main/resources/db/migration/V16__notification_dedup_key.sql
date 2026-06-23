-- 008 FR-042: 어드민 인앱 알림 멱등. notifications에 결정적 dedup_key(005 outbox와 동일 키 패턴).
-- 부분 unique 인덱스: dedup_key 있는 행만 유일성 강제 → 기존 dedup 없는(NULL) 알림과 공존.

ALTER TABLE notifications ADD COLUMN dedup_key VARCHAR(200) NULL;

CREATE UNIQUE INDEX uq_notification_dedup
    ON notifications (dedup_key)
    WHERE dedup_key IS NOT NULL;
