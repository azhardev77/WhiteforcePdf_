package com.pdfeditor.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfeditor.process.dto.ResumeExtractResponse;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "pdfeditor.security.api-key=test_b2b_secret_key_2026",
        "app.security.api-key=test_b2b_secret_key_2026",
        "app.security.header-name=X-API-KEY"
})
class ResumeExtractControllerTest {

    private static final String VALID_API_KEY = "test_b2b_secret_key_2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private byte[] createStandardResumePdf() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4); // 595 x 842 pt
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // Header: "RESUME" (blacklist test, size 14)
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                cs.newLineAtOffset(50, 790);
                cs.showText("RESUME");
                cs.endText();

                // Candidate Name: "Jane Doe" (size 22 bold, y = 760 -> in top 25% of 842)
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 22);
                cs.newLineAtOffset(50, 760);
                cs.showText("Jane Doe");
                cs.endText();

                // Subtitle: "Senior Software Engineer" (job title blacklist test, size 12)
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 735);
                cs.showText("Senior Software Engineer");
                cs.endText();

                // Contact Details
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(50, 715);
                cs.showText("Email: jane.doe@example.com | Phone: +1 555-0199");
                cs.endText();

                // Plain text URL
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(50, 695);
                cs.showText("Portfolio: https://github.com/janedoe");
                cs.endText();

                // Section: SUMMARY
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                cs.newLineAtOffset(50, 660);
                cs.showText("SUMMARY");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 640);
                cs.showText("Experienced backend developer with 6+ years building microservices.");
                cs.endText();

                // Section: EXPERIENCE
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                cs.newLineAtOffset(50, 600);
                cs.showText("EXPERIENCE");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 580);
                cs.showText("Acme Corp - Senior Engineer (2020 - Present)");
                cs.newLineAtOffset(0, -15);
                cs.showText("Architected Spring Boot microservices and REST APIs.");
                cs.endText();

                // Section: EDUCATION
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                cs.newLineAtOffset(50, 520);
                cs.showText("EDUCATION");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 500);
                cs.showText("B.S. in Computer Science - Stanford University");
                cs.endText();

                // Section: SKILLS (testing symbol-safe C++, C#, .NET, etc.)
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                cs.newLineAtOffset(50, 460);
                cs.showText("SKILLS");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 440);
                cs.showText("Technical Skills: C++, C#, .NET, Java, Spring Boot, Docker, PostgreSQL, Kubernetes");
                cs.endText();
            }

            // Clickable annotation link
            PDAnnotationLink link = new PDAnnotationLink();
            PDRectangle rect = new PDRectangle(50, 695, 200, 15);
            link.setRectangle(rect);
            PDActionURI uriAction = new PDActionURI();
            uriAction.setURI("https://linkedin.com/in/janedoe");
            link.setAction(uriAction);
            page.getAnnotations().add(link);

            doc.save(baos);
        }
        return baos.toByteArray();
    }

    private byte[] createTwoColumnResumePdf() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // Left Column: Candidate Name & Contact
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 20);
                cs.newLineAtOffset(50, 770);
                cs.showText("Michael Scott");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(50, 745);
                cs.showText("michael@dundermifflin.com | 555-0177");
                cs.endText();

                // Left Column: SKILLS section
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                cs.newLineAtOffset(50, 680);
                cs.showText("SKILLS");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 660);
                cs.showText("Python, Django, AWS, Git");
                cs.endText();

                // Right Column: WORK HISTORY & EDUCATION
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                cs.newLineAtOffset(320, 680);
                cs.showText("WORK HISTORY");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(320, 660);
                cs.showText("Regional Manager at Dunder Mifflin Paper Company");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                cs.newLineAtOffset(320, 580);
                cs.showText("EDUCATION");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(320, 560);
                cs.showText("Scranton High School");
                cs.endText();
            }

            doc.save(baos);
        }
        return baos.toByteArray();
    }

    private byte[] createEncryptedPdf() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("Secret Protected Resume");
                cs.endText();
            }

            AccessPermission ap = new AccessPermission();
            StandardProtectionPolicy spp = new StandardProtectionPolicy("ownerSecret", "userSecret", ap);
            spp.setEncryptionKeyLength(128);
            doc.protect(spp);

            doc.save(baos);
        }
        return baos.toByteArray();
    }

    @Test
    @DisplayName("Extract standard resume PDF extracts contact, name, sections and symbol-safe skills")
    void testExtractStandardResume() throws Exception {
        byte[] pdfBytes = createStandardResumePdf();
        MockMultipartFile file = new MockMultipartFile(
                "file", "jane_doe_resume.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes
        );

        MvcResult result = mockMvc.perform(multipart("/api/v1/resume/extract")
                        .file(file)
                        .header("X-API-KEY", VALID_API_KEY))
                .andExpect(status().isOk())
                .andReturn();

        ResumeExtractResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), ResumeExtractResponse.class
        );

        assertThat(response).isNotNull();
        assertThat(response.name()).isEqualTo("Jane Doe");

        assertThat(response.contact()).isNotNull();
        assertThat(response.contact().emails()).contains("jane.doe@example.com");
        assertThat(response.contact().phones()).isNotEmpty();
        assertThat(response.contact().links()).contains("https://linkedin.com/in/janedoe");
        assertThat(response.contact().links()).contains("https://github.com/janedoe");

        // Sections
        assertThat(response.sections()).containsKeys("SUMMARY", "EXPERIENCE", "EDUCATION", "SKILLS");
        assertThat(response.sections().get("SUMMARY")).isNotEmpty();
        assertThat(response.sections().get("EXPERIENCE")).isNotEmpty();

        // Symbol-safe skills
        List<String> skills = response.skills();
        assertThat(skills).contains("C++", "C#", ".NET", "Java", "Spring Boot", "Docker", "PostgreSQL", "Kubernetes");

        // Metadata
        assertThat(response.metadata().pageCount()).isEqualTo(1);
        assertThat(response.metadata().wordCount()).isGreaterThan(20);
    }

    @Test
    @DisplayName("Extract two-column resume PDF correctly parses name, skills, and sections")
    void testExtractTwoColumnResume() throws Exception {
        byte[] pdfBytes = createTwoColumnResumePdf();
        MockMultipartFile file = new MockMultipartFile(
                "file", "michael_scott_resume.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes
        );

        MvcResult result = mockMvc.perform(multipart("/api/v1/resume/extract")
                        .file(file)
                        .header("X-API-KEY", VALID_API_KEY))
                .andExpect(status().isOk())
                .andReturn();

        ResumeExtractResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), ResumeExtractResponse.class
        );

        assertThat(response.name()).isEqualTo("Michael Scott");
        assertThat(response.contact().emails()).contains("michael@dundermifflin.com");
        assertThat(response.skills()).contains("Python", "Django", "AWS", "Git");
        assertThat(response.sections()).containsKeys("WORK HISTORY", "SKILLS", "EDUCATION");
    }

    @Test
    @DisplayName("Empty file returns 400 Bad Request")
    void testExtractEmptyFileReturns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[0]
        );

        mockMvc.perform(multipart("/api/v1/resume/extract")
                        .file(file)
                        .header("X-API-KEY", VALID_API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("File is required and cannot be empty"));
    }

    @Test
    @DisplayName("Non-PDF file missing %PDF- header returns 400 Bad Request")
    void testExtractNonPdfFileReturns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.txt", MediaType.TEXT_PLAIN_VALUE, "This is plain text".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/v1/resume/extract")
                        .file(file)
                        .header("X-API-KEY", VALID_API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid file format: missing '%PDF-' header"));
    }

    @Test
    @DisplayName("Corrupt PDF returns 422 Unprocessable Entity")
    void testExtractCorruptPdfReturns422() throws Exception {
        byte[] corruptBytes = "%PDF-1.4\ncorrupted garbage stream content\n%%EOF".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "corrupt.pdf", MediaType.APPLICATION_PDF_VALUE, corruptBytes
        );

        mockMvc.perform(multipart("/api/v1/resume/extract")
                        .file(file)
                        .header("X-API-KEY", VALID_API_KEY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    @DisplayName("Password protected PDF returns 422 Unprocessable Entity")
    void testExtractPasswordProtectedPdfReturns422() throws Exception {
        byte[] encryptedBytes = createEncryptedPdf();
        MockMultipartFile file = new MockMultipartFile(
                "file", "protected.pdf", MediaType.APPLICATION_PDF_VALUE, encryptedBytes
        );

        mockMvc.perform(multipart("/api/v1/resume/extract")
                        .file(file)
                        .header("X-API-KEY", VALID_API_KEY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("Password-protected or encrypted PDFs are not supported"));
    }
}
