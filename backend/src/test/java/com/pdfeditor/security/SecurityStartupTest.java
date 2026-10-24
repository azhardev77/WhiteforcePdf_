package com.pdfeditor.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfeditor.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityStartupTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SecurityConfig.class, ObjectMapper.class);

    @Test
    @DisplayName("Application must fail to start when API_KEY is missing or empty")
    void shouldFailStartupWhenApiKeyMissing() {
        contextRunner
                .withPropertyValues("app.security.api-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("API_KEY environment variable is required but not set. Please set the API_KEY environment variable before starting the application.");
                });
    }

    @Test
    @DisplayName("Application starts successfully when valid API_KEY is provided")
    void shouldStartWhenApiKeyProvided() {
        contextRunner
                .withPropertyValues("app.security.api-key=test_valid_key_123")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ApiKeyAuthFilter.class);
                    ApiKeyAuthFilter filter = context.getBean(ApiKeyAuthFilter.class);
                    assertThat(filter.getExpectedApiKey()).isEqualTo("test_valid_key_123");
                });
    }
}
