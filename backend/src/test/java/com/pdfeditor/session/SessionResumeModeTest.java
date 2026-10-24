package com.pdfeditor.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.security.api-key=test_secret_api_key_xyz",
        "app.security.header-name=X-API-KEY",
        "app.public-base-url=http://localhost:5173",
        "app.sessions.ttl-minutes=60",
        "app.sessions.max-size-bytes=52428800",
        "app.sessions.rate-limit-per-minute=100"
})
class SessionResumeModeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SessionProperties sessionProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        sessionProperties.setDir(tempDir.toString());
    }

    private String getBaseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    @DisplayName("POST /api/v1/sessions with mode=RESUME automatically redacts email and phone on creation")
    void testApiSessionResumeModeRedaction() throws Exception {
        File resumeFile = new File("../sample-pdfs/resume.pdf");
        if (!resumeFile.exists()) {
            resumeFile = new File("sample-pdfs/resume.pdf");
        }
        assertThat(resumeFile).exists();
        byte[] resumeBytes = Files.readAllBytes(resumeFile.toPath());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-API-KEY", "test_secret_api_key_xyz");
        headers.set("X-CLIENT-ID", "client-resume-test");

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(resumeBytes) {
            @Override
            public String getFilename() {
                return "resume.pdf";
            }
        });
        body.add("mode", "RESUME");

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(getBaseUrl() + "/api/v1/sessions", requestEntity, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> responseBody = response.getBody();
        assertThat(responseBody).isNotNull();
        String sessionId = (String) responseBody.get("id");
        assertThat(sessionId).isNotEmpty();

        // Now download the processed result via GET /api/v1/sessions/{id}/result
        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.set("X-API-KEY", "test_secret_api_key_xyz");
        getHeaders.set("X-CLIENT-ID", "client-resume-test");
        HttpEntity<Void> getEntity = new HttpEntity<>(getHeaders);

        ResponseEntity<byte[]> resultResponse = restTemplate.exchange(
                getBaseUrl() + "/api/v1/sessions/" + sessionId + "/result",
                HttpMethod.GET,
                getEntity,
                byte[].class
        );

        assertThat(resultResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] redactedPdf = resultResponse.getBody();
        assertThat(redactedPdf).isNotNull();

        try (PDDocument doc = Loader.loadPDF(redactedPdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String extracted = stripper.getText(doc);
            assertThat(extracted).doesNotContain("azharmansoori725@gmail.com");
            assertThat(extracted).doesNotContain("+91-7747047876");
            assertThat(extracted).contains("Mohd Azhar Mansoori");
            assertThat(extracted).contains("Jabalpur");
        }
    }

    @Test
    @DisplayName("POST /app/sessions with mode=RESUME automatically redacts email and phone on creation")
    void testAppSessionResumeModeRedaction() throws Exception {
        File resumeFile = new File("../sample-pdfs/resume.pdf");
        if (!resumeFile.exists()) {
            resumeFile = new File("sample-pdfs/resume.pdf");
        }
        assertThat(resumeFile).exists();
        byte[] resumeBytes = Files.readAllBytes(resumeFile.toPath());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(resumeBytes) {
            @Override
            public String getFilename() {
                return "resume.pdf";
            }
        });
        body.add("mode", "RESUME");

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(getBaseUrl() + "/app/sessions", requestEntity, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> responseBody = response.getBody();
        assertThat(responseBody).isNotNull();
        String sessionId = (String) responseBody.get("id");
        assertThat(sessionId).isNotEmpty();

        // Download via GET /app/sessions/{id}/pdf
        ResponseEntity<byte[]> pdfResponse = restTemplate.getForEntity(
                getBaseUrl() + "/app/sessions/" + sessionId + "/pdf",
                byte[].class
        );

        assertThat(pdfResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] redactedPdf = pdfResponse.getBody();
        assertThat(redactedPdf).isNotNull();

        try (PDDocument doc = Loader.loadPDF(redactedPdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String extracted = stripper.getText(doc);
            assertThat(extracted).doesNotContain("azharmansoori725@gmail.com");
            assertThat(extracted).doesNotContain("+91-7747047876");
            assertThat(extracted).contains("Mohd Azhar Mansoori");
            assertThat(extracted).contains("Jabalpur");
        }
    }

    @Test
    @DisplayName("POST /app/sessions with mode=RESUME on scanned PDF returns 422 Unprocessable Entity")
    void testAppSessionScannedPdfReturns422() throws IOException {
        byte[] scannedPdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
            PDImageXObject pdImage = LosslessFactory.createFromImage(doc, image);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(pdImage, 50, 50, 100, 100);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            scannedPdf = baos.toByteArray();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(scannedPdf) {
            @Override
            public String getFilename() {
                return "scanned.pdf";
            }
        });
        body.add("mode", "RESUME");

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(getBaseUrl() + "/app/sessions", requestEntity, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        Map<?, ?> responseBody = response.getBody();
        assertThat(responseBody).isNotNull();
        assertThat(responseBody.get("status")).isEqualTo(422);
        assertThat(responseBody.get("error")).isEqualTo("Unprocessable Entity");
        assertThat((String) responseBody.get("message")).containsIgnoringCase("scanned or image-only");
    }
}
