package com.pdfeditor.document;

import com.pdfeditor.extraction.ExtractionPipeline;
import com.pdfeditor.model.DocumentModel;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class DocumentService {

    private final ExtractionPipeline extractionPipeline;

    public DocumentService(ExtractionPipeline extractionPipeline) {
        this.extractionPipeline = extractionPipeline;
    }

    public DocumentModel extractFromUpload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file cannot be empty");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF files are supported");
        }

        return extractionPipeline.extract(file.getBytes(), filename);
    }

    public DocumentModel extractFromPath(Path path) throws IOException {
        if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Specified path does not exist or is not a file: " + path);
        }

        return extractionPipeline.extract(path);
    }
}
