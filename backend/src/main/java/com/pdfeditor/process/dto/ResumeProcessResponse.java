package com.pdfeditor.process.dto;

import java.util.Collections;
import java.util.List;

public record ResumeProcessResponse(
        ExtractedDetails extractedDetails,
        String redactedPdfBase64,
        int pageCount,
        List<String> warnings
) {
    public ResumeProcessResponse(ExtractedDetails extractedDetails, String redactedPdfBase64, int pageCount) {
        this(extractedDetails, redactedPdfBase64, pageCount, Collections.emptyList());
    }

    public record ExtractedDetails(
            List<String> emails,
            List<String> phones
    ) {}
}
