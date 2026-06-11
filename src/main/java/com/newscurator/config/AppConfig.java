package com.newscurator.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
    CollectionProperties.class,
    RetentionProperties.class,
    FeedProperties.class,
    AiProperties.class
})
public class AppConfig {}
