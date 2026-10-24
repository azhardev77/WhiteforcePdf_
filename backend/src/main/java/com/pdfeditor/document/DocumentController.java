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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
        Path resolvedPath;
        try {
            resolvedPath = resolveSamplePdf(filename);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        if (resolvedPath == null || !Files.exists(resolvedPath) || !Files.isRegularFile(resolvedPath)) {
            return ResponseEntity.notFound().build();
        }

        DocumentModel documentModel = documentService.extractFromPath(resolvedPath);
        return ResponseEntity.ok(documentModel);
    }

    @org.springframework.web.bind.annotation.GetMapping("/raw-sample")
    public ResponseEntity<byte[]> getRawSample(@RequestParam("filename") String filename) throws IOException {
        Path resolvedPath;
        try {
            resolvedPath = resolveSamplePdf(filename);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        if (resolvedPath == null || !Files.exists(resolvedPath) || !Files.isRegularFile(resolvedPath)) {
            return ResponseEntity.notFound().build();
        }

        byte[] bytes = Files.readAllBytes(resolvedPath);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .body(bytes);
    }

    private Path resolveSamplePdf(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename must not be empty");
        }

        // Decode URL-encoded or double URL-encoded sequences
        String decoded = filename;
        try {
            while (decoded.contains("%")) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL encoding in filename: " + filename);
        }

        // Check for null characters
        if (decoded.contains("\0")) {
            throw new IllegalArgumentException("Filename contains null character: " + filename);
        }

        // Reject directory traversal sequences
        if (decoded.contains("..")) {
            throw new IllegalArgumentException("Directory traversal '..' is not permitted: " + filename);
        }

        // Reject absolute paths
        try {
            Path pathObj = Paths.get(decoded);
            if (pathObj.isAbsolute()) {
                throw new IllegalArgumentException("Absolute paths are not permitted: " + filename);
            }
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid path syntax: " + filename);
        }

        // Locate sample-pdfs directory
        Path baseDir = findSamplePdfsDirectory();
        if (baseDir == null) {
            return null;
        }

        Path resolved = baseDir.resolve(decoded).normalize();
        if (!resolved.startsWith(baseDir) || resolved.equals(baseDir)) {
            throw new IllegalArgumentException("Path escapes sample-pdfs directory: " + filename);
        }

        return resolved;
    }

    private Path findSamplePdfsDirectory() {
        Path[] candidates = new Path[] {
            Paths.get("sample-pdfs").toAbsolutePath().normalize(),
            Paths.get("..", "sample-pdfs").toAbsolutePath().normalize(),
            Paths.get("backend", "sample-pdfs").toAbsolutePath().normalize()
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
