-- 009 읽기 추적: 기사 조회 이벤트(append-only). P1=VIEW·SERVER만 기록, 나머지 컬럼은 forward-seam(후속 클라이언트 계측 이벤트 수용).
-- best-effort 기록(상세 핫패스와 별개 TX, 실패 격리). 디바운스 30분(account+article).

CREATE TABLE article_event (
    id           BIGSERIAL    PRIMARY KEY,
    account_id   UUID         NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    article_id   BIGINT       NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    event_type   VARCHAR(16)  NOT NULL DEFAULT 'VIEW',
    metric_value INTEGER      NULL,
    source       VARCHAR(8)   NOT NULL DEFAULT 'SERVER',
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 디바운스 EXISTS(account, article, 30분 윈도우) + 읽은수 distinct(account별 article_id)
CREATE INDEX idx_article_event_debounce
    ON article_event (account_id, article_id, occurred_at);

-- 조회 이력 최신순(account별 occurred_at DESC) — debounce 인덱스가 정렬 미커버라 별도(research D3)
CREATE INDEX idx_article_event_history
    ON article_event (account_id, occurred_at DESC);
