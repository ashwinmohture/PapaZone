package com.papazone.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RazorpayWebhookControllerTest {

    private static final String SECRET = "whsec_test";

    private PaymentRepository repo;
    private OrderServiceClient orders;
    private RazorpayWebhookController controller;

    @BeforeEach
    void setUp() {
        repo = mock(PaymentRepository.class);
        orders = mock(OrderServiceClient.class);
        controller = new RazorpayWebhookController(repo, orders);
        ReflectionTestUtils.setField(controller, "webhookSecret", SECRET);
    }

    private static String body(String event, String rzpOrder, long paise) {
        return "{\"event\":\"" + event + "\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_1\",\"order_id\":\""
                + rzpOrder + "\",\"amount\":" + paise + "}}}}";
    }

    private static String sign(String body) {
        return RazorpaySignature.hmacSha256Hex(SECRET, body);
    }

    private static PaymentEntity payment(String status) {
        PaymentEntity p = new PaymentEntity();
        p.setOrderId(5L);
        p.setMethod("RAZORPAY");
        p.setStatus(status);
        p.setAmount(500.0);
        p.setTransactionRef("order_1");
        return p;
    }

    @Test
    void rejectsInvalidSignature() {
        ResponseEntity<?> r = controller.webhook("bad", body("payment.captured", "order_1", 50000));
        assertEquals(400, r.getStatusCode().value());
        verifyNoInteractions(repo, orders);
    }

    @Test
    void returns503WhenSecretIsNotConfigured() {
        ReflectionTestUtils.setField(controller, "webhookSecret", "");
        String b = body("payment.captured", "order_1", 50000);
        assertEquals(503, controller.webhook(sign(b), b).getStatusCode().value());
    }

    @Test
    void confirmsPaymentAndNotifiesOrderService() {
        PaymentEntity p = payment("CREATED");
        when(repo.findByTransactionRef("order_1")).thenReturn(Optional.of(p));
        String b = body("payment.captured", "order_1", 50000);

        ResponseEntity<?> r = controller.webhook(sign(b), b);

        assertEquals(200, r.getStatusCode().value());
        assertEquals("SUCCESS", p.getStatus());
        assertEquals("pay_1", p.getGatewayPaymentId());
        verify(orders).paymentConfirmed(5L);
    }

    @Test
    void ignoresAmountMismatch() {
        PaymentEntity p = payment("CREATED");
        when(repo.findByTransactionRef("order_1")).thenReturn(Optional.of(p));
        String b = body("payment.captured", "order_1", 100);    // customer "paid" only Rs. 1

        ResponseEntity<?> r = controller.webhook(sign(b), b);

        assertEquals(200, r.getStatusCode().value());
        assertEquals("CREATED", p.getStatus());
        verifyNoInteractions(orders);
    }

    @Test
    void ignoresUnrelatedEvents() {
        String b = body("payment.failed", "order_1", 50000);
        ResponseEntity<?> r = controller.webhook(sign(b), b);
        assertEquals(200, r.getStatusCode().value());
        verifyNoInteractions(repo, orders);
    }

    @Test
    void ignoresUnknownRazorpayOrder() {
        when(repo.findByTransactionRef("order_1")).thenReturn(Optional.empty());
        String b = body("payment.captured", "order_1", 50000);
        assertEquals(200, controller.webhook(sign(b), b).getStatusCode().value());
        verifyNoInteractions(orders);
    }

    @Test
    void neverReopensARefundedPayment() {
        PaymentEntity p = payment("REFUNDED");
        when(repo.findByTransactionRef("order_1")).thenReturn(Optional.of(p));
        String b = body("payment.captured", "order_1", 50000);

        controller.webhook(sign(b), b);

        assertEquals("REFUNDED", p.getStatus());
        verifyNoInteractions(orders);
    }

    @Test
    void returns500SoRazorpayRetriesWhenOrderServiceFails() {
        when(repo.findByTransactionRef("order_1")).thenReturn(Optional.of(payment("CREATED")));
        doThrow(new RuntimeException("order-service down")).when(orders).paymentConfirmed(any());
        String b = body("payment.captured", "order_1", 50000);

        assertEquals(500, controller.webhook(sign(b), b).getStatusCode().value());
    }
}
