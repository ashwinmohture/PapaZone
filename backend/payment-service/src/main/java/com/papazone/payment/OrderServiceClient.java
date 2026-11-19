package com.papazone.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Tells order-service that an order has been paid (used by the webhook). Throws on failure so Razorpay retries. */
@Component
public class OrderServiceClient {

    private final RestClient http = RestClient.create();

    @Value("${INTERNAL_API_KEY:}")
    private String internalKey;

    @Value("${ORDER_SERVICE_URL:http://localhost:8084}")
    private String orderUrl;

    public void paymentConfirmed(Long orderId) {
        http.post()
                .uri(orderUrl + "/orders/internal/" + orderId + "/payment-confirmed")
                .header("X-Internal-Key", internalKey)
                .retrieve()
                .toBodilessEntity();
    }
}
