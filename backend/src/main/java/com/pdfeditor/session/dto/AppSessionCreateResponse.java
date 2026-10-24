package com.pdfeditor.session.dto;

public class AppSessionCreateResponse {
    private final String id;

    public AppSessionCreateResponse(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
