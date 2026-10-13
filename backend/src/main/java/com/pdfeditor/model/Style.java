package com.pdfeditor.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Style(
    String fontFamily,
    double fontSize,
    boolean bold,
    boolean italic,
    String color,
    double lineHeight
) {
    public static Style defaultStyle() {
        return new Style("sans-serif", 12.0, false, false, "#000000", 1.2);
    }

    public static Style of(String fontFamily, double fontSize, boolean bold, boolean italic) {
        return new Style(fontFamily, fontSize, bold, italic, "#000000", 1.2);
    }

    public static Style of(String fontFamily, double fontSize, boolean bold, boolean italic, String color) {
        return new Style(fontFamily, fontSize, bold, italic, color, 1.2);
    }
}
