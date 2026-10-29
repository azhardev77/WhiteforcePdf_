package com.pdfeditor.export;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfeditor.model.DocumentModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final PdfExportService pdfExportService;
    private final PdfRedactionService pdfRedactionService;
    private final ObjectMapper objectMapper;

    public ExportController(PdfExportService pdfExportService,
                            PdfRedactionService pdfRedactionService,
                            ObjectMapper objectMapper) {
        this.pdfExportService = pdfExportService;
        this.pdfRedactionService = pdfRedactionService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @PostMapping(value = "/pdf", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exportPdf(@RequestBody DocumentModel documentModel) {
        if (documentModel == null) {
            return ResponseEntity.badRequest().build();
        }

        byte[] pdfBytes = pdfExportService.exportToPdf(documentModel);

        String filename = documentModel.getName();
        if (filename == null || filename.isBlank()) {
            filename = "document.pdf";
        } else if (!filename.toLowerCase().endsWith(".pdf")) {
            filename = filename + ".pdf";
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(pdfBytes.length);

        return ResponseEntity.ok()
            .headers(headers)
            .body(pdfBytes);
    }

    @PostMapping(value = "/html", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> exportHtml(@RequestBody DocumentModel documentModel) {
        if (documentModel == null) {
            return ResponseEntity.badRequest().build();
        }

        String html = pdfExportService.exportToHtml(documentModel);

        return ResponseEntity.ok()
            .contentType(MediaType.valueOf("text/html; charset=UTF-8"))
            .body(html);
    }

    @PostMapping(value = "/redact", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> redactPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "edits", required = false) String editsJson) throws IOException {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        List<TextRedactionEdit> edits = new ArrayList<>();
        if (editsJson != null && !editsJson.isBlank()) {
            edits = objectMapper.readValue(editsJson, new TypeReference<List<TextRedactionEdit>>() {});
        }

        byte[] originalBytes = file.getBytes();
        byte[] modifiedBytes = pdfRedactionService.redactAndReplace(originalBytes, edits);
        int warnings = pdfRedactionService.getLastReplacedCharCount();

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "edited_document.pdf";
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(modifiedBytes.length);
        if (warnings > 0) {
            headers.set("X-Export-Warnings", String.valueOf(warnings));
            headers.add("Access-Control-Expose-Headers", "X-Export-Warnings");
        }

        return ResponseEntity.ok()
            .headers(headers)
            .body(modifiedBytes);
    }

    @PostMapping(value = "/redact-json", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> redactPdfJson(@RequestBody RedactionJsonRequest request) throws IOException {
        if (request == null || request.getPdfBase64() == null || request.getPdfBase64().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        byte[] originalBytes = Base64.getDecoder().decode(request.getPdfBase64());
        byte[] modifiedBytes = pdfRedactionService.redactAndReplace(originalBytes, request.getEdits());
        int warnings = pdfRedactionService.getLastReplacedCharCount();

        String filename = request.getFilename();
        if (filename == null || filename.isBlank()) {
            filename = "edited_document.pdf";
        } else if (!filename.toLowerCase().endsWith(".pdf")) {
            filename = filename + ".pdf";
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(modifiedBytes.length);
        if (warnings > 0) {
            headers.set("X-Export-Warnings", String.valueOf(warnings));
            headers.add("Access-Control-Expose-Headers", "X-Export-Warnings");
        }

        return ResponseEntity.ok()
            .headers(headers)
            .body(modifiedBytes);
    }
}
