package com.papazone.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Real Razorpay integration (works in Razorpay TEST mode for free).
 *
 *   1. POST /payments/razorpay/order  -> creates a Razorpay order, returns what Checkout.js needs
 *   2. Browser pays inside Razorpay Checkout
 *   3. POST /payments/razorpay/verify -> verifies the HMAC signature, marks the payment SUCCESS
 *
 * Both endpoints are internal (X-Internal-Key) because the gateway exposes /api/payments/** publicly.
 */
@RestController
@RequestMapping("/payments/razorpay")
@CrossOrigin(origins = "*")
public class RazorpayController {

    private final PaymentRepository repo;
    private final RazorpayClient razorpay;

    @Value("${INTERNAL_API_KEY:}")
    private String internalKey;

    public RazorpayController(PaymentRepository repo, RazorpayClient razorpay) {
        this.repo = repo;
        this.razorpay = razorpay;
    }

    // ---------------------------------------------------------------- create order

    @PostMapping("/order")
    public ResponseEntity<?> createOrder(
            @RequestHeader(value = "X-Internal-Key", required = false) String key,
            @RequestBody Map<String, Object> body) {

        if (!internalKeyOk(key)) return error(403, "Forbidden");
        if (!razorpay.configured()) {
            return error(503, "Razorpay is not configured (RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET)");
        }

        Long orderId;
        double amount;
        try {
            orderId = Long.valueOf(body.get("orderId").toString());
            amount = Double.parseDouble(body.get("amount").toString());
        } catch (Exception e) {
            return error(400, "orderId and amount are required");
        }

        long paise = Math.round(amount * 100.0);
        if (paise < 100) return error(400, "Razorpay needs a minimum amount of Rs. 1");

        PaymentEntity existing = repo.findByOrderId(orderId).orElse(null);

        if (existing != null) {
            if ("SUCCESS".equals(existing.getStatus())) return error(409, "Order is already paid");
            if ("REFUNDED".equals(existing.getStatus())) return error(409, "Order was refunded");

            // Idempotent: retrying the same unpaid order re-uses the same Razorpay order.
            if ("RAZORPAY".equals(existing.getMethod()) && "CREATED".equals(existing.getStatus())
                    && Math.round(existing.getAmount() * 100.0) == paise) {
                return ResponseEntity.ok(checkoutPayload(existing.getTransactionRef(), paise));
            }
        }

        Map<String, Object> request = new HashMap<>();
        request.put("amount", paise);
        request.put("currency", "INR");
        request.put("receipt", "PZ-" + orderId);
        request.put("notes", Map.of("orderId", String.valueOf(orderId)));

        Map<String, Object> rzp;
        try {
            rzp = razorpay.createOrder(request);
        } catch (RestClientResponseException e) {
            return error(502, "Razorpay rejected the request: " + e.getStatusCode().value());
        } catch (Exception e) {
            return error(502, "Could not reach Razorpay");
        }

        if (rzp == null || rzp.get("id") == null) return error(502, "Invalid response from Razorpay");

        PaymentEntity p = existing != null ? existing : new PaymentEntity();
        p.setOrderId(orderId);
        p.setAmount(amount);
        p.setMethod("RAZORPAY");
        p.setStatus("CREATED");
        p.setTransactionRef(rzp.get("id").toString());
        p.setGatewayPaymentId(null);
        repo.save(p);

        return ResponseEntity.status(201).body(checkoutPayload(p.getTransactionRef(), paise));
    }

    // ---------------------------------------------------------------- verify

    @PostMapping("/verify")
    public ResponseEntity<?> verify(
            @RequestHeader(value = "X-Internal-Key", required = false) String key,
            @RequestBody Map<String, Object> body) {

        if (!internalKeyOk(key)) return error(403, "Forbidden");
        if (!razorpay.configured()) return error(503, "Razorpay is not configured");

        String orderId = str(body.get("orderId"));
        String rzpOrderId = str(body.get("razorpay_order_id"));
        String rzpPaymentId = str(body.get("razorpay_payment_id"));
        String signature = str(body.get("razorpay_signature"));

        if (orderId.isBlank() || rzpOrderId.isBlank() || rzpPaymentId.isBlank() || signature.isBlank()) {
            return error(400, "Missing payment verification fields");
        }

        PaymentEntity p = repo.findByTransactionRef(rzpOrderId).orElse(null);
        if (p == null || !String.valueOf(p.getOrderId()).equals(orderId)) {
            return error(400, "Payment does not belong to this order");
        }

        // Already verified with the same payment id -> idempotent success.
        if ("SUCCESS".equals(p.getStatus()) && rzpPaymentId.equals(p.getGatewayPaymentId())) {
            return ResponseEntity.ok(Map.of("verified", true, "status", "SUCCESS"));
        }
        if (!"CREATED".equals(p.getStatus())) return error(409, "Payment is not awaiting verification");

        // Do not change state on a bad signature: it could be a forged request,
        // and the real payment may still arrive (browser retry or webhook).
        if (!RazorpaySignature.isValidPayment(razorpay.keySecret(), rzpOrderId, rzpPaymentId, signature)) {
            return error(400, "Invalid payment signature");
        }

        p.setStatus("SUCCESS");
        p.setGatewayPaymentId(rzpPaymentId);
        repo.save(p);

        return ResponseEntity.ok(Map.of("verified", true, "status", "SUCCESS"));
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> checkoutPayload(String razorpayOrderId, long paise) {
        Map<String, Object> out = new HashMap<>();
        out.put("keyId", razorpay.keyId());      // public key id: safe to send to the browser
        out.put("razorpayOrderId", razorpayOrderId);
        out.put("amount", paise);
        out.put("currency", "INR");
        return out;
    }

    private boolean internalKeyOk(String provided) {
        return internalKey != null && !internalKey.isBlank() && provided != null
                && MessageDigest.isEqual(
                internalKey.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
