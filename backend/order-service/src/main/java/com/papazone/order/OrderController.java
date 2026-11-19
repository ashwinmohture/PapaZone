
package com.papazone.order;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientResponseException;
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
    private final PaymentConfirmationService confirmation;
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

    // Free payment method (promo / demo orders). Can be switched off without a code change.
    @Value("${FREE_PAYMENT_ENABLED:true}")
    boolean freePaymentEnabled;

    // Optional cap for FREE orders (order total in INR). 0 = no cap.
    @Value("${FREE_PAYMENT_MAX_AMOUNT:0}")
    double freePaymentMaxAmount;

    // Order status while waiting for the customer to pay through Razorpay.
    private static final String PAYMENT_PENDING = PaymentConfirmationService.PAYMENT_PENDING;

    private static final Set<String> PAYMENT_METHODS = Set.of("COD", "CARD", "UPI", "FREE", "RAZORPAY");

    private static final List<String> DELIVERY_STATUSES = List.of(
            "PLACED",
            "PREPARING",
            "READY_FOR_PICKUP",
            "PICKED_UP",
            "DELIVERED"
    );

    public OrderController(
            OrderRepository r,
            OrderEventPublisher events,
            PaymentConfirmationService confirmation) {
        repo = r;
        this.events = events;
        this.confirmation = confirmation;
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

        // Normalise + validate payment method (null/blank keeps the old default: COD).
        String method = (req.paymentMethod() == null || req.paymentMethod().isBlank())
                ? "COD"
                : req.paymentMethod().trim().toUpperCase();

        if (!PAYMENT_METHODS.contains(method)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Unsupported payment method"));
        }

        if ("FREE".equals(method) && !freePaymentEnabled) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Free payment is not available right now"));
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
            o.setPaymentMethod(method);

            // COD is collected on delivery; CARD / UPI (simulated) and FREE are settled immediately;
            // RAZORPAY stays PENDING until the payment is verified.
            o.setPaymentStatus(switch (method) {
                case "CARD", "UPI", "FREE" -> "PAID";
                default -> "PENDING";
            });

            if ("FREE".equals(method)) {
                if (freePaymentMaxAmount > 0 && o.getTotal() > freePaymentMaxAmount) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("message",
                                    "Free payment is only available for orders up to Rs. "
                                            + freePaymentMaxAmount));
                }
                // Nothing to collect, so the order is already settled.
                o.setPaymentStatus("PAID");
            }

            o = repo.save(o);

            // Real gateway: the order is only confirmed after the payment is verified.
            if ("RAZORPAY".equals(method)) {
                o.setStatus(PAYMENT_PENDING);
                o.setPaymentStatus("PENDING");
                o = repo.save(o);

                Map<String, Object> details = createRazorpayOrder(o);
                if (details == null) {
                    repo.delete(o);
                    return ResponseEntity.status(502)
                            .body(Map.of("message", "Could not start the online payment. Please try again."));
                }

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", o.getId());
                out.put("status", o.getStatus());
                out.put("total", o.getTotal());
                out.put("razorpay", details);
                return ResponseEntity.status(201).body(out);
            }

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
                    "method", method
            );

            try {
                http.post()
                        .uri(paymentUrl + "/payments")
                        .header("X-Internal-Key", internalKey)
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

    // Retry / resume an unpaid Razorpay order (e.g. the customer closed the payment window).
    @PostMapping("/{id:\\d+}/payment/razorpay")
    public ResponseEntity<?> retryRazorpay(@PathVariable("id") Long id, HttpServletRequest request) {
        AuthenticatedUser user = currentUser(request);
        if (user == null) return unauthorized();

        Optional<OrderEntity> found = repo.findByIdAndCustomerId(id, user.customerId());
        if (found.isEmpty()) return ResponseEntity.notFound().build();

        OrderEntity o = found.get();
        if (!PAYMENT_PENDING.equals(o.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "This order is not awaiting payment"));
        }

        Map<String, Object> details = createRazorpayOrder(o);
        if (details == null) {
            return ResponseEntity.status(502).body(Map.of("message", "Could not start the online payment"));
        }
        return ResponseEntity.ok(Map.of("id", o.getId(), "razorpay", details));
    }

    // Called by the browser after Razorpay Checkout succeeds. The signature is verified server-side.
    @PostMapping("/{id:\\d+}/payment/verify")
    public ResponseEntity<?> verifyRazorpay(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {

        AuthenticatedUser user = currentUser(request);
        if (user == null) return unauthorized();

        Optional<OrderEntity> found = repo.findByIdAndCustomerId(id, user.customerId());
        if (found.isEmpty()) return ResponseEntity.notFound().build();

        OrderEntity o = found.get();

        // Idempotent: already confirmed.
        if ("PAID".equals(o.getPaymentStatus())) return ResponseEntity.ok(o);

        if (!PAYMENT_PENDING.equals(o.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "This order is not awaiting payment"));
        }

        Map<String, Object> verifyReq = new HashMap<>();
        verifyReq.put("orderId", o.getId());
        verifyReq.put("razorpay_order_id", body.get("razorpay_order_id"));
        verifyReq.put("razorpay_payment_id", body.get("razorpay_payment_id"));
        verifyReq.put("razorpay_signature", body.get("razorpay_signature"));

        try {
            http.post()
                    .uri(paymentUrl + "/payments/razorpay/verify")
                    .header("X-Internal-Key", internalKey)
                    .body(verifyReq)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Payment verification failed. If money was deducted it will be refunded."));
        }

        return ResponseEntity.ok(confirmation.confirmPaid(o.getId()));
    }

    // Called by payment-service when the Razorpay webhook confirms a payment
    // (covers customers who paid but closed the browser before /payment/verify ran).
    @PostMapping("/internal/{id}/payment-confirmed")
    public ResponseEntity<?> paymentConfirmedInternal(
            @PathVariable("id") Long id,
            @RequestHeader(value = "X-Internal-Key", required = false) String key) {

        if (internalKey == null || internalKey.isBlank() || key == null
                || !MessageDigest.isEqual(
                internalKey.getBytes(StandardCharsets.UTF_8),
                key.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(403).body(Map.of("message", "Forbidden"));
        }

        OrderEntity o = confirmation.confirmPaid(id);
        return o == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(Map.of("status", o.getStatus()));
    }

    // Asks payment-service to create (or re-use) the Razorpay order. Returns null on failure.
    @SuppressWarnings("unchecked")
    private Map<String, Object> createRazorpayOrder(OrderEntity o) {
        try {
            return http.post()
                    .uri(paymentUrl + "/payments/razorpay/order")
                    .header("X-Internal-Key", internalKey)
                    .body(Map.of("orderId", o.getId(), "amount", o.getTotal()))
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            System.err.println("Razorpay order creation failed for order #" + o.getId() + ": " + e.getMessage());
            return null;
        }
    }

    // Admin refund: Razorpay payments are refunded through the gateway; the order is then cancelled
    // (unless it was already delivered) and its payment status becomes REFUNDED.
    @PostMapping("/{id:\\d+}/refund")
    public ResponseEntity<?> refund(@PathVariable("id") Long id, HttpServletRequest request) {
        AuthenticatedUser user = currentUser(request);
        if (user == null) return unauthorized();
        if (!user.isAdmin()) return ResponseEntity.status(403).body(Map.of("message", "Admin access required"));

        Optional<OrderEntity> found = repo.findById(id);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        OrderEntity o = found.get();

        try {
            http.post()
                    .uri(paymentUrl + "/payments/order/" + id + "/refund")
                    .header("X-Internal-Key", internalKey)
                    .retrieve()
                    .toBodilessEntity();

        } catch (RestClientResponseException e) {
            String message = "Refund failed";
            try {
                message = JSON.readTree(e.getResponseBodyAsString()).path("message").asText(message);
            } catch (Exception ignored) {
            }
            int status = e.getStatusCode().value();
            return ResponseEntity.status(status >= 400 && status < 600 ? status : 502)
                    .body(Map.of("message", message));

        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("message", "Payment service unavailable"));
        }

        o.setPaymentStatus("REFUNDED");
        if (!"DELIVERED".equalsIgnoreCase(o.getStatus())) {
            o.setStatus("CANCELLED");
        }
        return ResponseEntity.ok(repo.save(o));
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
                    .filter(o -> !PAYMENT_PENDING.equals(o.getStatus()))
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

        // Unpaid online-payment orders are not real orders yet.
        List<OrderEntity> orders = repo.findAllByOrderByCreatedAtDesc().stream()
                .filter(o -> !PAYMENT_PENDING.equals(o.getStatus()))
                .toList();

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
