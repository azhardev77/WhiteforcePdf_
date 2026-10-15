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

        // If not found, try under sample-pdfs/ or ../sample-pdfs/
        if (resolvedPath == null) {
            Path[] candidates = new Path[] {
                Paths.get("sample-pdfs", filename),
                Paths.get("..", "sample-pdfs", filename)
            };
            for (Path candidate : candidates) {
                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    resolvedPath = candidate;
                    break;
                }
            }
        }

        if (resolvedPath == null) {
            return ResponseEntity.notFound().build();
        }

        DocumentModel documentModel = documentService.extractFromPath(resolvedPath);
        return ResponseEntity.ok(documentModel);
    }

    @org.springframework.web.bind.annotation.GetMapping("/raw-sample")
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
            Path[] candidates = new Path[] {
                Paths.get("sample-pdfs", filename),
                Paths.get("..", "sample-pdfs", filename)
            };
            for (Path candidate : candidates) {
                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    resolvedPath = candidate;
                    break;
                }
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
