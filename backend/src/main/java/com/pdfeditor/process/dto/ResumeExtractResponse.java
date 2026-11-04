package com.pdfeditor.process.dto;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record ResumeExtractResponse(
        String name,
        ContactInfo contact,
        List<String> skills,
        Map<String, List<String>> sections,
        Metadata metadata
) {
    public ResumeExtractResponse {
        skills = skills != null ? List.copyOf(skills) : Collections.emptyList();
        sections = sections != null ? Map.copyOf(sections) : Collections.emptyMap();
    }

    public record ContactInfo(
            List<String> emails,
            List<String> phones,
            List<String> links
    ) {
        public ContactInfo {
            emails = emails != null ? List.copyOf(emails) : Collections.emptyList();
            phones = phones != null ? List.copyOf(phones) : Collections.emptyList();
            links = links != null ? List.copyOf(links) : Collections.emptyList();
        }
    }

    public record Metadata(
            int pageCount,
            int wordCount
    ) {}
}
