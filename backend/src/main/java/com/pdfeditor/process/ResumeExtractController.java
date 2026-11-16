package com.pdfeditor.process;

import com.pdfeditor.process.dto.ResumeExtractResponse;
import com.pdfeditor.process.validation.PdfFileValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/resume")
public class ResumeExtractController {

    private static final Logger log = LoggerFactory.getLogger(ResumeExtractController.class);

    private final ResumeExtractionService resumeExtractionService;
    private final PdfFileValidator pdfFileValidator;

    public ResumeExtractController(ResumeExtractionService resumeExtractionService,
                                   PdfFileValidator pdfFileValidator) {
        this.resumeExtractionService = resumeExtractionService;
        this.pdfFileValidator = pdfFileValidator;
    }

    /**
     * POST /api/v1/resume/extract
     * Automated single-pass resume parsing and structured data extraction.
     */
    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResumeExtractResponse> extractResume(
            @RequestParam("file") MultipartFile file) {

        log.info("Received resume extraction request for file: {}",
                file != null ? file.getOriginalFilename() : "null");

        byte[] pdfBytes = pdfFileValidator.validate(file);
        String filename = (file != null && file.getOriginalFilename() != null)
                ? file.getOriginalFilename()
                : "resume.pdf";

        ResumeExtractResponse response = resumeExtractionService.extract(pdfBytes, filename);

        return ResponseEntity.ok(response);
    }
}

