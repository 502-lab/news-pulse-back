package com.newscurator.service.admin;

import com.newscurator.domain.enums.AccountStatus;
import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.dto.response.AdminKpiResponse;
import com.newscurator.dto.response.BiasAdminViewResponse;
import com.newscurator.dto.response.CollectionVolumeResponse;
import com.newscurator.dto.response.SchedulerStatusResponse;
import com.newscurator.dto.response.TrendAdminViewResponse;
import com.newscurator.repository.AccountRepository;
import com.newscurator.repository.ArticleKeywordRepository;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.BiasAnalysisRepository;
import com.newscurator.repository.IssueSnapshotRepository;
import com.newscurator.repository.SourceDailyUsageRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 운영 모니터링 조회(008 US2). 전부 read-only(부수효과 0, 감사 비대상) — 기존 테이블 집계.
 *
 * <p>모든 비율은 분모 0 가드(빈 데이터 시 0.0). bias/trend 어드민 뷰는 hidden 기사 포함(admin_hidden_at
 * 필터 안 함) — 일반 사용자 뷰와 반대.
 */
@Service
public class AdminMonitoringService {

    private final AccountRepository accountRepository;
    private final ArticleRepository articleRepository;
    private final BiasAnalysisRepository biasAnalysisRepository;
    private final IssueSnapshotRepository issueSnapshotRepository;
    private final SourceDailyUsageRepository sourceDailyUsageRepository;
    private final ArticleKeywordRepository articleKeywordRepository;
    private final SchedulerControlService schedulerControlService;

    public AdminMonitoringService(
            AccountRepository accountRepository,
            ArticleRepository articleRepository,
            BiasAnalysisRepository biasAnalysisRepository,
            IssueSnapshotRepository issueSnapshotRepository,
            SourceDailyUsageRepository sourceDailyUsageRepository,
            ArticleKeywordRepository articleKeywordRepository,
            SchedulerControlService schedulerControlService) {
        this.accountRepository = accountRepository;
        this.articleRepository = articleRepository;
        this.biasAnalysisRepository = biasAnalysisRepository;
        this.issueSnapshotRepository = issueSnapshotRepository;
        this.sourceDailyUsageRepository = sourceDailyUsageRepository;
        this.articleKeywordRepository = articleKeywordRepository;
        this.schedulerControlService = schedulerControlService;
    }

    /** 핵심 KPI. 빈 데이터 시 0/0.0(분모 0 가드). */
    @Transactional(readOnly = true)
    public AdminKpiResponse getKpi() {
        long totalUsers = accountRepository.count();
        long activeUsers = accountRepository.countByStatus(AccountStatus.ACTIVE);
        long totalArticles = articleRepository.count();

        long summaryProcessed =
                articleRepository.countByCategoryStatusIn(
                        List.of(ProcessingStatus.COMPLETED, ProcessingStatus.FAILED));
        long summaryCompleted = articleRepository.countSummaryCompleted();
        double summaryRate = rate(summaryCompleted, summaryProcessed);

        long biasTotal = biasAnalysisRepository.count();
        long biasDone = biasAnalysisRepository.countByStatusValue("DONE");
        double biasRate = rate(biasDone, biasTotal);

        long trendIssues = issueSnapshotRepository.count();

        return new AdminKpiResponse(
                totalUsers, activeUsers, totalArticles, summaryRate, biasRate, trendIssues);
    }

    /** 12 스케줄러 상태(enabled + 메타). */
    @Transactional(readOnly = true)
    public List<SchedulerStatusResponse> getSchedulerStatuses() {
        return schedulerControlService.findAll().stream()
                .map(
                        s ->
                                new SchedulerStatusResponse(
                                        s.getSchedulerKey(), s.isEnabled(), s.getUpdatedAt()))
                .sorted((a, b) -> a.schedulerKey().compareTo(b.schedulerKey()))
                .toList();
    }

    /** 소스별 수집량(최근 days일). 빈 데이터 시 빈 목록. */
    @Transactional(readOnly = true)
    public List<CollectionVolumeResponse> getCollectionVolume(int days) {
        LocalDate since = LocalDate.now(ZoneOffset.UTC).minusDays(Math.max(0, days));
        return sourceDailyUsageRepository.volumeSince(since).stream()
                .map(
                        row ->
                                new CollectionVolumeResponse(
                                        ((Number) row[0]).longValue(), ((Number) row[1]).longValue()))
                .toList();
    }

    /** 어드민 편향 뷰(★ hidden 포함). */
    @Transactional(readOnly = true)
    public BiasAdminViewResponse getBiasView() {
        long total = biasAnalysisRepository.count();
        long done = biasAnalysisRepository.countByStatusValue("DONE");
        long failed = biasAnalysisRepository.countByStatusValue("FAILED");
        long pending =
                biasAnalysisRepository.countByStatusValue("PENDING")
                        + biasAnalysisRepository.countByStatusValue("PROCESSING");
        long analyzedArticles = biasAnalysisRepository.countAnalyzedArticlesIncludingHidden();
        return new BiasAdminViewResponse(total, done, pending, failed, analyzedArticles);
    }

    /** 어드민 트렌드 뷰(★ hidden 포함). */
    @Transactional(readOnly = true)
    public TrendAdminViewResponse getTrendView() {
        long issueCount = issueSnapshotRepository.count();
        long keyworded = articleKeywordRepository.countKeywordedArticlesIncludingHidden();
        return new TrendAdminViewResponse(issueCount, keyworded);
    }

    /** 분모 0 가드 비율(%). */
    private static double rate(long numerator, long denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return Math.round((double) numerator / denominator * 1000.0) / 10.0;
    }
}
