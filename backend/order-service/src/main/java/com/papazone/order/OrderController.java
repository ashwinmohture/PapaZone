
package com.papazone.order;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.*;

@RestController
@RequestMapping("/orders")
@CrossOrigin(origins = "*")
public class OrderController {

    private final OrderRepository repo;
    private final OrderEventPublisher events;
    private final RestClient http = RestClient.create();

    @Value("${CART_SERVICE_URL:http://localhost:8083}")
    String cartUrl;

    @Value("${PAYMENT_SERVICE_URL:http://localhost:8085}")
    String paymentUrl;

    @Value("${DELIVERY_SERVICE_URL:http://localhost:8086}")
    String deliveryUrl;

    private static final List<String> DELIVERY_STATUSES = List.of(
            "PLACED",
            "PREPARING",
            "READY_FOR_PICKUP",
            "PICKED_UP",
            "DELIVERED"
    );

    public OrderController(
            OrderRepository r,
            OrderEventPublisher events) {
        repo = r;
        this.events = events;
    }

    public record Checkout(
            Long customerId,
            String address,
            String paymentMethod
    ) {}

    // Checkout
    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestBody Checkout req) {

        if (req.customerId() == null ||
                req.address() == null ||
                req.address().isBlank()) {

            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "message",
                            "customerId and delivery address are required"
                    ));
        }

        try {

            Map cart = http.get()
                    .uri(cartUrl + "/cart/" + req.customerId())
                    .retrieve()
                    .body(Map.class);

            List items = (List) cart.get("items");

            if (items == null || items.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Cart is empty"));
            }

            double subtotal = Double.parseDouble(
                    cart.get("total").toString()
            );

            OrderEntity o = new OrderEntity();

            o.setCustomerId(req.customerId());
            o.setItemsJson(items.toString());
            o.setSubtotal(subtotal);
            o.setDeliveryFee(subtotal >= 499 ? 0 : 40);
            o.setTax(Math.round(subtotal * 0.05 * 100.0) / 100.0);
            o.setTotal(
                    o.getSubtotal() +
                            o.getDeliveryFee() +
                            o.getTax()
            );
            o.setAddress(req.address());

            o = repo.save(o);

            events.publish(
                    "{\"eventType\":\"order.created\",\"orderId\":" +
                            o.getId() +
                            ",\"customerId\":" +
                            o.getCustomerId() +
                            ",\"total\":" +
                            o.getTotal() + "}"
            );

            Map<String, Object> pay = Map.of(
                    "orderId", o.getId(),
                    "amount", o.getTotal(),
                    "method", req.paymentMethod() == null
                            ? "COD"
                            : req.paymentMethod()
            );

            try {
                http.post()
                        .uri(paymentUrl + "/payments")
                        .body(pay)
                        .retrieve()
                        .toBodilessEntity();

            } catch (Exception ignored) {
            }

            try {
                http.post()
                        .uri(deliveryUrl + "/deliveries")
                        .body(Map.of(
                                "orderId", o.getId(),
                                "customerId", o.getCustomerId(),
                                "address", o.getAddress()
                        ))
                        .retrieve()
                        .toBodilessEntity();

            } catch (Exception ignored) {
            }

            try {
                http.delete()
                        .uri(cartUrl + "/cart/" + req.customerId())
                        .retrieve()
                        .toBodilessEntity();

            } catch (Exception ignored) {
            }

            return ResponseEntity.status(201).body(o);

        } catch (Exception e) {

            return ResponseEntity.status(502)
                    .body(Map.of(
                            "message",
                            "Checkout dependency failed: " + e.getMessage()
                    ));
        }
    }

    // Get Order by ID
    @GetMapping("/{id}")
    public ResponseEntity<?> get(
            @PathVariable("id") Long id) {

        return repo.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Get Orders by Customer ID
    @GetMapping("/customer/{id}")
    public List<OrderEntity> byCustomer(
            @PathVariable("id") Long id) {

        return repo.findByCustomerIdOrderByCreatedAtDesc(id);
    }

    // Update Order Status
    @PatchMapping("/{id}/status")
    public ResponseEntity<?> status(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> b) {

        return repo.findById(id)
                .map(o -> {

                    String requestedStatus = b.get("status");

                    if (requestedStatus == null ||
                            requestedStatus.isBlank()) {

                        return ResponseEntity.badRequest().body(
                                Map.of("message", "Status is required")
                        );
                    }

                    o.setStatus(requestedStatus.trim().toUpperCase());

                    return ResponseEntity.ok(repo.save(o));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // Synchronize an order with its Delivery Service record
    private void synchronizeDeliveryStatus(OrderEntity order) {

        try {

            Map<String, Object> delivery = http.get()
                    .uri(deliveryUrl + "/deliveries/order/" + order.getId())
                    .retrieve()
                    .body(Map.class);

            if (delivery == null || delivery.get("status") == null) {
                return;
            }

            String deliveryStatus =
                    delivery.get("status").toString().trim().toUpperCase();

            // Update only recognized delivery statuses.
            if (!DELIVERY_STATUSES.contains(deliveryStatus)) {
                return;
            }

            // Do not overwrite a cancelled order.
            if ("CANCELLED".equalsIgnoreCase(order.getStatus())) {
                return;
            }

            if (!deliveryStatus.equalsIgnoreCase(order.getStatus())) {

                order.setStatus(deliveryStatus);
                repo.save(order);
            }

        } catch (Exception e) {

            // Keep returning orders even if Delivery Service is unavailable.
            System.err.println(
                    "Could not synchronize order #" +
                            order.getId() + ": " + e.getMessage()
            );
        }
    }

    // Get All Orders for Admin Dashboard
    @GetMapping
    public List<OrderEntity> getAllOrders() {

        List<OrderEntity> orders =
                repo.findAllByOrderByCreatedAtDesc();

        for (OrderEntity order : orders) {
            synchronizeDeliveryStatus(order);
        }

        return orders;
    }
}
