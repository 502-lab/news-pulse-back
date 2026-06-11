-- V2: 초기 뉴스 출처 시드 데이터
-- RSS 3개 (연합뉴스·YTN·한겨레) + 네이버-경제 NAVER 1개

INSERT INTO sources (name, feed_url, adapter_type, active, collection_interval_minutes, call_budget_daily)
VALUES
    ('연합뉴스', 'https://www.yna.co.kr/rss/news.xml', 'RSS', TRUE, NULL, 1000),
    ('YTN', 'https://www.ytn.co.kr/rss/allnews.xml', 'RSS', TRUE, NULL, 1000),
    ('한겨레', 'https://www.hani.co.kr/rss/', 'RSS', TRUE, NULL, 1000),
    ('네이버-경제', '경제 뉴스', 'NAVER', TRUE, 30, 100);
