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

    @Value("${app.security.api-key:my_secure_secret_key_12345}")
    private String apiKey;

    @Value("${app.security.header-name:X-API-KEY}")
    private String headerName;

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
