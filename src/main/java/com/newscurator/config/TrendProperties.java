package com.newscurator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 트렌드 집계/조회 설정 (`app.trend.*`). 스케줄러 주기/cron은 스케줄러에서 @Value로 주입.
 */
@ConfigurationProperties(prefix = "app.trend")
public record TrendProperties(
        int slotHours,
        int top5WindowHours,
        int retentionDays,
        int minArticleCount,
        int smoothingK,
        int extractWindowHours,
        int summaryWaitHours,
        Cooccurrence cooccurrence) {

    public TrendProperties {
        if (slotHours <= 0) slotHours = 1;
        if (top5WindowHours <= 0) top5WindowHours = 24;
        if (retentionDays <= 0) retentionDays = 90;
        if (minArticleCount <= 0) minArticleCount = 2;
        if (smoothingK <= 0) smoothingK = 1;
        if (extractWindowHours <= 0) extractWindowHours = 25;
        if (summaryWaitHours <= 0) summaryWaitHours = 1;
        if (cooccurrence == null) cooccurrence = new Cooccurrence(2, 2);
    }

    public record Cooccurrence(int minEdgeWeight, int minClusterSize) {
        public Cooccurrence {
            if (minEdgeWeight <= 0) minEdgeWeight = 2;
            if (minClusterSize <= 0) minClusterSize = 2;
        }
    }
}
