package com.pdfeditor.process.dto;

import java.util.List;

public record ResumeProcessResponse(
        ExtractedDetails extractedDetails,
        String redactedPdfBase64,
        int pageCount
) {
    public record ExtractedDetails(
            List<String> emails,
            List<String> phones
    ) {}
}

