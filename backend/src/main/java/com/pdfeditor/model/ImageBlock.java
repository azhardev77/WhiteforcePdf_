package com.pdfeditor.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImageBlock extends Block {
    private String src;
    private String format;

    public ImageBlock() {
        super(BlockType.IMAGE, new double[4]);
        this.src = "";
        this.format = "png";
    }

    public ImageBlock(double[] bbox, String src, String format) {
        super(BlockType.IMAGE, bbox);
        this.src = src != null ? src : "";
        this.format = format != null ? format : "png";
    }

    public ImageBlock(String id, double[] bbox, String src, String format) {
        super(id, BlockType.IMAGE, bbox);
        this.src = src != null ? src : "";
        this.format = format != null ? format : "png";
    }

    public String getSrc() {
        return src;
    }

    public void setSrc(String src) {
        this.src = src;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        ImageBlock imageBlock = (ImageBlock) o;
        return Objects.equals(src, imageBlock.src) &&
               Objects.equals(format, imageBlock.format);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), src, format);
    }
}
