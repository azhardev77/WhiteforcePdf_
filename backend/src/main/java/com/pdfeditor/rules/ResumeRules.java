package com.pdfeditor.rules;

import com.pdfeditor.export.TextRedactionEdit;
import com.pdfeditor.model.Block;
import com.pdfeditor.model.DocumentModel;
import com.pdfeditor.model.HeadingBlock;
import com.pdfeditor.model.ImageBlock;
import com.pdfeditor.model.ListBlock;
import com.pdfeditor.model.PageModel;
import com.pdfeditor.model.ParagraphBlock;
import com.pdfeditor.model.Style;
import com.pdfeditor.model.TableBlock;
import com.pdfeditor.model.TextRun;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class ResumeRules implements RuleSet {

    private static class TextBlockWrapper {
        final Block sourceBlock;
        final List<TextRun> runs;
        final double[] blockBbox;

        TextBlockWrapper(Block sourceBlock, List<TextRun> runs, double[] blockBbox) {
            this.sourceBlock = sourceBlock;
            this.runs = runs;
            this.blockBbox = blockBbox;
        }
    }

    private static class RunOffset {
        final TextRun run;
        final int startOffset;
        final int endOffset;

        RunOffset(TextRun run, int startOffset, int endOffset) {
            this.run = run;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }
    }

    @Override
    public List<Match> find(DocumentModel doc) {
        if (doc == null || doc.getPages() == null || doc.getPages().isEmpty()) {
            return Collections.emptyList();
        }

        List<Match> allMatches = new ArrayList<>();

        for (PageModel page : doc.getPages()) {
            int pageNumber = page.getPageNumber();
            List<Block> rawBlocks = page.getBlocks();
            if (rawBlocks == null || rawBlocks.isEmpty()) {
                continue;
            }

            // Extract all text blocks from paragraphs, headings, lists, tables
            List<TextBlockWrapper> textBlocks = extractTextBlocks(rawBlocks);

            for (TextBlockWrapper tb : textBlocks) {
                List<TextRun> runs = tb.runs;
                if (runs == null || runs.isEmpty()) {
                    continue;
                }

                // Build reconstructed text with run offset tracking
                StringBuilder fullText = new StringBuilder();
                List<RunOffset> runOffsets = new ArrayList<>();

                for (int i = 0; i < runs.size(); i++) {
                    TextRun run = runs.get(i);
                    String rText = run.getText() != null ? run.getText() : "";
                    if (i > 0 && !fullText.isEmpty() && !fullText.toString().endsWith(" ") && !rText.startsWith(" ")) {
                        fullText.append(" ");
                    }
                    int startOffset = fullText.length();
                    fullText.append(rText);
                    int endOffset = fullText.length();
                    runOffsets.add(new RunOffset(run, startOffset, endOffset));
                }

                String blockText = fullText.toString();
                List<PiiDetector.PiiSpan> spans = PiiDetector.findPIISpans(blockText);
                if (spans.isEmpty()) {
                    continue;
                }

                for (PiiDetector.PiiSpan span : spans) {
                    List<double[]> lineBoxes = new ArrayList<>();

                    for (RunOffset ro : runOffsets) {
                        int overlapStart = Math.max(span.getStart(), ro.startOffset);
                        int overlapEnd = Math.min(span.getEnd(), ro.endOffset);

                        if (overlapStart < overlapEnd) {
                            double[] rBox = ro.run.getBbox();
                            if (rBox == null || rBox.length < 4 || (rBox[2] <= 0 && rBox[3] <= 0)) {
                                rBox = tb.blockBbox;
                            }

                            if (rBox != null && rBox.length >= 4) {
                                double rx = rBox[0];
                                double ry = rBox[1];
                                double rw = rBox[2];
                                double rh = rBox[3];

                                int runLen = Math.max(ro.run.getText() != null ? ro.run.getText().length() : 0, 1);
                                if (overlapStart <= ro.startOffset && overlapEnd >= ro.endOffset) {
                                    lineBoxes.add(new double[]{rx, ry, rw, rh});
                                } else {
                                    double charWidth = rw / runLen;
                                    int subStart = overlapStart - ro.startOffset;
                                    int subLen = overlapEnd - overlapStart;
                                    double subX = rx + (subStart * charWidth);
                                    double subW = Math.max(subLen * charWidth, 1.0);
                                    lineBoxes.add(new double[]{subX, ry, subW, rh});
                                }
                            }
                        }
                    }

                    if (lineBoxes.isEmpty() && tb.blockBbox != null && tb.blockBbox.length >= 4) {
                        lineBoxes.add(tb.blockBbox.clone());
                    }

                    if (lineBoxes.isEmpty()) {
                        continue;
                    }

                    // Handle adjacent sibling icon boxes (e.g. phone/mail icons directly preceding this match)
                    handleAdjacentSiblingIcons(lineBoxes, rawBlocks, tb);

                    // Compute overall bounding box encompassing all line boxes
                    double minX = Double.MAX_VALUE;
                    double minY = Double.MAX_VALUE;
                    double maxX = -Double.MAX_VALUE;
                    double maxY = -Double.MAX_VALUE;

                    for (double[] b : lineBoxes) {
                        minX = Math.min(minX, b[0]);
                        minY = Math.min(minY, b[1]);
                        maxX = Math.max(maxX, b[0] + b[2]);
                        maxY = Math.max(maxY, b[1] + b[3]);
                    }

                    double[] overallBbox = new double[]{minX, minY, Math.max(0, maxX - minX), Math.max(0, maxY - minY)};
                    allMatches.add(new Match(pageNumber, span.getType(), span.getMatch(), overallBbox, lineBoxes));
                }
            }
        }

        return allMatches;
    }

    public record ReplacementMatch(List<TextRedactionEdit> edits, int matchCount) {}

    /**
     * Reuses the exact same document model text extraction and block locator
     * to find arbitrary text occurrences and map them to block-level TextRedactionEdits.
     */
    public ReplacementMatch findTextReplacementEdits(DocumentModel doc, String findText, String replaceText) {
        if (doc == null || doc.getPages() == null || doc.getPages().isEmpty() || findText == null || findText.isEmpty()) {
            return new ReplacementMatch(Collections.emptyList(), 0);
        }

        List<TextRedactionEdit> edits = new ArrayList<>();
        int totalOccurrences = 0;

        for (PageModel page : doc.getPages()) {
            int pageNumber = page.getPageNumber();
            List<Block> rawBlocks = page.getBlocks();
            if (rawBlocks == null || rawBlocks.isEmpty()) {
                continue;
            }

            List<TextBlockWrapper> textBlocks = extractTextBlocks(rawBlocks);

            for (TextBlockWrapper tb : textBlocks) {
                List<TextRun> runs = tb.runs;
                if (runs == null || runs.isEmpty()) {
                    continue;
                }

                StringBuilder fullText = new StringBuilder();
                for (int i = 0; i < runs.size(); i++) {
                    TextRun run = runs.get(i);
                    String rText = run.getText() != null ? run.getText() : "";
                    if (i > 0 && !fullText.isEmpty() && !fullText.toString().endsWith(" ") && !rText.startsWith(" ")) {
                        fullText.append(" ");
                    }
                    fullText.append(rText);
                }

                String blockText = fullText.toString();
                if (!blockText.contains(findText)) {
                    continue;
                }

                // Count occurrences in this block
                int countInBlock = 0;
                int idx = 0;
                while ((idx = blockText.indexOf(findText, idx)) != -1) {
                    countInBlock++;
                    idx += findText.length();
                }
                totalOccurrences += countInBlock;

                String updatedBlockText = blockText.replace(findText, replaceText != null ? replaceText : "");

                Style matchedStyle = null;
                List<double[]> lineBoxes = new ArrayList<>();
                for (TextRun run : runs) {
                    if (run.getText() != null && run.getText().contains(findText)) {
                        if (matchedStyle == null && run.getStyle() != null) {
                            matchedStyle = run.getStyle();
                        }
                    }
                    if (run.getBbox() != null && run.getBbox().length >= 4) {
                        lineBoxes.add(run.getBbox().clone());
                    }
                }

                if (matchedStyle == null && !runs.isEmpty() && runs.get(0).getStyle() != null) {
                    matchedStyle = runs.get(0).getStyle();
                }

                if (lineBoxes.isEmpty() && tb.blockBbox != null && tb.blockBbox.length >= 4) {
                    lineBoxes.add(tb.blockBbox.clone());
                }

                double[] targetBbox = tb.blockBbox != null ? tb.blockBbox.clone() : new double[]{0, 0, 0, 0};
                TextRedactionEdit edit = new TextRedactionEdit(
                        pageNumber,
                        targetBbox,
                        blockText,
                        updatedBlockText,
                        matchedStyle,
                        "#FFFFFF"
                );
                edit.setWhiteoutBoxes(lineBoxes);
                edits.add(edit);
            }
        }

        return new ReplacementMatch(edits, totalOccurrences);
    }

    private List<TextBlockWrapper> extractTextBlocks(List<Block> blocks) {
        List<TextBlockWrapper> result = new ArrayList<>();
        for (Block b : blocks) {
            if (b instanceof ParagraphBlock pb) {
                result.add(new TextBlockWrapper(pb, pb.getRuns(), pb.getBbox()));
            } else if (b instanceof HeadingBlock hb) {
                result.add(new TextBlockWrapper(hb, hb.getRuns(), hb.getBbox()));
            } else if (b instanceof ListBlock lb) {
                if (lb.getItems() != null) {
                    for (ParagraphBlock item : lb.getItems()) {
                        result.add(new TextBlockWrapper(item, item.getRuns(), item.getBbox()));
                    }
                }
            } else if (b instanceof TableBlock tb) {
                if (tb.getCells() != null) {
                    for (List<ParagraphBlock> row : tb.getCells()) {
                        if (row != null) {
                            for (ParagraphBlock cell : row) {
                                if (cell != null) {
                                    result.add(new TextBlockWrapper(cell, cell.getRuns(), cell.getBbox()));
                                }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private void handleAdjacentSiblingIcons(List<double[]> lineBoxes, List<Block> pageBlocks, TextBlockWrapper currentTb) {
        if (lineBoxes.isEmpty()) return;

        // Find the leftmost box
        double[] firstBox = lineBoxes.get(0);
        for (double[] box : lineBoxes) {
            if (box[0] < firstBox[0]) {
                firstBox = box;
            }
        }

        double origX = firstBox[0];
        double origY = firstBox[1];
        double origW = firstBox[2];
        double origH = firstBox[3];

        double padLeft = Math.max(origH, 12.0) * 1.6;
        double expandedX = origX;
        double boxTop = origY;
        double boxBottom = origY + origH;

        for (Block sibling : pageBlocks) {
            if (sibling == currentTb.sourceBlock) continue;
            double[] sibBbox = sibling.getBbox();
            if (sibBbox == null || sibBbox.length < 4) continue;

            double sibX = sibBbox[0];
            double sibY = sibBbox[1];
            double sibW = sibBbox[2];
            double sibH = sibBbox[3];
            double sibTop = sibY;
            double sibBottom = sibY + sibH;

            // Check vertical overlap on same horizontal band
            double yOverlap = Math.max(0, Math.min(boxBottom, sibBottom) - Math.max(boxTop, sibTop));
            if (yOverlap > 3) {
                double sibRight = sibX + sibW;
                // If sibling is an image or icon to the left within padLeft distance
                if (sibling instanceof ImageBlock && sibRight <= origX && (origX - sibRight) <= padLeft) {
                    expandedX = Math.min(expandedX, sibX);
                } else if (sibRight <= origX && sibRight > expandedX) {
                    // Non-redacted text block to the left clamps expansion
                    expandedX = Math.min(origX, sibRight + 2.0);
                }
            }
        }

        if (expandedX < origX) {
            firstBox[2] = origW + (origX - expandedX);
            firstBox[0] = expandedX;
        }
    }
}
