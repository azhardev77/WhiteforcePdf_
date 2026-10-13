package com.pdfeditor.extraction;

import java.util.Objects;

public record Glyph(
    String text,
    double x,
    double y,
    double width,
    double height,
    double baselineY,
    String fontName,
    double fontSize,
    boolean isBold,
    boolean isItalic,
    String color
) {
    public Glyph {
        if (text == null) {
            text = "";
        }
        if (fontName == null) {
            fontName = "sans-serif";
        }
        if (color == null) {
            color = "#000000";
        }
    }

    public Glyph(String text, double x, double y, double width, double height, String fontName, double fontSize, boolean isBold) {
        this(text, x, y, width, height, y + height, fontName, fontSize, isBold, false, "#000000");
    }

    public Glyph(String text, double x, double y, double width, double height, double baselineY, String fontName, double fontSize, boolean isBold) {
        this(text, x, y, width, height, baselineY, fontName, fontSize, isBold, false, "#000000");
    }

    public String getText() {
        return text;
    }

    public String getChar() {
        return text;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }

    public double getBaselineY() {
        return baselineY;
    }

    public String getFontName() {
        return fontName;
    }

    public double getFontSize() {
        return fontSize;
    }

    public boolean isBold() {
        return isBold;
    }

    public boolean isItalic() {
        return isItalic;
    }

    public String getColor() {
        return color;
    }
}
