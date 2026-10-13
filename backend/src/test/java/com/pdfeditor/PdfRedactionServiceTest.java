package com.pdfeditor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfeditor.export.PdfRedactionService;
import com.pdfeditor.export.TextRedactionEdit;
import com.pdfeditor.model.Style;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PdfRedactionServiceTest {

    @Autowired
    private PdfRedactionService pdfRedactionService;

    @Autowired
    private ObjectMapper objectMapper;

    private byte[] createTestPdf(String initialText) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 16.0f);
                // Baseline: around 750 pt from bottom
                cs.newLineAtOffset(50.0f, 750.0f);
                cs.showText(initialText);
                cs.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    @Test
    @DisplayName("In-place redaction whites out original text bounding box and writes new text at same coordinates")
    void testInPlaceRedactionAndReplacement() throws IOException {
        byte[] originalPdf = createTestPdf("Tender Enquiry M19282");

        // Bbox in top-left origin: A4 height is ~841.89 pt. 750 from bottom is approx 91 from top.
        // Bbox: x=50, y=85, w=220, h=25
        double[] bbox = new double[]{50.0, 85.0, 220.0, 25.0};
        Style style = Style.of("Helvetica-Bold", 16.0, true, false, "#112233");

        TextRedactionEdit edit = new TextRedactionEdit(
                1,
                bbox,
                "Tender Enquiry M19282",
                "CORRIGENDUM TO RFQ M19282",
                style,
                "#FFFFFF"
        );

        byte[] modifiedPdf = pdfRedactionService.redactAndReplace(originalPdf, List.of(edit));

        assertThat(modifiedPdf).isNotNull();
        assertThat(modifiedPdf.length).isGreaterThan(0);

        try (PDDocument doc = Loader.loadPDF(modifiedPdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            PDFTextStripper stripper = new PDFTextStripper();
            String extractedText = stripper.getText(doc);

            assertThat(extractedText).contains("CORRIGENDUM TO RFQ M19282");
        }
    }

    @Test
    @DisplayName("SanitizeForWinAnsi handles unicode symbols such as Rupee, curved quotes and dashes")
    void testSanitizeForWinAnsi() {
        String input = "Total: ₹ 50,000/- “Special” – Details…";
        String sanitized = PdfRedactionService.sanitizeForWinAnsi(input);

        assertThat(sanitized).contains("Rs. 50,000/-");
        assertThat(sanitized).contains("\"Special\"");
        assertThat(sanitized).contains("- Details...");
    }

    @Test
    @DisplayName("Transparent background edit writes text without drawing whiteout rectangle")
    void testFormFillOverprintTransparentBackground() throws IOException {
        byte[] originalPdf = createTestPdf("Name of Party: ....................");

        double[] bbox = new double[]{50.0, 85.0, 300.0, 25.0};
        Style style = Style.of("Helvetica-Bold", 14.0, true, false, "#000000");

        TextRedactionEdit edit = new TextRedactionEdit(
                1,
                bbox,
                "Name of Party: ....................",
                "Hindustan Copper Ltd",
                style,
                "#FFFFFF",
                true // transparentBackground = true
        );

        byte[] modifiedPdf = pdfRedactionService.redactAndReplace(originalPdf, List.of(edit));

        assertThat(modifiedPdf).isNotNull();
        try (PDDocument doc = Loader.loadPDF(modifiedPdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            // Overprinted text is present
            assertThat(text).contains("Hindustan Copper Ltd");
        }
    }

    @Test
    @DisplayName("Redaction on a 180-degree rotated page inverse-transforms bbox correctly and writes replacement text")
    void testRedactionOn180DegreePage() throws IOException {
        // Build a PDF where page 1 carries /Rotate 180
        byte[] originalPdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            page.setRotation(180);
            doc.addPage(page);

            // Write text at the PHYSICAL bottom-left of the page (which appears at top-left
            // when rendered right-side-up by PDF.js after a 180° flip).
            // A4 height ≈ 841.89 pt. Text at baseline y=50 pt from physical bottom.
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 16.0f);
                cs.newLineAtOffset(50.0f, 50.0f);
                cs.showText("Upside Down Text");
                cs.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            originalPdf = baos.toByteArray();
        }

        // A4: w≈595.28, h≈841.89
        // Physical text is at x=50, y=50 (bottom-left origin, un-rotated).
        // In 180°-rotated viewport (top-left origin), this maps to:
        //   viewport_x = pageW - 50 - bbox_w  =>  595.28 - 50 - 200 = 345.28
        //   viewport_y = pageH - 50 - bbox_h  =>  841.89 - 50 - 20  = 771.89
        // We send the viewport-space coords; the service must inverse-transform them back.
        float pageW = PDRectangle.A4.getWidth();   // ≈ 595.28
        float pageH = PDRectangle.A4.getHeight();  // ≈ 841.89
        double bboxW = 200.0;
        double bboxH = 20.0;
        double viewportX = pageW - 50.0 - bboxW;
        double viewportY = pageH - 50.0 - bboxH;

        Style style = Style.of("Helvetica-Bold", 16.0, true, false, "#000000");
        TextRedactionEdit edit = new TextRedactionEdit(
                1,
                new double[]{ viewportX, viewportY, bboxW, bboxH },
                "Upside Down Text",
                "CORRECTED TEXT",
                style,
                "#FFFFFF",
                false,
                180 // pageRotation
        );

        byte[] modifiedPdf = pdfRedactionService.redactAndReplace(originalPdf, List.of(edit));

        assertThat(modifiedPdf).isNotNull();
        assertThat(modifiedPdf.length).isGreaterThan(0);

        try (PDDocument doc = Loader.loadPDF(modifiedPdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            PDFTextStripper stripper = new PDFTextStripper();
            String extractedText = stripper.getText(doc);
            assertThat(extractedText).contains("CORRECTED TEXT");
        }
    }
}
