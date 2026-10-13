package com.pdfeditor;

import com.pdfeditor.extraction.BlockGrouper;
import com.pdfeditor.extraction.Line;
import com.pdfeditor.extraction.LineGrouper;
import com.pdfeditor.extraction.TableExtractor;
import com.pdfeditor.extraction.Word;
import com.pdfeditor.model.Block;
import com.pdfeditor.model.BlockType;
import com.pdfeditor.model.HeadingBlock;
import com.pdfeditor.model.ParagraphBlock;
import com.pdfeditor.model.Style;
import com.pdfeditor.model.TableBlock;
import com.pdfeditor.model.TextRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TableExtractorTest {

    private final LineGrouper lineGrouper = new LineGrouper();
    private final TableExtractor tableExtractor = new TableExtractor();
    private final BlockGrouper blockGrouper = new BlockGrouper();

    @Test
    @DisplayName("LineGrouper splits words on same baseline across X_split plane even with narrow gap < 40pt")
    void testLineGrouperSplitsMultiColumnBaselineNarrowGap() {
        Style style = Style.defaultStyle();
        List<Word> wordsOnSameBaseline = List.of(
            // Left column ending at X=235 (right edge = 235)
            new Word("HINDUSTAN", new double[]{50.0, 100.0, 90.0, 12.0}, 109.0, style, List.of(), false),
            new Word("COPPER", new double[]{145.0, 100.0, 45.0, 12.0}, 109.0, style, List.of(), true),
            new Word("LIMITED", new double[]{195.0, 100.0, 40.0, 12.0}, 109.0, style, List.of(), true),
            // Right column starting at X=248 (gap = 248 - 235 = 13pt < 40pt)
            new Word("Enquiry", new double[]{248.0, 100.0, 45.0, 12.0}, 109.0, style, List.of(), false),
            new Word("No.:M19282", new double[]{296.0, 100.0, 70.0, 12.0}, 109.0, style, List.of(), true)
        );

        List<Line> lines = lineGrouper.groupWordsIntoLines(wordsOnSameBaseline);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getText()).isEqualTo("HINDUSTAN COPPER LIMITED");
        assertThat(lines.get(0).getX()).isEqualTo(50.0);

        assertThat(lines.get(1).getText()).isEqualTo("Enquiry No.:M19282");
        assertThat(lines.get(1).getX()).isEqualTo(248.0);
    }

    @Test
    @DisplayName("TableExtractor creates one unified TableBlock retaining middle lines without dropping or fragmenting")
    void testContiguousTableRetentionWithMiddleIsolatedLines() {
        Style style = Style.defaultStyle();

        // Top header: Left & Right
        Line leftLine1 = createLine("HINDUSTAN COPPER LIMITED", 50.0, 170.0, 185.0, 12.0, 179.0, style);
        Line rightLine1 = createLine("Enquiry No.:M19282", 250.0, 170.0, 140.0, 12.0, 179.0, style);

        // Second row: Left & Right
        Line leftLine2 = createLine("(A Govt. of India Enterprise)", 50.0, 195.0, 160.0, 10.0, 204.0, style);
        Line rightLine2 = createLine("Date: 23-12-2025", 250.0, 195.0, 100.0, 10.0, 204.0, style);

        // Middle line: ONLY on the left (e.g. description of work spanning multiple lines)
        Line middleLeftOnly = createLine("Description of work: Civil Maintenance Works", 50.0, 230.0, 190.0, 10.0, 239.0, style);

        // Middle line: ONLY on the right (e.g. EMD details)
        Line middleRightOnly = createLine("Earnest Money Deposit (EMD): Rs. 50,000/-", 250.0, 260.0, 180.0, 10.0, 269.0, style);

        // Bottom section: Cost of tender document
        Line bottomLeft = createLine("Cost of Tender Document / Reference", 50.0, 510.0, 180.0, 10.0, 519.0, style);
        Line bottomRight = createLine("Rs. 1000/- Non-Refundable", 250.0, 510.0, 140.0, 10.0, 519.0, style);

        // Following full-width section heading that terminates table (baseline Y > 560)
        Style headingStyle = new Style("Helvetica", 20.0, true, false, "#000000", 1.2);
        Line followingHeading = createLine("INSTRUCTIONS TO BIDDERS FOR ONLINE TENDER SUBMISSION", 50.0, 580.0, 480.0, 20.0, 595.0, headingStyle);

        List<Line> lines = List.of(
            leftLine1, rightLine1,
            leftLine2, rightLine2,
            middleLeftOnly,
            middleRightOnly,
            bottomLeft, bottomRight,
            followingHeading
        );

        List<Block> blocks = tableExtractor.extractBlocks(lines, blockGrouper);

        // Exactly 2 blocks: 1 single unified TableBlock + 1 following HeadingBlock
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)).isInstanceOf(TableBlock.class);
        assertThat(blocks.get(1)).isInstanceOf(HeadingBlock.class);

        TableBlock table = (TableBlock) blocks.get(0);
        assertThat(table.getRows()).isEqualTo(1);
        assertThat(table.getCols()).isEqualTo(2);

        ParagraphBlock leftCell = table.getCells().get(0).get(0);
        ParagraphBlock rightCell = table.getCells().get(0).get(1);

        // Left Cell contains top, middle, and bottom left content
        assertThat(leftCell.getText()).contains("HINDUSTAN COPPER LIMITED");
        assertThat(leftCell.getText()).contains("(A Govt. of India Enterprise)");
        assertThat(leftCell.getText()).contains("Description of work: Civil Maintenance Works");
        assertThat(leftCell.getText()).contains("Cost of Tender Document / Reference");

        // Right Cell contains top, middle, and bottom right content
        assertThat(rightCell.getText()).contains("Enquiry No.:M19282");
        assertThat(rightCell.getText()).contains("Date: 23-12-2025");
        assertThat(rightCell.getText()).contains("Earnest Money Deposit (EMD): Rs. 50,000/-");
        assertThat(rightCell.getText()).contains("Rs. 1000/- Non-Refundable");
    }

    @Test
    @DisplayName("TableExtractor preserves surrounding Heading and Paragraph blocks")
    void testTableExtractorPreservesSurroundingBlocks() {
        Style titleStyle = new Style("Helvetica", 20.0, true, false, "#000000", 1.2);
        Style bodyStyle = Style.defaultStyle();

        // 1. Title Heading at top (baseline Y < 160)
        Line titleLine = createLine("TENDER NOTICE", 50.0, 50.0, 200.0, 20.0, 66.0, titleStyle);

        // 2. 2-column header table (160 <= baseline Y <= 560)
        Line leftLine1 = createLine("HINDUSTAN COPPER", 50.0, 180.0, 120.0, 12.0, 190.0, bodyStyle);
        Line rightLine1 = createLine("Enquiry No.: 123", 250.0, 180.0, 110.0, 12.0, 190.0, bodyStyle);
        Line leftLine2 = createLine("Kolkata Branch", 50.0, 210.0, 100.0, 10.0, 220.0, bodyStyle);
        Line rightLine2 = createLine("Date: Today", 250.0, 210.0, 80.0, 10.0, 220.0, bodyStyle);

        // 3. Body Paragraph at bottom (baseline Y > 560)
        Line bodyLine = createLine("Sealed tenders are invited from eligible contractors for civil works.", 50.0, 580.0, 450.0, 12.0, 590.0, bodyStyle);

        List<Line> lines = List.of(titleLine, leftLine1, rightLine1, leftLine2, rightLine2, bodyLine);

        List<Block> blocks = tableExtractor.extractBlocks(lines, blockGrouper, 12.0);

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).getType()).isEqualTo(BlockType.HEADING);
        assertThat(blocks.get(1).getType()).isEqualTo(BlockType.TABLE);
        assertThat(blocks.get(2).getType()).isEqualTo(BlockType.PARAGRAPH);

        HeadingBlock heading = (HeadingBlock) blocks.get(0);
        assertThat(heading.getText()).isEqualTo("TENDER NOTICE");

        TableBlock table = (TableBlock) blocks.get(1);
        assertThat(table.getCols()).isEqualTo(2);

        ParagraphBlock para = (ParagraphBlock) blocks.get(2);
        assertThat(para.getText()).isEqualTo("Sealed tenders are invited from eligible contractors for civil works.");
    }

    @Test
    @DisplayName("External headers 'Malanjkhand Copper Project' and 'Tender Enquiry (RFQ)' remain above table")
    void testExternalHeadersNotSwallowedIntoTable() {
        Style headingStyle = new Style("Helvetica-Bold", 14.0, true, false, "#000000", 1.2);
        Style rfqHeadingStyle = new Style("Helvetica-Bold", 16.0, true, false, "#000000", 1.2);
        Style bodyStyle = Style.defaultStyle();

        // 1. External Header 1: Company / Unit Title above table (baseline Y < 160)
        Line headerUnit = createLine("Malanjkhand Copper Project", 50.0, 40.0, 180.0, 14.0, 52.0, headingStyle);

        // 2. External Header 2: Standalone Centered Heading around X ~ 260pt (baseline Y < 160)
        Line rfqHeader = createLine("Tender Enquiry (RFQ)", 255.0, 65.0, 150.0, 16.0, 79.0, rfqHeadingStyle);

        // 3. Table Begins: Left has Company address (X < 150pt), Right has Enquiry No (X > 240pt)
        Line leftLine1 = createLine("Hindustan Copper Limited", 50.0, 180.0, 160.0, 12.0, 190.0, bodyStyle);
        Line rightLine1 = createLine("Enquiry No.: M19282", 250.0, 180.0, 130.0, 12.0, 190.0, bodyStyle);

        // Second row of table
        Line leftLine2 = createLine("(A Govt. of India Enterprise)", 50.0, 210.0, 160.0, 10.0, 220.0, bodyStyle);
        Line rightLine2 = createLine("Date: 23-12-2025", 250.0, 210.0, 100.0, 10.0, 220.0, bodyStyle);

        // Third row: Right cell with Deptt
        Line rightLine3 = createLine("Deptt.:- Contract Cell, M&C Department", 242.0, 240.0, 220.0, 10.0, 250.0, bodyStyle);

        List<Line> lines = List.of(headerUnit, rfqHeader, leftLine1, rightLine1, leftLine2, rightLine2, rightLine3);

        List<Block> blocks = tableExtractor.extractBlocks(lines, blockGrouper, 12.0);

        // Blocks: Header 1 (Heading/Paragraph), Header 2 (HeadingBlock), then TableBlock
        assertThat(blocks.get(0).getType()).isIn(BlockType.HEADING, BlockType.PARAGRAPH);
        if (blocks.get(0) instanceof HeadingBlock hb) {
            assertThat(hb.getText()).contains("Malanjkhand Copper Project");
        } else if (blocks.get(0) instanceof ParagraphBlock pb) {
            assertThat(pb.getText()).contains("Malanjkhand Copper Project");
        }

        assertThat(blocks.get(1)).isInstanceOf(HeadingBlock.class);
        assertThat(((HeadingBlock) blocks.get(1)).getText()).contains("Tender Enquiry (RFQ)");

        // TableBlock follows
        Block tableCandidate = blocks.stream().filter(b -> b instanceof TableBlock).findFirst().orElseThrow();
        TableBlock table = (TableBlock) tableCandidate;
        ParagraphBlock leftCell = table.getCells().get(0).get(0);
        ParagraphBlock rightCell = table.getCells().get(0).get(1);

        // Verify external headers are NOT swallowed into table
        assertThat(leftCell.getText()).doesNotContain("Tender Enquiry (RFQ)");
        assertThat(leftCell.getText()).doesNotContain("Malanjkhand Copper Project");
        assertThat(rightCell.getText()).doesNotContain("Tender Enquiry (RFQ)");
        assertThat(rightCell.getText()).doesNotContain("Malanjkhand Copper Project");

        // Verify intra-cell table contents
        assertThat(leftCell.getText()).contains("Hindustan Copper Limited");
        assertThat(leftCell.getText()).contains("(A Govt. of India Enterprise)");

        assertThat(rightCell.getText()).contains("Enquiry No.: M19282");
        assertThat(rightCell.getText()).contains("Date: 23-12-2025");
        assertThat(rightCell.getText()).contains("Deptt.:- Contract Cell, M&C Department");
    }

    @Test
    @DisplayName("Deptt.:- is never sliced into D/e and p/t t . : - and stays intact in Right Cell")
    void testDepttWordNotSlicedAndIntactInRightCell() {
        Style style = Style.defaultStyle();

        // Table initiation with Left address and Right Enquiry
        Line initLeft = createLine("Hindustan Copper Limited", 50.0, 180.0, 150.0, 12.0, 190.0, style);
        Line initRight = createLine("Enquiry No.: M19282", 250.0, 180.0, 120.0, 12.0, 190.0, style);

        // Left Line ending at X=200
        Line leftLine = createLine("Malanjkhand Copper Project", 50.0, 210.0, 150.0, 12.0, 220.0, style);

        // Right Line starting at X=240, straddling dominant X_split plane (250)
        Line rightLine = createLine("Deptt.:- Contract Cell, M&C Department", 240.0, 210.0, 230.0, 12.0, 220.0, style);

        List<Line> lines = List.of(initLeft, initRight, leftLine, rightLine);
        List<Block> blocks = tableExtractor.extractBlocks(lines, blockGrouper, 12.0);

        assertThat(blocks).hasSize(1);
        TableBlock table = (TableBlock) blocks.get(0);
        ParagraphBlock leftCell = table.getCells().get(0).get(0);
        ParagraphBlock rightCell = table.getCells().get(0).get(1);

        // Left cell must NOT contain sliced fragments like "D", "e", "De"
        assertThat(leftCell.getText()).doesNotContain("Deptt");
        assertThat(leftCell.getText()).doesNotContain("De");

        // Right cell must contain the entire intact token and line
        assertThat(rightCell.getText()).contains("Deptt.:- Contract Cell, M&C Department");
        assertThat(rightCell.getText()).doesNotContain("t t . : -");
    }

    @Test
    @DisplayName("Balaghat and Last Date are never merged across X_DIVIDER, and TableExtractor spatial bounds [160, 560] form unified table")
    void testCrossColumnLineSeparationAndSpatialTableBounds() {
        Style style = Style.defaultStyle();

        // 1. LineGrouper test: "Balaghat- 481116" (left of divider) and "Last Date & Time..." (right of divider)
        Word leftWord1 = new Word("Balaghat-", new double[]{50.0, 300.0, 60.0, 12.0}, 309.0, style, List.of(), false);
        Word leftWord2 = new Word("481116", new double[]{115.0, 300.0, 45.0, 12.0}, 309.0, style, List.of(), true);
        Word rightWord1 = new Word("Last", new double[]{248.0, 300.0, 30.0, 12.0}, 309.0, style, List.of(), false);
        Word rightWord2 = new Word("Date", new double[]{282.0, 300.0, 30.0, 12.0}, 309.0, style, List.of(), true);
        Word rightWord3 = new Word("&", new double[]{316.0, 300.0, 12.0, 12.0}, 309.0, style, List.of(), true);
        Word rightWord4 = new Word("Time", new double[]{332.0, 300.0, 30.0, 12.0}, 309.0, style, List.of(), true);

        List<Line> grouped = lineGrouper.groupWordsIntoLines(List.of(leftWord1, leftWord2, rightWord1, rightWord2, rightWord3, rightWord4));
        assertThat(grouped).hasSize(2);
        assertThat(grouped.get(0).getText()).isEqualTo("Balaghat- 481116");
        assertThat(grouped.get(1).getText()).isEqualTo("Last Date & Time");

        // 2. Spatial Table Extraction test:
        // Pre-table heading (Y = 140 < 160)
        Line rfqHeading = createLine("Tender Enquiry (RFQ)", 255.0, 130.0, 150.0, 16.0, 142.0, style);

        // Lines in table range (160 <= baseline Y <= 560)
        Line tableLeft1 = createLine("HINDUSTAN COPPER LIMITED", 50.0, 165.0, 180.0, 12.0, 175.0, style);
        Line tableRight1 = createLine("Enquiry No.: M19282", 250.0, 165.0, 140.0, 12.0, 175.0, style);
        Line tableLeft2 = createLine("Balaghat- 481116", 50.0, 300.0, 120.0, 12.0, 310.0, style);
        Line tableRight2 = createLine("Last Date & Time for Tender Submission: 15-01-2026", 248.0, 300.0, 260.0, 12.0, 310.0, style);
        Line tableLeft3 = createLine("Cost of Tender Document / Reference", 50.0, 530.0, 190.0, 12.0, 540.0, style);
        Line tableRight3 = createLine("Rs. 1000/- Non-Refundable", 250.0, 530.0, 140.0, 12.0, 540.0, style);

        // Post-table heading (Y = 580 > 560)
        Line postHeading = createLine("INSTRUCTIONS TO BIDDERS FOR ONLINE TENDER SUBMISSION", 50.0, 580.0, 480.0, 16.0, 592.0, style);

        List<Line> pageLines = List.of(rfqHeading, tableLeft1, tableRight1, tableLeft2, tableRight2, tableLeft3, tableRight3, postHeading);
        List<Block> blocks = tableExtractor.extractBlocks(pageLines, blockGrouper, 12.0);

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(1)).isInstanceOf(TableBlock.class);

        TableBlock table = (TableBlock) blocks.get(1);
        ParagraphBlock leftCell = table.getCells().get(0).get(0);
        ParagraphBlock rightCell = table.getCells().get(0).get(1);

        assertThat(leftCell.getText()).contains("HINDUSTAN COPPER LIMITED");
        assertThat(leftCell.getText()).contains("Balaghat- 481116");
        assertThat(leftCell.getText()).contains("Cost of Tender Document / Reference");

        assertThat(rightCell.getText()).contains("Enquiry No.: M19282");
        assertThat(rightCell.getText()).contains("Last Date & Time for Tender Submission: 15-01-2026");
        assertThat(rightCell.getText()).contains("Rs. 1000/- Non-Refundable");
    }

    private Line createLine(String text, double x, double y, double w, double h, double baselineY, Style style) {
        TextRun run = new TextRun(text, style, new double[]{x, y, w, h});
        Word word = new Word(text, new double[]{x, y, w, h}, baselineY, style, List.of(), false);
        return new Line(List.of(run), new double[]{x, y, w, h}, baselineY, style.fontSize(), List.of(word));
    }
}
