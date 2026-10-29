package com.pdfeditor.process.dto;

public record ProcessResult(
    byte[] pdfBytes,
    int totalMatches,
    int warningsCount
) {}

