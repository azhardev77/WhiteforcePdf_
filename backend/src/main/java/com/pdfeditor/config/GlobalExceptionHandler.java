package com.pdfeditor.config;

import com.pdfeditor.session.SessionValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SessionValidationException.class)
    public ResponseEntity<Map<String, Object>> handleSessionValidationException(SessionValidationException ex, HttpServletRequest request) {
        String errorName = switch (ex.getStatusCode()) {
            case 413 -> "Payload Too Large";
            case 422 -> "Unprocessable Entity";
            default -> "Bad Request";
        };
        return buildResponse(ex.getStatusCode(), errorName, ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException ex, HttpServletRequest request) {
        return buildResponse(400, "Bad Request", ex.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return buildResponse(413, "Payload Too Large", "Maximum upload size exceeded: " + ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception processing request to [{}]: {}", request.getRequestURI(), ex.getMessage(), ex);
        String message = ex.getMessage() != null ? ex.getMessage() : "An unexpected server error occurred";
        return buildResponse(500, "Internal Server Error", message, request);
    }

    private ResponseEntity<Map<String, Object>> buildResponse(int status, String error, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status);
        body.put("error", error);
        body.put("message", message);
        body.put("path", request != null ? request.getRequestURI() : "");
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}

