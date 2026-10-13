package com.pdfeditor.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableBlock extends Block {
    private int rows;
    private int cols;
    private List<List<ParagraphBlock>> cells;

    public TableBlock() {
        super(BlockType.TABLE, new double[4]);
        this.rows = 0;
        this.cols = 0;
        this.cells = new ArrayList<>();
    }

    public TableBlock(double[] bbox, int rows, int cols, List<List<ParagraphBlock>> cells) {
        super(BlockType.TABLE, bbox);
        this.rows = rows;
        this.cols = cols;
        this.cells = cells != null ? cells : new ArrayList<>();
    }

    public TableBlock(String id, double[] bbox, int rows, int cols, List<List<ParagraphBlock>> cells) {
        super(id, BlockType.TABLE, bbox);
        this.rows = rows;
        this.cols = cols;
        this.cells = cells != null ? cells : new ArrayList<>();
    }

    public int getRows() {
        return rows;
    }

    public void setRows(int rows) {
        this.rows = rows;
    }

    public int getCols() {
        return cols;
    }

    public void setCols(int cols) {
        this.cols = cols;
    }

    public List<List<ParagraphBlock>> getCells() {
        return cells;
    }

    public void setCells(List<List<ParagraphBlock>> cells) {
        this.cells = cells != null ? cells : new ArrayList<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        TableBlock that = (TableBlock) o;
        return rows == that.rows &&
               cols == that.cols &&
               Objects.equals(cells, that.cells);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), rows, cols, cells);
    }
}
