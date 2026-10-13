package com.pdfeditor.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentModel {
    private String id;
    private String name;
    private List<PageModel> pages;

    public DocumentModel() {
        this.id = UUID.randomUUID().toString();
        this.name = "document.pdf";
        this.pages = new ArrayList<>();
    }

    public DocumentModel(String id, String name, List<PageModel> pages) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.name = name != null ? name : "document.pdf";
        this.pages = pages != null ? pages : new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<PageModel> getPages() {
        return pages;
    }

    public void setPages(List<PageModel> pages) {
        this.pages = pages != null ? pages : new ArrayList<>();
    }

    public void addPage(PageModel page) {
        if (page != null) {
            this.pages.add(page);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DocumentModel that = (DocumentModel) o;
        return Objects.equals(id, that.id) &&
               Objects.equals(name, that.name) &&
               Objects.equals(pages, that.pages);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, pages);
    }

    @Override
    public String toString() {
        return "DocumentModel{" +
               "id='" + id + '\'' +
               ", name='" + name + '\'' +
               ", pages=" + pages +
               '}';
    }
}
