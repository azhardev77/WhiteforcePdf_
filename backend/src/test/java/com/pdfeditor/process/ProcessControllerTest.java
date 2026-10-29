package com.pdfeditor.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
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
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.security.api-key=test_secret_api_key_xyz",
        "app.security.header-name=X-API-KEY"
})
class ProcessControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private byte[] samplePdfBytes;
    private final String originalText = "Hello World Software Developer";

    @BeforeEach
    void setUp() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 750);
                cs.showText(originalText);
                cs.endText();
            }
            doc.save(baos);
        }
        samplePdfBytes = baos.toByteArray();
    }

    @Test
    @DisplayName("Missing API key returns 401 Unauthorized")
    void testMissingApiKeyReturns401() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", samplePdfBytes);

        mockMvc.perform(multipart("/api/v1/process")
                        .file(file)
                        .param("ops", "[{\"type\":\"REDACT_PII\"}]"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("Unauthorized")));
    }

    @Test
    @DisplayName("Invalid API key returns 401 Unauthorized")
    void testInvalidApiKeyReturns401() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", samplePdfBytes);

        mockMvc.perform(multipart("/api/v1/process")
                        .file(file)
                        .header("X-API-KEY", "wrong_key")
                        .param("ops", "[{\"type\":\"REDACT_PII\"}]"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("Unauthorized")));
    }

    @Test
    @DisplayName("Invalid JSON in ops returns 400 Bad Request")
    void testBadOpsInvalidJsonReturns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", samplePdfBytes);

        mockMvc.perform(multipart("/api/v1/process")
                        .file(file)
                        .header("X-API-KEY", "test_secret_api_key_xyz")
                        .param("ops", "not-a-valid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Invalid JSON")));
    }

    @Test
    @DisplayName("Ops not a JSON array returns 400 Bad Request")
    void testBadOpsNotAnArrayReturns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", samplePdfBytes);

        mockMvc.perform(multipart("/api/v1/process")
                        .file(file)
                        .header("X-API-KEY", "test_secret_api_key_xyz")
                        .param("ops", "{\"type\":\"REDACT_PII\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("must be a JSON array")));
    }

    @Test
    @DisplayName("Unknown operation type returns 400 Bad Request")
    void testBadOpsUnknownTypeReturns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", samplePdfBytes);

        mockMvc.perform(multipart("/api/v1/process")
                        .file(file)
                        .header("X-API-KEY", "test_secret_api_key_xyz")
                        .param("ops", "[{\"type\":\"UNKNOWN_OPERATION\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Unknown operation type")));
    }

    @Test
    @DisplayName("Empty find in REPLACE_TEXT returns 400 Bad Request")
    void testBadOpsEmptyFindReturns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", samplePdfBytes);

        mockMvc.perform(multipart("/api/v1/process")
                        .file(file)
                        .header("X-API-KEY", "test_secret_api_key_xyz")
                        .param("ops", "[{\"type\":\"REPLACE_TEXT\",\"find\":\"\",\"replace\":\"new\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("'find' string cannot be empty")));
    }

    @Test
    @DisplayName("REPLACE_TEXT with zero matches returns 200 with X-Process-Matches: 0")
    void testReplaceTextZeroMatchesReturns200WithZeroHeader() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", samplePdfBytes);

        mockMvc.perform(multipart("/api/v1/process")
                        .file(file)
                        .header("X-API-KEY", "test_secret_api_key_xyz")
                        .param("ops", "[{\"type\":\"REPLACE_TEXT\",\"find\":\"NonExistentString12345\",\"replace\":\"replacement\"}]"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Process-Matches", "0"))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    @Test
    @DisplayName("REPLACE_TEXT changes word and old word is gone from extracted text")
    void testReplaceTextChangesWordAndOldWordIsGone() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", samplePdfBytes);

        MvcResult result = mockMvc.perform(multipart("/api/v1/process")
                        .file(file)
                        .header("X-API-KEY", "test_secret_api_key_xyz")
                        .param("ops", "[{\"type\":\"REPLACE_TEXT\",\"find\":\"Software Developer\",\"replace\":\"Lead Architect\"}]"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Process-Matches", "1"))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();

        byte[] outputBytes = result.getResponse().getContentAsByteArray();
        assertThat(outputBytes).isNotEmpty();

        try (PDDocument doc = Loader.loadPDF(outputBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String extracted = stripper.getText(doc);

            assertThat(extracted).contains("Lead Architect");
            assertThat(extracted).doesNotContain("Software Developer");
        }
    }

    @Test
    @DisplayName("REDACT_PII on resume fixture removes email and phone completely")
    void testRedactPiiRemovesEmailAndPhone() throws Exception {
        ClassPathResource resumeResource = new ClassPathResource("fixtures/resume.pdf");
        byte[] resumeBytes = resumeResource.getInputStream().readAllBytes();
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", resumeBytes);

        MvcResult result = mockMvc.perform(multipart("/api/v1/process")
                        .file(file)
                        .header("X-API-KEY", "test_secret_api_key_xyz")
                        .param("ops", "[{\"type\":\"REDACT_PII\"}]"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();

        byte[] outputBytes = result.getResponse().getContentAsByteArray();
        assertThat(outputBytes).isNotEmpty();

        try (PDDocument doc = Loader.loadPDF(outputBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String extracted = stripper.getText(doc);

            // Verify email and phone are completely removed
            assertThat(extracted).doesNotContain("azharmansoori725@gmail.com");
            assertThat(extracted).doesNotContain("+91-7747047876");
            // Verify non-PII text is preserved
            assertThat(extracted).contains("Mohd Azhar Mansoori");
            assertThat(extracted).contains("Jabalpur");
        }
    }
}
