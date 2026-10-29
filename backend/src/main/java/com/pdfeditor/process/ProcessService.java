package com.pdfeditor.process;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfeditor.export.PdfRedactionService;
import com.pdfeditor.export.TextRedactionEdit;
import com.pdfeditor.extraction.ExtractionPipeline;
import com.pdfeditor.model.DocumentModel;
import com.pdfeditor.process.dto.ProcessOperation;
import com.pdfeditor.process.dto.ProcessResult;
import com.pdfeditor.rules.ResumeRedactionService;
import com.pdfeditor.rules.ResumeRules;
import com.pdfeditor.session.SessionValidationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class ProcessService {

    private final ExtractionPipeline extractionPipeline;
    private final ResumeRules resumeRules;
    private final ResumeRedactionService resumeRedactionService;
    private final PdfRedactionService pdfRedactionService;
    private final ObjectMapper objectMapper;

    public ProcessService(ExtractionPipeline extractionPipeline,
                          ResumeRules resumeRules,
                          ResumeRedactionService resumeRedactionService,
                          PdfRedactionService pdfRedactionService,
                          ObjectMapper objectMapper) {
        this.extractionPipeline = extractionPipeline;
        this.resumeRules = resumeRules;
        this.resumeRedactionService = resumeRedactionService;
        this.pdfRedactionService = pdfRedactionService;
        this.objectMapper = objectMapper;
    }

    public List<ProcessOperation> parseAndValidateOps(String opsJson) {
        if (opsJson == null || opsJson.isBlank()) {
            throw new SessionValidationException("'ops' parameter is required and cannot be empty", 400);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(opsJson);
        } catch (JsonProcessingException e) {
            throw new SessionValidationException("Invalid JSON for 'ops' parameter: " + e.getOriginalMessage(), 400);
        }

        if (!root.isArray()) {
            throw new SessionValidationException("'ops' parameter must be a JSON array", 400);
        }

        List<ProcessOperation> operations = new ArrayList<>();
        for (int i = 0; i < root.size(); i++) {
            JsonNode item = root.get(i);
            if (!item.isObject()) {
                throw new SessionValidationException("Each item in 'ops' must be a JSON object", 400);
            }

            JsonNode typeNode = item.get("type");
            if (typeNode == null || typeNode.asText().isBlank()) {
                throw new SessionValidationException("Missing or empty 'type' in operation at index " + i, 400);
            }

            String type = typeNode.asText().trim();
            if ("REDACT_PII".equalsIgnoreCase(type)) {
                operations.add(new ProcessOperation("REDACT_PII", null, null));
            } else if ("REPLACE_TEXT".equalsIgnoreCase(type)) {
                JsonNode findNode = item.get("find");
                if (findNode == null || findNode.asText().isEmpty()) {
                    throw new SessionValidationException("'find' string cannot be empty in REPLACE_TEXT operation at index " + i, 400);
                }
                String find = findNode.asText();
                JsonNode replaceNode = item.get("replace");
                String replace = (replaceNode != null && !replaceNode.isNull()) ? replaceNode.asText() : "";
                operations.add(new ProcessOperation("REPLACE_TEXT", find, replace));
            } else {
                throw new SessionValidationException("Unknown operation type: " + type, 400);
            }
        }

        return operations;
    }

    public ProcessResult processPdf(byte[] originalPdfBytes, List<ProcessOperation> operations) throws IOException {
        byte[] currentBytes = originalPdfBytes;
        int totalMatches = 0;

        for (ProcessOperation op : operations) {
            if ("REDACT_PII".equalsIgnoreCase(op.getType())) {
                currentBytes = resumeRedactionService.redactResume(currentBytes);
            } else if ("REPLACE_TEXT".equalsIgnoreCase(op.getType())) {
                DocumentModel docModel = extractionPipeline.extract(currentBytes, "process.pdf");
                ResumeRules.ReplacementMatch match = resumeRules.findTextReplacementEdits(docModel, op.getFind(), op.getReplace());
                totalMatches += match.matchCount();
                if (!match.edits().isEmpty()) {
                    currentBytes = pdfRedactionService.redactAndReplace(currentBytes, match.edits());
                }
            }
        }

        int warnings = pdfRedactionService.getLastReplacedCharCount();
        return new ProcessResult(currentBytes, totalMatches, warnings);
    }
}

