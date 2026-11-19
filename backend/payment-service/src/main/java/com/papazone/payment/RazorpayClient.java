package com.papazone.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Thin wrapper over the Razorpay REST API (Basic auth with key id / secret). Mocked in unit tests. */
@Component
public class RazorpayClient {

    private static final String API = "https://api.razorpay.com/v1";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<Map<String, Object>>() {
            };

    @Value("${RAZORPAY_KEY_ID:}")
    private String keyId;

    @Value("${RAZORPAY_KEY_SECRET:}")
    private String keySecret;

    public boolean configured() {
        return keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank();
    }

    public String keyId() {
        return keyId;
    }

    public String keySecret() {
        return keySecret;
    }

    /** POST /orders. Throws RestClientResponseException when Razorpay rejects the request. */
    public Map<String, Object> createOrder(Map<String, Object> body) {
        return client().post().uri("/orders").body(body).retrieve().body(MAP_TYPE);
    }

    /** POST /payments/{id}/refund (full refund when no amount is given). */
    public Map<String, Object> refund(String paymentId, Map<String, Object> body) {
        return client().post().uri("/payments/{id}/refund", paymentId).body(body).retrieve().body(MAP_TYPE);
    }

    private RestClient client() {
        return RestClient.builder()
                .baseUrl(API)
                .defaultHeaders(h -> h.setBasicAuth(keyId, keySecret))
                .build();
    }
}
