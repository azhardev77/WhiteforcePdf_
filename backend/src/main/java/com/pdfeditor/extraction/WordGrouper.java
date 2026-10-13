package com.pdfeditor.extraction;

import com.pdfeditor.model.Style;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class WordGrouper {

    public static final double SPACE_THRESHOLD_RATIO = 0.40;

    public List<Word> groupWords(List<Glyph> glyphs) {
        if (glyphs == null || glyphs.isEmpty()) {
            return Collections.emptyList();
        }

        // Ensure glyphs are sorted by X coordinate
        List<Glyph> sortedGlyphs = new ArrayList<>(glyphs);
        sortedGlyphs.sort(Comparator.comparingDouble(Glyph::x));

        List<Word> words = new ArrayList<>();
        List<Glyph> currentWordGlyphs = new ArrayList<>();
        boolean currentWordPrecededBySpace = false;
        boolean nextPrecededBySpace = false;

        for (int i = 0; i < sortedGlyphs.size(); i++) {
            Glyph current = sortedGlyphs.get(i);
            String text = current.text();

            // Handle explicit whitespace glyphs
            if (text == null || text.isBlank()) {
                nextPrecededBySpace = true;
                continue;
            }

            if (!currentWordGlyphs.isEmpty()) {
                Glyph prev = currentWordGlyphs.get(currentWordGlyphs.size() - 1);
                boolean dynamicSpaceBreak = isSpaceBreak(prev, current);
                boolean styleChanged = !isSameStyle(prev, current);

                if (dynamicSpaceBreak || nextPrecededBySpace || styleChanged) {
                    words.add(buildWord(currentWordGlyphs, currentWordPrecededBySpace));
                    currentWordGlyphs.clear();
                    currentWordPrecededBySpace = dynamicSpaceBreak || nextPrecededBySpace;
                    nextPrecededBySpace = false;
                }
            } else {
                currentWordPrecededBySpace = nextPrecededBySpace;
                nextPrecededBySpace = false;
            }

            currentWordGlyphs.add(current);
        }

        if (!currentWordGlyphs.isEmpty()) {
            words.add(buildWord(currentWordGlyphs, currentWordPrecededBySpace));
        }

        return consolidateWords(words);
    }

    public boolean isSpaceBreak(Glyph prev, Glyph current) {
        if (prev == null || current == null) return false;
        double gap = current.x() - (prev.x() + prev.width());
        if (gap <= 0) return false;

        double prevFontSize = Math.max(prev.fontSize(), 1.0);
        String prevText = prev.text();
        String currText = current.text();

        if (prevText != null && !prevText.isEmpty() && currText != null && !currText.isEmpty()) {
            char prevChar = prevText.charAt(prevText.length() - 1);
            char currChar = currText.charAt(0);

            // Punctuation sequence rules: Characters '.', ':', '-' immediately following a word
            // or preceding punctuation must fuse into the preceding word token without space breaks.
            if (currChar == '.' || currChar == ':' || currChar == '-' || currChar == '/') {
                return gap > (prevFontSize * 0.60);
            }

            // Punctuation like '-' or ':' or '/' followed by letter/digit (e.g. "-Contract" or "23-12-2025")
            if ((prevChar == '-' || prevChar == ':' || prevChar == '/') && Character.isLetterOrDigit(currChar)) {
                return gap > (prevFontSize * 0.40);
            }

            // NEVER insert a space between two consecutive alphabetic characters (A-Z, a-z) or digits
            // unless the horizontal gap strictly exceeds (prevFontSize * 0.40) or >= 3.8pt
            if (Character.isLetterOrDigit(prevChar) && Character.isLetterOrDigit(currChar)) {
                return gap > Math.min(prevFontSize * 0.40, 3.8);
            }
        }

        return gap > Math.min(prevFontSize * 0.40, 3.8);
    }

    public boolean isSpaceBreak(double x, double prevX, double prevWidth, double prevFontSize) {
        double effectiveFontSize = Math.max(prevFontSize, 1.0);
        return (x - (prevX + prevWidth)) > Math.min(effectiveFontSize * SPACE_THRESHOLD_RATIO, 3.8);
    }

    public String joinWithDynamicSpaces(List<Glyph> glyphs) {
        List<Word> words = groupWords(glyphs);
        if (words.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(i);
            if (i > 0 && word.isPrecededBySpace()) {
                sb.append(" ");
            }
            sb.append(word.getText());
        }
        return sb.toString();
    }

    private Word buildWord(List<Glyph> glyphList, boolean precededBySpace) {
        StringBuilder sb = new StringBuilder();
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double sumBaseline = 0;

        for (Glyph g : glyphList) {
            sb.append(g.text());
            minX = Math.min(minX, g.x());
            minY = Math.min(minY, g.y());
            maxX = Math.max(maxX, g.x() + g.width());
            maxY = Math.max(maxY, g.y() + g.height());
            sumBaseline += g.baselineY();
        }

        double width = Math.max(0, maxX - minX);
        double height = Math.max(0, maxY - minY);
        double[] bbox = new double[]{minX, minY, width, height};
        double avgBaseline = sumBaseline / glyphList.size();

        Glyph first = glyphList.get(0);
        Style style = Style.of(first.fontName(), first.fontSize(), first.isBold(), first.isItalic(), first.color());

        return new Word(sb.toString(), bbox, avgBaseline, style, new ArrayList<>(glyphList), precededBySpace);
    }

    private boolean isSameStyle(Glyph g1, Glyph g2) {
        if (g1.isBold() != g2.isBold()) return false;
        if (g1.isItalic() != g2.isItalic()) return false;
        if (Math.abs(g1.fontSize() - g2.fontSize()) > 0.5) return false;
        if (!Objects.equals(g1.color(), g2.color())) return false;
        return Objects.equals(g1.fontName(), g2.fontName());
    }

    private List<Word> consolidateWords(List<Word> rawWords) {
        if (rawWords == null || rawWords.size() < 2) {
            return rawWords != null ? rawWords : Collections.emptyList();
        }

        List<Word> result = new ArrayList<>();
        for (Word w : rawWords) {
            if (!result.isEmpty()) {
                Word prev = result.get(result.size() - 1);
                String text = w.getText();
                boolean isPunct = (text.equals(".") || text.equals(":") || text.equals("-")
                        || text.equals(".:-") || text.equals(":-") || text.equals(".-")
                        || text.equals(",") || text.equals(";"));
                boolean startsWithFusePunct = text.startsWith(".") || text.startsWith(":") || text.startsWith("-");
                boolean canMerge = (isPunct || startsWithFusePunct) && !w.isPrecededBySpace() && isSameWordStyle(prev.getStyle(), w.getStyle());
                if (canMerge) {
                    result.set(result.size() - 1, mergeWords(prev, w));
                    continue;
                }
            }
            result.add(w);
        }
        return result;
    }

    private Word mergeWords(Word w1, Word w2) {
        String newText = w1.getText() + w2.getText();
        double[] b1 = w1.getBbox();
        double[] b2 = w2.getBbox();
        double minX = Math.min(b1[0], b2[0]);
        double minY = Math.min(b1[1], b2[1]);
        double maxX = Math.max(b1[0] + b1[2], b2[0] + b2[2]);
        double maxY = Math.max(b1[1] + b1[3], b2[1] + b2[3]);
        double[] bbox = new double[]{minX, minY, Math.max(0, maxX - minX), Math.max(0, maxY - minY)};
        List<Glyph> glyphs = new ArrayList<>();
        if (w1.getGlyphs() != null) glyphs.addAll(w1.getGlyphs());
        if (w2.getGlyphs() != null) glyphs.addAll(w2.getGlyphs());
        return new Word(newText, bbox, w1.getBaselineY(), w1.getStyle(), glyphs, w1.isPrecededBySpace());
    }

    private boolean isSameWordStyle(Style s1, Style s2) {
        if (s1 == s2) return true;
        if (s1 == null || s2 == null) return false;
        return s1.bold() == s2.bold() && s1.italic() == s2.italic()
                && Math.abs(s1.fontSize() - s2.fontSize()) <= 0.5
                && Objects.equals(s1.fontFamily(), s2.fontFamily())
                && Objects.equals(s1.color(), s2.color());
    }
}
