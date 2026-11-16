package com.pdfeditor.process.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResumeProcessResponse(
        ExtractedDetails extractedDetails,
        String redactedPdfBase64,
        int pageCount,
        List<String> warnings,
        String fileName,
        String status,
        String remark,
        boolean isBatch,
        Integer totalFiles,
        Integer successCount,
        Integer errorCount,
        List<ResumeItemResult> batchResults,
        String outputZipBase64
) {
    public ResumeProcessResponse(ExtractedDetails extractedDetails, String redactedPdfBase64, int pageCount, List<String> warnings) {
        this(extractedDetails, redactedPdfBase64, pageCount, warnings, null, "SUCCESS", null, false, 1, 1, 0, null, null);
    }

    public ResumeProcessResponse(ExtractedDetails extractedDetails, String redactedPdfBase64, int pageCount) {
        this(extractedDetails, redactedPdfBase64, pageCount, Collections.emptyList(), null, "SUCCESS", null, false, 1, 1, 0, null, null);
    }

    public static ResumeProcessResponse single(
            String fileName,
            String status,
            String remark,
            ExtractedDetails extractedDetails,
            String redactedPdfBase64,
            int pageCount,
            List<String> warnings) {
        return new ResumeProcessResponse(
                extractedDetails,
                redactedPdfBase64,
                pageCount,
                warnings != null ? warnings : Collections.emptyList(),
                fileName,
                status != null ? status : "SUCCESS",
                remark,
                false,
                1,
                "SUCCESS".equalsIgnoreCase(status) ? 1 : 0,
                "SUCCESS".equalsIgnoreCase(status) ? 0 : 1,
                null,
                null
        );
    }

    public static ResumeProcessResponse batch(
            String fileName,
            int totalFiles,
            int successCount,
            int errorCount,
            List<ResumeItemResult> batchResults,
            String outputZipBase64,
            List<String> warnings) {
        java.util.List<String> allEmails = new java.util.ArrayList<>();
        java.util.List<String> allPhones = new java.util.ArrayList<>();
        if (batchResults != null) {
            for (ResumeItemResult item : batchResults) {
                if (item != null && item.extractedDetails() != null) {
                    if (item.extractedDetails().emails() != null) allEmails.addAll(item.extractedDetails().emails());
                    if (item.extractedDetails().phones() != null) allPhones.addAll(item.extractedDetails().phones());
                }
            }
        }
        String remark = String.format("Batch completed: %d total, %d success, %d error(s).", totalFiles, successCount, errorCount);
        String overallStatus = errorCount == 0 ? "SUCCESS" : (successCount > 0 ? "PARTIAL_SUCCESS" : "ERROR");

        return new ResumeProcessResponse(
                new ExtractedDetails(allEmails, allPhones),
                null,
                0,
                warnings != null ? warnings : Collections.emptyList(),
                fileName,
                overallStatus,
                remark,
                true,
                totalFiles,
                successCount,
                errorCount,
                batchResults != null ? batchResults : Collections.emptyList(),
                outputZipBase64
        );
    }

    public record ExtractedDetails(
            List<String> emails,
            List<String> phones
    ) {}
}
