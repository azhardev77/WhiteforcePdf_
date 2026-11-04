package com.pdfeditor.process.exception;

public class ResumeParsingException extends RuntimeException {

    private final int statusCode;

    public ResumeParsingException(String message) {
        this(message, 400);
    }

    public ResumeParsingException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public ResumeParsingException(String message, Throwable cause) {
        this(message, cause, 500);
    }

    public ResumeParsingException(String message, Throwable cause, int statusCode) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
