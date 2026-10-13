package com.pdfeditor.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ListBlock extends Block {
    private boolean ordered;
    private List<ParagraphBlock> items;

    public ListBlock() {
        super(BlockType.LIST, new double[4]);
        this.ordered = false;
        this.items = new ArrayList<>();
    }

    public ListBlock(double[] bbox, boolean ordered, List<ParagraphBlock> items) {
        super(BlockType.LIST, bbox);
        this.ordered = ordered;
        this.items = items != null ? items : new ArrayList<>();
    }

    public ListBlock(String id, double[] bbox, boolean ordered, List<ParagraphBlock> items) {
        super(id, BlockType.LIST, bbox);
        this.ordered = ordered;
        this.items = items != null ? items : new ArrayList<>();
    }

    public boolean isOrdered() {
        return ordered;
    }

    public void setOrdered(boolean ordered) {
        this.ordered = ordered;
    }

    public List<ParagraphBlock> getItems() {
        return items;
    }

    public void setItems(List<ParagraphBlock> items) {
        this.items = items != null ? items : new ArrayList<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        ListBlock listBlock = (ListBlock) o;
        return ordered == listBlock.ordered &&
               Objects.equals(items, listBlock.items);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), ordered, items);
    }
}
