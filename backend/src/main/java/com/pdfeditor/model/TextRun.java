package com.pdfeditor.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Arrays;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextRun {
    private String text;
    private Style style;
    private double[] bbox; // [x, y, w, h]

    public TextRun() {
        this.bbox = new double[4];
    }

    public TextRun(String text, Style style, double[] bbox) {
        this.text = text;
        this.style = style;
        this.bbox = bbox != null ? bbox : new double[4];
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style;
    }

    public double[] getBbox() {
        return bbox;
    }

    public void setBbox(double[] bbox) {
        this.bbox = bbox != null ? bbox : new double[4];
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TextRun textRun = (TextRun) o;
        return Objects.equals(text, textRun.text) &&
               Objects.equals(style, textRun.style) &&
               Arrays.equals(bbox, textRun.bbox);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(text, style);
        result = 31 * result + Arrays.hashCode(bbox);
        return result;
    }

    @Override
    public String toString() {
        return "TextRun{" +
               "text='" + text + '\'' +
               ", style=" + style +
               ", bbox=" + Arrays.toString(bbox) +
               '}';
    }
}
