package com.tempoapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /** HMAC signing secret; must be at least 32 bytes for HS256. */
    private String secret;

    /** Access token lifetime in milliseconds. */
    private long accessExpiryMs;

    /** Refresh token lifetime in milliseconds. */
    private long refreshExpiryMs;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getAccessExpiryMs() {
        return accessExpiryMs;
    }

    public void setAccessExpiryMs(long accessExpiryMs) {
        this.accessExpiryMs = accessExpiryMs;
    }

    public long getRefreshExpiryMs() {
        return refreshExpiryMs;
    }

    public void setRefreshExpiryMs(long refreshExpiryMs) {
        this.refreshExpiryMs = refreshExpiryMs;
    }
}
