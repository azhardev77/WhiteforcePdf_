package com.pdfeditor.session;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SessionServiceTest {

    private byte[] createMinimalPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    @Test
    @DisplayName("SessionService falls back to in-memory storage when disk storage path is invalid")
    void testInMemoryStorageFallback() throws IOException {
        SessionProperties props = new SessionProperties();
        // Set an invalid directory path on Windows that cannot be created
        props.setDir("Z:\\non\\existent\\invalid\\path\\that\\cannot\\be\\created");
        props.setTtlMinutes(60);
        props.setMaxSizeBytes(10_000_000);

        SessionValidator validator = new SessionValidator(props);
        SessionService service = new SessionService(props, validator, null);

        byte[] pdfBytes = createMinimalPdf();
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", pdfBytes);

        PdfSession session = service.createSession(file, Mode.EDIT, Source.UPLOAD, "test-client");

        assertThat(session).isNotNull();
        assertThat(session.getId()).isNotBlank();
        // Check that bytes were captured in in-memory storage fallback
        assertThat(service.getInMemoryStorage()).containsKey(session.getId());

        // Verify retrieval directly from memory storage
        byte[] retrievedBytes = service.getSessionPdfBytes(session);
        assertThat(retrievedBytes).isEqualTo(pdfBytes);
    }

    @Test
    @DisplayName("SessionService purges in-memory storage when session expires")
    void testInMemoryStorageExpirationCleanup() throws IOException {
        SessionProperties props = new SessionProperties();
        // Negative TTL forces immediate expiration
        props.setTtlMinutes(-1);
        props.setMaxSizeBytes(10_000_000);

        SessionValidator validator = new SessionValidator(props);
        SessionService service = new SessionService(props, validator, null);

        byte[] pdfBytes = createMinimalPdf();
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", pdfBytes);

        PdfSession session = service.createSession(file, Mode.EDIT, Source.UPLOAD, "test-client");
        service.getInMemoryStorage().put(session.getId(), pdfBytes);

        Optional<PdfSession> fetched = service.getSession(session.getId());
        assertThat(fetched).isEmpty();
        assertThat(service.getInMemoryStorage()).doesNotContainKey(session.getId());
    }
}

