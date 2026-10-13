package com.pdfeditor.document;

import com.pdfeditor.model.DocumentModel;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentModel> extract(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest().build();
        }

        DocumentModel documentModel = documentService.extractFromUpload(file);
        return ResponseEntity.ok(documentModel);
    }

    @PostMapping("/extract-sample")
    public ResponseEntity<DocumentModel> extractSample(@RequestParam("filename") String filename) throws IOException {
        if (filename == null || filename.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Path resolvedPath = null;

        // Try as an absolute or direct path first
        try {
            Path directPath = Paths.get(filename);
            if (Files.exists(directPath) && Files.isRegularFile(directPath)) {
                resolvedPath = directPath;
            }
        } catch (InvalidPathException ignored) {
        }

        // If not found, try under sample-pdfs/
        if (resolvedPath == null) {
            try {
                Path samplePath = Paths.get("sample-pdfs", filename);
                if (Files.exists(samplePath) && Files.isRegularFile(samplePath)) {
                    resolvedPath = samplePath;
                }
            } catch (InvalidPathException ignored) {
            }
        }

        if (resolvedPath == null) {
            return ResponseEntity.notFound().build();
        }

        DocumentModel documentModel = documentService.extractFromPath(resolvedPath);
        return ResponseEntity.ok(documentModel);
    }

    @org.springframework.web.bind.annotation.GetMapping(value = "/raw-sample", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getRawSample(@RequestParam("filename") String filename) throws IOException {
        if (filename == null || filename.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Path resolvedPath = null;
        try {
            Path directPath = Paths.get(filename);
            if (Files.exists(directPath) && Files.isRegularFile(directPath)) {
                resolvedPath = directPath;
            }
        } catch (InvalidPathException ignored) {
        }

        if (resolvedPath == null) {
            try {
                Path samplePath = Paths.get("sample-pdfs", filename);
                if (Files.exists(samplePath) && Files.isRegularFile(samplePath)) {
                    resolvedPath = samplePath;
                }
            } catch (InvalidPathException ignored) {
            }
        }

        if (resolvedPath == null) {
            return ResponseEntity.notFound().build();
        }

        byte[] bytes = Files.readAllBytes(resolvedPath);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .body(bytes);
    }
}
