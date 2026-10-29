package com.pdfeditor.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfeditor.model.Style;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.security.api-key=test_secret_api_key_xyz",
        "app.security.header-name=X-API-KEY"
})
class ExportRedactionControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @SpyBean
    private PdfRedactionService pdfRedactionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private byte[] samplePdfBytes;
    private final String originalSecretText = "CONFIDENTIAL_TENDER_SECRET_12345";
    private final String replacementText = "PUBLIC_DISCLOSED_INFO";

    @BeforeEach
    void setUp() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(0, 0, 612, 792));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                cs.beginText();
                cs.setFont(font, 14);
                cs.newLineAtOffset(50, 700);
                cs.showText(originalSecretText);
                cs.endText();
            }
            doc.save(baos);
        }
        samplePdfBytes = baos.toByteArray();
    }

    @Test
    @DisplayName("POST /api/export/redact strips original text completely, verified by PDFTextStripper")
    void testRedactAndReplaceStripsOriginalText() throws Exception {
        double[] bbox = new double[]{50.0, 85.0, 300.0, 25.0};
        Style style = Style.of("Helvetica", 14.0, false, false, "#000000");

        TextRedactionEdit edit = new TextRedactionEdit(
                1,
                bbox,
                originalSecretText,
                replacementText,
                style,
                "#FFFFFF"
        );
        String editsJson = objectMapper.writeValueAsString(List.of(edit));

        ByteArrayResource fileResource = new ByteArrayResource(samplePdfBytes) {
            @Override
            public String getFilename() {
                return "tender_secret.pdf";
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);
        body.add("edits", editsJson);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-API-KEY", "test_secret_api_key_xyz");

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/export/redact",
                HttpMethod.POST,
                requestEntity,
                byte[].class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] outputPdf = response.getBody();
        assertThat(outputPdf).isNotNull().isNotEmpty();

        try (PDDocument doc = Loader.loadPDF(outputPdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String extracted = stripper.getText(doc);

            // Crucial security assertion: original text must be gone from the file
            assertThat(extracted).doesNotContain(originalSecretText);
            assertThat(extracted).contains(replacementText);
        }
    }

    @Test
    @DisplayName("POST /api/export/redact returns non-2xx status and does NOT return original bytes when redactAndReplace throws")
    void testRedactAndReplaceFailureReturnsNon2xxAndNeverReturnsOriginalBytes() throws Exception {
        // Force pdfRedactionService.redactAndReplace to throw IOException
        doThrow(new IOException("Simulated PDFBox token stripping failure"))
                .when(pdfRedactionService).redactAndReplace(any(), any());

        double[] bbox = new double[]{50.0, 85.0, 300.0, 25.0};
        Style style = Style.of("Helvetica", 14.0, false, false, "#000000");
        TextRedactionEdit edit = new TextRedactionEdit(1, bbox, originalSecretText, replacementText, style, "#FFFFFF");
        String editsJson = objectMapper.writeValueAsString(List.of(edit));

        ByteArrayResource fileResource = new ByteArrayResource(samplePdfBytes) {
            @Override
            public String getFilename() {
                return "tender_secret.pdf";
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);
        body.add("edits", editsJson);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-API-KEY", "test_secret_api_key_xyz");

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/export/redact",
                HttpMethod.POST,
                requestEntity,
                byte[].class
        );

        // Assert non-2xx status: Spring Boot returns HTTP 500 Internal Server Error by default
        assertThat(response.getStatusCode().isError()).isTrue();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        byte[] responseContent = response.getBody();
        // Crucial security assertion: failure must NEVER return original bytes
        assertThat(responseContent).isNotEqualTo(samplePdfBytes);

        String responseBodyStr = responseContent != null ? new String(responseContent, StandardCharsets.UTF_8) : "";
        assertThat(responseBodyStr).doesNotContain(originalSecretText);
    }
}
