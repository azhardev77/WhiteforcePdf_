package com.pdfeditor.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Filter that authenticates incoming requests using an API Key provided in the request headers (default: X-API-KEY).
 * Blocks unauthorized or unauthenticated requests with a 401 Unauthorized status and a JSON error response.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    public static final String DEFAULT_HEADER_NAME = "X-API-KEY";

    private final String expectedApiKey;
    private final String headerName;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(String expectedApiKey, String headerName, ObjectMapper objectMapper) {
        this.expectedApiKey = (expectedApiKey != null) ? expectedApiKey.trim() : "";
        this.headerName = (headerName != null && !headerName.isBlank()) ? headerName.trim() : DEFAULT_HEADER_NAME;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 1. Allow all CORS preflight requests without authentication
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }

        // 2. Only enforce API key authentication on /api/** endpoints
        if (!path.startsWith("/api/")) {
            return true;
        }

        // 3. Public system / health endpoints can bypass
        if (path.equals("/api/health") || path.startsWith("/api/public/")) {
            return true;
        }

        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Check configured header, with case-insensitive fallback to x-api-key
        String providedApiKey = request.getHeader(headerName);
        if (providedApiKey == null || providedApiKey.isBlank()) {
            providedApiKey = request.getHeader("x-api-key");
        }

        if (providedApiKey != null) {
            providedApiKey = providedApiKey.trim();
        }

        // Validate key
        if (providedApiKey == null || !providedApiKey.equals(expectedApiKey)) {
            log.warn("Unauthorized API access attempt to [{}] from IP [{}]: missing or invalid '{}' header",
                    request.getRequestURI(), request.getRemoteAddr(), headerName);

            sendUnauthorizedResponse(request, response);
            return;
        }

        // Key is valid - proceed with request
        filterChain.doFilter(request, response);
    }

    private void sendUnauthorizedResponse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Set CORS headers so cross-origin callers receive 401 response instead of CORS network error
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Access-Control-Allow-Headers", "*");
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> errorDetails = new LinkedHashMap<>();
        errorDetails.put("timestamp", Instant.now().toString());
        errorDetails.put("status", HttpServletResponse.SC_UNAUTHORIZED);
        errorDetails.put("error", "Unauthorized");
        errorDetails.put("message", "Invalid or missing API Key. Please provide a valid '" + headerName + "' header.");
        errorDetails.put("path", request.getRequestURI());

        response.getWriter().write(objectMapper.writeValueAsString(errorDetails));
        response.getWriter().flush();
    }

    public String getExpectedApiKey() {
        return expectedApiKey;
    }

    public String getHeaderName() {
        return headerName;
    }
}
