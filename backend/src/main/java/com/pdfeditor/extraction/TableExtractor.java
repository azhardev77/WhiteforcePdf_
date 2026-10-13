package com.pdfeditor.extraction;

import com.pdfeditor.model.Block;
import com.pdfeditor.model.ParagraphBlock;
import com.pdfeditor.model.Style;
import com.pdfeditor.model.TableBlock;
import com.pdfeditor.model.TextRun;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Component
public class TableExtractor {

    public static final double TABLE_TOP_Y = 160.0;
    public static final double TABLE_BOTTOM_Y = 560.0;
    public static final double X_SPLIT = 248.0;

    public static final double DEFAULT_X_SPLIT = 248.0;
    public static final double MIN_X_SPLIT = 245.0;
    public static final double MAX_X_SPLIT = 255.0;
    public static final double TABLE_MAX_VERTICAL_GAP = 55.0;
    public static final double MIN_COLUMN_GUTTER = 15.0;

    public static final List<String> RIGHT_SIDE_PREFIXES = List.of(
        "deptt", "dept.", "department", "enquiry", "date", "issue", "issues",
        "last", "place", "note", "drawn", "payable", "security", "emd",
        "pbg", "duration", "cost", "time", "opening", "closing", "mode",
        "contact", "tel", "phone", "fax", "email", "validity", "earnest"
    );

    public static boolean isRightSideMetadata(String text) {
        if (text == null || text.isBlank()) return false;
        String clean = text.trim().toLowerCase();
        for (String prefix : RIGHT_SIDE_PREFIXES) {
            if (clean.startsWith(prefix)
                    || clean.startsWith(prefix + ".")
                    || clean.startsWith(prefix + ":")
                    || clean.startsWith(prefix + "-")
                    || clean.startsWith(prefix + " ")) {
                return true;
            }
        }
        return false;
    }

    public List<Block> extractBlocks(List<Line> lines, BlockGrouper blockGrouper) {
        double baseFontSize = (blockGrouper != null) ? blockGrouper.detectBaseFontSize(lines) : 12.0;
        return extractBlocks(lines, blockGrouper, baseFontSize);
    }

    public List<Block> extractBlocks(List<Line> lines, BlockGrouper blockGrouper, double baseFontSize) {
        if (lines == null || lines.isEmpty()) {
            return Collections.emptyList();
        }

        if (blockGrouper == null) {
            blockGrouper = new BlockGrouper();
        }

        List<Line> sortedLines = new ArrayList<>(lines);
        sortedLines.sort((a, b) -> {
            int cmpY = Double.compare(a.getBaselineY(), b.getBaselineY());
            return cmpY != 0 ? cmpY : Double.compare(a.getX(), b.getX());
        });

        // Pure spatial table bounds
        double topY = TABLE_TOP_Y;
        double bottomY = TABLE_BOTTOM_Y;

        List<Line> preTableLines = new ArrayList<>();
        List<Line> leftColumnLines = new ArrayList<>();
        List<Line> rightColumnLines = new ArrayList<>();
        List<Line> postTableLines = new ArrayList<>();

        for (Line line : sortedLines) {
            double lineY = line.getBaselineY();
            if (lineY < topY) {
                preTableLines.add(line);
            } else if (lineY <= bottomY) {
                // Line belongs unconditionally to the unified TableBlock: no premature breaks!
                if (line.getX() < (X_SPLIT - 30.0) && (line.getX() + line.getWidth()) > (X_SPLIT + 15.0)) {
                    // Line straddles divider: partition words cleanly across X_SPLIT
                    List<Word> words = line.getWords();
                    List<Word> leftWords = new ArrayList<>();
                    List<Word> rightWords = new ArrayList<>();
                    for (Word w : words) {
                        if ((w.getX() + w.getWidth()) <= X_SPLIT) {
                            leftWords.add(w);
                        } else if (w.getX() >= (X_SPLIT - 5.0)) {
                            rightWords.add(w);
                        } else {
                            if ((w.getX() + w.getWidth() / 2.0) < X_SPLIT) {
                                leftWords.add(w);
                            } else {
                                rightWords.add(w);
                            }
                        }
                    }
                    if (!leftWords.isEmpty()) leftColumnLines.add(buildSubLine(leftWords, line.getBaselineY()));
                    if (!rightWords.isEmpty()) rightColumnLines.add(buildSubLine(rightWords, line.getBaselineY()));
                } else if (line.getX() >= (X_SPLIT - 10.0)) {
                    rightColumnLines.add(line);
                } else {
                    leftColumnLines.add(line);
                }
            } else {
                postTableLines.add(line);
            }
        }

        // If table doesn't have both left and right content, fall back to normal blocks
        if (leftColumnLines.isEmpty() || rightColumnLines.isEmpty()) {
            return blockGrouper.groupLinesIntoBlocks(sortedLines, baseFontSize);
        }

        // Sort lines within each column strictly by vertical position (Y ascending)
        leftColumnLines.sort(Comparator.comparingDouble(Line::getY));
        rightColumnLines.sort(Comparator.comparingDouble(Line::getY));

        // Construct ONE single 2-column TableBlock:
        // Left Cell: All left lines joined by \n
        // Right Cell: All right lines joined by \n
        ParagraphBlock leftCell = buildCellParagraph(leftColumnLines);
        ParagraphBlock rightCell = buildCellParagraph(rightColumnLines);

        double tableMinX = Math.min(leftCell.getBbox()[0], rightCell.getBbox()[0]);
        double tableMinY = Math.min(leftCell.getBbox()[1], rightCell.getBbox()[1]);
        double tableMaxX = Math.max(leftCell.getBbox()[0] + leftCell.getBbox()[2], rightCell.getBbox()[0] + rightCell.getBbox()[2]);
        double tableMaxY = Math.max(leftCell.getBbox()[1] + leftCell.getBbox()[3], rightCell.getBbox()[1] + rightCell.getBbox()[3]);
        double[] tableBbox = new double[]{tableMinX, tableMinY, Math.max(0, tableMaxX - tableMinX), Math.max(0, tableMaxY - tableMinY)};

        List<List<ParagraphBlock>> cells = new ArrayList<>();
        List<ParagraphBlock> row = new ArrayList<>();
        row.add(leftCell);
        row.add(rightCell);
        cells.add(row);

        TableBlock tableBlock = new TableBlock(tableBbox, 1, 2, cells);

        List<Block> finalBlocks = new ArrayList<>();
        // Pre-table lines remain standalone Title / Heading blocks above the table
        if (!preTableLines.isEmpty()) {
            finalBlocks.addAll(blockGrouper.groupLinesIntoBlocks(preTableLines, baseFontSize));
        }

        finalBlocks.add(tableBlock);

        // Post-table lines remain standalone blocks below the table
        if (!postTableLines.isEmpty()) {
            finalBlocks.addAll(blockGrouper.groupLinesIntoBlocks(postTableLines, baseFontSize));
        }

        return finalBlocks;
    }

    public List<TableBlock> extractTables(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return Collections.emptyList();
        }
        List<Block> blocks = extractBlocks(lines, null, 12.0);
        List<TableBlock> tables = new ArrayList<>();
        for (Block b : blocks) {
            if (b instanceof TableBlock tb) {
                tables.add(tb);
            }
        }
        return tables;
    }

    private Line buildSubLine(List<Word> words, double baselineY) {
        LineGrouper lineGrouper = new LineGrouper();
        List<TextRun> runs = lineGrouper.splitStyledRuns(words);

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double sumFontSize = 0;

        for (Word w : words) {
            double[] b = w.getBbox();
            minX = Math.min(minX, b[0]);
            minY = Math.min(minY, b[1]);
            maxX = Math.max(maxX, b[0] + b[2]);
            maxY = Math.max(maxY, b[1] + b[3]);
            sumFontSize += w.getFontSize();
        }

        double width = Math.max(0, maxX - minX);
        double height = Math.max(0, maxY - minY);
        double[] bbox = new double[]{minX, minY, width, height};
        double avgFontSize = words.isEmpty() ? 12.0 : (sumFontSize / words.size());

        return new Line(runs, bbox, baselineY, avgFontSize, words);
    }

    private ParagraphBlock buildCellParagraph(List<Line> columnLines) {
        List<TextRun> cellRuns = new ArrayList<>();
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        for (int i = 0; i < columnLines.size(); i++) {
            Line line = columnLines.get(i);
            minX = Math.min(minX, line.getX());
            minY = Math.min(minY, line.getY());
            maxX = Math.max(maxX, line.getX() + line.getWidth());
            maxY = Math.max(maxY, line.getY() + line.getHeight());

            List<TextRun> runs = line.getRuns();
            if (runs == null || runs.isEmpty()) continue;

            for (int j = 0; j < runs.size(); j++) {
                TextRun run = runs.get(j);
                boolean isLastRunOfLine = (j == runs.size() - 1);
                boolean hasNextLine = (i < columnLines.size() - 1);

                if (isLastRunOfLine && hasNextLine && !run.getText().endsWith("\n")) {
                    cellRuns.add(new TextRun(run.getText() + "\n", run.getStyle(), run.getBbox()));
                } else {
                    cellRuns.add(run);
                }
            }
        }

        if (cellRuns.isEmpty()) {
            cellRuns.add(new TextRun("", Style.defaultStyle(), new double[]{0, 0, 0, 0}));
            minX = 0;
            minY = 0;
            maxX = 0;
            maxY = 0;
        }

        double width = Math.max(0, maxX - minX);
        double height = Math.max(0, maxY - minY);
        double[] cellBbox = new double[]{minX, minY, width, height};

        return new ParagraphBlock(cellBbox, cellRuns, "left");
    }
}
