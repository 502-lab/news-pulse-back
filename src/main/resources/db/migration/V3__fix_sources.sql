-- YTN RSS URL이 폐기됨 → 조선일보로 교체
UPDATE sources
SET name = '조선일보',
    feed_url = 'https://www.chosun.com/arc/outboundfeeds/rss/?outputType=xml'
WHERE name = 'YTN';

-- 코드 버그(incrementAndGetCallCount)로 인해 쌓인 허위 failure count 리셋
UPDATE sources
SET consecutive_failure_count = 0
WHERE consecutive_failure_count > 0;
