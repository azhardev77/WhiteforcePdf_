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
import org.apache.pdfbox.pdmodel.font.PDFont;
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
                // 0. Primary paragraph block (verifies schema assertions in testExtractValidPdf)
                PDFont regularFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                cs.beginText();
                cs.setFont(regularFont, 12);
                cs.newLineAtOffset(50, 750);
                cs.showText("Sample REST Controller PDF");
                cs.endText();

                // 1. Company header
                PDFont boldFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
                cs.beginText();
                cs.setFont(boldFont, 14);
                cs.newLineAtOffset(50, 720);
                cs.showText("Malanjkhand Copper Project");
                cs.endText();

                // 2. Centered heading "Tender Enquiry (RFQ)"
                cs.beginText();
                cs.setFont(boldFont, 16);
                cs.newLineAtOffset(220, 685);
                cs.showText("Tender Enquiry (RFQ)");
                cs.endText();

                // Vector underline strictly underneath "Tender Enquiry (RFQ)" (y = 682.0)
                float textWidth = (boldFont.getStringWidth("Tender Enquiry (RFQ)") / 1000.0f) * 16.0f;
                cs.setLineWidth(1.0f);
                cs.moveTo(220, 682.0f);
                cs.lineTo(220 + textWidth, 682.0f);
                cs.stroke();

                // 3. Subheading / clause: "Supply of semi-skilled and skilled manpower..."
                cs.beginText();
                cs.setFont(regularFont, 11);
                cs.newLineAtOffset(50, 650);
                cs.showText("Supply of semi-skilled and skilled manpower for maintenance work at MCP");
                cs.endText();

                // 4. Two-column details table
                // Left column
                cs.beginText();
                cs.setFont(boldFont, 11);
                cs.newLineAtOffset(50, 620);
                cs.showText("HindustanCopperLtd.,");
                cs.endText();

                // 3 horizontal blank lines underneath HindustanCopperLtd.,
                // Blank Line 1:
                cs.setLineWidth(1.0f);
                cs.moveTo(50, 603);
                cs.lineTo(250, 603);
                cs.stroke();
                cs.beginText();
                cs.setFont(regularFont, 10);
                cs.newLineAtOffset(250, 605);
                cs.showText(",");
                cs.endText();

                // Blank Line 2:
                cs.moveTo(50, 588);
                cs.lineTo(250, 588);
                cs.stroke();
                cs.beginText();
                cs.setFont(regularFont, 10);
                cs.newLineAtOffset(250, 590);
                cs.showText(",");
                cs.endText();

                // Blank Line 3:
                cs.moveTo(50, 573);
                cs.lineTo(250, 573);
                cs.stroke();
                cs.beginText();
                cs.setFont(regularFont, 10);
                cs.newLineAtOffset(250, 575);
                cs.showText(",");
                cs.endText();

                // Right column
                cs.beginText();
                cs.setFont(boldFont, 11);
                cs.newLineAtOffset(310, 620);
                cs.showText("Enquiry No.: M19282");
                cs.endText();

                cs.beginText();
                cs.setFont(regularFont, 10);
                cs.newLineAtOffset(310, 605);
                cs.showText("Date: 23-12-2025");
                cs.endText();

                cs.beginText();
                cs.setFont(regularFont, 10);
                cs.newLineAtOffset(310, 590);
                cs.showText("Last Date & Time for Tender Submission: 15-01-2026");
                cs.endText();

                // 5. Section heading below
                cs.beginText();
                cs.setFont(boldFont, 12);
                cs.newLineAtOffset(50, 540);
                cs.showText("INSTRUCTIONS TO BIDDERS FOR ONLINE TENDER SUBMISSION");
                cs.endText();

                // 6. Bank Guarantee clause lines (WPS Office paragraph block scenario)
                cs.beginText();
                cs.setFont(regularFont, 12);
                cs.newLineAtOffset(50, 515);
                cs.showText("M/s. Bidder upon being issued LOI shall furnish Hindustan Copper Limited");
                cs.endText();

                cs.beginText();
                cs.setFont(regularFont, 12);
                cs.newLineAtOffset(50, 492);
                cs.showText("at .................... a Bank Guarantee for Rs. ---only (Rupees");
                cs.endText();

                cs.beginText();
                cs.setFont(regularFont, 12);
                cs.newLineAtOffset(50, 469);
                cs.showText("Fifty Thousand only) towards Earnest Money Deposit as hereinafter mentioned.");
                cs.endText();

                // 7. Subsequent paragraph blocks (WPS Office test scenario)
                cs.beginText();
                cs.setFont(regularFont, 12);
                cs.newLineAtOffset(50, 430);
                cs.showText("1. We hereby agree to pay the said guarantee amount upon demand.");
                cs.endText();

                cs.beginText();
                cs.setFont(regularFont, 12);
                cs.newLineAtOffset(50, 407);
                cs.showText("2. You will have full liberty without our consent to vary the terms of contract.");
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
    void testExtractSampleFile() throws Exception {
        mockMvc.perform(post("/api/documents/extract-sample")
                .param("filename", "sample.pdf"))
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
    @DisplayName("POST /api/documents/extract-sample rejects directory traversal (..) with 400 Bad Request")
    void testExtractSampleRejectsTraversal() throws Exception {
        mockMvc.perform(post("/api/documents/extract-sample")
                .param("filename", "../sample-pdfs/sample.pdf"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/documents/extract-sample")
                .param("filename", "../../etc/passwd"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/documents/extract-sample rejects absolute paths with 400 Bad Request")
    void testExtractSampleRejectsAbsolutePaths(@TempDir Path tempDir) throws Exception {
        Path tempFile = tempDir.resolve("outside.pdf");
        java.nio.file.Files.write(tempFile, samplePdfBytes);

        mockMvc.perform(post("/api/documents/extract-sample")
                .param("filename", tempFile.toAbsolutePath().toString()))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/documents/extract-sample rejects URL encoded traversal variants with 400 Bad Request")
    void testExtractSampleRejectsEncodedTraversal() throws Exception {
        mockMvc.perform(post("/api/documents/extract-sample")
                .param("filename", "..%2f..%2fsample.pdf"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/documents/extract-sample")
                .param("filename", "%252e%252e%252fsample.pdf"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/documents/raw-sample with valid filename returns 200 OK and PDF bytes")
    void testGetRawSampleValid() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/documents/raw-sample")
                .param("filename", "sample.pdf"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"));
    }

    @Test
    @DisplayName("GET /api/documents/raw-sample rejects traversal with 400 Bad Request")
    void testGetRawSampleRejectsTraversal() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/documents/raw-sample")
                .param("filename", "../sample.pdf"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/documents/raw-sample")
                .param("filename", "..%2f..%2fsample.pdf"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Ensure sample-pdfs directory contains sample.pdf, tender.pdf, and resume.pdf")
    void testGenerateSamplePdfFiles(@TempDir Path tempDir) throws Exception {
        byte[] resumePdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595.28f, 841.89f));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                PDFont boldFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
                PDFont regularFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

                // 1. Top-left: 20pt bold heading "Mohd Azhar Mansoori"
                cs.beginText();
                cs.setFont(boldFont, 20);
                cs.newLineAtOffset(50, 785);
                cs.showText("Mohd Azhar Mansoori");
                cs.endText();

                // 2. Top-right at X=350: 9pt contact text "Jabalpur   +91-7747047876"
                cs.beginText();
                cs.setFont(regularFont, 9);
                cs.newLineAtOffset(350, 785);
                cs.showText("Jabalpur   +91-7747047876");
                cs.endText();

                // 3. Right underneath at Y=768: 9pt Blue Links "LinkedIn  GitHub  LeetCode  Hackerank  Scaler  My Portfolio"
                cs.setNonStrokingColor(new java.awt.Color(0, 102, 204));
                cs.beginText();
                cs.setFont(regularFont, 9);
                cs.newLineAtOffset(280, 768);
                cs.showText("LinkedIn  GitHub  LeetCode  Hackerank  Scaler  My Portfolio");
                cs.endText();

                // 3.5 Right underneath at Y=751: 9pt Email "azharmansoori725@gmail.com"
                cs.beginText();
                cs.setFont(regularFont, 9);
                cs.newLineAtOffset(340, 751);
                cs.showText("azharmansoori725@gmail.com");
                cs.endText();
                cs.setNonStrokingColor(java.awt.Color.BLACK);

                // 4. Subtitle on left: 12pt "Software Developer"
                cs.beginText();
                cs.setFont(regularFont, 12);
                cs.newLineAtOffset(50, 760);
                cs.showText("Software Developer");
                cs.endText();

                // 5. Professional Summary section
                cs.beginText();
                cs.setFont(boldFont, 14);
                cs.newLineAtOffset(50, 725);
                cs.showText("Professional Summary");
                cs.endText();

                cs.beginText();
                cs.setFont(regularFont, 11);
                cs.newLineAtOffset(50, 705);
                cs.showText("Passionate full-stack software engineer with extensive experience in building robust enterprise applications.");
                cs.endText();

                // 6. Experience section
                cs.beginText();
                cs.setFont(boldFont, 14);
                cs.newLineAtOffset(50, 670);
                cs.showText("Experience");
                cs.endText();

                cs.beginText();
                cs.setFont(regularFont, 11);
                cs.newLineAtOffset(50, 650);
                cs.showText("Senior Software Engineer at Enterprise Solutions. Architected high-throughput microservices and scalable document processing pipelines.");
                cs.endText();

                // 7. Projects section
                cs.beginText();
                cs.setFont(boldFont, 14);
                cs.newLineAtOffset(50, 595);
                cs.showText("Projects");
                cs.endText();

                cs.beginText();
                cs.setFont(regularFont, 11);
                cs.newLineAtOffset(50, 575);
                cs.showText("Enterprise Document Visual Editor: High-performance canvas-based PDF editing studio with real-time text reflow.");
                cs.endText();

                // 8. Achievements section
                cs.beginText();
                cs.setFont(boldFont, 14);
                cs.newLineAtOffset(50, 520);
                cs.showText("Achievements");
                cs.endText();

                cs.beginText();
                cs.setFont(regularFont, 11);
                cs.newLineAtOffset(50, 500);
                cs.showText("Winner of the National Cloud Innovation Hackathon 2024 and Top Open Source Contributor award.");
                cs.endText();

                // 9. Education section
                cs.beginText();
                cs.setFont(boldFont, 14);
                cs.newLineAtOffset(50, 445);
                cs.showText("Education");
                cs.endText();

                cs.beginText();
                cs.setFont(regularFont, 11);
                cs.newLineAtOffset(50, 425);
                cs.showText("Bachelor of Technology in Computer Science and Engineering, Global Institute of Technology.");
                cs.endText();
            }
            ByteArrayOutputStream baosResume = new ByteArrayOutputStream();
            doc.save(baosResume);
            resumePdfBytes = baosResume.toByteArray();
        }

        java.nio.file.Files.write(tempDir.resolve("sample.pdf"), samplePdfBytes);
        java.nio.file.Files.write(tempDir.resolve("tender.pdf"), samplePdfBytes);
        java.nio.file.Files.write(tempDir.resolve("resume.pdf"), resumePdfBytes);

        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(tempDir.resolve("sample.pdf")));
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(tempDir.resolve("tender.pdf")));
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(tempDir.resolve("resume.pdf")));
    }
}
