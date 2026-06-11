package com.newscurator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jwt")
public class JwtConfig {

    private String secret = "default-test-secret-change-in-prod-minimum-32-chars!!";
    private long accessTtlSeconds = 3600;
    private int refreshTtlDays = 30;

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public long getAccessTtlSeconds() { return accessTtlSeconds; }
    public void setAccessTtlSeconds(long accessTtlSeconds) { this.accessTtlSeconds = accessTtlSeconds; }

    public int getRefreshTtlDays() { return refreshTtlDays; }
    public void setRefreshTtlDays(int refreshTtlDays) { this.refreshTtlDays = refreshTtlDays; }
}
