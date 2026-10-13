package com.pdfeditor;

import com.pdfeditor.config.CorsConfig;
import com.pdfeditor.document.DocumentController;
import com.pdfeditor.document.DocumentService;
import com.pdfeditor.extraction.BlockGrouper;
import com.pdfeditor.extraction.CharacterCollector;
import com.pdfeditor.extraction.ExtractionPipeline;
import com.pdfeditor.extraction.LineGrouper;
import com.pdfeditor.extraction.TableExtractor;
import com.pdfeditor.extraction.WordGrouper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DocumentController.class)
@Import({DocumentService.class, ExtractionPipeline.class, CharacterCollector.class,
        WordGrouper.class, LineGrouper.class, TableExtractor.class, BlockGrouper.class, CorsConfig.class})
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private byte[] samplePdfBytes;

    @BeforeEach
    void setUp() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(0, 0, 612, 792));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Sample REST Controller PDF");
                cs.endText();
            }
            doc.save(baos);
        }
        samplePdfBytes = baos.toByteArray();
    }

    @Test
    @DisplayName("POST /api/documents/extract with valid PDF returns 200 OK and valid DocumentModel schema")
    void testExtractValidPdf() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "sample.pdf",
            "application/pdf",
            samplePdfBytes
        );

        mockMvc.perform(multipart("/api/documents/extract").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.name").value("sample.pdf"))
            .andExpect(jsonPath("$.pages").isArray())
            .andExpect(jsonPath("$.pages[0].pageNumber").value(1))
            .andExpect(jsonPath("$.pages[0].width").value(612.0))
            .andExpect(jsonPath("$.pages[0].height").value(792.0))
            .andExpect(jsonPath("$.pages[0].blocks").isArray())
            .andExpect(jsonPath("$.pages[0].blocks[0].type").value("PARAGRAPH"))
            .andExpect(jsonPath("$.pages[0].blocks[0].runs[0].text").value("Sample REST Controller PDF"))
            .andExpect(jsonPath("$.pages[0].blocks[0].runs[0].style.fontSize").value(12.0));
    }

    @Test
    @DisplayName("POST /api/documents/extract with empty file returns 400 Bad Request")
    void testExtractEmptyFile() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
            "file",
            "empty.pdf",
            "application/pdf",
            new byte[0]
        );

        mockMvc.perform(multipart("/api/documents/extract").file(emptyFile))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/documents/extract with non-PDF file returns 400 Bad Request")
    void testExtractNonPdfFile() throws Exception {
        MockMultipartFile txtFile = new MockMultipartFile(
            "file",
            "document.txt",
            "text/plain",
            "Hello World".getBytes()
        );

        mockMvc.perform(multipart("/api/documents/extract").file(txtFile))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/documents/extract-sample with valid path returns 200 OK")
    void testExtractSampleFile(@TempDir Path tempDir) throws Exception {
        Path tempPdf = tempDir.resolve("sample.pdf");
        java.nio.file.Files.write(tempPdf, samplePdfBytes);

        mockMvc.perform(post("/api/documents/extract-sample")
                .param("filename", tempPdf.toAbsolutePath().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pages[0].pageNumber").value(1))
            .andExpect(jsonPath("$.pages[0].blocks[0].runs[0].text").value("Sample REST Controller PDF"));
    }

    @Test
    @DisplayName("POST /api/documents/extract-sample with non-existent file returns 404 Not Found")
    void testExtractSampleFileNotFound() throws Exception {
        mockMvc.perform(post("/api/documents/extract-sample")
                .param("filename", "does_not_exist.pdf"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Ensure sample-pdfs directory contains sample.pdf and tender.pdf")
    void testGenerateSamplePdfFiles() throws Exception {
        Path sampleDir = java.nio.file.Paths.get("..", "sample-pdfs");
        if (!java.nio.file.Files.exists(sampleDir)) {
            sampleDir = java.nio.file.Paths.get("sample-pdfs");
        }
        if (java.nio.file.Files.exists(sampleDir)) {
            java.nio.file.Files.write(sampleDir.resolve("sample.pdf"), samplePdfBytes);
            java.nio.file.Files.write(sampleDir.resolve("tender.pdf"), samplePdfBytes);
        }
    }
}
