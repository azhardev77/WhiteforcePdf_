package com.pdfeditor.extraction;

import com.pdfeditor.model.Style;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class Word {
    private final String text;
    private final double[] bbox; // [x, y, w, h]
    private final double baselineY;
    private final Style style;
    private final List<Glyph> glyphs;
    private final boolean precededBySpace;

    public Word(String text, double[] bbox, double baselineY, Style style, List<Glyph> glyphs, boolean precededBySpace) {
        this.text = text != null ? text : "";
        this.bbox = bbox != null ? bbox : new double[4];
        this.baselineY = baselineY;
        this.style = style != null ? style : Style.defaultStyle();
        this.glyphs = glyphs != null ? glyphs : new ArrayList<>();
        this.precededBySpace = precededBySpace;
    }

    public String getText() {
        return text;
    }

    public double[] getBbox() {
        return bbox;
    }

    public double getBaselineY() {
        return baselineY;
    }

    public Style getStyle() {
        return style;
    }

    public List<Glyph> getGlyphs() {
        return glyphs;
    }

    public boolean isPrecededBySpace() {
        return precededBySpace;
    }

    public double getX() {
        return bbox[0];
    }

    public double getY() {
        return bbox[1];
    }

    public double getWidth() {
        return bbox[2];
    }

    public double getHeight() {
        return bbox[3];
    }

    public double getFontSize() {
        return style.fontSize();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Word word = (Word) o;
        return Double.compare(word.baselineY, baselineY) == 0 &&
               precededBySpace == word.precededBySpace &&
               Objects.equals(text, word.text) &&
               Arrays.equals(bbox, word.bbox) &&
               Objects.equals(style, word.style);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(text, baselineY, style, precededBySpace);
        result = 31 * result + Arrays.hashCode(bbox);
        return result;
    }

    @Override
    public String toString() {
        return "Word{" +
               "text='" + text + '\'' +
               ", bbox=" + Arrays.toString(bbox) +
               ", baselineY=" + baselineY +
               ", style=" + style +
               ", precededBySpace=" + precededBySpace +
               '}';
    }
}
