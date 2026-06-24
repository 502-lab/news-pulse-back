package com.newscurator.service.recommendation;

import com.newscurator.dto.response.RecommendationResponse;
import java.util.UUID;

/**
 * 010 놓친 기사 추천 엔진(US2) — 교체 가능한 seam(007 IssueClusterer 패턴).
 *
 * <p>MVP는 {@link RuleBasedRecommender}(룰베이스). 임베딩/ML v2는 같은 인터페이스의 다른 구현으로 교체하며
 * 호출부(컨트롤러)는 불변이다.
 */
public interface RecommendationEngine {

    /** 본인 미열람·미저장·비숨김 기사 추천 top-{@code limit}. 콜드스타트 시 트렌드 fallback(빈 목록 금지). */
    RecommendationResponse recommend(UUID accountId, int limit);
}
