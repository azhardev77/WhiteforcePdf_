package com.pdfeditor.extraction;

import com.pdfeditor.model.TextRun;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class Line {
    private final List<TextRun> runs;
    private final double[] bbox; // [x, y, w, h]
    private final double baselineY;
    private final double primaryFontSize;
    private final List<Word> words;

    public Line(List<TextRun> runs, double[] bbox, double baselineY, double primaryFontSize) {
        this(runs, bbox, baselineY, primaryFontSize, new ArrayList<>());
    }

    public Line(List<TextRun> runs, double[] bbox, double baselineY, double primaryFontSize, List<Word> words) {
        this.runs = runs != null ? runs : new ArrayList<>();
        this.bbox = bbox != null ? bbox : new double[4];
        this.baselineY = baselineY;
        this.primaryFontSize = primaryFontSize;
        this.words = words != null ? words : new ArrayList<>();
    }

    public List<TextRun> getRuns() {
        return runs;
    }

    public double[] getBbox() {
        return bbox;
    }

    public double getBaselineY() {
        return baselineY;
    }

    public double getPrimaryFontSize() {
        return primaryFontSize;
    }

    public List<Word> getWords() {
        return words;
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

    public String getText() {
        if (runs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.size(); i++) {
            if (i > 0 && !sb.isEmpty() && !sb.toString().endsWith(" ") && !runs.get(i).getText().startsWith(" ")) {
                sb.append(" ");
            }
            sb.append(runs.get(i).getText());
        }
        return sb.toString();
    }

    public boolean isBold() {
        if (runs != null) {
            for (TextRun run : runs) {
                if (run.getStyle() != null && run.getStyle().bold()) {
                    return true;
                }
            }
        }
        if (words != null) {
            for (Word word : words) {
                if (word.getStyle() != null && word.getStyle().bold()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Line line = (Line) o;
        return Double.compare(line.baselineY, baselineY) == 0 &&
                Double.compare(line.primaryFontSize, primaryFontSize) == 0 &&
                Objects.equals(runs, line.runs) &&
                Arrays.equals(bbox, line.bbox);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(runs, baselineY, primaryFontSize);
        result = 31 * result + Arrays.hashCode(bbox);
        return result;
    }

    @Override
    public String toString() {
        return "Line{" +
                "text='" + getText() + '\'' +
                ", bbox=" + Arrays.toString(bbox) +
                ", baselineY=" + baselineY +
                ", primaryFontSize=" + primaryFontSize +
                ", runs=" + runs +
                '}';
    }
}
