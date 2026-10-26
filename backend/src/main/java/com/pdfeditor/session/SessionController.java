package com.pdfeditor.session;

import com.pdfeditor.session.dto.AppSessionCreateResponse;
import com.pdfeditor.session.dto.SessionCreateResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
public class SessionController {

    private final SessionService sessionService;
    private final SessionRateLimiter sessionRateLimiter;

    public SessionController(SessionService sessionService, SessionRateLimiter sessionRateLimiter) {
        this.sessionService = sessionService;
        this.sessionRateLimiter = sessionRateLimiter;
    }

    /**
     * POST /api/v1/sessions [X-API-KEY]
     * Multipart file + mode -> {id, editUrl, expiresAt}
     */
    @PostMapping(value = "/api/v1/sessions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SessionCreateResponse> createApiSession(
            @RequestParam("file") MultipartFile file,
            @RequestParam("mode") String modeStr,
            HttpServletRequest request) throws IOException {

        Mode mode = Mode.fromString(modeStr);
        String clientId = resolveClientId(request);

        PdfSession session = sessionService.createSession(file, mode, Source.API, clientId);
        String editUrl = sessionService.buildEditUrl(session.getId());

        SessionCreateResponse response = new SessionCreateResponse(
                session.getId(),
                editUrl,
                session.getExpiresAt()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/v1/sessions/{id}/result [X-API-KEY]
     * -> processed PDF bytes
     * An API client may only read sessions it created.
     */
    @GetMapping("/api/v1/sessions/{id}/result")
    public ResponseEntity<?> getApiSessionResult(
            @PathVariable("id") String id,
            HttpServletRequest request) throws IOException {

        Optional<PdfSession> sessionOpt = sessionService.getSession(id);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorBody(404, "Not Found", "Session not found or has expired"));
        }

        PdfSession session = sessionOpt.get();
        String callerClientId = resolveClientId(request);

        // Verify ownership: Source must be API and clientId must match
        if (session.getSource() != Source.API || !session.getClientId().equals(callerClientId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorBody(403, "Forbidden", "Access denied: session belongs to another client or origin"));
        }

        byte[] pdfBytes = sessionService.getSessionPdfBytes(session);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + session.getId() + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    /**
     * POST /app/sessions [no key, rate/size limited]
     * Multipart file + mode -> {id}
     */
    @PostMapping(value = "/app/sessions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> createAppSession(
            @RequestParam("file") MultipartFile file,
            @RequestParam("mode") String modeStr,
            HttpServletRequest request) throws IOException {

        String clientIp = getClientIp(request);
        if (!sessionRateLimiter.tryAcquire(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorBody(429, "Too Many Requests", "Rate limit exceeded. Please try again later."));
        }

        Mode mode = Mode.fromString(modeStr);
        PdfSession session = sessionService.createSession(file, mode, Source.UPLOAD, "upload");

        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AppSessionCreateResponse(session.getId()));
    }

    /**
     * GET /app/sessions/{id}/pdf
     * -> PDF bytes (404 if expired)
     */
    @GetMapping("/app/sessions/{id}/pdf")
    public ResponseEntity<?> getAppSessionPdf(@PathVariable("id") String id) throws IOException {
        Optional<PdfSession> sessionOpt = sessionService.getSession(id);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorBody(404, "Not Found", "Session not found or has expired"));
        }

        PdfSession session = sessionOpt.get();
        byte[] pdfBytes = sessionService.getSessionPdfBytes(session);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + session.getId() + ".pdf\"")
                .header("X-Session-Mode", session.getMode().name())
                .header("X-Session-Source", session.getSource().name())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    /**
     * GET /app/sessions/{id}
     * -> Session metadata {id, mode, source, status, expiresAt} (404 if expired)
     */
    @GetMapping("/app/sessions/{id}")
    public ResponseEntity<?> getAppSession(@PathVariable("id") String id) {
        Optional<PdfSession> sessionOpt = sessionService.getSession(id);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorBody(404, "Not Found", "Session not found or has expired"));
        }

        PdfSession session = sessionOpt.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", session.getId());
        body.put("mode", session.getMode().name());
        body.put("source", session.getSource().name());
        body.put("status", session.getStatus().name());
        body.put("expiresAt", session.getExpiresAt().toString());
        return ResponseEntity.ok(body);
    }

    @ExceptionHandler(SessionValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(SessionValidationException ex) {
        String errorName = switch (ex.getStatusCode()) {
            case 413 -> "Payload Too Large";
            case 422 -> "Unprocessable Entity";
            default -> "Bad Request";
        };
        return ResponseEntity.status(ex.getStatusCode())
                .body(createErrorBody(ex.getStatusCode(), errorName, ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(createErrorBody(400, "Bad Request", ex.getMessage()));
    }

    private String resolveClientId(HttpServletRequest request) {
        String clientId = request.getHeader("X-CLIENT-ID");
        if (clientId != null && !clientId.isBlank()) {
            return clientId.trim();
        }
        String apiKey = request.getHeader("X-API-KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = request.getHeader("x-api-key");
        }
        if (apiKey != null && !apiKey.isBlank()) {
            return "api-client-" + Integer.toHexString(apiKey.trim().hashCode());
        }
        return "api-client-default";
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private Map<String, Object> createErrorBody(int status, String error, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        map.put("error", error);
        map.put("message", message);
        return map;
    }
}
