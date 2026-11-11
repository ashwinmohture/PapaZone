package com.papazone.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Mirror of menu-service's MenuItem JSON. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MenuEntry(
        Long id,
        String name,
        String description,
        String category,
        double price,
        String imageUrl,
        boolean available
) {
}
