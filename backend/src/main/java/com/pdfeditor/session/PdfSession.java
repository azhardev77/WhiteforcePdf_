package com.pdfeditor.session;

import java.nio.file.Path;
import java.time.Instant;

public class PdfSession {
    private final String id;
    private final Mode mode;
    private final Source source;
    private final String clientId;
    private final Path filePath;
    private final Instant createdAt;
    private final Instant expiresAt;
    private SessionStatus status;

    public PdfSession(String id, Mode mode, Source source, String clientId, Path filePath,
                      Instant createdAt, Instant expiresAt, SessionStatus status) {
        this.id = id;
        this.mode = mode;
        this.source = source;
        this.clientId = clientId;
        this.filePath = filePath;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = status;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public String getId() {
        return id;
    }

    public Mode getMode() {
        return mode;
    }

    public Source getSource() {
        return source;
    }

    public String getClientId() {
        return clientId;
    }

    public Path getFilePath() {
        return filePath;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
    }
}
