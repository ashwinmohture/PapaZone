package com.papazone.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Owns the lifecycle of online-payment orders:
 *   PAYMENT_PENDING --paid--> PLACED (+ fulfillment)       confirmPaid()
 *   PAYMENT_PENDING --stale-> CANCELLED / FAILED           cancelAbandonedOrders()
 *   CANCELLED + late payment -> paymentStatus REFUND_REQUIRED
 *
 * Methods are synchronized so the browser "verify" call and the Razorpay webhook can race safely
 * (fulfillment runs exactly once). Use a database lock if you ever scale order-service out.
 */
@Service
public class PaymentConfirmationService {

    public static final String PAYMENT_PENDING = "PAYMENT_PENDING";

    private final OrderRepository repo;
    private final OrderFulfillment fulfillment;

    // Unpaid Razorpay orders are auto-cancelled after this many minutes.
    @Value("${UNPAID_ORDER_TTL_MINUTES:60}")
    long unpaidOrderTtlMinutes = 60;

    public PaymentConfirmationService(OrderRepository repo, OrderFulfillment fulfillment) {
        this.repo = repo;
        this.fulfillment = fulfillment;
    }

    /** Returns the order (null when it does not exist). Safe to call many times. */
    public synchronized OrderEntity confirmPaid(Long id) {
        OrderEntity o = repo.findById(id).orElse(null);
        if (o == null) return null;

        if (PAYMENT_PENDING.equals(o.getStatus())) {
            o.setPaymentStatus("PAID");
            o.setStatus("PLACED");
            o = repo.save(o);
            fulfillment.onConfirmed(o);

        } else if ("CANCELLED".equals(o.getStatus())
                && !"PAID".equals(o.getPaymentStatus())
                && !"REFUNDED".equals(o.getPaymentStatus())
                && !"REFUND_REQUIRED".equals(o.getPaymentStatus())) {
            // Money arrived after the unpaid order was auto-cancelled -> needs a manual refund.
            o.setPaymentStatus("REFUND_REQUIRED");
            o = repo.save(o);
            System.err.println("Order #" + o.getId() + " was paid after cancellation - refund required");
        }
        return o;
    }

    @Scheduled(fixedDelayString = "${UNPAID_ORDER_CLEANUP_MS:600000}")
    public void scheduledCleanup() {
        cancelAbandonedOrders();
    }

    /** Cancels PAYMENT_PENDING orders older than the TTL. Returns how many were cancelled. */
    public synchronized int cancelAbandonedOrders() {
        Instant cutoff = Instant.now().minusSeconds(unpaidOrderTtlMinutes * 60);
        int cancelled = 0;

        for (OrderEntity stale : repo.findByStatusAndCreatedAtBefore(PAYMENT_PENDING, cutoff)) {
            OrderEntity fresh = repo.findById(stale.getId()).orElse(null);
            if (fresh != null && PAYMENT_PENDING.equals(fresh.getStatus())) {
                fresh.setStatus("CANCELLED");
                fresh.setPaymentStatus("FAILED");
                repo.save(fresh);
                cancelled++;
            }
        }
        return cancelled;
    }
}
