package com.pdfeditor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfeditor.security.ApiKeyAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class SecurityConfig {

    @Value("${app.security.api-key:}")
    private String apiKey;

    @Value("${app.security.header-name:X-API-KEY}")
    private String headerName;

    @jakarta.annotation.PostConstruct
    public void validateApiKey() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("API_KEY environment variable is required but not set. Please set the API_KEY environment variable before starting the application.");
        }
    }

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(ObjectMapper objectMapper) {
        return new ApiKeyAuthFilter(apiKey, headerName, objectMapper);
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
