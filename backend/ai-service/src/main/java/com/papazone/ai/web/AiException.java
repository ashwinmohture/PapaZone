package com.papazone.ai.web;

/** An error with a customer-safe message and the HTTP status to return. */
public class AiException extends RuntimeException {

    private final int status;

    public AiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
