package com.doorstep.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Every error leaves the API as {timestamp, status, code, message, path, details?}. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e, HttpServletRequest req) {
        return body(e.getStatus(), e.getCode(), e.getMessage(), req, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).toList();
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request validation failed", req, details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingRequestHeaderException.class})
    public ResponseEntity<Map<String, Object>> handleMalformed(Exception e, HttpServletRequest req) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Malformed or missing input: " + shortMessage(e), req, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e, HttpServletRequest req) {
        log.error("Unhandled error on {}", req.getRequestURI(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error", req, null);
    }

    private static String shortMessage(Exception e) {
        if (e instanceof MissingServletRequestParameterException m) {
            return "parameter '" + m.getParameterName() + "' is required";
        }
        if (e instanceof MethodArgumentTypeMismatchException m) {
            return "parameter '" + m.getName() + "' has an invalid value";
        }
        if (e instanceof MissingRequestHeaderException m) {
            return "header '" + m.getHeaderName() + "' is required";
        }
        return "request body is not valid JSON or has a bad field format";
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message,
                                                            HttpServletRequest req, List<String> details) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("timestamp", Instant.now().toString());
        b.put("status", status.value());
        b.put("code", code);
        b.put("message", message);
        b.put("path", req.getRequestURI());
        if (details != null) {
            b.put("details", details);
        }
        return ResponseEntity.status(status).body(b);
    }
}
