
package com.papazone.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.*;

@RestController
@RequestMapping("/deliveries")
@CrossOrigin(origins = "*")
public class DeliveryController {

    private final DeliveryRepository repo;

    private final RestClient http = RestClient.create();

    @Value("${ORDER_SERVICE_URL:http://localhost:8084}")
    private String orderUrl;

    private static final List<String> DELIVERY_STATUSES = List.of(
            "PLACED",
            "PREPARING",
            "READY_FOR_PICKUP",
            "PICKED_UP",
            "DELIVERED"
    );

    public DeliveryController(DeliveryRepository r) {
        repo = r;
    }

    // Create Delivery
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> b) {

        if (b.get("orderId") == null ||
                b.get("customerId") == null ||
                b.get("address") == null) {

            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "message",
                            "orderId, customerId and address are required"
                    ));
        }

        Delivery d = new Delivery();

        d.setOrderId(Long.valueOf(b.get("orderId").toString()));
        d.setCustomerId(Long.valueOf(b.get("customerId").toString()));
        d.setAddress(b.get("address").toString());

        d.setTrackingCode(
                "PZTRK" + d.getOrderId() +
                        (new Random().nextInt(90) + 10)
        );

        d.setStatus("PLACED");

        return ResponseEntity.status(201).body(repo.save(d));
    }

    // Get Delivery by Order ID
    @GetMapping("/order/{id}")
    public ResponseEntity<?> byOrder(@PathVariable("id") Long id) {

        return repo.findByOrderId(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Get Delivery by Delivery ID
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable("id") Long id) {

        return repo.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Update Delivery and synchronize Order status
    @PatchMapping("/{id}/status")
    public ResponseEntity<?> status(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> b) {

        Optional<Delivery> optional = repo.findById(id);

        if (optional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String requestedStatus = b.get("status");

        if (requestedStatus == null || requestedStatus.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Status is required"));
        }

        requestedStatus = requestedStatus.trim().toUpperCase();

        if (!DELIVERY_STATUSES.contains(requestedStatus)) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "message", "Invalid status",
                            "allowedStatuses", DELIVERY_STATUSES
                    ));
        }

        Delivery d = optional.get();

        String currentStatus = d.getStatus();

        // Support existing CREATED records
        if ("CREATED".equalsIgnoreCase(currentStatus)) {
            currentStatus = "PLACED";
        }

        int currentIndex = DELIVERY_STATUSES.indexOf(currentStatus);
        int requestedIndex = DELIVERY_STATUSES.indexOf(requestedStatus);

        if (currentIndex == -1) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "message", "Current delivery status is invalid"
                    ));
        }

        // Allow only the next sequential status
        if (requestedIndex != currentIndex + 1) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "message", "Invalid status transition",
                            "currentStatus", currentStatus,
                            "nextAllowedStatus",
                            currentIndex < DELIVERY_STATUSES.size() - 1
                                    ? DELIVERY_STATUSES.get(currentIndex + 1)
                                    : "No further status available"
                    ));
        }

        // First update Delivery Service database
        d.setStatus(requestedStatus);

        Delivery savedDelivery = repo.save(d);

        // Synchronize the corresponding Order Service status
        try {

            http.patch()
                    .uri(orderUrl + "/orders/" + d.getOrderId() + "/status")
                    .body(Map.of("status", requestedStatus))
                    .retrieve()
                    .toBodilessEntity();

        } catch (Exception e) {

            return ResponseEntity.status(502)
                    .body(Map.of(
                            "message",
                            "Delivery updated, but Order status synchronization failed",
                            "deliveryStatus",
                            requestedStatus,
                            "orderId",
                            d.getOrderId()
                    ));
        }

        return ResponseEntity.ok(savedDelivery);
    }

    // Update Delivery Provider
    @PatchMapping("/{id}/provider")
    public ResponseEntity<?> provider(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> b) {

        return repo.findById(id)
                .map(d -> {
                    String p = b.getOrDefault("provider", "DOORDASH");

                    d.setProvider(p.toUpperCase() + "_SIMULATED");

                    return ResponseEntity.ok(repo.save(d));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
