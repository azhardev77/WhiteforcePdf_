package com.pdfeditor.process.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResumeItemResult(
        String fileName,
        String status,
        String remark,
        ResumeProcessResponse.ExtractedDetails extractedDetails,
        int pageCount,
        String redactedPdfBase64
) {}
