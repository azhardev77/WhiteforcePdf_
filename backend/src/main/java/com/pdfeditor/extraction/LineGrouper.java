package com.pdfeditor.extraction;

import com.pdfeditor.model.Style;
import com.pdfeditor.model.TextRun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class LineGrouper {

    public static final double BASELINE_TOLERANCE_RATIO = 0.25;
    public static final double COLUMN_GAP_THRESHOLD = 15.0;
    public static final double X_DIVIDER = 248.0;

    public static final double X_SPLIT_MIN = 240.0;
    public static final double X_SPLIT_MAX = 260.0;
    public static final double DEFAULT_X_SPLIT = 250.0;

    private final WordGrouper wordGrouper;

    public LineGrouper() {
        this(new WordGrouper());
    }

    public LineGrouper(WordGrouper wordGrouper) {
        this.wordGrouper = wordGrouper != null ? wordGrouper : new WordGrouper();
    }

    public boolean areSameLine(double y1, double y2, double fontSize) {
        double effectiveFontSize = Math.max(fontSize, 1.0);
        return Math.abs(y1 - y2) <= (effectiveFontSize * BASELINE_TOLERANCE_RATIO);
    }

    public List<Line> groupGlyphsIntoLines(List<Glyph> glyphs) {
        if (glyphs == null || glyphs.isEmpty()) {
            return Collections.emptyList();
        }

        // Sort glyphs primarily by baselineY ascending, secondarily by X ascending
        List<Glyph> sorted = new ArrayList<>(glyphs);
        sorted.sort((a, b) -> {
            int cmpY = Double.compare(a.baselineY(), b.baselineY());
            return cmpY != 0 ? cmpY : Double.compare(a.x(), b.x());
        });

        // Cluster glyphs into raw line clusters using baseline threshold
        List<List<Glyph>> rawLines = new ArrayList<>();
        for (Glyph glyph : sorted) {
            boolean matched = false;
            for (List<Glyph> lineCluster : rawLines) {
                double refBaseline = computeAverageBaseline(lineCluster);
                double refFontSize = lineCluster.get(0).fontSize();
                if (areSameLine(glyph.baselineY(), refBaseline, refFontSize)) {
                    lineCluster.add(glyph);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                List<Glyph> newLine = new ArrayList<>();
                newLine.add(glyph);
                rawLines.add(newLine);
            }
        }

        // Sort line clusters from top to bottom
        rawLines.sort(Comparator.comparingDouble(this::computeAverageBaseline));

        List<Line> lines = new ArrayList<>();
        for (List<Glyph> rawLine : rawLines) {
            rawLine.sort(Comparator.comparingDouble(Glyph::x));
            List<Word> words = wordGrouper.groupWords(rawLine);
            if (!words.isEmpty()) {
                // Split words on the same baseline across column gaps or global split plane
                List<List<Word>> segments = splitIntoHorizontalSegments(words, COLUMN_GAP_THRESHOLD);
                for (List<Word> segment : segments) {
                    if (!segment.isEmpty()) {
                        lines.add(buildLineFromWords(segment));
                    }
                }
            }
        }

        return lines;
    }

    public List<Line> groupWordsIntoLines(List<Word> words) {
        if (words == null || words.isEmpty()) {
            return Collections.emptyList();
        }

        List<Word> sorted = new ArrayList<>(words);
        sorted.sort((a, b) -> {
            int cmpY = Double.compare(a.getBaselineY(), b.getBaselineY());
            return cmpY != 0 ? cmpY : Double.compare(a.getX(), b.getX());
        });

        List<List<Word>> rawLines = new ArrayList<>();
        for (Word word : sorted) {
            boolean matched = false;
            for (List<Word> lineCluster : rawLines) {
                double refBaseline = computeAverageWordBaseline(lineCluster);
                double refFontSize = lineCluster.get(0).getFontSize();
                if (areSameLine(word.getBaselineY(), refBaseline, refFontSize)) {
                    lineCluster.add(word);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                List<Word> newLine = new ArrayList<>();
                newLine.add(word);
                rawLines.add(newLine);
            }
        }

        rawLines.sort(Comparator.comparingDouble(this::computeAverageWordBaseline));

        List<Line> lines = new ArrayList<>();
        for (List<Word> rawLine : rawLines) {
            rawLine.sort(Comparator.comparingDouble(Word::getX));
            List<List<Word>> segments = splitIntoHorizontalSegments(rawLine, COLUMN_GAP_THRESHOLD);
            for (List<Word> segment : segments) {
                if (!segment.isEmpty()) {
                    lines.add(buildLineFromWords(segment));
                }
            }
        }

        return lines;
    }

    /**
     * Splits words on the same baseline into separate horizontal segments if:
     * 1) The gap between adjacent words exceeds the column gutter threshold, OR
     * 2) The words cross the dominant vertical column divider plane (X_split ~ 245-255 pt).
     */
    public List<List<Word>> splitIntoHorizontalSegments(List<Word> words, double gapThreshold) {
        if (words == null || words.isEmpty()) {
            return Collections.emptyList();
        }

        List<List<Word>> segments = new ArrayList<>();
        List<Word> currentSegment = new ArrayList<>();

        for (Word word : words) {
            if (currentSegment.isEmpty()) {
                currentSegment.add(word);
            } else {
                Word prev = currentSegment.get(currentSegment.size() - 1);
                double prevRight = prev.getX() + prev.getWidth();
                double gap = word.getX() - prevRight;

                boolean gapExceeded = (gap >= gapThreshold);
                // Strictly prevent cross-column line merging:
                // Glyphs on the left of the divider (x < X_DIVIDER) and glyphs on the right of the divider (x >= X_DIVIDER - 5.0)
                // MUST NEVER be merged into the same Line object, regardless of how small the gap is.
                boolean crossesDivider = (prev.getX() < X_DIVIDER && word.getX() >= (X_DIVIDER - 5.0));

                if (gapExceeded || crossesDivider) {
                    segments.add(currentSegment);
                    currentSegment = new ArrayList<>();
                }
                currentSegment.add(word);
            }
        }

        if (!currentSegment.isEmpty()) {
            segments.add(currentSegment);
        }

        return segments;
    }

    public List<TextRun> splitStyledRuns(List<Word> words) {
        if (words == null || words.isEmpty()) {
            return Collections.emptyList();
        }

        List<TextRun> runs = new ArrayList<>();
        List<Word> currentRunWords = new ArrayList<>();
        Style currentStyle = words.get(0).getStyle();

        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(i);
            if (isSameStyle(currentStyle, word.getStyle())) {
                currentRunWords.add(word);
            } else {
                // Style changed: split into a distinct TextRun
                runs.add(createRun(currentRunWords, currentStyle));
                currentRunWords.clear();
                currentStyle = word.getStyle();
                currentRunWords.add(word);
            }
        }

        if (!currentRunWords.isEmpty()) {
            runs.add(createRun(currentRunWords, currentStyle));
        }

        return runs;
    }

    private Line buildLineFromWords(List<Word> words) {
        List<TextRun> runs = splitStyledRuns(words);

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double sumBaseline = 0;
        double sumFontSize = 0;

        for (Word w : words) {
            double[] b = w.getBbox();
            minX = Math.min(minX, b[0]);
            minY = Math.min(minY, b[1]);
            maxX = Math.max(maxX, b[0] + b[2]);
            maxY = Math.max(maxY, b[1] + b[3]);
            sumBaseline += w.getBaselineY();
            sumFontSize += w.getFontSize();
        }

        double width = Math.max(0, maxX - minX);
        double height = Math.max(0, maxY - minY);
        double[] lineBbox = new double[]{minX, minY, width, height};
        double avgBaseline = sumBaseline / words.size();
        double avgFontSize = sumFontSize / words.size();

        return new Line(runs, lineBbox, avgBaseline, avgFontSize, words);
    }

    private TextRun createRun(List<Word> runWords, Style style) {
        StringBuilder sb = new StringBuilder();

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        for (int i = 0; i < runWords.size(); i++) {
            Word w = runWords.get(i);
            if (i > 0 && w.isPrecededBySpace()) {
                String wText = w.getText();
                boolean isAttachedPunct = wText != null && (wText.startsWith(".") || wText.startsWith(":") || wText.startsWith(",") || wText.startsWith(";"));
                if (!isAttachedPunct) {
                    sb.append(" ");
                }
            }
            sb.append(w.getText());

            double[] b = w.getBbox();
            minX = Math.min(minX, b[0]);
            minY = Math.min(minY, b[1]);
            maxX = Math.max(maxX, b[0] + b[2]);
            maxY = Math.max(maxY, b[1] + b[3]);
        }

        double width = Math.max(0, maxX - minX);
        double height = Math.max(0, maxY - minY);
        double[] runBbox = new double[]{minX, minY, width, height};

        return new TextRun(sb.toString(), style, runBbox);
    }

    private boolean isSameStyle(Style s1, Style s2) {
        if (s1 == s2) return true;
        if (s1 == null || s2 == null) return false;
        if (s1.bold() != s2.bold()) return false;
        if (s1.italic() != s2.italic()) return false;
        if (Math.abs(s1.fontSize() - s2.fontSize()) > 0.5) return false;
        if (!Objects.equals(s1.color(), s2.color())) return false;
        return Objects.equals(s1.fontFamily(), s2.fontFamily());
    }

    private double computeAverageBaseline(List<Glyph> list) {
        double sum = 0;
        for (Glyph g : list) {
            sum += g.baselineY();
        }
        return sum / list.size();
    }

    private double computeAverageWordBaseline(List<Word> list) {
        double sum = 0;
        for (Word w : list) {
            sum += w.getBaselineY();
        }
        return sum / list.size();
    }
}
