package com.pdfeditor.session;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SessionProperties {

    @Value("${app.public-base-url:http://localhost:5173}")
    private String publicBaseUrl;

    @Value("${app.sessions.dir:target/sessions}")
    private String dir;

    @Value("${app.sessions.ttl-minutes:60}")
    private long ttlMinutes;

    @Value("${app.sessions.max-size-bytes:52428800}")
    private long maxSizeBytes;

    @Value("${app.sessions.rate-limit-per-minute:10}")
    private int rateLimitPerMinute;

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public long getTtlMinutes() {
        return ttlMinutes;
    }

    public void setTtlMinutes(long ttlMinutes) {
        this.ttlMinutes = ttlMinutes;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public void setMaxSizeBytes(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }
}
