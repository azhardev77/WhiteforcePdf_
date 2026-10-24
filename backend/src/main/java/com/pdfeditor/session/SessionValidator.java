package com.pdfeditor.session;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Component
public class SessionValidator {

    private final SessionProperties properties;

    public SessionValidator(SessionProperties properties) {
        this.properties = properties;
    }

    public byte[] validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new SessionValidationException("File is required and cannot be empty", 400);
        }

        if (file.getSize() > properties.getMaxSizeBytes()) {
            throw new SessionValidationException(
                    "File size exceeds maximum allowed size of " + properties.getMaxSizeBytes() + " bytes",
                    413
            );
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new SessionValidationException("Failed to read file contents: " + e.getMessage(), 400);
        }

        validateBytes(bytes);
        return bytes;
    }

    public void validateBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            throw new SessionValidationException("Invalid file format: file is too small to be a PDF", 400);
        }

        // 1. Magic bytes check: first 4 bytes must be "%PDF" (0x25, 0x50, 0x44, 0x46)
        if (bytes[0] != 0x25 || bytes[1] != 0x50 || bytes[2] != 0x44 || bytes[3] != 0x46) {
            throw new SessionValidationException("Invalid file format: missing '%PDF' header", 400);
        }

        // 2. Loadable by PDFBox and check password protection
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw new SessionValidationException("Password-protected or encrypted PDFs are not supported", 422);
            }
        } catch (InvalidPasswordException e) {
            throw new SessionValidationException("Password-protected or encrypted PDFs are not supported", 422);
        } catch (SessionValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new SessionValidationException("Corrupted or invalid PDF document: " + e.getMessage(), 400);
        }
    }
}
