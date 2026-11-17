
package com.papazone.order;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import com.papazone.order.security.AuthenticatedUser;

@RestController
@RequestMapping("/orders")
@CrossOrigin(origins = "*")
public class OrderController {

    private final OrderRepository repo;
    private final OrderEventPublisher events;
    private final RestClient http = RestClient.create();
    private static final ObjectMapper JSON = new ObjectMapper();

    // Shared secret for service-to-service calls (ai-service). Empty = export disabled.
    @Value("${INTERNAL_API_KEY:}")
    String internalKey;

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
    public ResponseEntity<?> checkout(@RequestBody Checkout req, HttpServletRequest request) {

        AuthenticatedUser user = currentUser(request);
        if (user == null) return unauthorized();
        Long customerId = user.customerId();

        if (req.address() == null || req.address().isBlank()) {

            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "message",
                            "Delivery address is required"
                    ));
        }

        try {

            Map cart = http.get()
                    .uri(cartUrl + "/cart/" + customerId)
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

            o.setCustomerId(customerId);
            // Store real JSON (was List.toString(), which is not parseable JSON).
            o.setItemsJson(JSON.writeValueAsString(items));
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
                        .uri(cartUrl + "/cart/" + customerId)
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

    // Minimal order view for internal analytics: no address, no payment data.
    public record OrderExport(
            Long id,
            Long customerId,
            Instant createdAt,
            String status,
            double subtotal,
            double total,
            String itemsJson
    ) {}

    // Internal export for ai-service: orders created in [from, to). Requires X-Internal-Key.
    @GetMapping("/export")
    public ResponseEntity<?> export(
            @RequestHeader(value = "X-Internal-Key", required = false) String key,
            @RequestParam("from") String from,
            @RequestParam("to") String to) {

        if (internalKey == null || internalKey.isBlank() || key == null ||
                !MessageDigest.isEqual(
                        internalKey.getBytes(StandardCharsets.UTF_8),
                        key.getBytes(StandardCharsets.UTF_8))) {

            return ResponseEntity.status(403)
                    .body(Map.of("message", "Forbidden"));
        }

        try {
            List<OrderExport> rows = repo
                    .findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                            Instant.parse(from), Instant.parse(to))
                    .stream()
                    .map(o -> new OrderExport(
                            o.getId(), o.getCustomerId(), o.getCreatedAt(),
                            o.getStatus(), o.getSubtotal(), o.getTotal(),
                            o.getItemsJson()))
                    .toList();

            return ResponseEntity.ok(rows);

        } catch (java.time.format.DateTimeParseException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "from/to must be ISO-8601 instants"));
        }
    }

    // Customers may only read their own order; admins may read any order.
    @GetMapping("/{id:\\d+}")
    public ResponseEntity<?> get(@PathVariable("id") Long id, HttpServletRequest request) {
        AuthenticatedUser user = currentUser(request);
        if (user == null) return unauthorized();
        Optional<OrderEntity> order = user.isAdmin() ? repo.findById(id)
                : repo.findByIdAndCustomerId(id, user.customerId());
        return order.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Legacy URL retained for compatibility, but customer identity comes from JWT.
    @GetMapping("/customer/{id}")
    public ResponseEntity<?> byCustomer(@PathVariable("id") Long id, HttpServletRequest request) {
        AuthenticatedUser user = currentUser(request);
        if (user == null) return unauthorized();
        if (!user.isAdmin() && !user.customerId().equals(id)) {
            return ResponseEntity.status(403).body(Map.of("message", "You cannot access another customer's orders"));
        }
        return ResponseEntity.ok(repo.findByCustomerIdOrderByCreatedAtDesc(id));
    }

    @GetMapping("/my-orders")
    public ResponseEntity<?> myOrders(HttpServletRequest request) {
        AuthenticatedUser user = currentUser(request);
        if (user == null) return unauthorized();
        return ResponseEntity.ok(repo.findByCustomerIdOrderByCreatedAtDesc(user.customerId()));
    }


    // Internal status update for Delivery Service.
// Requires X-Internal-Key instead of a customer/admin JWT.
    @PatchMapping("/internal/{id}/status")
    public ResponseEntity<?> updateStatusInternal(
            @PathVariable("id") Long id,
            @RequestHeader(value = "X-Internal-Key", required = false) String key,
            @RequestBody Map<String, String> body) {

        if (internalKey == null || internalKey.isBlank()
                || key == null
                || !MessageDigest.isEqual(
                internalKey.getBytes(StandardCharsets.UTF_8),
                key.getBytes(StandardCharsets.UTF_8))) {

            return ResponseEntity.status(403)
                    .body(Map.of("message", "Forbidden"));
        }

        String requestedStatus = body.get("status");

        if (requestedStatus == null || requestedStatus.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Status is required"));
        }

        requestedStatus = requestedStatus.trim().toUpperCase();

        if (!DELIVERY_STATUSES.contains(requestedStatus)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Invalid delivery status"));
        }

        final String finalStatus = requestedStatus;

        return repo.findById(id)
                .map(order -> {
                    if ("CANCELLED".equalsIgnoreCase(order.getStatus())) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("message", "Cancelled order cannot be updated"));
                    }

                    order.setStatus(finalStatus);
                    return ResponseEntity.ok(repo.save(order));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // Update Order Status
    @PatchMapping("/{id}/status")
    public ResponseEntity<?> status(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> b,
            HttpServletRequest request) {

        AuthenticatedUser user = currentUser(request);
        if (user == null) return unauthorized();
        if (!user.isAdmin()) return ResponseEntity.status(403).body(Map.of("message", "Admin access required"));

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
    public ResponseEntity<?> getAllOrders(HttpServletRequest request) {
        AuthenticatedUser user = currentUser(request);
        if (user == null) return unauthorized();
        if (!user.isAdmin()) return ResponseEntity.status(403).body(Map.of("message", "Admin access required"));

        List<OrderEntity> orders =
                repo.findAllByOrderByCreatedAtDesc();

        for (OrderEntity order : orders) {
            synchronizeDeliveryStatus(order);
        }

        return ResponseEntity.ok(orders);
    }

    private AuthenticatedUser currentUser(HttpServletRequest request) {
        return (AuthenticatedUser) request.getAttribute(AuthenticatedUser.class.getName());
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("message", "Authentication required"));
    }
}
