package com.pdfeditor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfeditor.config.CorsConfig;
import com.pdfeditor.export.ExportController;
import com.pdfeditor.export.PdfExportService;
import com.pdfeditor.export.PdfRedactionService;
import com.pdfeditor.export.PlaywrightManager;
import com.pdfeditor.model.DocumentModel;
import com.pdfeditor.model.HeadingBlock;
import com.pdfeditor.model.PageModel;
import com.pdfeditor.model.ParagraphBlock;
import com.pdfeditor.model.Style;
import com.pdfeditor.model.TextRun;
import com.pdfeditor.render.CssGenerator;
import com.pdfeditor.render.HtmlGenerator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ExportController.class)
@Import({PdfExportService.class, PdfRedactionService.class, HtmlGenerator.class, CssGenerator.class, PlaywrightManager.class, CorsConfig.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PdfExportServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PdfExportService pdfExportService;

    private DocumentModel sampleDocument;

    @BeforeAll
    void initSample() {
        HeadingBlock heading = new HeadingBlock(
            new double[]{50.0, 40.0, 300.0, 30.0},
            1,
            List.of(new TextRun("Executive Summary", Style.of("Helvetica-Bold", 20.0, true, false, "#003366"), new double[]{50.0, 40.0, 300.0, 30.0}))
        );

        ParagraphBlock paragraph = new ParagraphBlock(
            new double[]{50.0, 80.0, 450.0, 40.0},
            List.of(
                new TextRun("This is the ", Style.defaultStyle(), new double[]{50.0, 80.0, 80.0, 14.0}),
                new TextRun("verified export", Style.of("Helvetica-Bold", 12.0, true, false, "#000000"), new double[]{135.0, 80.0, 95.0, 14.0}),
                new TextRun(" PDF output.", Style.defaultStyle(), new double[]{235.0, 80.0, 80.0, 14.0})
            ),
            "left"
        );

        PageModel page = new PageModel(1, 612.0, 792.0, List.of(heading, paragraph));
        sampleDocument = new DocumentModel("doc-exec-1", "executive-summary.pdf", List.of(page));
    }

    @Test
    @DisplayName("Export DocumentModel to pixel-accurate PDF via Playwright and verify using PDFBox")
    void testExportToPdfAndVerifyWithPdfBox() throws IOException {
        byte[] pdfBytes = pdfExportService.exportToPdf(sampleDocument);

        assertThat(pdfBytes).isNotNull();
        assertThat(pdfBytes.length).isGreaterThan(100);

        // Verify PDF Magic Bytes: %PDF-
        String header = new String(pdfBytes, 0, 5, StandardCharsets.US_ASCII);
        assertThat(header).isEqualTo("%PDF-");

        // Load generated PDF into PDFBox to verify structure and content
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);

            PDFTextStripper stripper = new PDFTextStripper();
            String extractedText = stripper.getText(doc);

            assertThat(extractedText).contains("Executive Summary");
            assertThat(extractedText).contains("verified export");
            assertThat(extractedText).contains("PDF output.");
        }
    }

    @Test
    @DisplayName("Export DocumentModel to HTML")
    void testExportToHtml() {
        String html = pdfExportService.exportToHtml(sampleDocument);

        assertThat(html).contains("Executive Summary");
        assertThat(html).contains("verified export");
        assertThat(html).contains("@page {\n    size: 612.00pt 792.00pt;");
    }

    @Test
    @DisplayName("REST POST /api/export/pdf returns PDF file download")
    void testRestExportPdf() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(sampleDocument);

        mockMvc.perform(post("/api/export/pdf")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition", "form-data; name=\"attachment\"; filename=\"executive-summary.pdf\""))
            .andExpect(result -> {
                byte[] bytes = result.getResponse().getContentAsByteArray();
                assertThat(bytes).isNotNull();
                assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
            });
    }

    @Test
    @DisplayName("REST POST /api/export/html returns HTML string")
    void testRestExportHtml() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(sampleDocument);

        mockMvc.perform(post("/api/export/html")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/html;charset=UTF-8"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Executive Summary")));
    }
}
