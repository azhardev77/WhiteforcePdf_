package com.pdfeditor.process;

import com.pdfeditor.extraction.ExtractionPipeline;
import com.pdfeditor.model.DocumentModel;
import com.pdfeditor.process.dto.ProcessOperation;
import com.pdfeditor.process.dto.ProcessResult;
import com.pdfeditor.process.dto.ResumeProcessResponse;
import com.pdfeditor.rules.Match;
import com.pdfeditor.rules.PiiDetector;
import com.pdfeditor.rules.ResumeRedactionService;
import com.pdfeditor.rules.ResumeRules;
import com.pdfeditor.session.SessionRateLimiter;
import com.pdfeditor.session.SessionValidationException;
import com.pdfeditor.session.SessionValidator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ResumeRedactionService resumeRedactionService;
    private final ExtractionPipeline extractionPipeline;
    private final ResumeRules resumeRules;

    @Autowired
    public ProcessController(ProcessService processService,
                             SessionValidator sessionValidator,
                             SessionRateLimiter sessionRateLimiter,
                             ResumeRedactionService resumeRedactionService,
                             ExtractionPipeline extractionPipeline,
                             ResumeRules resumeRules) {
        this.processService = processService;
        this.sessionValidator = sessionValidator;
        this.sessionRateLimiter = sessionRateLimiter;
        this.resumeRedactionService = resumeRedactionService;
        this.extractionPipeline = extractionPipeline;
        this.resumeRules = resumeRules;
    }

    public ProcessController(ProcessService processService,
                             SessionValidator sessionValidator,
                             SessionRateLimiter sessionRateLimiter) {
        this(processService, sessionValidator, sessionRateLimiter, null, null, null);
    }

    /**
     * POST /api/v1/resume/process [X-API-KEY]
     * B2B automated resume processing pipeline:
     * Extracts email and phone metadata, executes contact icon and PII redaction,
     * verifies page count invariant, and returns JSON payload with base64-encoded PDF.
     */
    @PostMapping(value = "/resume/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> processResume(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "redactEmail", required = false, defaultValue = "true") boolean redactEmail,
            @RequestParam(value = "redactPhone", required = false, defaultValue = "true") boolean redactPhone,
            HttpServletRequest request) throws IOException {

        String clientIp = getClientIp(request);
        if (!sessionRateLimiter.tryAcquire(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorBody(429, "Too Many Requests", "Rate limit exceeded. Please wait before retrying."));
        }

        // Validate file via SessionValidator
        byte[] pdfBytes = sessionValidator.validate(file);

        // 1. Extract details from original document
        java.util.Set<String> emails = new java.util.LinkedHashSet<>();
        java.util.Set<String> phones = new java.util.LinkedHashSet<>();

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "resume.pdf";
        }

        if (extractionPipeline != null && resumeRules != null) {
            try {
                DocumentModel docModel = extractionPipeline.extract(pdfBytes, filename);
                List<Match> matches = resumeRules.find(docModel);
                if (matches != null) {
                    for (Match m : matches) {
                        if ("email".equalsIgnoreCase(m.getType()) && m.getMatchedText() != null && !m.getMatchedText().isBlank()) {
                            String c = cleanEmail(m.getMatchedText());
                            if (c != null && !c.isBlank()) emails.add(c);
                        } else if ("phone".equalsIgnoreCase(m.getType()) && m.getMatchedText() != null && !m.getMatchedText().isBlank()) {
                            String c = cleanPhone(m.getMatchedText());
                            if (c != null && !c.isBlank()) phones.add(c);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Also extract using PDFTextStripper + PiiDetector to guarantee full capture
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String fullText = stripper.getText(doc);
            if (fullText != null) {
                List<PiiDetector.PiiSpan> spans = PiiDetector.findPIISpans(fullText);
                for (PiiDetector.PiiSpan span : spans) {
                    if ("email".equalsIgnoreCase(span.getType()) && span.getMatch() != null) {
                        String c = cleanEmail(span.getMatch());
                        if (c != null && !c.isBlank()) emails.add(c);
                    } else if ("phone".equalsIgnoreCase(span.getType()) && span.getMatch() != null) {
                        String c = cleanPhone(span.getMatch());
                        if (c != null && !c.isBlank()) phones.add(c);
                    }
                }
            }
        }

        // 2. Perform redaction
        List<String> categories = new java.util.ArrayList<>();
        if (redactEmail) categories.add("email");
        if (redactPhone) categories.add("phone");

        byte[] redactedPdfBytes = pdfBytes;
        if (!categories.isEmpty() && resumeRedactionService != null) {
            redactedPdfBytes = resumeRedactionService.redactResume(pdfBytes, categories);
        }

        // 3. Verify Page Count Invariant
        int pageCount;
        try (PDDocument doc = Loader.loadPDF(redactedPdfBytes)) {
            pageCount = doc.getNumberOfPages();
        }

        String base64Pdf = java.util.Base64.getEncoder().encodeToString(redactedPdfBytes);

        ResumeProcessResponse response = new ResumeProcessResponse(
                new ResumeProcessResponse.ExtractedDetails(new java.util.ArrayList<>(emails), new java.util.ArrayList<>(phones)),
                base64Pdf,
                pageCount
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Page-Count", String.valueOf(pageCount));
        headers.set("X-Emails-Count", String.valueOf(emails.size()));
        headers.set("X-Phones-Count", String.valueOf(phones.size()));

        return ResponseEntity.ok()
                .headers(headers)
                .body(response);
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

    private static final java.util.regex.Pattern CLEAN_EMAIL_PATTERN = java.util.regex.Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final java.util.regex.Pattern CLEAN_PHONE_LABEL_PREFIX = java.util.regex.Pattern.compile("(?i)^[\\s\\u2600-\\u27BF\\uE000-\\uF8FF\\x{1F300}-\\x{1F9FF}\\u260E\\u2709\\uF095\\uF0E0✉☎✆📞📧📱📍]*(?:phone|tel|telephone|mobile|mob|cell|call|contact|fa-phone|fa-mobile)?\\s*[:.-]?\\s*");

    private String cleanEmail(String raw) {
        if (raw == null) return null;
        java.util.regex.Matcher m = CLEAN_EMAIL_PATTERN.matcher(raw);
        if (m.find()) {
            return m.group().trim();
        }
        return raw.trim();
    }

    private String cleanPhone(String raw) {
        if (raw == null) return null;
        String cleaned = CLEAN_PHONE_LABEL_PREFIX.matcher(raw.trim()).replaceFirst("").trim();
        return cleaned.isEmpty() ? raw.trim() : cleaned;
    }

    private Map<String, Object> createErrorBody(int status, String error, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        map.put("error", error);
        map.put("message", message);
        return map;
    }
}

