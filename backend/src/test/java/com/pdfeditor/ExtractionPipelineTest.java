package com.pdfeditor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfeditor.extraction.BlockGrouper;
import com.pdfeditor.extraction.CharacterCollector;
import com.pdfeditor.extraction.ExtractionPipeline;
import com.pdfeditor.extraction.Glyph;
import com.pdfeditor.extraction.Line;
import com.pdfeditor.extraction.LineGrouper;
import com.pdfeditor.extraction.Word;
import com.pdfeditor.extraction.WordGrouper;
import com.pdfeditor.model.Block;
import com.pdfeditor.model.BlockType;
import com.pdfeditor.model.DocumentModel;
import com.pdfeditor.model.HeadingBlock;
import com.pdfeditor.model.PageModel;
import com.pdfeditor.model.ParagraphBlock;
import com.pdfeditor.model.Style;
import com.pdfeditor.model.TextRun;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractionPipelineTest {

    private WordGrouper wordGrouper;
    private LineGrouper lineGrouper;
    private BlockGrouper blockGrouper;
    private ExtractionPipeline pipeline;

    @BeforeEach
    void setUp() {
        wordGrouper = new WordGrouper();
        lineGrouper = new LineGrouper(wordGrouper);
        blockGrouper = new BlockGrouper();
        pipeline = new ExtractionPipeline(new CharacterCollector(), wordGrouper, lineGrouper, blockGrouper);
    }

    @Test
    @DisplayName("1. Dynamic space separation: Glyphs with space gap yield 'Single Bid' instead of 'SingleBid'")
    void testDynamicSpaceSeparation() {
        double fontSize = 12.0;
        double threshold = fontSize * 0.22; // 2.64
        double baselineY = 50.0;

        // "Single": S(10, w=8), i(18, w=4), n(22, w=7), g(29, w=7), l(36, w=3), e(39, w=6) -> ends at 45.0
        List<Glyph> singleGlyphs = List.of(
            new Glyph("S", 10.0, 38.0, 8.0, 12.0, baselineY, "Helvetica", fontSize, false),
            new Glyph("i", 18.0, 38.0, 4.0, 12.0, baselineY, "Helvetica", fontSize, false),
            new Glyph("n", 22.0, 38.0, 7.0, 12.0, baselineY, "Helvetica", fontSize, false),
            new Glyph("g", 29.0, 38.0, 7.0, 12.0, baselineY, "Helvetica", fontSize, false),
            new Glyph("l", 36.0, 38.0, 3.0, 12.0, baselineY, "Helvetica", fontSize, false),
            new Glyph("e", 39.0, 38.0, 6.0, 12.0, baselineY, "Helvetica", fontSize, false)
        );

        // Gap = 49.0 - 45.0 = 4.0 > 2.64 (threshold)
        // "Bid": B(49, w=8), i(57, w=4), d(61, w=7)
        List<Glyph> bidGlyphsWithGap = List.of(
            new Glyph("B", 49.0, 38.0, 8.0, 12.0, baselineY, "Helvetica", fontSize, false),
            new Glyph("i", 57.0, 38.0, 4.0, 12.0, baselineY, "Helvetica", fontSize, false),
            new Glyph("d", 61.0, 38.0, 7.0, 12.0, baselineY, "Helvetica", fontSize, false)
        );

        List<Glyph> separatedGlyphs = new ArrayList<>();
        separatedGlyphs.addAll(singleGlyphs);
        separatedGlyphs.addAll(bidGlyphsWithGap);

        // Test at WordGrouper level
        List<Word> words = wordGrouper.groupWords(separatedGlyphs);
        assertThat(words).hasSize(2);
        assertThat(words.get(0).getText()).isEqualTo("Single");
        assertThat(words.get(1).getText()).isEqualTo("Bid");
        assertTrue(words.get(1).isPrecededBySpace(), "Second word must be marked as preceded by space");

        // Test at LineGrouper level
        List<Line> lines = lineGrouper.groupGlyphsIntoLines(separatedGlyphs);
        assertThat(lines).hasSize(1);
        Line line = lines.get(0);
        assertThat(line.getText()).isEqualTo("Single Bid");
        assertThat(line.getText()).isNotEqualTo("SingleBid");

        // Verify negative case: gap <= threshold merges glyphs into "SingleBid"
        List<Glyph> bidGlyphsWithoutGap = List.of(
            new Glyph("B", 46.0, 38.0, 8.0, 12.0, baselineY, "Helvetica", fontSize, false), // gap = 46.0 - 45.0 = 1.0 <= 2.64
            new Glyph("i", 54.0, 38.0, 4.0, 12.0, baselineY, "Helvetica", fontSize, false),
            new Glyph("d", 58.0, 38.0, 7.0, 12.0, baselineY, "Helvetica", fontSize, false)
        );
        List<Glyph> mergedGlyphs = new ArrayList<>();
        mergedGlyphs.addAll(singleGlyphs);
        mergedGlyphs.addAll(bidGlyphsWithoutGap);

        List<Word> mergedWords = wordGrouper.groupWords(mergedGlyphs);
        assertThat(mergedWords).hasSize(1);
        assertThat(mergedWords.get(0).getText()).isEqualTo("SingleBid");
    }

    @Test
    @DisplayName("2. Style run boundary: 'Deptt.:- Contract' splits into two distinct TextRun objects in a single block")
    void testStyleRunBoundarySplitting() {
        double fontSize = 12.0;
        double baselineY = 100.0;

        // "Deptt.:-" is Regular font (isBold = false)
        List<Glyph> regularGlyphs = List.of(
            new Glyph("D", 20.0, 88.0, 8.0, 12.0, baselineY, "Helvetica", fontSize, false),
            new Glyph("e", 28.0, 88.0, 6.0, 12.0, baselineY, "Helvetica", fontSize, false),
            new Glyph("p", 34.0, 88.0, 6.0, 12.0, baselineY, "Helvetica", fontSize, false),
            new Glyph("t", 40.0, 88.0, 4.0, 12.0, baselineY, "Helvetica", fontSize, false),
            new Glyph("t", 44.0, 88.0, 4.0, 12.0, baselineY, "Helvetica", fontSize, false),
            new Glyph(".", 48.0, 88.0, 3.0, 12.0, baselineY, "Helvetica", fontSize, false),
            new Glyph(":", 51.0, 88.0, 3.0, 12.0, baselineY, "Helvetica", fontSize, false),
            new Glyph("-", 54.0, 88.0, 4.0, 12.0, baselineY, "Helvetica", fontSize, false)
        ); // ends at 58.0

        // Space gap = 62.0 - 58.0 = 4.0 > 2.64
        // "Contract" is Bold font (isBold = true)
        List<Glyph> boldGlyphs = List.of(
            new Glyph("C", 62.0, 88.0, 9.0, 12.0, baselineY, "Helvetica-Bold", fontSize, true),
            new Glyph("o", 71.0, 88.0, 7.0, 12.0, baselineY, "Helvetica-Bold", fontSize, true),
            new Glyph("n", 78.0, 88.0, 7.0, 12.0, baselineY, "Helvetica-Bold", fontSize, true),
            new Glyph("t", 85.0, 88.0, 5.0, 12.0, baselineY, "Helvetica-Bold", fontSize, true),
            new Glyph("r", 90.0, 88.0, 5.0, 12.0, baselineY, "Helvetica-Bold", fontSize, true),
            new Glyph("a", 95.0, 88.0, 7.0, 12.0, baselineY, "Helvetica-Bold", fontSize, true),
            new Glyph("c", 102.0, 88.0, 6.0, 12.0, baselineY, "Helvetica-Bold", fontSize, true),
            new Glyph("t", 108.0, 88.0, 5.0, 12.0, baselineY, "Helvetica-Bold", fontSize, true)
        );

        List<Glyph> mixedGlyphs = new ArrayList<>();
        mixedGlyphs.addAll(regularGlyphs);
        mixedGlyphs.addAll(boldGlyphs);

        // Group into lines
        List<Line> lines = lineGrouper.groupGlyphsIntoLines(mixedGlyphs);
        assertThat(lines).hasSize(1);

        Line line = lines.get(0);
        List<TextRun> runs = line.getRuns();
        assertThat(runs).hasSize(2);

        // First run: "Deptt.:-", Regular
        TextRun run1 = runs.get(0);
        assertThat(run1.getText()).isEqualTo("Deptt.:-");
        assertFalse(run1.getStyle().bold(), "Run 1 must be Regular font");

        // Second run: "Contract", Bold
        TextRun run2 = runs.get(1);
        assertThat(run2.getText()).isEqualTo("Contract");
        assertTrue(run2.getStyle().bold(), "Run 2 must be Bold font");

        // Group into blocks
        List<Block> blocks = blockGrouper.groupLinesIntoBlocks(lines);
        assertThat(blocks).hasSize(1);
        assertTrue(blocks.get(0) instanceof ParagraphBlock, "Must be a ParagraphBlock");

        ParagraphBlock paragraphBlock = (ParagraphBlock) blocks.get(0);
        assertThat(paragraphBlock.getRuns()).hasSize(2);
        assertThat(paragraphBlock.getRuns().get(0).getText()).isEqualTo("Deptt.:-");
        assertThat(paragraphBlock.getRuns().get(1).getText()).isEqualTo("Contract");
        assertThat(paragraphBlock.getText()).isEqualTo("Deptt.:- Contract");
    }

    @Test
    @DisplayName("3. Multi-page isolation: Ensure page 2 extraction does not inherit offsets from page 1")
    void testMultiPageIsolation(@TempDir Path tempDir) throws IOException {
        Path pdfPath = tempDir.resolve("multipage_test.pdf");

        // Create a 2-page PDF
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            // Page 1
            PDPage page1 = new PDPage(new PDRectangle(0, 0, 612, 792));
            doc.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Page One Header");
                cs.endText();
            }

            // Page 2
            PDPage page2 = new PDPage(new PDRectangle(0, 0, 612, 792));
            doc.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Page Two Header");
                cs.endText();
            }

            doc.save(pdfPath.toFile());
        }

        DocumentModel documentModel = pipeline.extract(pdfPath);
        assertNotNull(documentModel);
        assertThat(documentModel.getPages()).hasSize(2);

        PageModel page1 = documentModel.getPages().get(0);
        PageModel page2 = documentModel.getPages().get(1);

        assertEquals(1, page1.getPageNumber());
        assertEquals(2, page2.getPageNumber());
        assertEquals(612.0, page1.getWidth(), 0.1);
        assertEquals(792.0, page1.getHeight(), 0.1);
        assertEquals(612.0, page2.getWidth(), 0.1);
        assertEquals(792.0, page2.getHeight(), 0.1);

        assertThat(page1.getBlocks()).isNotEmpty();
        assertThat(page2.getBlocks()).isNotEmpty();

        Block page1Block = page1.getBlocks().get(0);
        Block page2Block = page2.getBlocks().get(0);

        // Verify page contents are correct
        assertTrue(page1Block instanceof ParagraphBlock);
        assertTrue(page2Block instanceof ParagraphBlock);
        assertThat(((ParagraphBlock) page1Block).getText()).contains("Page One Header");
        assertThat(((ParagraphBlock) page2Block).getText()).contains("Page Two Header");

        // Page 1 and Page 2 text were placed at identical PDF offset (50, 700).
        // Distance from top is 792 - 700 = 92.
        // Both page Y coordinates must be normalized relative to their own top-left origin (~92),
        // and Page 2 must NOT inherit page 1's height (which would be 792 + 92 = 884).
        double page1Y = page1Block.getBbox()[1];
        double page2Y = page2Block.getBbox()[1];

        assertThat(page1Y).isCloseTo(85.0, org.assertj.core.data.Offset.offset(15.0));
        assertThat(page2Y).isCloseTo(85.0, org.assertj.core.data.Offset.offset(15.0));
        assertThat(Math.abs(page1Y - page2Y)).isLessThan(2.0);
        assertThat(page2Y).isLessThan(200.0); // Definitely isolated from page 1 height accumulation
    }

    @Test
    @DisplayName("Heading detection: Lines with relatively larger font size become HeadingBlock with appropriate level")
    void testHeadingDetection() {
        double baseFontSize = 12.0;

        // Heading line: 24pt (ratio = 2.0 -> Level 1)
        List<TextRun> h1Runs = List.of(new TextRun("Document Title", Style.of("Helvetica-Bold", 24.0, true, false), new double[]{50, 40, 200, 24}));
        Line h1Line = new Line(h1Runs, new double[]{50, 40, 200, 24}, 64.0, 24.0);

        // Body paragraph line: 12pt
        List<TextRun> bodyRuns = List.of(new TextRun("This is normal paragraph text.", Style.of("Helvetica", 12.0, false, false), new double[]{50, 90, 250, 12}));
        Line bodyLine = new Line(bodyRuns, new double[]{50, 90, 250, 12}, 102.0, 12.0);

        List<Block> blocks = blockGrouper.groupLinesIntoBlocks(List.of(h1Line, bodyLine));
        assertThat(blocks).hasSize(2);

        Block firstBlock = blocks.get(0);
        assertThat(firstBlock.getType()).isEqualTo(BlockType.HEADING);
        assertTrue(firstBlock instanceof HeadingBlock);
        HeadingBlock heading = (HeadingBlock) firstBlock;
        assertEquals(1, heading.getLevel());
        assertThat(heading.getText()).isEqualTo("Document Title");

        Block secondBlock = blocks.get(1);
        assertThat(secondBlock.getType()).isEqualTo(BlockType.PARAGRAPH);
        assertTrue(secondBlock instanceof ParagraphBlock);
        ParagraphBlock paragraph = (ParagraphBlock) secondBlock;
        assertThat(paragraph.getText()).isEqualTo("This is normal paragraph text.");
    }

    @Test
    @DisplayName("Block vertical gap threshold: gap > fontSize * 1.5 triggers a new block")
    void testVerticalGapThreshold() {
        double fontSize = 12.0;
        double gapThreshold = fontSize * 1.5; // 18.0

        // Line 1: top=50, h=12, bottom=62
        Line line1 = new Line(List.of(new TextRun("Line 1", Style.of("Helvetica", 12.0, false, false), new double[]{50, 50, 100, 12})),
            new double[]{50, 50, 100, 12}, 62.0, 12.0);

        // Line 2 close: top=66, gap = 66 - 62 = 4.0 <= 18.0 -> Same block
        Line line2Close = new Line(List.of(new TextRun("Line 2 Close", Style.of("Helvetica", 12.0, false, false), new double[]{50, 66, 100, 12})),
            new double[]{50, 66, 100, 12}, 78.0, 12.0);

        List<Block> singleBlockResult = blockGrouper.groupLinesIntoBlocks(List.of(line1, line2Close), fontSize);
        assertThat(singleBlockResult).hasSize(1);
        assertThat(((ParagraphBlock) singleBlockResult.get(0)).getText()).isEqualTo("Line 1 Line 2 Close");

        // Line 2 far: top=90, gap = 90 - 62 = 28.0 > 18.0 -> New block
        Line line2Far = new Line(List.of(new TextRun("Line 2 Far", Style.of("Helvetica", 12.0, false, false), new double[]{50, 90, 100, 12})),
            new double[]{50, 90, 100, 12}, 102.0, 12.0);

        List<Block> separateBlockResult = blockGrouper.groupLinesIntoBlocks(List.of(line1, line2Far), fontSize);
        assertThat(separateBlockResult).hasSize(2);
    }

    @Test
    @DisplayName("Jackson JSON Polymorphic Serialization/Deserialization")
    void testJsonSerializationRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        ParagraphBlock paragraph = new ParagraphBlock(
            new double[]{10, 20, 200, 30},
            List.of(new TextRun("Sample text", Style.of("Helvetica", 12.0, false, false), new double[]{10, 20, 200, 30})),
            "left"
        );

        HeadingBlock heading = new HeadingBlock(
            new double[]{10, 60, 200, 24},
            2,
            List.of(new TextRun("Sample Heading", Style.of("Helvetica-Bold", 18.0, true, false), new double[]{10, 60, 200, 24}))
        );

        PageModel page = new PageModel(1, 612.0, 792.0, List.of(paragraph, heading));
        DocumentModel doc = new DocumentModel("doc-123", "test.pdf", List.of(page));

        String json = mapper.writeValueAsString(doc);
        assertThat(json).contains("\"type\":\"PARAGRAPH\"");
        assertThat(json).contains("\"type\":\"HEADING\"");

        DocumentModel deserialized = mapper.readValue(json, DocumentModel.class);
        assertNotNull(deserialized);
        assertEquals("doc-123", deserialized.getId());
        assertEquals("test.pdf", deserialized.getName());
        assertThat(deserialized.getPages()).hasSize(1);

        PageModel deserializedPage = deserialized.getPages().get(0);
        assertThat(deserializedPage.getBlocks()).hasSize(2);
        assertTrue(deserializedPage.getBlocks().get(0) instanceof ParagraphBlock);
        assertTrue(deserializedPage.getBlocks().get(1) instanceof HeadingBlock);

        HeadingBlock deserializedHeading = (HeadingBlock) deserializedPage.getBlocks().get(1);
        assertEquals(2, deserializedHeading.getLevel());
        assertEquals("Sample Heading", deserializedHeading.getText());
    }
}
