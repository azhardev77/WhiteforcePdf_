package com.pdfeditor.session;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

    private final ConcurrentHashMap<String, PdfSession> sessionMap = new ConcurrentHashMap<>();
    private final SessionProperties properties;
    private final SessionValidator sessionValidator;
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionService(SessionProperties properties, SessionValidator sessionValidator) {
        this.properties = properties;
        this.sessionValidator = sessionValidator;
    }

    public String generateSessionId() {
        byte[] bytes = new byte[16]; // 128 bits of entropy
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public Path getStorageDir() throws IOException {
        Path path = Paths.get(properties.getDir()).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
        return path;
    }

    public PdfSession createSession(MultipartFile file, Mode mode, Source source, String clientId) throws IOException {
        byte[] validPdfBytes = sessionValidator.validate(file);

        String sessionId = generateSessionId();
        Path storageDir = getStorageDir();
        Path filePath = storageDir.resolve(sessionId + ".pdf");
        Files.write(filePath, validPdfBytes);

        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plus(properties.getTtlMinutes(), ChronoUnit.MINUTES);

        PdfSession session = new PdfSession(
                sessionId,
                mode,
                source,
                clientId != null ? clientId.trim() : "anonymous",
                filePath,
                createdAt,
                expiresAt,
                SessionStatus.READY
        );

        sessionMap.put(sessionId, session);
        return session;
    }

    public Optional<PdfSession> getSession(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }

        PdfSession session = sessionMap.get(id);
        if (session == null) {
            return Optional.empty();
        }

        if (session.isExpired()) {
            session.setStatus(SessionStatus.EXPIRED);
            return Optional.empty();
        }

        return Optional.of(session);
    }

    public byte[] getSessionPdfBytes(PdfSession session) throws IOException {
        Path filePath = session.getFilePath();
        if (filePath == null || !Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new IOException("Session file not found on disk: " + session.getId());
        }
        return Files.readAllBytes(filePath);
    }

    public String buildEditUrl(String sessionId) {
        String base = properties.getPublicBaseUrl();
        if (base == null || base.isBlank()) {
            base = "http://localhost:5173";
        }
        base = base.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/edit/" + sessionId;
    }

    public ConcurrentHashMap<String, PdfSession> getSessionMap() {
        return sessionMap;
    }
}
