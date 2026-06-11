package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.newscurator.config.AiProperties;
import com.newscurator.domain.Summary;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SummaryServiceTest {

    private SummaryService summaryService;
    private AiProperties aiProperties;

    @BeforeEach
    void setUp() {
        AiProperties.DeepRetryProperties deepRetry = new AiProperties.DeepRetryProperties(60, 5);
        aiProperties = new AiProperties(10, 3, 200L, deepRetry);
        summaryService = new SummaryService(aiProperties);
    }

    // ===== truncateForBrief =====

    @Test
    @DisplayName("200자 초과 문자열은 200자로 잘림")
    void truncateForBrief_over200Chars_truncated() {
        String longContent = "가".repeat(201);
        String result = summaryService.truncateForBrief(longContent);
        assertThat(result.length()).isLessThanOrEqualTo(200);
    }

    @Test
    @DisplayName("200자 정확히 입력되면 그대로 반환")
    void truncateForBrief_exactly200Chars_unchanged() {
        String content = "가".repeat(200);
        String result = summaryService.truncateForBrief(content);
        assertThat(result).isEqualTo(content);
    }

    @Test
    @DisplayName("200자 미만 문자열은 그대로 반환")
    void truncateForBrief_under200Chars_unchanged() {
        String content = "짧은 요약";
        String result = summaryService.truncateForBrief(content);
        assertThat(result).isEqualTo(content);
    }

    @Test
    @DisplayName("null 입력은 null 반환")
    void truncateForBrief_null_returnsNull() {
        assertThat(summaryService.truncateForBrief(null)).isNull();
    }

    @Test
    @DisplayName("빈 문자열은 빈 문자열 반환")
    void truncateForBrief_empty_returnsEmpty() {
        assertThat(summaryService.truncateForBrief("")).isEmpty();
    }

    // ===== isDeepRetryAllowed =====

    @Test
    @DisplayName("쿨다운 미경과 시 재시도 불가")
    void isDeepRetryAllowed_cooldownNotElapsed_returnsFalse() {
        Summary summary = buildFailedDeepSummary(OffsetDateTime.now().minusMinutes(30), 1);
        assertThat(summaryService.isDeepRetryAllowed(summary)).isFalse();
    }

    @Test
    @DisplayName("retry_count >= limit 시 재시도 불가")
    void isDeepRetryAllowed_retryLimitReached_returnsFalse() {
        Summary summary = buildFailedDeepSummary(OffsetDateTime.now().minusMinutes(70), 5);
        assertThat(summaryService.isDeepRetryAllowed(summary)).isFalse();
    }

    @Test
    @DisplayName("쿨다운 경과 + retry_count < limit 시 재시도 허용")
    void isDeepRetryAllowed_allowed_returnsTrue() {
        Summary summary = buildFailedDeepSummary(OffsetDateTime.now().minusMinutes(70), 2);
        assertThat(summaryService.isDeepRetryAllowed(summary)).isTrue();
    }

    @Test
    @DisplayName("lastAttemptAt null이면 재시도 허용")
    void isDeepRetryAllowed_nullLastAttemptAt_returnsTrue() {
        Summary summary = buildDeepSummaryWithNullAttempt();
        assertThat(summaryService.isDeepRetryAllowed(summary)).isTrue();
    }

    private Summary buildFailedDeepSummary(OffsetDateTime lastAttemptAt, int retryCount) {
        Summary summary = mock(Summary.class);
        when(summary.getLastAttemptAt()).thenReturn(lastAttemptAt);
        when(summary.getRetryCount()).thenReturn(retryCount);
        return summary;
    }

    private Summary buildDeepSummaryWithNullAttempt() {
        Summary summary = mock(Summary.class);
        when(summary.getLastAttemptAt()).thenReturn(null);
        return summary;
    }
}
