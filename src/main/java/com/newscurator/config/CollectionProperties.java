package com.newscurator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.scheduler.collection")
public record CollectionProperties(long intervalMs, int batchSize) {}
