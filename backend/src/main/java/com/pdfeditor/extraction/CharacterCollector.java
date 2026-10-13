package com.pdfeditor.extraction;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CharacterCollector extends PDFTextStripper {

    private final List<Glyph> glyphs;

    public CharacterCollector() {
        this.glyphs = new ArrayList<>();
    }

    public CharacterCollector(List<Glyph> targetList) {
        this.glyphs = targetList != null ? targetList : new ArrayList<>();
    }

    @Override
    protected void processTextPosition(TextPosition text) {
        if (text == null) {
            return;
        }

        String unicode = text.getUnicode();
        if (unicode == null) {
            return;
        }

        double x = text.getXDirAdj();
        double baselineY = text.getYDirAdj();
        double width = text.getWidthDirAdj();
        double height = text.getHeightDir() > 0 ? text.getHeightDir() : text.getFontSizeInPt();
        double y = baselineY - height;
        if (y < 0) {
            y = 0;
        }

        double fontSize = text.getFontSizeInPt();
        if (fontSize <= 0) {
            fontSize = text.getFontSize();
        }
        if (fontSize <= 0) {
            fontSize = 12.0;
        }

        String fontName = "sans-serif";
        boolean isBold = false;
        boolean isItalic = false;

        PDFont font = text.getFont();
        if (font != null) {
            if (font.getName() != null) {
                fontName = font.getName();
            }

            PDFontDescriptor descriptor = font.getFontDescriptor();
            if (descriptor != null) {
                if (descriptor.isForceBold() || descriptor.getFontWeight() >= 700) {
                    isBold = true;
                }
                if (descriptor.isItalic()) {
                    isItalic = true;
                }
            }

            String lowerName = fontName.toLowerCase();
            if (lowerName.contains("bold") || lowerName.contains("black") || lowerName.contains("heavy")
                || lowerName.contains("w7") || lowerName.contains("w8") || lowerName.contains("w9")
                || lowerName.contains("demi") || lowerName.contains("semibold")) {
                isBold = true;
            }
            if (lowerName.contains("italic") || lowerName.contains("oblique")) {
                isItalic = true;
            }
        }

        String colorHex = extractColorHex();

        Glyph glyph = new Glyph(
            unicode,
            x,
            y,
            width,
            height,
            baselineY,
            fontName,
            fontSize,
            isBold,
            isItalic,
            colorHex
        );

        glyphs.add(glyph);
    }

    private String extractColorHex() {
        try {
            PDGraphicsState state = getGraphicsState();
            if (state != null) {
                PDColor nonStrokingColor = state.getNonStrokingColor();
                if (nonStrokingColor != null) {
                    float[] components = nonStrokingColor.getComponents();
                    if (components != null && components.length >= 3) {
                        int r = Math.min(255, Math.max(0, Math.round(components[0] * 255)));
                        int g = Math.min(255, Math.max(0, Math.round(components[1] * 255)));
                        int b = Math.min(255, Math.max(0, Math.round(components[2] * 255)));
                        return String.format("#%02x%02x%02x", r, g, b);
                    } else if (components != null && components.length == 1) {
                        int gray = Math.min(255, Math.max(0, Math.round(components[0] * 255)));
                        return String.format("#%02x%02x%02x", gray, gray, gray);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "#000000";
    }

    public List<Glyph> getCollectedGlyphs() {
        return Collections.unmodifiableList(glyphs);
    }

    public void clear() {
        this.glyphs.clear();
    }

    public synchronized List<Glyph> collectPage(PDDocument document, int pageNumber) throws IOException {
        if (document == null) {
            throw new IllegalArgumentException("document cannot be null");
        }
        List<Glyph> pageGlyphs = new ArrayList<>();
        CharacterCollector stripper = new CharacterCollector(pageGlyphs);
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        stripper.writeText(document, new StringWriter());
        return pageGlyphs;
    }
}
