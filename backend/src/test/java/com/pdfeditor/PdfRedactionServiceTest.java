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
import org.apache.pdfbox.pdmodel.font.PDFont;
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

    @Test
    @DisplayName("shouldWrapLongTextWithoutHorizontalOverflow: Wrap long sentence without horizontal overflow in small bbox")
    void shouldWrapLongTextWithoutHorizontalOverflow() throws IOException {
        byte[] originalPdf = createTestPdf("Short original line");

        // Small bbox: width=80.0, height=15.0
        double[] bbox = new double[]{50.0, 85.0, 80.0, 15.0};
        Style style = Style.of("Helvetica", 11.0, false, false, "#000000");

        String longSentence = "This is a comprehensive and very long text sentence containing more than thirty to forty English words without any manual newline characters designed specifically to test the automatic word wrapping engine of the PDF redaction service";

        TextRedactionEdit edit = new TextRedactionEdit(
                1,
                bbox,
                "Short original line",
                longSentence,
                style,
                "#FFFFFF"
        );

        byte[] modifiedPdf = pdfRedactionService.redactAndReplace(originalPdf, List.of(edit));
        assertThat(modifiedPdf).isNotNull();
        assertThat(modifiedPdf.length).isGreaterThan(0);

        try (PDDocument doc = Loader.loadPDF(modifiedPdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            PDFTextStripper stripper = new PDFTextStripper();
            String extracted = stripper.getText(doc);

            assertThat(extracted).contains("comprehensive and very long text sentence");
            assertThat(extracted).contains("automatic word wrapping engine");
            assertThat(extracted).contains("PDF redaction service");
        }
    }

    @Test
    @DisplayName("shouldShiftLowerEditsDownWhenUpperTextExpands: Lower edits shift down when upper edit text expands into multiple lines")
    void shouldShiftLowerEditsDownWhenUpperTextExpands() throws IOException {
        // Create PDF with two lines
        byte[] originalPdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12.0f);
                // Upper text: y=740 from bottom (~100 from top)
                cs.newLineAtOffset(50.0f, 740.0f);
                cs.showText("First upper text line");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12.0f);
                // Lower text: y=720 from bottom (~120 from top)
                cs.newLineAtOffset(50.0f, 720.0f);
                cs.showText("Second lower text line");
                cs.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            originalPdf = baos.toByteArray();
        }

        // Upper edit at Y=100 with long multi-line text
        double[] upperBbox = new double[]{50.0, 100.0, 100.0, 15.0};
        String longUpperText = "The quick brown fox jumps over the lazy dog repeatedly until multiple lines are created requiring vertical expansion and content shift.";
        TextRedactionEdit upperEdit = new TextRedactionEdit(
                1,
                upperBbox,
                "First upper text line",
                longUpperText,
                Style.of("Helvetica", 11.0, false, false, "#000000"),
                "#FFFFFF"
        );

        // Lower edit at Y=120
        double[] lowerBbox = new double[]{50.0, 120.0, 120.0, 15.0};
        String lowerText = "Lower section content that must not be overlapped";
        TextRedactionEdit lowerEdit = new TextRedactionEdit(
                1,
                lowerBbox,
                "Second lower text line",
                lowerText,
                Style.of("Helvetica", 11.0, false, false, "#000000"),
                "#FFFFFF"
        );

        byte[] modifiedPdf = pdfRedactionService.redactAndReplace(originalPdf, List.of(upperEdit, lowerEdit));
        assertThat(modifiedPdf).isNotNull();
        assertThat(modifiedPdf.length).isGreaterThan(0);

        try (PDDocument doc = Loader.loadPDF(modifiedPdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            PDFTextStripper stripper = new PDFTextStripper();
            String extracted = stripper.getText(doc);

            assertThat(extracted).contains("quick brown fox jumps");
            assertThat(extracted).contains("Lower section content that must not be overlapped");

            int upperIndex = extracted.indexOf("quick brown fox");
            int lowerIndex = extracted.indexOf("Lower section content");
            assertThat(upperIndex).isGreaterThanOrEqualTo(0);
            assertThat(lowerIndex).isGreaterThan(upperIndex);
        }
    }

    @Test
    @DisplayName("Moved block whites out originalBbox and renders text at new bbox")
    void testMovedBlockRedaction() throws IOException {
        byte[] originalPdf = createTestPdf("Tender Enquiry M19282");

        TextRedactionEdit edit = new TextRedactionEdit();
        edit.setPageNumber(1);
        edit.setOriginalText("Tender Enquiry M19282");
        edit.setNewText("Tender Enquiry M19282 (Relocated)");
        edit.setOriginalBbox(new double[]{50.0, 85.0, 220.0, 25.0});
        edit.setBbox(new double[]{100.0, 150.0, 250.0, 25.0});
        edit.setMoved(true);
        edit.setStyle(Style.of("Helvetica-Bold", 14.0, true, false, "#000000"));

        byte[] modifiedPdf = pdfRedactionService.redactAndReplace(originalPdf, List.of(edit));
        assertThat(modifiedPdf).isNotNull().isNotEmpty();

        try (PDDocument doc = Loader.loadPDF(modifiedPdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String extracted = stripper.getText(doc);
            assertThat(extracted).contains("Tender Enquiry M19282 (Relocated)");
        }
    }

    @Test
    @DisplayName("Email and phone number redaction: empty replacement text whites out email and replaces mixed phone text")
    void testAutomaticEmailAndPhoneRedactionExport() throws IOException {
        byte[] originalPdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(0, 0, 612, 792));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(50, 720);
                cs.showText("Jabalpur | +91-7879797876");
                cs.endText();

                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(50, 690);
                cs.showText("azharmansoori729@gmail.com");
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            originalPdf = baos.toByteArray();
        }

        // Edit 1: Pure email redaction (newText is empty string)
        TextRedactionEdit emailEdit = new TextRedactionEdit(
                1,
                new double[]{50.0, 95.0, 200.0, 20.0},
                "azharmansoori729@gmail.com",
                "",
                Style.of("Helvetica", 12.0, false, false, "#000000"),
                "#FFFFFF"
        );

        // Edit 2: Mixed text with stripped phone number (newText is "Jabalpur")
        TextRedactionEdit phoneEdit = new TextRedactionEdit(
                1,
                new double[]{50.0, 65.0, 200.0, 20.0},
                "Jabalpur | +91-7879797876",
                "Jabalpur",
                Style.of("Helvetica", 12.0, false, false, "#000000"),
                "#FFFFFF"
        );

        byte[] modifiedPdf = pdfRedactionService.redactAndReplace(originalPdf, List.of(emailEdit, phoneEdit));
        assertThat(modifiedPdf).isNotNull().isNotEmpty();

        try (PDDocument doc = Loader.loadPDF(modifiedPdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            PDFTextStripper stripper = new PDFTextStripper();
            String extracted = stripper.getText(doc);
            assertThat(extracted).contains("Jabalpur");
        }
    }
}

