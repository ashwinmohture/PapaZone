package com.papazone.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RefundServiceTest {

    private PaymentRepository repo;
    private RazorpayClient client;
    private RefundService service;

    @BeforeEach
    void setUp() {
        repo = mock(PaymentRepository.class);
        client = mock(RazorpayClient.class);
        when(client.configured()).thenReturn(true);
        service = new RefundService(repo, client);
    }

    private PaymentEntity payment(String method, String status, double amount, String gatewayPaymentId) {
        PaymentEntity p = new PaymentEntity();
        p.setOrderId(9L);
        p.setMethod(method);
        p.setStatus(status);
        p.setAmount(amount);
        p.setGatewayPaymentId(gatewayPaymentId);
        when(repo.findByOrderId(9L)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void razorpayPaymentIsRefundedThroughTheGatewayAndMarkedRefunded() {
        PaymentEntity p = payment("RAZORPAY", "SUCCESS", 500, "pay_1");
        when(client.refund(eq("pay_1"), any())).thenReturn(Map.of("id", "rfnd_1"));

        ResponseEntity<?> r = service.refundOrder(9L);

        assertEquals(200, r.getStatusCode().value());
        assertEquals("REFUNDED", p.getStatus());
        assertEquals("rfnd_1", p.getGatewayRefundId());
        verify(client).refund(eq("pay_1"), any());
        verify(repo).save(p);
    }

    @Test
    void gatewayFailureLeavesPaymentUntouched() {
        PaymentEntity p = payment("RAZORPAY", "SUCCESS", 500, "pay_1");
        String json = "{\"error\":{\"description\":\"Insufficient balance\"}}";
        when(client.refund(any(), any())).thenThrow(new RestClientResponseException(
                "bad", 400, "Bad Request", null, json.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        ResponseEntity<?> r = service.refundOrder(9L);

        assertEquals(502, r.getStatusCode().value());
        assertTrue(((Map<?, ?>) r.getBody()).get("message").toString().contains("Insufficient balance"));
        assertEquals("SUCCESS", p.getStatus());            // NOT marked refunded
        assertNull(p.getGatewayRefundId());
        verify(repo, never()).save(any());
    }

    @Test
    void simulatedCardPaymentIsOnlyMarkedRefundedWithoutCallingRazorpay() {
        PaymentEntity p = payment("CARD", "SUCCESS", 300, null);

        ResponseEntity<?> r = service.refundOrder(9L);

        assertEquals(200, r.getStatusCode().value());
        assertEquals("REFUNDED", p.getStatus());
        verify(client, never()).refund(any(), any());
    }

    @Test
    void alreadyRefundedIsIdempotent() {
        payment("RAZORPAY", "REFUNDED", 500, "pay_1");

        ResponseEntity<?> r = service.refundOrder(9L);

        assertEquals(200, r.getStatusCode().value());
        verify(client, never()).refund(any(), any());
        verify(repo, never()).save(any());
    }

    @Test
    void uncollectedCashOnDeliveryCannotBeRefunded() {
        payment("COD", "PENDING", 300, null);
        assertEquals(400, service.refundOrder(9L).getStatusCode().value());
    }

    @Test
    void unpaidRazorpayOrderCannotBeRefunded() {
        payment("RAZORPAY", "CREATED", 300, null);
        assertEquals(400, service.refundOrder(9L).getStatusCode().value());
    }

    @Test
    void freeOrderHasNothingToRefund() {
        payment("FREE", "SUCCESS", 0, null);
        assertEquals(400, service.refundOrder(9L).getStatusCode().value());
    }

    @Test
    void razorpayPaymentWithoutGatewayIdIsRejected() {
        payment("RAZORPAY", "SUCCESS", 500, null);
        assertEquals(400, service.refundOrder(9L).getStatusCode().value());
        verify(client, never()).refund(any(), any());
    }

    @Test
    void unknownOrderReturns404() {
        when(repo.findByOrderId(9L)).thenReturn(Optional.empty());
        assertEquals(404, service.refundOrder(9L).getStatusCode().value());
    }
}
