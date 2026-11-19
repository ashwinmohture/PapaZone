package com.papazone.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Razorpay webhook: server-to-server confirmation, so an order is still confirmed
 * when the customer pays and then closes the browser before /payment/verify runs.
 *
 * Dashboard -> Settings -> Webhooks -> Add:
 *   URL    : https://YOUR-DOMAIN/api/payments/razorpay/webhook
 *   Secret : the value of RAZORPAY_WEBHOOK_SECRET
 *   Events : payment.captured, order.paid
 *
 * Public endpoint (Razorpay calls it) protected only by the HMAC signature.
 */
@RestController
@RequestMapping("/payments/razorpay")
public class RazorpayWebhookController {

    private final PaymentRepository repo;
    private final OrderServiceClient orders;
    private final ObjectMapper json = new ObjectMapper();

    @Value("${RAZORPAY_WEBHOOK_SECRET:}")
    private String webhookSecret;

    public RazorpayWebhookController(PaymentRepository repo, OrderServiceClient orders) {
        this.repo = repo;
        this.orders = orders;
    }

    @PostMapping("/webhook")
    public ResponseEntity<?> webhook(
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestBody String rawBody) {

        if (webhookSecret == null || webhookSecret.isBlank()) {
            return ResponseEntity.status(503).body(Map.of("message", "Webhook not configured"));
        }

        // The signature covers the exact raw body, so the body is taken as a String.
        if (!RazorpaySignature.isValidWebhook(webhookSecret, rawBody, signature)) {
            return ResponseEntity.status(400).body(Map.of("message", "Invalid signature"));
        }

        try {
            JsonNode root = json.readTree(rawBody);
            String event = root.path("event").asText("");

            if (!event.equals("payment.captured") && !event.equals("order.paid")) {
                return ResponseEntity.ok(Map.of("ignored", event));
            }

            JsonNode pay = root.path("payload").path("payment").path("entity");
            String rzpOrderId = pay.path("order_id").asText("");
            String rzpPaymentId = pay.path("id").asText("");
            long paidPaise = pay.path("amount").asLong(-1);

            PaymentEntity p = repo.findByTransactionRef(rzpOrderId).orElse(null);
            if (p == null) return ResponseEntity.ok(Map.of("ignored", "unknown order"));

            // Never trust an amount that differs from what we asked Razorpay to collect.
            if (paidPaise != Math.round(p.getAmount() * 100.0)) {
                System.err.println("Webhook amount mismatch for order #" + p.getOrderId());
                return ResponseEntity.ok(Map.of("ignored", "amount mismatch"));
            }

            // A refunded payment must never flip back to SUCCESS.
            if ("REFUNDED".equals(p.getStatus())) return ResponseEntity.ok(Map.of("ignored", "already refunded"));

            if (!"SUCCESS".equals(p.getStatus())) {
                p.setStatus("SUCCESS");
                p.setGatewayPaymentId(rzpPaymentId);
                repo.save(p);
            }

            // Idempotent on the order side. A failure returns 500 so Razorpay retries.
            orders.paymentConfirmed(p.getOrderId());

            return ResponseEntity.ok(Map.of("status", "ok"));

        } catch (Exception e) {
            System.err.println("Razorpay webhook processing failed: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of("message", "Processing failed, please retry"));
        }
    }
}
