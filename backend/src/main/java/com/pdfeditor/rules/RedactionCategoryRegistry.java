package com.pdfeditor.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Registry of available redaction categories.
 * Built only from existing detectors in PiiDetector.
 */
@Component
public class RedactionCategoryRegistry {

    private final Map<String, RedactionCategory> categories = new LinkedHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedactionCategoryRegistry() {
        register(new RedactionCategory(
                "email",
                "Email Addresses",
                true,
                text -> PiiDetector.findPIISpans(text).stream()
                        .filter(s -> "email".equalsIgnoreCase(s.getType()))
                        .toList()
        ));
        register(new RedactionCategory(
                "phone",
                "Phone Numbers",
                true,
                text -> PiiDetector.findPIISpans(text).stream()
                        .filter(s -> "phone".equalsIgnoreCase(s.getType()))
                        .toList()
        ));
    }

    public void register(RedactionCategory category) {
        categories.put(category.getId().toLowerCase(), category);
    }

    public List<RedactionCategory> getCategories() {
        return new ArrayList<>(categories.values());
    }

    public Optional<RedactionCategory> findById(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(categories.get(id.trim().toLowerCase()));
    }

    public boolean isValid(String id) {
        if (id == null) return false;
        return categories.containsKey(id.trim().toLowerCase());
    }

    public void validateCategoryIds(Collection<String> ids) {
        if (ids == null) return;
        for (String id : ids) {
            if (id == null || id.isBlank() || !isValid(id)) {
                throw new IllegalArgumentException("Unknown redaction category: '" + id + "'");
            }
        }
    }

    /**
     * Parses raw input list of category strings (may contain comma-separated values or JSON arrays)
     * and validates all category IDs against the registry.
     * Returns null if rawCategories is null (preserving backward compatibility).
     */
    public List<String> parseCategoryIds(List<String> rawCategories) {
        if (rawCategories == null) {
            return null;
        }

        List<String> result = new ArrayList<>();
        for (String raw : rawCategories) {
            if (raw == null) continue;
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                try {
                    JsonNode node = objectMapper.readTree(trimmed);
                    if (node.isArray()) {
                        for (JsonNode item : node) {
                            if (item.isTextual() && !item.asText().isBlank()) {
                                result.add(item.asText().trim().toLowerCase());
                            }
                        }
                        continue;
                    }
                } catch (Exception ignored) {
                }
            }

            if (trimmed.contains(",")) {
                String[] parts = trimmed.split(",");
                for (String p : parts) {
                    if (!p.isBlank()) {
                        result.add(p.trim().toLowerCase());
                    }
                }
            } else {
                result.add(trimmed.toLowerCase());
            }
        }

        validateCategoryIds(result);
        return result;
    }
}
