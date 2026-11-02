package com.pdfeditor.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfeditor.process.dto.ResumeProcessResponse;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "pdfeditor.security.api-key=wf_live_secret_key_2026",
        "app.security.api-key=wf_live_secret_key_2026",
        "app.security.header-name=X-API-KEY"
})
class ResumeB2BPipelineTest {

    private static final String VALID_API_KEY = "wf_live_secret_key_2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private byte[] createSampleResumePdf(String name, String email, String phone, boolean withVectorIcon) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // Header Name
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 16);
                cs.newLineAtOffset(50, 750);
                cs.showText(name);
                cs.endText();

                // Vector icon (small box immediately before email)
                if (withVectorIcon) {
                    cs.saveGraphicsState();
                    cs.setLineWidth(1.0f);
                    cs.addRect(50, 718, 10, 10);
                    cs.stroke();
                    cs.restoreGraphicsState();
                }

                // Contact Email
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(65, 720);
                cs.showText("Email: " + email);
                cs.endText();

                // Contact Phone
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(65, 700);
                cs.showText("Phone: " + phone);
                cs.endText();

                // Non-PII Content
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 650);
                cs.showText("Professional Experience: Senior Software Engineer at Tech Corp.");
                cs.newLineAtOffset(0, -20);
                cs.showText("Skills: Java, Spring Boot, Architecture, Microservices, Vue.js.");
                cs.endText();
            }
            doc.save(baos);
        }
        return baos.toByteArray();
    }

    @Test
    @DisplayName("B2B Pipeline rejects requests with missing or invalid API key")
    void testAuthenticationEnforced() throws Exception {
        byte[] pdf = createSampleResumePdf("John Doe", "john.doe@example.com", "+1-555-123-4567", false);
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", pdf);

        // Missing key -> 401
        mockMvc.perform(multipart("/api/v1/resume/process")
                        .file(file))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("Unauthorized")));

        // Invalid key -> 401
        mockMvc.perform(multipart("/api/v1/resume/process")
                        .file(file)
                        .header("X-API-KEY", "wrong_invalid_key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("Unauthorized")));
    }

    @Test
    @DisplayName("B2B Pipeline processes resume fixture, scrubs contact info and preserves page count invariant")
    void testResumeProcessWithFixture() throws Exception {
        File resumeFile = new File("../sample-pdfs/resume.pdf");
        if (!resumeFile.exists()) {
            resumeFile = new File("sample-pdfs/resume.pdf");
        }
        byte[] resumeBytes;
        if (resumeFile.exists()) {
            resumeBytes = Files.readAllBytes(resumeFile.toPath());
        } else {
            ClassPathResource res = new ClassPathResource("fixtures/resume.pdf");
            resumeBytes = res.getInputStream().readAllBytes();
        }

        int origPageCount;
        try (PDDocument doc = Loader.loadPDF(resumeBytes)) {
            origPageCount = doc.getNumberOfPages();
        }

        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", resumeBytes);

        MvcResult result = mockMvc.perform(multipart("/api/v1/resume/process")
                        .file(file)
                        .header("X-API-KEY", VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page-Count", String.valueOf(origPageCount)))
                .andReturn();

        ResumeProcessResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                ResumeProcessResponse.class
        );

        // 1. Assert extracted details contain email and phone
        assertThat(response.extractedDetails()).isNotNull();
        assertThat(response.extractedDetails().emails()).isNotEmpty();
        assertThat(response.extractedDetails().phones()).isNotEmpty();

        // 2. Assert page count invariant
        assertThat(response.pageCount()).isEqualTo(origPageCount);

        // 3. Inspect sanitized PDF bytes
        assertThat(response.redactedPdfBase64()).isNotBlank();
        byte[] sanitizedPdf = Base64.getDecoder().decode(response.redactedPdfBase64());

        try (PDDocument doc = Loader.loadPDF(sanitizedPdf)) {
            // Verify page count invariant
            assertThat(doc.getNumberOfPages()).isEqualTo(origPageCount);

            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);

            // Assert extracted emails are gone from the sanitized PDF
            for (String email : response.extractedDetails().emails()) {
                assertThat(text).doesNotContain(email);
            }
            // Assert extracted phones are gone from the sanitized PDF
            for (String phone : response.extractedDetails().phones()) {
                assertThat(text).doesNotContain(phone);
            }
        }
    }

    @Test
    @DisplayName("B2B Pipeline strips contact vector icons, email, phone and preserves non-PII text")
    void testSyntheticResumeWithIconsAndVectorDrawings() throws Exception {
        String testEmail = "alice.smith@example.com";
        String testPhone = "+91 98765 43210";
        byte[] pdf = createSampleResumePdf("Alice Smith", testEmail, testPhone, true);
        MockMultipartFile file = new MockMultipartFile("file", "alice_resume.pdf", "application/pdf", pdf);

        MvcResult result = mockMvc.perform(multipart("/api/v1/resume/process")
                        .file(file)
                        .header("X-API-KEY", VALID_API_KEY)
                        .param("redactEmail", "true")
                        .param("redactPhone", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page-Count", "1"))
                .andReturn();

        ResumeProcessResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                ResumeProcessResponse.class
        );

        assertThat(response.extractedDetails().emails()).contains(testEmail);
        assertThat(response.extractedDetails().phones()).contains(testPhone);
        assertThat(response.pageCount()).isEqualTo(1);

        byte[] sanitizedBytes = Base64.getDecoder().decode(response.redactedPdfBase64());
        try (PDDocument doc = Loader.loadPDF(sanitizedBytes)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);

            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);

            // Contact info redacted
            assertThat(text).doesNotContain(testEmail);
            assertThat(text).doesNotContain(testPhone);

            // Non-PII retained
            assertThat(text).contains("Alice Smith");
            assertThat(text).contains("Senior Software Engineer");
            assertThat(text).contains("Spring Boot");
        }
    }

    @Test
    @DisplayName("B2B Pipeline supports selective redaction (keep email, redact phone)")
    void testSelectiveRedaction() throws Exception {
        String testEmail = "bob.dev@domain.org";
        String testPhone = "+1 202 555 0199";
        byte[] pdf = createSampleResumePdf("Bob Developer", testEmail, testPhone, false);
        MockMultipartFile file = new MockMultipartFile("file", "bob_resume.pdf", "application/pdf", pdf);

        MvcResult result = mockMvc.perform(multipart("/api/v1/resume/process")
                        .file(file)
                        .header("X-API-KEY", VALID_API_KEY)
                        .param("redactEmail", "false")
                        .param("redactPhone", "true"))
                .andExpect(status().isOk())
                .andReturn();

        ResumeProcessResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                ResumeProcessResponse.class
        );

        // Extracted metadata captures both
        assertThat(response.extractedDetails().emails()).contains(testEmail);
        assertThat(response.extractedDetails().phones()).contains(testPhone);

        byte[] sanitizedBytes = Base64.getDecoder().decode(response.redactedPdfBase64());
        try (PDDocument doc = Loader.loadPDF(sanitizedBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);

            // Email preserved because redactEmail = false
            assertThat(text).contains(testEmail);

            // Phone redacted because redactPhone = true
            assertThat(text).doesNotContain(testPhone);

            // Non-PII retained
            assertThat(text).contains("Bob Developer");
        }
    }

    @Test
    @DisplayName("B2B Pipeline strictly preserves multi-page document page count invariant")
    void testMultiPagePageCountInvariant() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            for (int p = 1; p <= 3; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 750);
                    cs.showText("Page " + p + " Content");
                    if (p == 1) {
                        cs.newLineAtOffset(0, -30);
                        cs.showText("Contact: multipage.user@example.com - 9876543210");
                    }
                    cs.endText();
                }
            }
            doc.save(baos);
        }

        MockMultipartFile file = new MockMultipartFile("file", "multipage.pdf", "application/pdf", baos.toByteArray());

        MvcResult result = mockMvc.perform(multipart("/api/v1/resume/process")
                        .file(file)
                        .header("X-API-KEY", VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page-Count", "3"))
                .andReturn();

        ResumeProcessResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                ResumeProcessResponse.class
        );

        assertThat(response.pageCount()).isEqualTo(3);
        byte[] outputBytes = Base64.getDecoder().decode(response.redactedPdfBase64());
        try (PDDocument doc = Loader.loadPDF(outputBytes)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(3);
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            assertThat(text).doesNotContain("multipage.user@example.com");
            assertThat(text).contains("Page 1 Content");
            assertThat(text).contains("Page 2 Content");
            assertThat(text).contains("Page 3 Content");
        }
    }
    @Test
    @DisplayName("B2B Pipeline preserves adjacent location text 'Jabalpur, MP |' while stripping icon, email and phone")
    void testAdjacentLocationAndContactPreservesPrecedingTextB2B() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // Name
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 16);
                cs.newLineAtOffset(50, 750);
                cs.showText("Alice Smith");
                cs.endText();

                // Location directly adjacent to vector icon and email: "Jabalpur, MP | [Icon] test@example.com"
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 720);
                cs.showText("Jabalpur, MP | ");
                cs.endText();

                // Vector icon immediately before email
                cs.saveGraphicsState();
                cs.setLineWidth(1.0f);
                cs.addRect(135, 716, 10, 10);
                cs.stroke();
                cs.restoreGraphicsState();

                // Email
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(150, 720);
                cs.showText("test@example.com");
                cs.endText();

                // Phone with vector icon
                cs.saveGraphicsState();
                cs.setLineWidth(1.0f);
                cs.addRect(50, 696, 10, 10);
                cs.stroke();
                cs.restoreGraphicsState();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(65, 700);
                cs.showText("+91 98765 43210");
                cs.endText();

                // Other content
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 650);
                cs.showText("Senior Software Engineer at Tech Corp.");
                cs.endText();
            }
            doc.save(baos);
        }

        MockMultipartFile file = new MockMultipartFile("file", "resume_adjacent.pdf", "application/pdf", baos.toByteArray());

        MvcResult result = mockMvc.perform(multipart("/api/v1/resume/process")
                        .file(file)
                        .header("X-API-KEY", VALID_API_KEY)
                        .param("redactEmail", "true")
                        .param("redactPhone", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page-Count", "1"))
                .andReturn();

        ResumeProcessResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                ResumeProcessResponse.class
        );

        assertThat(response.extractedDetails().emails()).contains("test@example.com");
        assertThat(response.extractedDetails().phones()).contains("+91 98765 43210");

        byte[] sanitizedBytes = Base64.getDecoder().decode(response.redactedPdfBase64());
        try (PDDocument doc = Loader.loadPDF(sanitizedBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);

            // Assert that all characters of "Jabalpur, MP" (including 'P' and pipe '|') are 100% preserved
            assertThat(text).contains("Jabalpur, MP |");
            assertThat(text).contains("Jabalpur");
            assertThat(text).contains("MP");
            assertThat(text).contains("P");
            assertThat(text).contains("|");
            assertThat(text).contains("Alice Smith");
            assertThat(text).contains("Senior Software Engineer");

            // Assert email and phone are removed
            assertThat(text).doesNotContain("test@example.com");
            assertThat(text).doesNotContain("+91 98765 43210");
            assertThat(text).doesNotContain("98765 43210");
        }
    }
}
