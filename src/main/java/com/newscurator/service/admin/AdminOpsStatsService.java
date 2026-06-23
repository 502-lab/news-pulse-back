package com.newscurator.service.admin;

import com.newscurator.domain.enums.ProcessingStatus;
import com.newscurator.dto.response.AuditLogItemResponse;
import com.newscurator.dto.response.CollectionDetailItemResponse;
import com.newscurator.dto.response.ErrorLogResponse;
import com.newscurator.dto.response.OpsStatItemResponse;
import com.newscurator.repository.AdminAuditLogRepository;
import com.newscurator.repository.ArticleRepository;
import com.newscurator.repository.BiasAnalysisRepository;
import com.newscurator.repository.NotificationOutboxRepository;
import com.newscurator.repository.SourceDailyUsageRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 심층 통계(008 US5). 전부 read-only(감사 비대상) — 기존 데이터 집계.
 *
 * <p>ErrorLog는 신규 저장소 없이 기존 FAILED 상태(요약/편향/알림)를 집계(Q2 결정). 빈 데이터 시 0/빈목록.
 */
@Service
public class AdminOpsStatsService {

    private final ArticleRepository articleRepository;
    private final BiasAnalysisRepository biasAnalysisRepository;
    private final NotificationOutboxRepository notificationOutboxRepository;
    private final SourceDailyUsageRepository sourceDailyUsageRepository;
    private final AdminAuditLogRepository auditLogRepository;

    public AdminOpsStatsService(
            ArticleRepository articleRepository,
            BiasAnalysisRepository biasAnalysisRepository,
            NotificationOutboxRepository notificationOutboxRepository,
            SourceDailyUsageRepository sourceDailyUsageRepository,
            AdminAuditLogRepository auditLogRepository) {
        this.articleRepository = articleRepository;
        this.biasAnalysisRepository = biasAnalysisRepository;
        this.notificationOutboxRepository = notificationOutboxRepository;
        this.sourceDailyUsageRepository = sourceDailyUsageRepository;
        this.auditLogRepository = auditLogRepository;
    }

    /** OpsStats: 일자별 수집 추이(최근 days일). 빈 윈도우면 빈 목록. */
    @Transactional(readOnly = true)
    public List<OpsStatItemResponse> getOpsStats(int days) {
        Instant since = Instant.now().minus(Math.max(0, days), ChronoUnit.DAYS);
        return articleRepository.dailyCollectedSince(since).stream()
                .map(
                        row ->
                                new OpsStatItemResponse(
                                        toInstant(row[0]), ((Number) row[1]).longValue()))
                .toList();
    }

    /** ErrorLog: 기존 FAILED 집계(요약/편향/알림). 신규 테이블 없음. 빈 DB면 0. */
    @Transactional(readOnly = true)
    public ErrorLogResponse getErrorLog() {
        long summaryFailed = articleRepository.countBySummaryStatus(ProcessingStatus.FAILED);
        long biasFailed = biasAnalysisRepository.countByStatusValue("FAILED");
        long notificationFailed = notificationOutboxRepository.countFailed();
        return ErrorLogResponse.of(summaryFailed, biasFailed, notificationFailed);
    }

    /** 소스 수집량 드릴다운(일자별). 빈 데이터면 빈 목록. */
    @Transactional(readOnly = true)
    public List<CollectionDetailItemResponse> getCollectionDetail(long sourceId, int days) {
        LocalDate since = LocalDate.now(ZoneOffset.UTC).minusDays(Math.max(0, days));
        return sourceDailyUsageRepository.detailBySource(sourceId, since).stream()
                .map(
                        row ->
                                new CollectionDetailItemResponse(
                                        ((java.sql.Date) row[0]).toLocalDate(),
                                        ((Number) row[1]).longValue()))
                .toList();
    }

    /** 감사 로그 조회(action·actor·기간 필터, 시간 역순). */
    @Transactional(readOnly = true)
    public Page<AuditLogItemResponse> getAuditLogs(
            String action, UUID actorId, Instant from, Instant to, Pageable pageable) {
        String actionFilter = (action == null || action.isBlank()) ? null : action;
        String actorFilter = actorId == null ? null : actorId.toString();
        return auditLogRepository
                .search(actionFilter, actorFilter, from, to, pageable)
                .map(AuditLogItemResponse::from);
    }

    /** date_trunc 결과는 Instant 또는 Timestamp로 옴 — 둘 다 처리(007 패턴). */
    private static Instant toInstant(Object ts) {
        if (ts instanceof Instant i) {
            return i;
        }
        return ((Timestamp) ts).toInstant();
    }
}
