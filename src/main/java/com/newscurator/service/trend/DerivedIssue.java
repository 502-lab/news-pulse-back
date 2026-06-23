package com.newscurator.service.trend;

import java.util.List;

/**
 * 재산출된 이슈 1건.
 *
 * @param keywords   대표 키워드(top3 — 클러스터 내 동시출현 가중치 상위)
 * @param articleIds 관련 기사 묶음
 * @param delta      증감 — 멤버 키워드 WoW delta 집계(평균). 산출 불가면 null
 */
public record DerivedIssue(List<String> keywords, List<Long> articleIds, Double delta) {}
