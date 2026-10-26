package com.pdfeditor.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfeditor.rules.PiiDetector;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.rendering.PDFRenderer;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

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
public class SessionPhase3bVerificationTest {

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

    private static class FixtureExpectation {
        String filename;
        Path filePath;
        String expectedEmail;
        String expectedPhone;
        List<String> requiredNonPii;

        FixtureExpectation(String filename, Path filePath, String expectedEmail, String expectedPhone, List<String> requiredNonPii) {
            this.filename = filename;
            this.filePath = filePath;
            this.expectedEmail = expectedEmail;
            this.expectedPhone = expectedPhone;
            this.requiredNonPii = requiredNonPii;
        }
    }

    @Test
    @DisplayName("Phase 3b: Real-world verification of resume hiding across all fixtures via POST /app/sessions")
    void testPhase3bAllFixturesVerification() throws Exception {
        Path fixturesDir = Paths.get("src/test/resources/fixtures");
        if (!Files.exists(fixturesDir)) {
            fixturesDir = Paths.get("backend/src/test/resources/fixtures");
        }
        assertThat(Files.exists(fixturesDir))
                .withFailMessage("backend/src/test/resources/fixtures folder missing!")
                .isTrue();

        Path expectedFile = fixturesDir.resolve("EXPECTED.txt");
        assertThat(Files.exists(expectedFile))
                .withFailMessage("EXPECTED.txt missing in fixtures folder!")
                .isTrue();

        List<String> lines = Files.readAllLines(expectedFile);
        List<FixtureExpectation> fixtures = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\\|");
            assertThat(parts.length).isGreaterThanOrEqualTo(3);
            String fn = parts[0].trim();
            String email = parts[1].trim();
            String phone = parts[2].trim();
            Path path = fixturesDir.resolve(fn);
            assertThat(Files.exists(path))
                    .withFailMessage("Fixture file %s from EXPECTED.txt does not exist!", fn)
                    .isTrue();
            fixtures.add(new FixtureExpectation(
                    fn,
                    path,
                    email,
                    phone,
                    List.of("Test User", "Jabalpur", "Software Developer")
            ));
        }

        // Also add sample-pdfs/resume.pdf
        Path sampleResume = Paths.get("../sample-pdfs/resume.pdf");
        if (!Files.exists(sampleResume)) {
            sampleResume = Paths.get("sample-pdfs/resume.pdf");
        }
        if (Files.exists(sampleResume)) {
            fixtures.add(new FixtureExpectation(
                    "resume.pdf",
                    sampleResume,
                    "azharmansoori725@gmail.com",
                    "+91-7747047876",
                    List.of("Mohd Azhar Mansoori", "Jabalpur", "Software Developer")
            ));
        }

        // Ensure we have real fixtures to test
        assertThat(fixtures)
                .withFailMessage("No fixtures found to test! Only toy resume or empty folder.")
                .hasSizeGreaterThanOrEqualTo(5);

        // Prepare output dir for pdfjs verification
        Path outputDir = Paths.get("target/phase3b-output");
        Files.createDirectories(outputDir);

        // Prepare temp dir for PNG renderings
        Path pngDir = Paths.get(System.getProperty("java.io.tmpdir"), "phase3b-pngs");
        Files.createDirectories(pngDir);

        System.out.println("=== Phase 3b: Starting Verification on " + fixtures.size() + " fixtures ===");

        for (FixtureExpectation fixture : fixtures) {
            System.out.println("\n------------------------------------------------------------");
            System.out.println("Verifying Fixture: " + fixture.filename);
            System.out.println("  Expected email: " + fixture.expectedEmail);
            System.out.println("  Expected phone: " + fixture.expectedPhone);

            byte[] originalBytes = Files.readAllBytes(fixture.filePath);
            int origPageCount;
            List<float[]> origPageDimensions = new ArrayList<>();

            try (PDDocument origDoc = Loader.loadPDF(originalBytes)) {
                origPageCount = origDoc.getNumberOfPages();
                for (int i = 0; i < origPageCount; i++) {
                    PDPage page = origDoc.getPage(i);
                    origPageDimensions.add(new float[]{page.getMediaBox().getWidth(), page.getMediaBox().getHeight()});
                }
            }

            // 1. Create RESUME session via POST /app/sessions
            HttpHeaders postHeaders = new HttpHeaders();
            postHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new ByteArrayResource(originalBytes) {
                @Override
                public String getFilename() {
                    return fixture.filename;
                }
            });
            body.add("mode", "RESUME");

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, postHeaders);
            ResponseEntity<Map> postResponse = restTemplate.postForEntity(
                    getBaseUrl() + "/app/sessions",
                    requestEntity,
                    Map.class
            );

            assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Map<?, ?> postResponseBody = postResponse.getBody();
            assertThat(postResponseBody).isNotNull();
            String sessionId = (String) postResponseBody.get("id");
            assertThat(sessionId).isNotEmpty();
            System.out.println("  Created session ID: " + sessionId);

            // 2. Retrieve output PDF via GET /app/sessions/{id}/pdf
            ResponseEntity<byte[]> pdfResponse = restTemplate.getForEntity(
                    getBaseUrl() + "/app/sessions/" + sessionId + "/pdf",
                    byte[].class
            );

            assertThat(pdfResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            byte[] outputBytes = pdfResponse.getBody();
            assertThat(outputBytes).isNotNull();
            assertThat(outputBytes.length).isGreaterThan(0);

            // Save output PDF for node pdfjs verification
            Path outPdfPath = outputDir.resolve(fixture.filename);
            Files.write(outPdfPath, outputBytes);
            System.out.println("  Saved output PDF: " + outPdfPath.toAbsolutePath());

            // 3. Inspect output PDF with PDFBox
            try (PDDocument outputDoc = Loader.loadPDF(outputBytes)) {
                // (5) Page count and dimensions unchanged
                assertThat(outputDoc.getNumberOfPages())
                        .withFailMessage("Page count changed for %s", fixture.filename)
                        .isEqualTo(origPageCount);

                for (int i = 0; i < origPageCount; i++) {
                    PDPage p = outputDoc.getPage(i);
                    float[] origDim = origPageDimensions.get(i);
                    assertThat(p.getMediaBox().getWidth())
                            .withFailMessage("Width changed on page %d for %s", i + 1, fixture.filename)
                            .isEqualTo(origDim[0]);
                    assertThat(p.getMediaBox().getHeight())
                            .withFailMessage("Height changed on page %d for %s", i + 1, fixture.filename)
                            .isEqualTo(origDim[1]);
                }
                System.out.println("  [PASS] Page count (" + origPageCount + ") and dimensions preserved");

                // (1) PDFTextStripper finds no email or phone (including digits-only and spaced/dashed)
                PDFTextStripper stripper = new PDFTextStripper();
                String extractedText = stripper.getText(outputDoc);

                String digitsOnlyPhone = fixture.expectedPhone.replaceAll("\\D", "");
                String textWithoutSpaces = extractedText.replaceAll("\\s+", "");

                assertThat(extractedText)
                        .withFailMessage("Found email '%s' in extracted text of %s", fixture.expectedEmail, fixture.filename)
                        .doesNotContain(fixture.expectedEmail);

                if (!digitsOnlyPhone.isEmpty()) {
                    assertThat(textWithoutSpaces)
                            .withFailMessage("Found phone digits '%s' in extracted text of %s", digitsOnlyPhone, fixture.filename)
                            .doesNotContain(digitsOnlyPhone);
                }

                List<PiiDetector.PiiSpan> piiSpans = PiiDetector.findPIISpans(extractedText);
                assertThat(piiSpans)
                        .withFailMessage("PiiDetector found remaining PII spans in %s: %s", fixture.filename, piiSpans)
                        .isEmpty();
                System.out.println("  [PASS] PDFTextStripper: No email/phone found; PiiDetector returned 0 spans");

                // (3) Link annotations check: no mailto: or tel: links remain
                for (int i = 0; i < outputDoc.getNumberOfPages(); i++) {
                    PDPage page = outputDoc.getPage(i);
                    List<PDAnnotation> annotations = page.getAnnotations();
                    for (PDAnnotation annot : annotations) {
                        if (annot instanceof PDAnnotationLink link) {
                            PDAction action = link.getAction();
                            if (action instanceof PDActionURI actionUri) {
                                String uri = actionUri.getURI();
                                if (uri != null) {
                                    String lowerUri = uri.toLowerCase();
                                    assertThat(lowerUri)
                                            .withFailMessage("Link annotation still contains mailto: in %s (%s)", fixture.filename, uri)
                                            .doesNotContain("mailto:");
                                    assertThat(lowerUri)
                                            .withFailMessage("Link annotation still contains tel: in %s (%s)", fixture.filename, uri)
                                            .doesNotContain("tel:");
                                    assertThat(uri)
                                            .withFailMessage("Link annotation still contains email in %s (%s)", fixture.filename, uri)
                                            .doesNotContain(fixture.expectedEmail);
                                }
                            }
                        }
                    }
                }
                System.out.println("  [PASS] Link annotations: Clean of mailto:, tel:, and target PII");

                // (4) Document Info and XMP metadata clean
                PDDocumentInformation docInfo = outputDoc.getDocumentInformation();
                if (docInfo != null) {
                    List<String> infoFields = List.of(
                            Optional.ofNullable(docInfo.getTitle()).orElse(""),
                            Optional.ofNullable(docInfo.getAuthor()).orElse(""),
                            Optional.ofNullable(docInfo.getSubject()).orElse(""),
                            Optional.ofNullable(docInfo.getKeywords()).orElse(""),
                            Optional.ofNullable(docInfo.getCreator()).orElse(""),
                            Optional.ofNullable(docInfo.getProducer()).orElse("")
                    );
                    for (String field : infoFields) {
                        assertThat(field).doesNotContain(fixture.expectedEmail);
                        if (!digitsOnlyPhone.isEmpty()) {
                            assertThat(field.replaceAll("\\D", "")).doesNotContain(digitsOnlyPhone);
                        }
                    }
                }

                PDMetadata xmpMetadata = outputDoc.getDocumentCatalog().getMetadata();
                if (xmpMetadata != null) {
                    try (var is = xmpMetadata.createInputStream()) {
                        String xmpStr = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        assertThat(xmpStr).doesNotContain(fixture.expectedEmail);
                        if (!digitsOnlyPhone.isEmpty()) {
                            assertThat(xmpStr.replaceAll("\\D", "")).doesNotContain(digitsOnlyPhone);
                        }
                    }
                }
                System.out.println("  [PASS] Document Info & XMP metadata clean");

                // (6) Non-PII text unchanged
                for (String nonPii : fixture.requiredNonPii) {
                    assertThat(extractedText)
                            .withFailMessage("Non-PII text '%s' missing from %s!", nonPii, fixture.filename)
                            .contains(nonPii);
                }
                System.out.println("  [PASS] Non-PII text preserved: " + fixture.requiredNonPii);

                // (7) Render Page 1 to PNG in temp folder and list the path
                PDFRenderer renderer = new PDFRenderer(outputDoc);
                BufferedImage page1Image = renderer.renderImageWithDPI(0, 150);
                Path pngFile = pngDir.resolve(fixture.filename.replace(".pdf", "") + "_page1.png");
                ImageIO.write(page1Image, "png", pngFile.toFile());
                assertThat(Files.exists(pngFile)).isTrue();
                assertThat(Files.size(pngFile)).isGreaterThan(0);
                System.out.println("  [PASS] Rendered Page 1 PNG: " + pngFile.toAbsolutePath());
            }

            System.out.println("  >>> RESULT FOR " + fixture.filename + ": PASS");
        }

        System.out.println("\n=== Phase 3b: All " + fixtures.size() + " fixtures passed verification! ===");
    }
}
