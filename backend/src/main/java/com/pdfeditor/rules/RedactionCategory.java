package com.pdfeditor.rules;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Registry entry representing a selectable redaction category.
 * Built only from existing detectors.
 */
public class RedactionCategory {

    private final String id;
    private final String label;

    @JsonProperty("default")
    private final boolean defaultValue;

    @JsonIgnore
    private final Function<String, List<PiiDetector.PiiSpan>> detector;

    public RedactionCategory(String id, String label, boolean defaultValue, Function<String, List<PiiDetector.PiiSpan>> detector) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.label = Objects.requireNonNull(label, "label must not be null");
        this.defaultValue = defaultValue;
        this.detector = Objects.requireNonNull(detector, "detector must not be null");
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    @JsonProperty("default")
    public boolean isDefault() {
        return defaultValue;
    }

    @JsonIgnore
    public Function<String, List<PiiDetector.PiiSpan>> getDetector() {
        return detector;
    }
}

