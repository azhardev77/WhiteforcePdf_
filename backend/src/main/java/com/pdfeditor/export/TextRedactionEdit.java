package com.pdfeditor.export;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.pdfeditor.model.Style;

import java.util.Arrays;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TextRedactionEdit {

    private String id;
    private int pageNumber; // 1-indexed
    private double[] bbox; // [x, y, w, h] in PDF points (origin top-left)
    private String originalText;
    private String newText;
    private Style style;
    private String backgroundColor; // hex e.g. "#FFFFFF"
    private boolean transparentBackground = false; // When true, do not whiteout background (preserves dots/lines)
    /**
     * Effective rotation (0, 90, 180, 270) that was applied on the frontend canvas when
     * the edit was committed. Used by PdfRedactionService to inverse-transform the bbox
     * from viewport-space back to PDF-space when the page carries a /Rotate flag.
     * Defaults to 0 (no rotation).
     */
    private int pageRotation = 0;
    private String lineId;
    private boolean reflowed = false;

    public TextRedactionEdit() {
        this.pageNumber = 1;
        this.bbox = new double[4];
        this.backgroundColor = "#FFFFFF";
        this.transparentBackground = false;
    }

    public TextRedactionEdit(int pageNumber, double[] bbox, String originalText, String newText, Style style) {
        this.pageNumber = pageNumber;
        this.bbox = bbox != null ? bbox : new double[4];
        this.originalText = originalText;
        this.newText = newText;
        this.style = style;
        this.backgroundColor = "#FFFFFF";
        this.transparentBackground = false;
    }

    public TextRedactionEdit(int pageNumber, double[] bbox, String originalText, String newText, Style style, String backgroundColor) {
        this.pageNumber = pageNumber;
        this.bbox = bbox != null ? bbox : new double[4];
        this.originalText = originalText;
        this.newText = newText;
        this.style = style;
        this.backgroundColor = backgroundColor != null ? backgroundColor : "#FFFFFF";
        this.transparentBackground = false;
    }

    public TextRedactionEdit(int pageNumber, double[] bbox, String originalText, String newText, Style style, String backgroundColor, boolean transparentBackground) {
        this.pageNumber = pageNumber;
        this.bbox = bbox != null ? bbox : new double[4];
        this.originalText = originalText;
        this.newText = newText;
        this.style = style;
        this.backgroundColor = backgroundColor != null ? backgroundColor : "#FFFFFF";
        this.transparentBackground = transparentBackground;
        this.pageRotation = 0;
    }

    public TextRedactionEdit(int pageNumber, double[] bbox, String originalText, String newText, Style style, String backgroundColor, boolean transparentBackground, int pageRotation) {
        this.pageNumber = pageNumber;
        this.bbox = bbox != null ? bbox : new double[4];
        this.originalText = originalText;
        this.newText = newText;
        this.style = style;
        this.backgroundColor = backgroundColor != null ? backgroundColor : "#FFFFFF";
        this.transparentBackground = transparentBackground;
        this.pageRotation = ((pageRotation % 360) + 360) % 360;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(int pageNumber) {
        this.pageNumber = pageNumber;
    }

    public double[] getBbox() {
        return bbox;
    }

    public void setBbox(double[] bbox) {
        this.bbox = bbox != null ? bbox : new double[4];
    }

    public String getOriginalText() {
        return originalText;
    }

    public void setOriginalText(String originalText) {
        this.originalText = originalText;
    }

    public String getNewText() {
        return newText;
    }

    public void setNewText(String newText) {
        this.newText = newText;
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style;
    }

    public String getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(String backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    public boolean isTransparentBackground() {
        return transparentBackground;
    }

    public void setTransparentBackground(boolean transparentBackground) {
        this.transparentBackground = transparentBackground;
    }

    public int getPageRotation() {
        return pageRotation;
    }

    public void setPageRotation(int pageRotation) {
        this.pageRotation = ((pageRotation % 360) + 360) % 360;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLineId() {
        return lineId;
    }

    public void setLineId(String lineId) {
        this.lineId = lineId;
    }

    public boolean isReflowed() {
        return reflowed;
    }

    public void setReflowed(boolean reflowed) {
        this.reflowed = reflowed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TextRedactionEdit that = (TextRedactionEdit) o;
        return pageNumber == that.pageNumber &&
                transparentBackground == that.transparentBackground &&
                pageRotation == that.pageRotation &&
                Arrays.equals(bbox, that.bbox) &&
                Objects.equals(originalText, that.originalText) &&
                Objects.equals(newText, that.newText) &&
                Objects.equals(style, that.style) &&
                Objects.equals(backgroundColor, that.backgroundColor);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(pageNumber, originalText, newText, style, backgroundColor, transparentBackground, pageRotation);
        result = 31 * result + Arrays.hashCode(bbox);
        return result;
    }

    @Override
    public String toString() {
        return "TextRedactionEdit{" +
                "pageNumber=" + pageNumber +
                ", bbox=" + Arrays.toString(bbox) +
                ", originalText='" + originalText + '\'' +
                ", newText='" + newText + '\'' +
                ", style=" + style +
                ", backgroundColor='" + backgroundColor + '\'' +
                ", transparentBackground=" + transparentBackground +
                ", pageRotation=" + pageRotation +
                '}';
    }
}
