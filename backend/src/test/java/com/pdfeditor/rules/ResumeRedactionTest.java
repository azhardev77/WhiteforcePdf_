package com.pdfeditor.rules;

import com.pdfeditor.session.SessionValidationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "app.security.api-key=test_secret_api_key_xyz"
})
class ResumeRedactionTest {

    @Autowired
    private ResumeRedactionService resumeRedactionService;

    @Test
    @DisplayName("Mandatory verification test on sample-pdfs/resume.pdf")
    void testSamplePdfResumeVerification() throws Exception {
        File resumeFile = new File("../sample-pdfs/resume.pdf");
        if (!resumeFile.exists()) {
            resumeFile = new File("sample-pdfs/resume.pdf");
        }
        assertThat(resumeFile).exists();
        byte[] originalBytes = Files.readAllBytes(resumeFile.toPath());

        // Extract original info before redaction
        int origPageCount;
        float origWidth;
        float origHeight;
        try (PDDocument origDoc = Loader.loadPDF(originalBytes)) {
            origPageCount = origDoc.getNumberOfPages();
            PDPage p1 = origDoc.getPage(0);
            origWidth = p1.getMediaBox().getWidth();
            origHeight = p1.getMediaBox().getHeight();
        }

        byte[] redactedBytes = resumeRedactionService.redactResume(originalBytes);
        assertThat(redactedBytes).isNotNull();

        try (PDDocument doc = Loader.loadPDF(redactedBytes)) {
            // 1. Assert page count and dimensions unchanged
            assertThat(doc.getNumberOfPages()).isEqualTo(origPageCount);
            PDPage p1 = doc.getPage(0);
            assertThat(p1.getMediaBox().getWidth()).isEqualTo(origWidth);
            assertThat(p1.getMediaBox().getHeight()).isEqualTo(origHeight);

            // 2. Extract text with PDFTextStripper and assert no email/phone remains
            PDFTextStripper stripper = new PDFTextStripper();
            String extracted = stripper.getText(doc);

            assertThat(extracted).doesNotContain("azharmansoori725@gmail.com");
            assertThat(extracted).doesNotContain("+91-7747047876");
            assertThat(extracted).doesNotContain("7747047876");

            // 3. Assert all non-PII text is unchanged
            assertThat(extracted).contains("Mohd Azhar Mansoori");
            assertThat(extracted).contains("Jabalpur");
            assertThat(extracted).contains("LinkedIn");
            assertThat(extracted).contains("GitHub");
            assertThat(extracted).contains("LeetCode");
            assertThat(extracted).contains("Software Developer");
            assertThat(extracted).contains("Professional Summary");

            // 4. Scan raw content streams for the strings
            for (PDPage page : doc.getPages()) {
                Iterator<PDStream> streams = page.getContentStreams();
                while (streams.hasNext()) {
                    PDStream stream = streams.next();
                    try (InputStream is = stream.createInputStream()) {
                        String streamContent = new String(is.readAllBytes(), StandardCharsets.ISO_8859_1);
                        assertThat(streamContent).doesNotContain("azharmansoori725@gmail.com");
                        assertThat(streamContent).doesNotContain("+91-7747047876");
                        assertThat(streamContent).doesNotContain("7747047876");
                    }
                }
            }

            // 5. Assert annotations and metadata are scrubbed
            for (PDPage page : doc.getPages()) {
                List<PDAnnotation> annotations = page.getAnnotations();
                if (annotations != null) {
                    for (PDAnnotation annot : annotations) {
                        if (annot instanceof PDAnnotationLink link) {
                            if (link.getAction() instanceof PDActionURI uriAction) {
                                String uri = uriAction.getURI();
                                assertThat(uri).doesNotContain("azharmansoori725@gmail.com");
                                assertThat(uri).doesNotContain("7747047876");
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Mandatory verification test on all PDFs in backend/src/test/resources/fixtures/")
    void testAllFixturesVerification() throws Exception {
        Path fixturesDir = Paths.get("src/test/resources/fixtures");
        if (!Files.exists(fixturesDir)) {
            fixturesDir = Paths.get("backend/src/test/resources/fixtures");
        }
        assertThat(Files.exists(fixturesDir)).isTrue();

        List<Path> fixturePdfs = new ArrayList<>();
        try (var stream = Files.walk(fixturesDir)) {
            stream.filter(p -> p.toString().toLowerCase().endsWith(".pdf"))
                    .forEach(fixturePdfs::add);
        }

        // Golden rule: if the folder is empty, STOP and ask me
        assertThat(fixturePdfs)
                .withFailMessage("backend/src/test/resources/fixtures/ folder is empty! Stop and ask user.")
                .isNotEmpty();

        for (Path pdfPath : fixturePdfs) {
            System.out.println("=== Processing fixture: " + pdfPath.getFileName() + " ===");
            byte[] originalBytes = Files.readAllBytes(pdfPath);
            int origPageCount;
            float origWidth;
            float origHeight;
            try (PDDocument origDoc = Loader.loadPDF(originalBytes)) {
                origPageCount = origDoc.getNumberOfPages();
                PDPage p1 = origDoc.getPage(0);
                origWidth = p1.getMediaBox().getWidth();
                origHeight = p1.getMediaBox().getHeight();
            }

            com.pdfeditor.model.DocumentModel model = new com.pdfeditor.extraction.ExtractionPipeline().extract(originalBytes, pdfPath.getFileName().toString());
            System.out.println("Model pages: " + model.getPages().size());
            List<Match> matches = new ResumeRules().find(model);
            System.out.println("Matches found (" + matches.size() + "):");
            for (Match m : matches) {
                System.out.println("  Match: type=" + m.getType() + ", text='" + m.getMatchedText() + "', bbox=" + java.util.Arrays.toString(m.getBbox()));
            }

            byte[] redactedBytes = resumeRedactionService.redactResume(originalBytes);
            assertThat(redactedBytes).isNotNull();

            try (PDDocument doc = Loader.loadPDF(redactedBytes)) {
                assertThat(doc.getNumberOfPages()).isEqualTo(origPageCount);
                PDPage p1 = doc.getPage(0);
                assertThat(p1.getMediaBox().getWidth()).isEqualTo(origWidth);
                assertThat(p1.getMediaBox().getHeight()).isEqualTo(origHeight);

                PDFTextStripper stripper = new PDFTextStripper();
                String extracted = stripper.getText(doc);
                System.out.println("Extracted text after redaction:\n" + extracted.trim());

                // Assert no email remains
                List<PiiDetector.PiiSpan> spans = PiiDetector.findPIISpans(extracted);
                if (!spans.isEmpty()) {
                    System.err.println("FAILED on fixture: " + pdfPath.getFileName() + " with spans: " + spans);
                }
                assertThat(spans).withFailMessage("Fixture failed: " + pdfPath.getFileName() + ", spans: " + spans).isEmpty();
                // Scan raw streams
                for (PDPage page : doc.getPages()) {
                    Iterator<PDStream> streams = page.getContentStreams();
                    while (streams.hasNext()) {
                        PDStream stream = streams.next();
                        try (InputStream is = stream.createInputStream()) {
                            String streamContent = new String(is.readAllBytes(), StandardCharsets.ISO_8859_1);
                            assertThat(PiiDetector.findPIISpans(streamContent)).isEmpty();
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Scanned or image-only PDF returns 422 Unprocessable Entity")
    void testScannedPdfReturns422() throws IOException {
        // Create an image-only PDF with zero text
        byte[] scannedPdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
            PDImageXObject pdImage = LosslessFactory.createFromImage(doc, image);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(pdImage, 50, 50, 100, 100);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            scannedPdf = baos.toByteArray();
        }

        assertThatThrownBy(() -> resumeRedactionService.redactResume(scannedPdf))
                .isInstanceOf(SessionValidationException.class)
                .satisfies(ex -> {
                    SessionValidationException sve = (SessionValidationException) ex;
                    assertThat(sve.getStatusCode()).isEqualTo(422);
                    assertThat(sve.getMessage()).containsIgnoringCase("scanned or image-only");
                });
    }

    @Test
    @DisplayName("Scrubbing link annotations and metadata containing PII")
    void testAnnotationsAndMetadataScrubbing() throws Exception {
        byte[] pdfWithMetadata;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            // Set document information
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setAuthor("John Doe");
            info.setSubject("Resume for john.doe@example.com - Tel: +1-555-432-1098");

            // Add text and link annotation
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("Contact: john.doe@example.com | Tel: +1-555-432-1098");
                cs.endText();
            }

            PDAnnotationLink link = new PDAnnotationLink();
            PDRectangle rect = new PDRectangle(100, 690, 200, 20);
            link.setRectangle(rect);
            PDActionURI uriAction = new PDActionURI();
            uriAction.setURI("mailto:john.doe@example.com");
            link.setAction(uriAction);
            page.getAnnotations().add(link);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            pdfWithMetadata = baos.toByteArray();
        }

        byte[] redacted = resumeRedactionService.redactResume(pdfWithMetadata);
        assertThat(redacted).isNotNull();

        try (PDDocument doc = Loader.loadPDF(redacted)) {
            // Verify link annotation removed
            List<PDAnnotation> annotations = doc.getPage(0).getAnnotations();
            boolean hasMailto = annotations.stream().anyMatch(a -> {
                if (a instanceof PDAnnotationLink l && l.getAction() instanceof PDActionURI u) {
                    return u.getURI().toLowerCase().contains("mailto:");
                }
                return false;
            });
            assertThat(hasMailto).isFalse();

            // Verify metadata scrubbed
            PDDocumentInformation info = doc.getDocumentInformation();
            assertThat(info.getSubject()).doesNotContain("john.doe@example.com");
            assertThat(info.getSubject()).doesNotContain("555-432-1098");
        }
    }
    @Test
    @DisplayName("Preceding location characters ('Jabalpur, MP |') are 100% preserved when directly adjacent to contact info and icons")
    void testPrecedingLocationTextPreservedWhenAdjacentToContactInfo() throws Exception {
        byte[] samplePdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // Header Name
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD), 16);
                cs.newLineAtOffset(50, 750);
                cs.showText("Mohd Azhar Mansoori");
                cs.endText();

                // Directly adjacent line: "Jabalpur, MP | " + [Vector Icon] + "test@example.com"
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 720);
                cs.showText("Jabalpur, MP | ");
                cs.endText();

                // Vector icon immediately before email (box at X: 135 to 145, Y: 716 to 726)
                cs.saveGraphicsState();
                cs.setLineWidth(1.0f);
                cs.addRect(135, 716, 10, 10);
                cs.stroke();
                cs.restoreGraphicsState();

                // Contact Email starting at X: 150
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 11);
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
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(65, 700);
                cs.showText("+91 98765 43210");
                cs.endText();

                // Other non-PII text
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 650);
                cs.showText("Skills: Java, Spring Boot, Architecture, Microservices");
                cs.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            samplePdf = baos.toByteArray();
        }

        byte[] redactedBytes = resumeRedactionService.redactResume(samplePdf);
        assertThat(redactedBytes).isNotNull();

        try (PDDocument doc = Loader.loadPDF(redactedBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String extracted = stripper.getText(doc);

            // Assert that all characters of "Jabalpur, MP" (including 'P' and pipe '|') are 100% preserved
            assertThat(extracted).contains("Jabalpur, MP |");
            assertThat(extracted).contains("Jabalpur");
            assertThat(extracted).contains("MP");
            assertThat(extracted).contains("P");
            assertThat(extracted).contains("|");
            assertThat(extracted).contains("Mohd Azhar Mansoori");
            assertThat(extracted).contains("Skills: Java, Spring Boot, Architecture, Microservices");

            // Assert contact info (email and phone) is removed
            assertThat(extracted).doesNotContain("test@example.com");
            assertThat(extracted).doesNotContain("+91 98765 43210");
            assertThat(extracted).doesNotContain("98765 43210");

            // Assert vector icon is scrubbed from stream
            for (PDPage page : doc.getPages()) {
                Iterator<PDStream> streams = page.getContentStreams();
                while (streams.hasNext()) {
                    PDStream stream = streams.next();
                    try (InputStream is = stream.createInputStream()) {
                        String streamContent = new String(is.readAllBytes(), StandardCharsets.ISO_8859_1);
                        assertThat(streamContent).doesNotContain("test@example.com");
                        assertThat(streamContent).doesNotContain("98765");
                        assertThat(streamContent).doesNotContain("135 716");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Phone label (e.g. 'Phone:') is completely redacted along with phone number across column breaks and gaps while preserving location")
    void testAdjacentPhoneLabelRedaction() throws Exception {
        byte[] samplePdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // Header Name
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD), 16);
                cs.newLineAtOffset(50, 750);
                cs.showText("Rahul Sharma");
                cs.endText();

                // Title
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 730);
                cs.showText("Junior / Fresher PHP Laravel Developer");
                cs.endText();

                // Contact line with separate showText runs placing "Phone:" near middle (X=230) and number (X=268)
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 710);
                cs.showText("rahul.laravel.dev@gmail.com");
                cs.newLineAtOffset(180, 0); // X = 230
                cs.showText("Phone: ");
                cs.showText("+91 98765 43210");
                cs.newLineAtOffset(180, 0); // X = 410
                cs.showText("Location: Bangalore, India (Open to Relocation)");
                cs.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            samplePdf = baos.toByteArray();
        }

        byte[] redactedBytes = resumeRedactionService.redactResume(samplePdf);
        assertThat(redactedBytes).isNotNull();

        try (PDDocument doc = Loader.loadPDF(redactedBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String extracted = stripper.getText(doc);

            // Assert email and phone are redacted
            assertThat(extracted).doesNotContain("rahul.laravel.dev@gmail.com");
            assertThat(extracted).doesNotContain("+91 98765 43210");
            assertThat(extracted).doesNotContain("98765 43210");

            // Assert 'Phone:' label is completely removed and does NOT remain orphaned
            assertThat(extracted).doesNotContain("Phone:");
            assertThat(extracted).doesNotContain("Phone");

            // Assert location text and candidate headers are 100% preserved
            assertThat(extracted).contains("Rahul Sharma");
            assertThat(extracted).contains("Junior / Fresher PHP Laravel Developer");
            assertThat(extracted).contains("Location: Bangalore, India (Open to Relocation)");
        }
    }
}
