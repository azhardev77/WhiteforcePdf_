package com.pdfeditor.process;

import com.pdfeditor.process.dto.ProcessOperation;
import com.pdfeditor.process.dto.ProcessResult;
import com.pdfeditor.session.SessionRateLimiter;
import com.pdfeditor.session.SessionValidationException;
import com.pdfeditor.session.SessionValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ProcessController {

    private final ProcessService processService;
    private final SessionValidator sessionValidator;
    private final SessionRateLimiter sessionRateLimiter;

    public ProcessController(ProcessService processService,
                             SessionValidator sessionValidator,
                             SessionRateLimiter sessionRateLimiter) {
        this.processService = processService;
        this.sessionValidator = sessionValidator;
        this.sessionRateLimiter = sessionRateLimiter;
    }

    @PostMapping(value = "/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> process(
            @RequestParam("file") MultipartFile file,
            @RequestParam("ops") String opsJson,
            HttpServletRequest request) throws IOException {

        String clientIp = getClientIp(request);
        if (!sessionRateLimiter.tryAcquire(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorBody(429, "Too Many Requests", "Rate limit exceeded. Please wait before retrying."));
        }

        // Validate file via SessionValidator (checks non-empty, size <= 10MB, %PDF magic bytes, loadable, unencrypted)
        byte[] pdfBytes = sessionValidator.validate(file);

        // Parse and validate ops
        List<ProcessOperation> operations = processService.parseAndValidateOps(opsJson);

        // Process PDF
        ProcessResult result = processService.processPdf(pdfBytes, operations);

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "processed.pdf";
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(result.pdfBytes().length);
        headers.set("X-Process-Matches", String.valueOf(result.totalMatches()));
        headers.add("Access-Control-Expose-Headers", "X-Process-Matches");

        if (result.warningsCount() > 0) {
            headers.set("X-Export-Warnings", String.valueOf(result.warningsCount()));
            headers.add("Access-Control-Expose-Headers", "X-Export-Warnings");
        }

        return ResponseEntity.ok()
                .headers(headers)
                .body(result.pdfBytes());
    }

    @ExceptionHandler(SessionValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(SessionValidationException ex) {
        String errorName = switch (ex.getStatusCode()) {
            case 413 -> "Payload Too Large";
            case 422 -> "Unprocessable Entity";
            default -> "Bad Request";
        };
        return ResponseEntity.status(ex.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(createErrorBody(ex.getStatusCode(), errorName, ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(createErrorBody(400, "Bad Request", ex.getMessage()));
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

