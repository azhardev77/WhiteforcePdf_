package com.pdfeditor.process.validation;

import com.pdfeditor.process.exception.ResumeParsingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Component
public class PdfFileValidator {

    private static final Logger log = LoggerFactory.getLogger(PdfFileValidator.class);
    private static final byte[] PDF_MAGIC_BYTES = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D}; // "%PDF-"
    private static final long DEFAULT_MAX_SIZE_BYTES = 20 * 1024 * 1024L; // 20 MB

    private final long maxSizeBytes;

    public PdfFileValidator(@Value("${pdfeditor.max-file-size-bytes:20971520}") long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes > 0 ? maxSizeBytes : DEFAULT_MAX_SIZE_BYTES;
    }

    public PdfFileValidator() {
        this(DEFAULT_MAX_SIZE_BYTES);
    }

    public byte[] validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            log.warn("Resume file validation failed: file is null or empty");
            throw new ResumeParsingException("File is required and cannot be empty", 400);
        }

        if (file.getSize() > maxSizeBytes) {
            log.warn("Resume file validation failed: size {} exceeds max limit of {}", file.getSize(), maxSizeBytes);
            throw new ResumeParsingException(
                    "File size exceeds maximum allowed size of " + maxSizeBytes + " bytes",
                    413
            );
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("Resume file validation failed: cannot read bytes from uploaded file", e);
            throw new ResumeParsingException("Failed to read file contents: " + e.getMessage(), e, 400);
        }

        validateBytes(bytes);
        return bytes;
    }

    public void validateBytes(byte[] bytes) {
        if (bytes == null || bytes.length < PDF_MAGIC_BYTES.length) {
            log.warn("Resume file validation failed: byte count is insufficient to be a PDF");
            throw new ResumeParsingException("Invalid file format: file is too small to be a PDF", 400);
        }

        for (int i = 0; i < PDF_MAGIC_BYTES.length; i++) {
            if (bytes[i] != PDF_MAGIC_BYTES[i]) {
                log.warn("Resume file validation failed: missing '%PDF-' header");
                throw new ResumeParsingException("Invalid file format: missing '%PDF-' header", 400);
            }
        }
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }
}
