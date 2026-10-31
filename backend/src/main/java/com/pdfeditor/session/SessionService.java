package com.pdfeditor.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final long REDACTION_TIMEOUT_SECONDS = 15L;

    private final ConcurrentHashMap<String, PdfSession> sessionMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> inMemoryStorage = new ConcurrentHashMap<>();
    private final SessionProperties properties;
    private final SessionValidator sessionValidator;
    private final com.pdfeditor.rules.ResumeRedactionService resumeRedactionService;
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionService(SessionProperties properties,
                          SessionValidator sessionValidator,
                          @org.springframework.beans.factory.annotation.Autowired(required = false) com.pdfeditor.rules.ResumeRedactionService resumeRedactionService) {
        this.properties = properties;
        this.sessionValidator = sessionValidator;
        this.resumeRedactionService = resumeRedactionService;
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
        return createSession(file, mode, source, clientId, null);
    }

    public PdfSession createSession(MultipartFile file, Mode mode, Source source, String clientId, List<String> redactCategories) throws IOException {
        byte[] pdfBytes = sessionValidator.validate(file);

        if (mode == Mode.RESUME && resumeRedactionService != null) {
            final byte[] inputBytes = pdfBytes;
            try {
                CompletableFuture<byte[]> redactFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return resumeRedactionService.redactResume(inputBytes, redactCategories);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                pdfBytes = redactFuture.get(REDACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.error("Resume PII redaction timed out after {} seconds for mode RESUME", REDACTION_TIMEOUT_SECONDS);
                throw new SessionValidationException(
                        "Resume PII redaction timed out after " + REDACTION_TIMEOUT_SECONDS + " seconds. Please try with a smaller document or fewer categories.",
                        422);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof SessionValidationException sve) {
                    throw sve;
                }
                log.error("Resume PII redaction failed: {}", cause.getMessage(), cause);
                throw new SessionValidationException("Failed to redact resume: " + cause.getMessage(), 422);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SessionValidationException("Resume PII redaction was interrupted.", 500);
            }
        }

        String sessionId = generateSessionId();
        inMemoryStorage.put(sessionId, pdfBytes);
        Path filePath = null;
        try {
            Path storageDir = getStorageDir();
            filePath = storageDir.resolve(sessionId + ".pdf");
            Files.write(filePath, pdfBytes);
        } catch (Exception e) {
            log.warn("Disk storage unavailable for session {}: {}. Serving from in-memory storage.", sessionId, e.getMessage());
        }

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
            inMemoryStorage.remove(session.getId());
            return Optional.empty();
        }

        return Optional.of(session);
    }

    public byte[] getSessionPdfBytes(PdfSession session) throws IOException {
        byte[] inMemoryBytes = inMemoryStorage.get(session.getId());
        if (inMemoryBytes != null) {
            return inMemoryBytes;
        }

        Path filePath = session.getFilePath();
        if (filePath != null && Files.exists(filePath) && Files.isRegularFile(filePath)) {
            return Files.readAllBytes(filePath);
        }

        throw new IOException("Session file not found on disk or in-memory: " + session.getId());
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

    public ConcurrentHashMap<String, byte[]> getInMemoryStorage() {
        return inMemoryStorage;
    }
}
