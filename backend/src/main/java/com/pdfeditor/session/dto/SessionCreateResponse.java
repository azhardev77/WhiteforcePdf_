package com.pdfeditor.session.dto;

import java.time.Instant;

public class SessionCreateResponse {
    private final String id;
    private final String editUrl;
    private final Instant expiresAt;

    public SessionCreateResponse(String id, String editUrl, Instant expiresAt) {
        this.id = id;
        this.editUrl = editUrl;
        this.expiresAt = expiresAt;
    }

    public String getId() {
        return id;
    }

    public String getEditUrl() {
        return editUrl;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
