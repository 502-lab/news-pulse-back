package com.newscurator.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.dto.response.AdminKpiResponse;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.ArticleKeywordRepository;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.BiasAnalysisRepository;
import com.newscurator.repository.IssueSnapshotRepository;
import com.newscurator.repository.SourceDailyUsageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T034 AdminMonitoringService 단위 — KPI 계산(비율) + 분모 0 가드.
 */
@ExtendWith(MockitoExtension.class)
class AdminMonitoringServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private ArticleRepository articleRepository;
    @Mock private BiasAnalysisRepository biasAnalysisRepository;
    @Mock private IssueSnapshotRepository issueSnapshotRepository;
    @Mock private SourceDailyUsageRepository sourceDailyUsageRepository;
    @Mock private ArticleKeywordRepository articleKeywordRepository;
    @Mock private SchedulerControlService schedulerControlService;
    @InjectMocks private AdminMonitoringService service;

    @Test
    void kpi_computesRates() {
        when(accountRepository.count()).thenReturn(100L);
        when(accountRepository.countByStatus(AccountStatus.ACTIVE)).thenReturn(80L);
        when(articleRepository.count()).thenReturn(500L);
        when(articleRepository.countByCategoryStatusIn(any())).thenReturn(10L);
        when(articleRepository.countSummaryCompleted()).thenReturn(5L); // 5/10 → 50.0
        when(biasAnalysisRepository.count()).thenReturn(4L);
        when(biasAnalysisRepository.countByStatusValue(eq("DONE"))).thenReturn(3L); // 3/4 → 75.0
        when(issueSnapshotRepository.count()).thenReturn(7L);

        AdminKpiResponse kpi = service.getKpi();

        assertThat(kpi.totalUsers()).isEqualTo(100);
        assertThat(kpi.activeUsers()).isEqualTo(80);
        assertThat(kpi.totalArticles()).isEqualTo(500);
        assertThat(kpi.summaryCompletionRate()).isEqualTo(50.0);
        assertThat(kpi.biasCompletionRate()).isEqualTo(75.0);
        assertThat(kpi.trendIssueCount()).isEqualTo(7);
    }

    @Test
    void kpi_zeroDenominator_noDivByZero() {
        when(accountRepository.count()).thenReturn(0L);
        when(accountRepository.countByStatus(any())).thenReturn(0L);
        when(articleRepository.count()).thenReturn(0L);
        when(articleRepository.countByCategoryStatusIn(any())).thenReturn(0L); // 분모 0
        when(articleRepository.countSummaryCompleted()).thenReturn(0L);
        when(biasAnalysisRepository.count()).thenReturn(0L); // 분모 0
        when(biasAnalysisRepository.countByStatusValue(any())).thenReturn(0L);
        when(issueSnapshotRepository.count()).thenReturn(0L);

        AdminKpiResponse kpi = service.getKpi();

        assertThat(kpi.summaryCompletionRate()).isEqualTo(0.0);
        assertThat(kpi.biasCompletionRate()).isEqualTo(0.0);
    }
}
