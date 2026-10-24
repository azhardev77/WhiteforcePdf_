package com.pdfeditor.session;

public class SessionValidationException extends RuntimeException {
    private final int statusCode;

    public SessionValidationException(String message) {
        this(message, 400);
    }

    public SessionValidationException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
