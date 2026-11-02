package com.pdfeditor.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRateLimiterTest {

    @Test
    @DisplayName("ApiRateLimiter keys by SHA-256 of API key and throttles after maxRequests")
    void testRateLimitByApiKeySha256() {
        ApiRateLimiter limiter = new ApiRateLimiter(5);
        String testKey = "secret_api_key_123";

        // First 5 requests must pass
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(testKey))
                    .withFailMessage("Request " + i + " should succeed")
                    .isTrue();
        }

        // 6th request must be rejected
        assertThat(limiter.tryAcquire(testKey)).isFalse();

        // Different key should still have its own quota
        String anotherKey = "another_secret_key_456";
        assertThat(limiter.tryAcquire(anotherKey)).isTrue();
    }

    @Test
    @DisplayName("ApiRateLimiter produces valid deterministic SHA-256 hash")
    void testSha256HashDeterministic() {
        String hash1 = ApiRateLimiter.hashApiKey("test_key");
        String hash2 = ApiRateLimiter.hashApiKey("test_key");
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 hex string is 64 chars
    }
}

