package com.papazone.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Publishes order.created, creates the delivery and clears the customer's cart. Failures never block the order. */
@Component
public class HttpOrderFulfillment implements OrderFulfillment {

    private final OrderEventPublisher events;
    private final RestClient http = RestClient.create();

    @Value("${CART_SERVICE_URL:http://localhost:8083}")
    String cartUrl;

    @Value("${DELIVERY_SERVICE_URL:http://localhost:8086}")
    String deliveryUrl;

    public HttpOrderFulfillment(OrderEventPublisher events) {
        this.events = events;
    }

    @Override
    public void onConfirmed(OrderEntity o) {
        events.publish(
                "{\"eventType\":\"order.created\",\"orderId\":" + o.getId()
                        + ",\"customerId\":" + o.getCustomerId()
                        + ",\"total\":" + o.getTotal() + "}"
        );

        try {
            http.post()
                    .uri(deliveryUrl + "/deliveries")
                    .body(Map.of(
                            "orderId", o.getId(),
                            "customerId", o.getCustomerId(),
                            "address", o.getAddress()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ignored) {
        }

        try {
            http.delete()
                    .uri(cartUrl + "/cart/" + o.getCustomerId())
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ignored) {
        }
    }
}
