package com.pdfeditor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfeditor.security.ApiKeyAuthFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${app.security.api-key:}")
    private String apiKey;

    @Value("${app.security.ui-api-key:}")
    private String uiApiKey;

    @Value("${app.security.header-name:X-API-KEY}")
    private String headerName;

    @jakarta.annotation.PostConstruct
    public void validateApiKey() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("API_KEY environment variable is required but not set. Please set the API_KEY environment variable before starting the application.");
        }
        if (uiApiKey == null || uiApiKey.trim().isEmpty()) {
            log.warn("UI_API_KEY is not set");
        } else if (uiApiKey.trim().equals(apiKey.trim())) {
            throw new IllegalStateException("UI_API_KEY must not be equal to API_KEY.");
        }
    }

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(ObjectMapper objectMapper) {
        String effectiveUiKey = (uiApiKey != null && !uiApiKey.trim().isEmpty()) ? uiApiKey : apiKey;
        return new ApiKeyAuthFilter(apiKey, effectiveUiKey, headerName, objectMapper);
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyFilterRegistration(ApiKeyAuthFilter filter) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setName("apiKeyAuthFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
