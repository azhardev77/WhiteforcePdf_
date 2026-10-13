package com.pdfeditor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class HeadingBlock extends Block {
    private int level; // 1-6
    private List<TextRun> runs;

    public HeadingBlock() {
        super(BlockType.HEADING, new double[4]);
        this.level = 1;
        this.runs = new ArrayList<>();
    }

    public HeadingBlock(double[] bbox, int level, List<TextRun> runs) {
        super(BlockType.HEADING, bbox);
        this.level = clampLevel(level);
        this.runs = runs != null ? runs : new ArrayList<>();
    }

    public HeadingBlock(String id, double[] bbox, int level, List<TextRun> runs) {
        super(id, BlockType.HEADING, bbox);
        this.level = clampLevel(level);
        this.runs = runs != null ? runs : new ArrayList<>();
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = clampLevel(level);
    }

    public List<TextRun> getRuns() {
        return runs;
    }

    public void setRuns(List<TextRun> runs) {
        this.runs = runs != null ? runs : new ArrayList<>();
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

    private static int clampLevel(int lvl) {
        if (lvl < 1) return 1;
        if (lvl > 6) return 6;
        return lvl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        HeadingBlock that = (HeadingBlock) o;
        return level == that.level &&
               Objects.equals(runs, that.runs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), level, runs);
    }

    @Override
    public String toString() {
        return "HeadingBlock{" +
               "id='" + id + '\'' +
               ", level=" + level +
               ", runs=" + runs +
               ", bbox=" + (bbox != null ? java.util.Arrays.toString(bbox) : "null") +
               '}';
    }
}
