package com.newscurator.service.trend;

import java.util.List;
import java.util.Map;

/**
 * 이슈 클러스터링 입력. 기사+키워드에서 재산출 가능(FR-012) — 수작업 큐레이션 아님.
 *
 * @param articleKeywords article_id → 해당 기사의 키워드 목록(윈도우 내)
 * @param keywordWowDelta term → WoW 증감률(raw %). 신규(prev=0) 또는 미산출이면 null
 */
public record IssueClusterContext(
        Map<Long, List<String>> articleKeywords,
        Map<String, Double> keywordWowDelta) {}
