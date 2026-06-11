package com.newscurator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        int batchSize,
        int retryLimit,
        long delayBetweenCallsMs,
        DeepRetryProperties deepRetry) {

    public record DeepRetryProperties(int cooldownMinutes, int limit) {}
}
