package com.papazone.ai.client;

import com.papazone.ai.config.AiProperties;
import com.papazone.ai.model.OrderRow;
import com.papazone.ai.web.AiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;

/** Read-only access to order-service. */
@Component
public class OrderClient {

    private static final Logger log = LoggerFactory.getLogger(OrderClient.class);

    private final RestClient http;
    private final String baseUrl;
    private final String internalKey;

    public OrderClient(@Qualifier("internalRestClient") RestClient http, AiProperties props) {
        this.http = http;
        this.baseUrl = props.services().orderUrl();
        this.internalKey = props.security().internalKey();
    }

    /** All orders created in [from, to). Uses the internal export endpoint (shared-secret protected). */
    public List<OrderRow> export(Instant from, Instant to) {
        if (internalKey == null || internalKey.isBlank()) {
            throw new AiException(503, "INTERNAL_API_KEY is not configured for ai-service.");
        }

        try {
            List<OrderRow> rows = http.get()
                    .uri(baseUrl + "/orders/export?from={from}&to={to}", from.toString(), to.toString())
                    .header("X-Internal-Key", internalKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<OrderRow>>() {
                    });

            return rows == null ? List.of() : rows;

        } catch (RestClientException e) {
            log.error("Order export failed: {}", e.getMessage());
            throw new AiException(502, "Order data is not available right now.");
        }
    }

    /** One customer's orders (existing public endpoint). */
    public List<OrderRow> customerOrders(long customerId) {
        try {
            List<OrderRow> rows = http.get()
                    .uri(baseUrl + "/orders/customer/{id}", customerId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<OrderRow>>() {
                    });

            return rows == null ? List.of() : rows;

        } catch (RestClientException e) {
            log.warn("Could not load orders for customer {}: {}", customerId, e.getMessage());
            throw new AiException(502, "Order history is not available right now.");
        }
    }
}
