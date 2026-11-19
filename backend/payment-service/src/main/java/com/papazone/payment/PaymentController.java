package com.papazone.payment;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
@CrossOrigin(origins = "*")
public class PaymentController {

    private final PaymentRepository repo;
    private final RefundService refunds;

    public PaymentController(PaymentRepository repo, RefundService refunds) {
        this.repo = repo;
        this.refunds = refunds;
    }

    @PostMapping
    public ResponseEntity<?> pay(@RequestBody Map<String, Object> body) {
        String method = body.getOrDefault("method", "COD").toString().trim().toUpperCase();

        PaymentEntity p = new PaymentEntity();
        p.setOrderId(Long.valueOf(body.get("orderId").toString()));
        p.setMethod(method);

        switch (method) {
            case "COD" -> {
                // Cash is collected on delivery.
                p.setAmount(Double.parseDouble(body.get("amount").toString()));
                p.setStatus("PENDING");
            }
            case "FREE" -> {
                // Free / promo order: nothing is charged, so it is settled immediately.
                p.setAmount(0.0);
                p.setStatus("SUCCESS");
            }
            case "RAZORPAY" -> {
                // A real gateway payment can only be confirmed through /payments/razorpay/verify.
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Use /payments/razorpay/order and /payments/razorpay/verify"));
            }
            default -> {
                // CARD / UPI (simulated)
                p.setAmount(Double.parseDouble(body.get("amount").toString()));
                p.setStatus("SUCCESS");
            }
        }

        p.setTransactionRef("PZ-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        return ResponseEntity.status(201).body(repo.save(p));
    }

    @GetMapping("/order/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return repo.findByOrderId(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Refund by order id: Razorpay payments go through the gateway, others are marked REFUNDED.
    @PostMapping("/order/{orderId}/refund")
    public ResponseEntity<?> refundByOrder(@PathVariable Long orderId) {
        return refunds.refundOrder(orderId);
    }

    // Legacy endpoint (kept). It can no longer mark a real gateway payment as refunded in the DB only.
    @PostMapping("/{id}/refund")
    public ResponseEntity<?> refund(@PathVariable Long id) {
        return repo.findById(id).<ResponseEntity<?>>map(p -> {
            if ("RAZORPAY".equals(p.getMethod())) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Use POST /payments/order/{orderId}/refund for Razorpay payments"));
            }
            p.setStatus("REFUNDED");
            return ResponseEntity.ok(repo.save(p));
        }).orElse(ResponseEntity.notFound().build());
    }
}
