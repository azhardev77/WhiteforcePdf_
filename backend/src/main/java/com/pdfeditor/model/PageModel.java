package com.pdfeditor.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageModel {
    private int pageNumber;
    private double width;
    private double height;
    private List<Block> blocks;

    public PageModel() {
        this.pageNumber = 1;
        this.width = 612.0;
        this.height = 792.0;
        this.blocks = new ArrayList<>();
    }

    public PageModel(int pageNumber, double width, double height, List<Block> blocks) {
        this.pageNumber = pageNumber;
        this.width = width;
        this.height = height;
        this.blocks = blocks != null ? blocks : new ArrayList<>();
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(int pageNumber) {
        this.pageNumber = pageNumber;
    }

    public double getWidth() {
        return width;
    }

    public void setWidth(double width) {
        this.width = width;
    }

    public double getHeight() {
        return height;
    }

    public void setHeight(double height) {
        this.height = height;
    }

    public List<Block> getBlocks() {
        return blocks;
    }

    public void setBlocks(List<Block> blocks) {
        this.blocks = blocks != null ? blocks : new ArrayList<>();
    }

    public void addBlock(Block block) {
        if (block != null) {
            this.blocks.add(block);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PageModel pageModel = (PageModel) o;
        return pageNumber == pageModel.pageNumber &&
               Double.compare(pageModel.width, width) == 0 &&
               Double.compare(pageModel.height, height) == 0 &&
               Objects.equals(blocks, pageModel.blocks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageNumber, width, height, blocks);
    }

    @Override
    public String toString() {
        return "PageModel{" +
               "pageNumber=" + pageNumber +
               ", width=" + width +
               ", height=" + height +
               ", blocks=" + blocks +
               '}';
    }
}
