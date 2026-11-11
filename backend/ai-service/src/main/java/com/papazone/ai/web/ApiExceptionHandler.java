package com.papazone.ai.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Same error shape as the other services: {"message": "..."}.
 * Only our own exceptions are handled here; Spring's built-in handling keeps
 * producing the correct 400 / 405 / 415 for malformed requests.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AiException.class)
    ResponseEntity<Map<String, String>> handle(AiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("message", e.getMessage()));
    }
}
