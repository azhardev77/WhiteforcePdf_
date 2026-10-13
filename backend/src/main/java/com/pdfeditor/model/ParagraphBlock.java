package com.pdfeditor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ParagraphBlock extends Block {
    private List<TextRun> runs;
    private String textAlign;

    public ParagraphBlock() {
        super(BlockType.PARAGRAPH, new double[4]);
        this.runs = new ArrayList<>();
        this.textAlign = "left";
    }

    public ParagraphBlock(double[] bbox, List<TextRun> runs, String textAlign) {
        super(BlockType.PARAGRAPH, bbox);
        this.runs = runs != null ? runs : new ArrayList<>();
        this.textAlign = textAlign != null ? textAlign : "left";
    }

    public ParagraphBlock(String id, double[] bbox, List<TextRun> runs, String textAlign) {
        super(id, BlockType.PARAGRAPH, bbox);
        this.runs = runs != null ? runs : new ArrayList<>();
        this.textAlign = textAlign != null ? textAlign : "left";
    }

    public List<TextRun> getRuns() {
        return runs;
    }

    public void setRuns(List<TextRun> runs) {
        this.runs = runs != null ? runs : new ArrayList<>();
    }

    public String getTextAlign() {
        return textAlign;
    }

    public void setTextAlign(String textAlign) {
        this.textAlign = textAlign != null ? textAlign : "left";
    }

    public void addRun(TextRun run) {
        if (run != null) {
            this.runs.add(run);
        }
    }

    @JsonIgnore
    public String getText() {
        if (runs == null || runs.isEmpty()) {
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        ParagraphBlock that = (ParagraphBlock) o;
        return Objects.equals(runs, that.runs) &&
               Objects.equals(textAlign, that.textAlign);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), runs, textAlign);
    }

    @Override
    public String toString() {
        return "ParagraphBlock{" +
               "id='" + id + '\'' +
               ", textAlign='" + textAlign + '\'' +
               ", runs=" + runs +
               ", bbox=" + (bbox != null ? java.util.Arrays.toString(bbox) : "null") +
               '}';
    }
}
