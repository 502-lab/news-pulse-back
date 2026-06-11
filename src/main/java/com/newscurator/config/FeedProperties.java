package com.newscurator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.feed")
public record FeedProperties(int defaultPageSize, int maxPageSize) {}
