package com.papazone.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Refund rules:
 *  - RAZORPAY payments are refunded through Razorpay's API, then marked REFUNDED.
 *  - CARD / UPI (simulated) payments are only marked REFUNDED in our database.
 *  - COD that was never collected, FREE (amount 0) and unpaid payments cannot be refunded.
 */
@Service
public class RefundService {

    private final PaymentRepository repo;
    private final RazorpayClient razorpay;
    private final ObjectMapper json = new ObjectMapper();

    public RefundService(PaymentRepository repo, RazorpayClient razorpay) {
        this.repo = repo;
        this.razorpay = razorpay;
    }

    public ResponseEntity<?> refundOrder(Long orderId) {
        PaymentEntity p = repo.findByOrderId(orderId).orElse(null);
        if (p == null) return error(404, "No payment found for this order");

        if ("REFUNDED".equals(p.getStatus())) {
            return ResponseEntity.ok(Map.of("status", "REFUNDED", "alreadyRefunded", true));
        }
        if (!"SUCCESS".equals(p.getStatus())) {
            return error(400, "Only a successful payment can be refunded (current status: " + p.getStatus() + ")");
        }
        if (p.getAmount() <= 0) return error(400, "Nothing to refund for this order");

        Map<String, Object> result = new LinkedHashMap<>();

        if ("RAZORPAY".equals(p.getMethod())) {
            if (p.getGatewayPaymentId() == null || p.getGatewayPaymentId().isBlank()) {
                return error(400, "Razorpay payment id is missing for this payment");
            }
            if (!razorpay.configured()) return error(503, "Razorpay is not configured");

            Map<String, Object> request = new HashMap<>();
            request.put("speed", "normal");
            request.put("notes", Map.of("orderId", String.valueOf(orderId), "reason", "admin_refund"));

            Map<String, Object> refund;
            try {
                refund = razorpay.refund(p.getGatewayPaymentId(), request);
            } catch (RestClientResponseException e) {
                return error(502, "Razorpay refused the refund: " + gatewayMessage(e));
            } catch (Exception e) {
                return error(502, "Could not reach Razorpay");
            }

            if (refund == null || refund.get("id") == null) return error(502, "Invalid refund response from Razorpay");

            p.setGatewayRefundId(refund.get("id").toString());
            result.put("refundId", p.getGatewayRefundId());
        }

        p.setStatus("REFUNDED");
        repo.save(p);

        result.put("status", "REFUNDED");
        result.put("amount", p.getAmount());
        result.put("method", p.getMethod());
        return ResponseEntity.ok(result);
    }

    private String gatewayMessage(RestClientResponseException e) {
        try {
            JsonNode d = json.readTree(e.getResponseBodyAsString()).path("error").path("description");
            if (!d.isMissingNode() && !d.asText().isBlank()) return d.asText();
        } catch (Exception ignored) {
        }
        return "HTTP " + e.getStatusCode().value();
    }

    private static ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
