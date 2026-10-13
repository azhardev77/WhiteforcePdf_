package com.pdfeditor.extraction;

import com.pdfeditor.model.Block;
import com.pdfeditor.model.BlockType;
import com.pdfeditor.model.HeadingBlock;
import com.pdfeditor.model.ParagraphBlock;
import com.pdfeditor.model.TextRun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BlockGrouper {

    public static final double GAP_THRESHOLD_RATIO = 1.5;
    public static final double HEADING_RATIO_THRESHOLD = 1.20;

    public List<Block> groupLinesIntoBlocks(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return Collections.emptyList();
        }

        double baseFontSize = detectBaseFontSize(lines);
        return groupLinesIntoBlocks(lines, baseFontSize);
    }

    public List<Block> groupLinesIntoBlocks(List<Line> lines, double baseFontSize) {
        if (lines == null || lines.isEmpty()) {
            return Collections.emptyList();
        }

        List<Block> blocks = new ArrayList<>();
        List<Line> currentBlockLines = new ArrayList<>();
        BlockType currentBlockType = null;

        for (int i = 0; i < lines.size(); i++) {
            Line currentLine = lines.get(i);
            boolean isHeading = isHeadingLine(currentLine, baseFontSize);
            BlockType lineType = isHeading ? BlockType.HEADING : BlockType.PARAGRAPH;

            if (currentBlockLines.isEmpty()) {
                currentBlockLines.add(currentLine);
                currentBlockType = lineType;
                continue;
            }

            Line prevLine = currentBlockLines.get(currentBlockLines.size() - 1);
            double prevBottom = prevLine.getY() + prevLine.getHeight();
            double currentTop = currentLine.getY();
            double gap = currentTop - prevBottom;
            double refFontSize = Math.max(prevLine.getPrimaryFontSize(), 1.0);
            double gapThreshold = refFontSize * GAP_THRESHOLD_RATIO;

            boolean gapExceeded = gap > gapThreshold;
            boolean typeChanged = (lineType != currentBlockType);
            boolean isHeadingBlock = (currentBlockType == BlockType.HEADING);

            // A new block is triggered if gap > fontSize * 1.5, or type changed, or heading block finished
            if (gapExceeded || typeChanged || isHeadingBlock) {
                blocks.add(buildBlock(currentBlockLines, currentBlockType, baseFontSize));
                currentBlockLines.clear();
                currentBlockLines.add(currentLine);
                currentBlockType = lineType;
            } else {
                currentBlockLines.add(currentLine);
            }
        }

        if (!currentBlockLines.isEmpty()) {
            blocks.add(buildBlock(currentBlockLines, currentBlockType, baseFontSize));
        }

        return blocks;
    }

    public boolean isHeadingLine(Line line, double baseFontSize) {
        if (line == null) return false;
        if (isHeading(line.getPrimaryFontSize(), baseFontSize)) {
            return true;
        }

        String text = line.getText();
        if (text != null && !text.isBlank()) {
            String lower = text.trim().toLowerCase();
            // Standalone tender headings like "Tender Enquiry (RFQ)", "Tender Notice", etc.
            if (lower.contains("tender enquiry") || lower.contains("(rfq)")
                    || lower.contains("tender notice") || lower.contains("instructions to bidders")
                    || lower.contains("corrigendum")) {
                return true;
            }

            // Standalone bold line centered horizontally (e.g. X ~ 220-380pt)
            double centerX = line.getX() + (line.getWidth() / 2.0);
            boolean isCentered = (centerX >= 220.0 && centerX <= 380.0) && (line.getX() >= 150.0);
            if (line.isBold() && isCentered) {
                return true;
            }
        }

        return false;
    }

    public boolean isHeading(double fontSize, double baseFontSize) {
        if (baseFontSize <= 0) baseFontSize = 12.0;
        return (fontSize / baseFontSize) >= HEADING_RATIO_THRESHOLD;
    }

    public int determineHeadingLevel(double fontSize, double baseFontSize) {
        if (baseFontSize <= 0) baseFontSize = 12.0;
        double ratio = fontSize / baseFontSize;

        if (ratio >= 2.0) return 1;
        if (ratio >= 1.6) return 2;
        if (ratio >= 1.4) return 3;
        if (ratio >= 1.25) return 4;
        if (ratio >= 1.15) return 5;
        if (ratio >= 1.0) return 2;
        return 6;
    }

    public double detectBaseFontSize(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return 12.0;
        }

        // Count frequency of rounded font sizes across all lines/runs
        Map<Integer, Integer> frequency = new HashMap<>();
        for (Line line : lines) {
            for (TextRun run : line.getRuns()) {
                if (run.getStyle() != null) {
                    int rounded = (int) Math.round(run.getStyle().fontSize());
                    frequency.put(rounded, frequency.getOrDefault(rounded, 0) + run.getText().length());
                }
            }
        }

        int mostFrequentSize = 12;
        int maxCount = -1;
        for (Map.Entry<Integer, Integer> entry : frequency.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                mostFrequentSize = entry.getKey();
            }
        }

        return mostFrequentSize > 0 ? mostFrequentSize : 12.0;
    }

    private Block buildBlock(List<Line> blockLines, BlockType blockType, double baseFontSize) {
        List<TextRun> allRuns = new ArrayList<>();
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxFontSize = 0;

        for (Line line : blockLines) {
            allRuns.addAll(line.getRuns());
            double[] b = line.getBbox();
            minX = Math.min(minX, b[0]);
            minY = Math.min(minY, b[1]);
            maxX = Math.max(maxX, b[0] + b[2]);
            maxY = Math.max(maxY, b[1] + b[3]);
            maxFontSize = Math.max(maxFontSize, line.getPrimaryFontSize());
        }

        double width = Math.max(0, maxX - minX);
        double height = Math.max(0, maxY - minY);
        double[] bbox = new double[]{minX, minY, width, height};

        if (blockType == BlockType.HEADING) {
            int level = determineHeadingLevel(maxFontSize, baseFontSize);
            return new HeadingBlock(bbox, level, allRuns);
        } else {
            return new ParagraphBlock(bbox, allRuns, "left");
        }
    }
}
