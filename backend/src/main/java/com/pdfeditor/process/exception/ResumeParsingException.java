package com.pdfeditor.process.exception;

import org.springframework.http.HttpStatus;

public class ResumeParsingException extends RuntimeException {

    private final HttpStatus status;

    public ResumeParsingException(HttpStatus status, String message) {
        super(message);
        this.status = status != null ? status : HttpStatus.BAD_REQUEST;
    }

    public ResumeParsingException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
    }

    public ResumeParsingException(String message) {
        this(HttpStatus.BAD_REQUEST, message);
    }

    public ResumeParsingException(String message, int statusCode) {
        this(HttpStatus.valueOf(statusCode), message);
    }

    public ResumeParsingException(String message, Throwable cause) {
        this(HttpStatus.INTERNAL_SERVER_ERROR, message, cause);
    }

    public ResumeParsingException(String message, Throwable cause, int statusCode) {
        this(HttpStatus.valueOf(statusCode), message, cause);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public int getStatusCode() {
        return status.value();
    }
}
