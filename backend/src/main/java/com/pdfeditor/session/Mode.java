package com.pdfeditor.session;

public enum Mode {
    RESUME,
    TENDER,
    EDIT,
    MARK;

    public static Mode fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Mode cannot be null or empty");
        }
        for (Mode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Invalid mode: " + value + ". Allowed values: RESUME, TENDER, EDIT, MARK");
    }
}
