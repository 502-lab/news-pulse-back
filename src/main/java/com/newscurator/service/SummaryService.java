package com.newscurator.service;

import com.newscurator.config.AiProperties;
import com.newscurator.domain.Summary;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

/**
 * 요약 슬롯 유틸리티: BRIEF 트런케이션과 DEEP 슬롯 재시도 허용 여부 판단.
 *
 * <p>ArticleDetailService(T044)와 AiProcessingService(T032)가 이 클래스를 호출한다.
 */
@Service
public class SummaryService {

    // BRIEF 트런케이션 기준 글자 수 (CHK014)
    static final int BRIEF_MAX_CHARS = 200;

    private final AiProperties aiProperties;

    public SummaryService(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    /**
     * BALANCED 요약을 balanced truncation으로 잘라 BRIEF 요약 생성.
     * AI 호출 없음; ~200자 기준.
     */
    public String truncateForBrief(String balancedContent) {
        if (balancedContent == null) {
            return null;
        }
        if (balancedContent.length() <= BRIEF_MAX_CHARS) {
            return balancedContent;
        }
        return balancedContent.substring(0, BRIEF_MAX_CHARS);
    }

    /**
     * DEEP 슬롯 재시도 허용 여부 판단.
     *
     * <ul>
     *   <li>lastAttemptAt이 null이면 허용 (최초 시도)
     *   <li>쿨다운 미경과 → 불가
     *   <li>retryCount >= deep-retry.limit → 불가
     *   <li>위 조건 모두 통과 → 허용
     * </ul>
     */
    public boolean isDeepRetryAllowed(Summary summary) {
        if (summary.getLastAttemptAt() == null) {
            return true;
        }

        int limit = aiProperties.deepRetry().limit();
        if (summary.getRetryCount() >= limit) {
            return false;
        }

        int cooldownMinutes = aiProperties.deepRetry().cooldownMinutes();
        OffsetDateTime cooldownExpiry = summary.getLastAttemptAt().plusMinutes(cooldownMinutes);
        return OffsetDateTime.now().isAfter(cooldownExpiry);
    }
}
