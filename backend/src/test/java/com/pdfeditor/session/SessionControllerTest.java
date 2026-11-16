package com.pdfeditor.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfeditor.config.CorsConfig;
import com.pdfeditor.config.SecurityConfig;
import com.pdfeditor.rules.RedactionCategoryRegistry;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = SessionController.class)
@Import({com.pdfeditor.config.GlobalExceptionHandler.class, SecurityConfig.class, CorsConfig.class, SessionService.class, SessionValidator.class, SessionRateLimiter.class, SessionProperties.class, RedactionCategoryRegistry.class})
@TestPropertySource(properties = {
        "app.security.api-key=test_secret_api_key_xyz",
        "app.security.header-name=X-API-KEY",
        "app.public-base-url=http://localhost:5173",
        "app.sessions.ttl-minutes=60",
        "app.sessions.max-size-bytes=52428800",
        "app.sessions.rate-limit-per-minute=10"
})
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionProperties sessionProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    private byte[] samplePdfBytes;

    @BeforeEach
    void setUp() throws IOException {
        sessionProperties.setDir(tempDir.toString());
        samplePdfBytes = createSamplePdf("Sample Test PDF Content");
    }

    @Test
    @DisplayName("POST /api/v1/sessions and GET /api/v1/sessions/{id}/result (create & read)")
    void testCreateAndReadApiSession() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                samplePdfBytes
        );

        MvcResult result = mockMvc.perform(multipart("/api/v1/sessions")
                        .file(file)
                        .param("mode", "RESUME")
                        .header("X-API-KEY", "test_secret_api_key_xyz")
                        .header("X-CLIENT-ID", "client-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.editUrl", startsWith("http://localhost:5173/edit/")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();

        String responseJson = result.getResponse().getContentAsString();
        String sessionId = objectMapper.readTree(responseJson).get("id").asText();

        // Read result using valid API key & client ID
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/result")
                        .header("X-API-KEY", "test_secret_api_key_xyz")
                        .header("X-CLIENT-ID", "client-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(samplePdfBytes));
    }

    @Test
    @DisplayName("POST /api/v1/sessions rejects missing or wrong API key with 401")
    void testWrongKeyAccess() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", samplePdfBytes);

        // Missing key
        mockMvc.perform(multipart("/api/v1/sessions")
                        .file(file)
                        .param("mode", "EDIT"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)));

        // Wrong key
        mockMvc.perform(multipart("/api/v1/sessions")
                        .file(file)
                        .param("mode", "EDIT")
                        .header("X-API-KEY", "wrong_key_123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)));
    }

    @Test
    @DisplayName("GET /api/v1/sessions/{id}/result rejects cross-client access with 403 Forbidden")
    void testCrossClientAccess() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", samplePdfBytes);

        MvcResult createResult = mockMvc.perform(multipart("/api/v1/sessions")
                        .file(file)
                        .param("mode", "TENDER")
                        .header("X-API-KEY", "test_secret_api_key_xyz")
                        .header("X-CLIENT-ID", "client-alpha"))
                .andExpect(status().isCreated())
                .andReturn();

        String sessionId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        // Different client tries to read the session
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/result")
                        .header("X-API-KEY", "test_secret_api_key_xyz")
                        .header("X-CLIENT-ID", "client-bravo"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.error", is("Forbidden")));
    }

    @Test
    @DisplayName("GET /api/v1/sessions/{id}/result and /app/sessions/{id}/pdf return 404 for expired session")
    void testExpiredSession() throws Exception {
        String sessionId = sessionService.generateSessionId();
        Path filePath = tempDir.resolve(sessionId + ".pdf");
        java.nio.file.Files.write(filePath, samplePdfBytes);

        // Created 2 hours ago, expired 1 hour ago
        Instant createdAt = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant expiredAt = Instant.now().minus(1, ChronoUnit.HOURS);

        PdfSession expiredSession = new PdfSession(
                sessionId,
                Mode.EDIT,
                Source.API,
                "client-1",
                filePath,
                createdAt,
                expiredAt,
                SessionStatus.READY
        );
        sessionService.getSessionMap().put(sessionId, expiredSession);

        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/result")
                        .header("X-API-KEY", "test_secret_api_key_xyz")
                        .header("X-CLIENT-ID", "client-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));

        mockMvc.perform(get("/app/sessions/" + sessionId + "/pdf"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    @DisplayName("Reject non-PDF file missing %PDF header with 400 Bad Request")
    void testNonPdfRejection() throws Exception {
        MockMultipartFile textFile = new MockMultipartFile(
                "file",
                "document.txt",
                "text/plain",
                "This is not a PDF file".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/sessions")
                        .file(textFile)
                        .param("mode", "EDIT")
                        .header("X-API-KEY", "test_secret_api_key_xyz"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("missing '%PDF' header")));

        mockMvc.perform(multipart("/app/sessions")
                        .file(textFile)
                        .param("mode", "EDIT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("missing '%PDF' header")));
    }

    @Test
    @DisplayName("Reject password-protected PDFs with 422 Unprocessable Entity")
    void testPasswordProtectedPdfRejection() throws Exception {
        byte[] encryptedPdf = createEncryptedPdf();
        MockMultipartFile encFile = new MockMultipartFile("file", "encrypted.pdf", "application/pdf", encryptedPdf);

        mockMvc.perform(multipart("/api/v1/sessions")
                        .file(encFile)
                        .param("mode", "RESUME")
                        .header("X-API-KEY", "test_secret_api_key_xyz"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is(422)))
                .andExpect(jsonPath("$.message", containsString("Password-protected")));
    }

    @Test
    @DisplayName("Reject oversized PDF with 413 Payload Too Large")
    void testOversizedPdfRejection() throws Exception {
        // Temporarily set max size to 100 bytes for this test
        long originalLimit = sessionProperties.getMaxSizeBytes();
        sessionProperties.setMaxSizeBytes(100);
        try {
            MockMultipartFile file = new MockMultipartFile("file", "sample.pdf", "application/pdf", samplePdfBytes);

            mockMvc.perform(multipart("/api/v1/sessions")
                            .file(file)
                            .param("mode", "EDIT")
                            .header("X-API-KEY", "test_secret_api_key_xyz"))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.status", is(413)))
                    .andExpect(jsonPath("$.message", containsString("File size exceeds maximum allowed size")));
        } finally {
            sessionProperties.setMaxSizeBytes(originalLimit);
        }
    }

    @Test
    @DisplayName("POST /app/sessions and GET /app/sessions/{id}/pdf work without API key")
    void testAppSessionFlow() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "upload.pdf", "application/pdf", samplePdfBytes);

        MvcResult result = mockMvc.perform(multipart("/app/sessions")
                        .file(file)
                        .param("mode", "MARK"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();

        String sessionId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        // Download via public endpoint
        mockMvc.perform(get("/app/sessions/" + sessionId + "/pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Session-Mode", "MARK"))
                .andExpect(header().string("X-Session-Source", "UPLOAD"))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(samplePdfBytes));

        // Read metadata via public endpoint
        mockMvc.perform(get("/app/sessions/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(sessionId)))
                .andExpect(jsonPath("$.mode", is("MARK")))
                .andExpect(jsonPath("$.source", is("UPLOAD")))
                .andExpect(jsonPath("$.status", is("READY")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("POST /app/sessions enforces in-memory IP rate limit with 429 Too Many Requests")
    void testAppSessionRateLimit() throws Exception {
        int originalLimit = sessionProperties.getRateLimitPerMinute();
        sessionProperties.setRateLimitPerMinute(2);
        try {
            MockMultipartFile file = new MockMultipartFile("file", "rate.pdf", "application/pdf", samplePdfBytes);

            // Request 1: OK
            mockMvc.perform(multipart("/app/sessions")
                            .file(file)
                            .param("mode", "EDIT")
                            .with(req -> { req.setRemoteAddr("192.168.1.100"); return req; }))
                    .andExpect(status().isCreated());

            // Request 2: OK
            mockMvc.perform(multipart("/app/sessions")
                            .file(file)
                            .param("mode", "EDIT")
                            .with(req -> { req.setRemoteAddr("192.168.1.100"); return req; }))
                    .andExpect(status().isCreated());

            // Request 3: 429 Too Many Requests
            mockMvc.perform(multipart("/app/sessions")
                            .file(file)
                            .param("mode", "EDIT")
                            .with(req -> { req.setRemoteAddr("192.168.1.100"); return req; }))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.status", is(429)))
                    .andExpect(jsonPath("$.error", is("Too Many Requests")));
        } finally {
            sessionProperties.setRateLimitPerMinute(originalLimit);
        }
    }

    @Test
    @DisplayName("Reject invalid mode string with 400 Bad Request")
    void testInvalidModeRejection() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", samplePdfBytes);

        mockMvc.perform(multipart("/api/v1/sessions")
                        .file(file)
                        .param("mode", "UNKNOWN_MODE")
                        .header("X-API-KEY", "test_secret_api_key_xyz"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", containsString("Invalid mode")));
    }

    @Test
    @DisplayName("GET /app/redaction-categories returns registered categories")
    void testGetRedactionCategories() throws Exception {
        mockMvc.perform(get("/app/redaction-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id", is("email")))
                .andExpect(jsonPath("$[0].label", is("Email Addresses")))
                .andExpect(jsonPath("$[0].default", is(true)))
                .andExpect(jsonPath("$[1].id", is("phone")))
                .andExpect(jsonPath("$[1].label", is("Phone Numbers")))
                .andExpect(jsonPath("$[1].default", is(true)));
    }

    @Test
    @DisplayName("POST /app/sessions and /api/v1/sessions reject unknown redaction category with 400")
    void testUnknownRedactionCategoryRejection() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", samplePdfBytes);

        mockMvc.perform(multipart("/app/sessions")
                        .file(file)
                        .param("mode", "RESUME")
                        .param("redact", "invalid_cat"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", containsString("Unknown redaction category: 'invalid_cat'")));

        mockMvc.perform(multipart("/api/v1/sessions")
                        .file(file)
                        .param("mode", "RESUME")
                        .param("redact", "unknown_one")
                        .header("X-API-KEY", "test_secret_api_key_xyz"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", containsString("Unknown redaction category: 'unknown_one'")));
    }

    private byte[] createSamplePdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private byte[] createEncryptedPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            AccessPermission ap = new AccessPermission();
            StandardProtectionPolicy spp = new StandardProtectionPolicy("owner_secret", "user_secret", ap);
            doc.protect(spp);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }
}
