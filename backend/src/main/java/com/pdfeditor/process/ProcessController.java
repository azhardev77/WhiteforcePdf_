package com.pdfeditor.process;

import com.pdfeditor.extraction.ExtractionPipeline;
import com.pdfeditor.model.DocumentModel;
import com.pdfeditor.process.dto.ProcessOperation;
import com.pdfeditor.process.dto.ProcessResult;
import com.pdfeditor.process.dto.ResumeItemResult;
import com.pdfeditor.process.dto.ResumeProcessResponse;
import com.pdfeditor.rules.Match;
import com.pdfeditor.rules.PiiDetector;
import com.pdfeditor.rules.ResumeRedactionService;
import com.pdfeditor.rules.ResumeRules;
import com.pdfeditor.security.ApiRateLimiter;
import com.pdfeditor.session.SessionRateLimiter;
import com.pdfeditor.session.SessionValidationException;
import com.pdfeditor.session.SessionValidator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;

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
import java.util.ArrayList;
import java.util.Collections;
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
    private final ApiRateLimiter apiRateLimiter;

    @Autowired
    public ProcessController(ProcessService processService,
                             SessionValidator sessionValidator,
                             SessionRateLimiter sessionRateLimiter,
                             ResumeRedactionService resumeRedactionService,
                             ExtractionPipeline extractionPipeline,
                             ResumeRules resumeRules,
                             ApiRateLimiter apiRateLimiter) {
        this.processService = processService;
        this.sessionValidator = sessionValidator;
        this.sessionRateLimiter = sessionRateLimiter;
        this.resumeRedactionService = resumeRedactionService;
        this.extractionPipeline = extractionPipeline;
        this.resumeRules = resumeRules;
        this.apiRateLimiter = apiRateLimiter;
    }

    public ProcessController(ProcessService processService,
                             SessionValidator sessionValidator,
                             SessionRateLimiter sessionRateLimiter) {
        this(processService, sessionValidator, sessionRateLimiter, null, null, null, null);
    }

    /**
     * POST /api/v1/resume/process [X-API-KEY]
     * B2B automated resume processing pipeline:
     * Supports both single PDF resume and bulk ZIP archive uploads.
     * Extracts email and phone metadata, executes contact icon and PII redaction according to active toggles,
     * verifies fail-closed invariants, and returns structured JSON with remarks and clean PDF/ZIP payloads.
     */
    @PostMapping(value = "/resume/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> processResume(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "files", required = false) List<MultipartFile> filesList,
            @RequestParam(value = "redactEmail", required = false, defaultValue = "true") boolean redactEmail,
            @RequestParam(value = "redactPhone", required = false, defaultValue = "true") boolean redactPhone,
            @RequestParam(value = "extractEmail", required = false) Boolean extractEmailParam,
            @RequestParam(value = "extractPhone", required = false) Boolean extractPhoneParam,
            HttpServletRequest request) throws IOException {

        String apiKey = request.getHeader("X-API-KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = request.getHeader("x-api-key");
        }
        if (apiRateLimiter != null && !apiRateLimiter.tryAcquire(apiKey)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorBody(429, "Too Many Requests", "Rate limit exceeded. Please wait before retrying.", request.getRequestURI()));
        }

        if ((file == null || file.isEmpty()) && (filesList == null || filesList.isEmpty())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorBody(400, "Bad Request", "File is required (parameter 'file' or 'files') and cannot be empty", request.getRequestURI()));
        }

        boolean effectiveExtractEmail = extractEmailParam != null ? extractEmailParam : true;
        boolean effectiveExtractPhone = extractPhoneParam != null ? extractPhoneParam : true;
        boolean effectiveRedactEmail = redactEmail;
        boolean effectiveRedactPhone = redactPhone;

        // If multiple files passed via 'files' parameter, handle as multi-PDF batch
        if (filesList != null && filesList.size() > 1) {
            List<ZipResumeProcessor.ZipEntryPdf> entries = new ArrayList<>();
            for (MultipartFile mf : filesList) {
                if (mf != null && !mf.isEmpty()) {
                    String name = mf.getOriginalFilename() != null ? mf.getOriginalFilename() : "resume_" + (entries.size() + 1) + ".pdf";
                    entries.add(new ZipResumeProcessor.ZipEntryPdf(name, mf.getBytes()));
                }
            }
            if (entries.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(createErrorBody(400, "Empty Files", "Uploaded files list contained only empty files", request.getRequestURI()));
            }
            return processPdfBatchEntries(entries, "multi_resumes_batch.zip", effectiveExtractEmail, effectiveExtractPhone, effectiveRedactEmail, effectiveRedactPhone);
        }

        // If single file in filesList, use it as 'file'
        if (file == null && filesList != null && !filesList.isEmpty()) {
            file = filesList.get(0);
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "upload.pdf";
        }

        byte[] rawBytes;
        try {
            rawBytes = file.getBytes();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorBody(400, "Bad Request", "Failed to read file contents: " + e.getMessage(), request.getRequestURI()));
        }

        // Check if input is a ZIP archive
        if (ZipResumeProcessor.isZip(rawBytes, originalFilename)) {
            List<ZipResumeProcessor.ZipEntryPdf> entries;
            try {
                entries = ZipResumeProcessor.extractPdfEntries(rawBytes);
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(createErrorBody(400, "Invalid ZIP", "Failed to unpack ZIP archive: " + e.getMessage(), request.getRequestURI()));
            }

            if (entries.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(createErrorBody(400, "Empty Archive", "No PDF files found in uploaded ZIP archive", request.getRequestURI()));
            }

            return processPdfBatchEntries(entries, originalFilename, effectiveExtractEmail, effectiveExtractPhone, effectiveRedactEmail, effectiveRedactPhone);
        }

        // Single PDF workflow: validate magic bytes, loadability, and encryption
        byte[] pdfBytes = sessionValidator.validate(file);

        SingleProcessResult singleResult = processSinglePdfBytes(
                pdfBytes,
                originalFilename,
                effectiveExtractEmail,
                effectiveExtractPhone,
                effectiveRedactEmail,
                effectiveRedactPhone
        );

        if (!singleResult.success()) {
            int errStatus = singleResult.warnings().contains("redaction_incomplete")
                    || singleResult.warnings().contains("encrypted_pdf")
                    || singleResult.warnings().contains("scanned_pdf") ? 422 : 400;
            Map<String, Object> errorBody = createErrorBody(errStatus, singleResult.warnings().isEmpty() ? "error" : singleResult.warnings().get(0),
                    singleResult.remark(), request.getRequestURI());
            return ResponseEntity.status(errStatus)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorBody);
        }

        String base64Pdf = java.util.Base64.getEncoder().encodeToString(singleResult.redactedPdfBytes());

        ResumeProcessResponse response = ResumeProcessResponse.single(
                originalFilename,
                "SUCCESS",
                singleResult.remark(),
                new ResumeProcessResponse.ExtractedDetails(singleResult.emails(), singleResult.phones()),
                base64Pdf,
                singleResult.pageCount(),
                singleResult.warnings()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Page-Count", String.valueOf(singleResult.pageCount()));
        headers.set("X-Emails-Count", String.valueOf(singleResult.emails().size()));
        headers.set("X-Phones-Count", String.valueOf(singleResult.phones().size()));

        return ResponseEntity.ok()
                .headers(headers)
                .body(response);
    }

    private ResponseEntity<?> processPdfBatchEntries(
            List<ZipResumeProcessor.ZipEntryPdf> entries,
            String batchName,
            boolean effectiveExtractEmail,
            boolean effectiveExtractPhone,
            boolean effectiveRedactEmail,
            boolean effectiveRedactPhone) {

        List<ResumeItemResult> batchResults = new ArrayList<>();
        Map<String, byte[]> cleanFilesForZip = new LinkedHashMap<>();
        int successCount = 0;
        int errorCount = 0;

        for (ZipResumeProcessor.ZipEntryPdf entry : entries) {
            try {
                SingleProcessResult res = processSinglePdfBytes(
                        entry.data(),
                        entry.name(),
                        effectiveExtractEmail,
                        effectiveExtractPhone,
                        effectiveRedactEmail,
                        effectiveRedactPhone
                );

                if (res.success()) {
                    successCount++;
                    String base64Pdf = res.redactedPdfBytes() != null ?
                            java.util.Base64.getEncoder().encodeToString(res.redactedPdfBytes()) : null;
                    if (res.redactedPdfBytes() != null) {
                        cleanFilesForZip.put("clean_" + entry.name(), res.redactedPdfBytes());
                    }
                    batchResults.add(new ResumeItemResult(
                            entry.name(),
                            "SUCCESS",
                            res.remark(),
                            new ResumeProcessResponse.ExtractedDetails(res.emails(), res.phones()),
                            res.pageCount(),
                            base64Pdf
                    ));
                } else {
                    errorCount++;
                    batchResults.add(new ResumeItemResult(
                            entry.name(),
                            "ERROR",
                            res.remark(),
                            new ResumeProcessResponse.ExtractedDetails(res.emails(), res.phones()),
                            res.pageCount(),
                            null
                    ));
                }
            } catch (Exception e) {
                errorCount++;
                batchResults.add(new ResumeItemResult(
                        entry.name(),
                        "ERROR",
                        "Processing failed: " + e.getMessage(),
                        new ResumeProcessResponse.ExtractedDetails(Collections.emptyList(), Collections.emptyList()),
                        0,
                        null
                ));
            }
        }

        String outputZipBase64 = null;
        try {
            if (!cleanFilesForZip.isEmpty()) {
                outputZipBase64 = java.util.Base64.getEncoder().encodeToString(ZipResumeProcessor.createZip(cleanFilesForZip));
            }
        } catch (Exception ignored) {}

        ResumeProcessResponse batchResponse = ResumeProcessResponse.batch(
                batchName,
                entries.size(),
                successCount,
                errorCount,
                batchResults,
                outputZipBase64,
                Collections.emptyList()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Total-Files", String.valueOf(entries.size()));
        headers.set("X-Success-Count", String.valueOf(successCount));
        headers.set("X-Error-Count", String.valueOf(errorCount));

        return ResponseEntity.ok()
                .headers(headers)
                .body(batchResponse);
    }

    private record SingleProcessResult(
            boolean success,
            String remark,
            List<String> emails,
            List<String> phones,
            byte[] redactedPdfBytes,
            int pageCount,
            List<String> warnings
    ) {}

    private SingleProcessResult processSinglePdfBytes(
            byte[] pdfBytes,
            String filename,
            boolean extractEmail,
            boolean extractPhone,
            boolean redactEmail,
            boolean redactPhone) {

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            if (doc.isEncrypted()) {
                return new SingleProcessResult(false, "Password-protected or encrypted PDFs are not supported",
                        Collections.emptyList(), Collections.emptyList(), null, 0, List.of("encrypted_pdf"));
            }
        } catch (Exception e) {
            return new SingleProcessResult(false, "Invalid or corrupted PDF file: " + e.getMessage(),
                    Collections.emptyList(), Collections.emptyList(), null, 0, List.of("corrupted_pdf"));
        }

        // 1. Extract details from original document
        java.util.Set<String> emails = new java.util.LinkedHashSet<>();
        java.util.Set<String> phones = new java.util.LinkedHashSet<>();

        if (extractionPipeline != null && resumeRules != null) {
            try {
                DocumentModel docModel = extractionPipeline.extract(pdfBytes, filename);
                List<Match> matches = resumeRules.find(docModel);
                if (matches != null) {
                    for (Match m : matches) {
                        if (extractEmail && "email".equalsIgnoreCase(m.getType()) && m.getMatchedText() != null && !m.getMatchedText().isBlank()) {
                            String c = cleanEmail(m.getMatchedText());
                            if (c != null && !c.isBlank()) emails.add(c);
                        } else if (extractPhone && "phone".equalsIgnoreCase(m.getType()) && m.getMatchedText() != null && !m.getMatchedText().isBlank()) {
                            String c = cleanPhone(m.getMatchedText());
                            if (c != null && !c.isBlank()) phones.add(c);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Fallback/additive extraction using PDFTextStripper + PiiDetector
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String fullText = stripper.getText(doc);
            if (fullText != null) {
                List<PiiDetector.PiiSpan> spans = PiiDetector.findPIISpans(fullText);
                for (PiiDetector.PiiSpan span : spans) {
                    if (extractEmail && "email".equalsIgnoreCase(span.getType()) && span.getMatch() != null) {
                        String c = cleanEmail(span.getMatch());
                        if (c != null && !c.isBlank()) emails.add(c);
                    } else if (extractPhone && "phone".equalsIgnoreCase(span.getType()) && span.getMatch() != null) {
                        String c = cleanPhone(span.getMatch());
                        if (c != null && !c.isBlank()) phones.add(c);
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2. Perform redaction
        List<String> categories = new ArrayList<>();
        if (redactEmail) categories.add("email");
        if (redactPhone) categories.add("phone");

        byte[] redactedPdfBytes = pdfBytes;
        if (!categories.isEmpty() && resumeRedactionService != null) {
            try {
                redactedPdfBytes = resumeRedactionService.redactResume(pdfBytes, categories);
            } catch (SessionValidationException sve) {
                return new SingleProcessResult(false, sve.getMessage(),
                        new ArrayList<>(emails), new ArrayList<>(phones), null, 0, List.of(sve.getStatusCode() == 422 ? "scanned_pdf" : "validation_error"));
            } catch (Exception e) {
                return new SingleProcessResult(false, "Redaction failed: " + e.getMessage(),
                        new ArrayList<>(emails), new ArrayList<>(phones), null, 0, List.of("redaction_failed"));
            }
        }

        // 3. Fail-closed verification on output PDF
        boolean leakDetected = false;
        int pageCount;
        try (PDDocument doc = Loader.loadPDF(redactedPdfBytes)) {
            pageCount = doc.getNumberOfPages();

            PDFTextStripper stripper = new PDFTextStripper();
            String outText = stripper.getText(doc);
            if (outText != null && !outText.isBlank()) {
                List<PiiDetector.PiiSpan> remainingSpans = PiiDetector.findPIISpans(outText);
                for (PiiDetector.PiiSpan span : remainingSpans) {
                    String spanType = span.getType() != null ? span.getType().toLowerCase() : "";
                    if ((redactEmail && "email".equals(spanType)) || (redactPhone && "phone".equals(spanType))) {
                        leakDetected = true;
                        break;
                    }
                }
            }

            if (!leakDetected) {
                for (PDPage page : doc.getPages()) {
                    List<PDAnnotation> annotations = page.getAnnotations();
                    if (annotations != null) {
                        for (PDAnnotation annot : annotations) {
                            if (annot instanceof PDAnnotationLink link) {
                                String uri = null;
                                if (link.getAction() instanceof PDActionURI uriAction) {
                                    uri = uriAction.getURI();
                                }
                                if (uri == null) {
                                    COSDictionary cosDict = link.getCOSObject();
                                    COSBase aBase = cosDict.getDictionaryObject(COSName.A);
                                    if (aBase instanceof COSDictionary aDict) {
                                        uri = aDict.getString(COSName.URI);
                                    }
                                }
                                if (uri != null) {
                                    String lowerUri = uri.toLowerCase();
                                    if ((redactEmail && lowerUri.startsWith("mailto:")) || (redactPhone && lowerUri.startsWith("tel:"))) {
                                        leakDetected = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    if (leakDetected) break;
                }
            }
        } catch (Exception e) {
            leakDetected = true;
            pageCount = 1;
        }

        if (leakDetected) {
            return new SingleProcessResult(false, "Redaction incomplete: contact info or links still detected in output PDF",
                    new ArrayList<>(emails), new ArrayList<>(phones), null, pageCount, List.of("redaction_incomplete"));
        }

        List<String> warnings = new ArrayList<>();
        if (emails.isEmpty() && phones.isEmpty()) {
            warnings.add("no_contact_found");
        }

        StringBuilder remark = new StringBuilder();
        if (!emails.isEmpty() && !phones.isEmpty()) {
            remark.append("Extracted ").append(emails.size()).append(" email(s) and ").append(phones.size()).append(" phone(s).");
        } else if (!emails.isEmpty()) {
            remark.append("Extracted ").append(emails.size()).append(" email(s). No phone numbers found.");
        } else if (!phones.isEmpty()) {
            remark.append("Extracted ").append(phones.size()).append(" phone(s). No email address found.");
        } else {
            remark.append("No email or phone contact details detected in document.");
        }
        if (!categories.isEmpty()) {
            remark.append(" Redaction successfully applied.");
        } else {
            remark.append(" Redaction was skipped (toggles OFF).");
        }

        return new SingleProcessResult(true, remark.toString(), new ArrayList<>(emails), new ArrayList<>(phones),
                redactedPdfBytes, pageCount, warnings);
    }

    @PostMapping(value = "/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> process(
            @RequestParam("file") MultipartFile file,
            @RequestParam("ops") String opsJson,
            HttpServletRequest request) throws IOException {

        String apiKey = request.getHeader("X-API-KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = request.getHeader("x-api-key");
        }
        if (apiRateLimiter != null && !apiRateLimiter.tryAcquire(apiKey)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorBody(429, "Too Many Requests", "Rate limit exceeded. Please wait before retrying.", request.getRequestURI()));
        }

        // Validate file via SessionValidator (checks non-empty, size <= maxSizeBytes, %PDF magic bytes, loadable, unencrypted)
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

    private Map<String, Object> createErrorBody(int status, String error, String message, String path) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", java.time.Instant.now().toString());
        map.put("status", status);
        map.put("error", error);
        map.put("message", message);
        map.put("path", path != null ? path : "");
        return map;
    }
}
