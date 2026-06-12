package com.newscurator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.feed.ranking")
public record FeedRankingProperties(
        int categoryMatchScore,
        int keywordMatchScore,
        int recencyWindowHours,
        int recencyMaxScore,
        int feedWindowDays) {}
