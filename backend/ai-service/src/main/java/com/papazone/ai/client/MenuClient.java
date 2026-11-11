package com.papazone.ai.client;

import com.papazone.ai.config.AiProperties;
import com.papazone.ai.model.MenuEntry;
import com.papazone.ai.web.AiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Reads the menu from menu-service, cached for 60 s (the chat needs it on every message). */
@Component
public class MenuClient {

    private static final Logger log = LoggerFactory.getLogger(MenuClient.class);
    private static final Duration TTL = Duration.ofSeconds(60);

    private record Snapshot(List<MenuEntry> items, Instant at) {
    }

    private final RestClient http;
    private final String baseUrl;
    private final Clock clock;
    private volatile Snapshot snapshot = new Snapshot(List.of(), Instant.EPOCH);

    public MenuClient(@Qualifier("internalRestClient") RestClient http, AiProperties props, Clock clock) {
        this.http = http;
        this.baseUrl = props.services().menuUrl();
        this.clock = clock;
    }

    /** Every menu item, including unavailable ones (needed to name old order lines). */
    public List<MenuEntry> allItems() {
        Snapshot current = snapshot;
        Instant now = clock.instant();

        if (!current.items().isEmpty() && Duration.between(current.at(), now).compareTo(TTL) < 0) {
            return current.items();
        }

        try {
            List<MenuEntry> fresh = http.get()
                    .uri(baseUrl + "/menu")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<MenuEntry>>() {
                    });

            snapshot = new Snapshot(fresh == null ? List.of() : List.copyOf(fresh), now);
            return snapshot.items();

        } catch (RestClientException e) {
            if (!current.items().isEmpty()) {
                log.warn("Menu service unavailable, serving stale menu: {}", e.getMessage());
                return current.items();
            }
            throw new AiException(503, "The menu is not available right now.");
        }
    }

    public List<MenuEntry> availableItems() {
        return allItems().stream().filter(MenuEntry::available).toList();
    }
}
