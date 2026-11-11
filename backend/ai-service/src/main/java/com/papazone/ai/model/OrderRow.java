package com.papazone.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Subset of order-service's order JSON. Works for both
 * GET /orders/customer/{id} and GET /orders/export.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderRow(
        Long id,
        Long customerId,
        Instant createdAt,
        String status,
        double subtotal,
        double total,
        String itemsJson
) {

    public boolean cancelled() {
        return "CANCELLED".equalsIgnoreCase(status);
    }
}
