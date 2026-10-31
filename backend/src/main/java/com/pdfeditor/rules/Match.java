package com.pdfeditor.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class Match {
    private int pageNumber;
    private String type;
    private String matchedText;
    private double[] bbox; // [x, y, w, h] in top-left PDF points
    private List<double[]> lineBboxes; // One bbox per run/line spanned
    private double[] expandedBbox;
    private List<double[]> expandedLineBboxes;

    public Match() {
        this.bbox = new double[4];
        this.lineBboxes = new ArrayList<>();
    }

    public Match(int pageNumber, String type, String matchedText, double[] bbox, List<double[]> lineBboxes) {
        this.pageNumber = pageNumber;
        this.type = type;
        this.matchedText = matchedText;
        this.bbox = bbox != null ? bbox : new double[4];
        this.lineBboxes = lineBboxes != null ? lineBboxes : new ArrayList<>();
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(int pageNumber) {
        this.pageNumber = pageNumber;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMatchedText() {
        return matchedText;
    }

    public void setMatchedText(String matchedText) {
        this.matchedText = matchedText;
    }

    public double[] getBbox() {
        return bbox;
    }

    public void setBbox(double[] bbox) {
        this.bbox = bbox != null ? bbox : new double[4];
    }

    public List<double[]> getLineBboxes() {
        return lineBboxes;
    }

    public void setLineBboxes(List<double[]> lineBboxes) {
        this.lineBboxes = lineBboxes != null ? lineBboxes : new ArrayList<>();
    }

    public double[] getExpandedBbox() {
        return expandedBbox != null ? expandedBbox : bbox;
    }

    public void setExpandedBbox(double[] expandedBbox) {
        this.expandedBbox = expandedBbox;
    }

    public List<double[]> getExpandedLineBboxes() {
        return expandedLineBboxes != null ? expandedLineBboxes : lineBboxes;
    }

    public void setExpandedLineBboxes(List<double[]> expandedLineBboxes) {
        this.expandedLineBboxes = expandedLineBboxes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Match match = (Match) o;
        return pageNumber == match.pageNumber &&
                Objects.equals(type, match.type) &&
                Objects.equals(matchedText, match.matchedText) &&
                Arrays.equals(bbox, match.bbox);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(pageNumber, type, matchedText);
        result = 31 * result + Arrays.hashCode(bbox);
        return result;
    }

    @Override
    public String toString() {
        return "Match{" +
                "pageNumber=" + pageNumber +
                ", type='" + type + '\'' +
                ", matchedText='" + matchedText + '\'' +
                ", bbox=" + Arrays.toString(bbox) +
                ", lineBboxes=" + lineBboxes.size() +
                '}';
    }
}
