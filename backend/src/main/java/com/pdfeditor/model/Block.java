package com.pdfeditor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type",
    visible = true
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ParagraphBlock.class, name = "PARAGRAPH"),
    @JsonSubTypes.Type(value = HeadingBlock.class, name = "HEADING"),
    @JsonSubTypes.Type(value = TableBlock.class, name = "TABLE"),
    @JsonSubTypes.Type(value = ListBlock.class, name = "LIST"),
    @JsonSubTypes.Type(value = ImageBlock.class, name = "IMAGE")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class Block {
    protected String id;
    protected BlockType type;
    protected double[] bbox; // [x, y, w, h]

    protected Block() {
        this.id = UUID.randomUUID().toString();
        this.bbox = new double[4];
    }

    protected Block(BlockType type, double[] bbox) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.bbox = bbox != null ? bbox : new double[4];
    }

    protected Block(String id, BlockType type, double[] bbox) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.type = type;
        this.bbox = bbox != null ? bbox : new double[4];
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public BlockType getType() {
        return type;
    }

    public void setType(BlockType type) {
        this.type = type;
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
        Block block = (Block) o;
        return Objects.equals(id, block.id) &&
               type == block.type &&
               Arrays.equals(bbox, block.bbox);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(id, type);
        result = 31 * result + Arrays.hashCode(bbox);
        return result;
    }

    @Override
    public String toString() {
        return "Block{" +
               "id='" + id + '\'' +
               ", type=" + type +
               ", bbox=" + Arrays.toString(bbox) +
               '}';
    }
}
