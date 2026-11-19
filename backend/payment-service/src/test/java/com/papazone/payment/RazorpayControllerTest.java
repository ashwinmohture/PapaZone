package com.papazone.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RazorpayControllerTest {

    private static final String KEY = "internal-key";
    private static final String SECRET = "test_secret";

    private PaymentRepository repo;
    private RazorpayClient client;
    private RazorpayController controller;

    @BeforeEach
    void setUp() {
        repo = mock(PaymentRepository.class);
        client = mock(RazorpayClient.class);
        when(client.configured()).thenReturn(true);
        when(client.keySecret()).thenReturn(SECRET);
        when(client.keyId()).thenReturn("rzp_test_key");

        controller = new RazorpayController(repo, client);
        ReflectionTestUtils.setField(controller, "internalKey", KEY);
    }

    private static PaymentEntity payment(long orderId, String rzpOrderId, String status, double amount) {
        PaymentEntity p = new PaymentEntity();
        p.setOrderId(orderId);
        p.setMethod("RAZORPAY");
        p.setStatus(status);
        p.setAmount(amount);
        p.setTransactionRef(rzpOrderId);
        return p;
    }

    private static Map<String, Object> verifyBody(long orderId, String rzpOrder, String rzpPayment, String sig) {
        Map<String, Object> b = new HashMap<>();
        b.put("orderId", orderId);
        b.put("razorpay_order_id", rzpOrder);
        b.put("razorpay_payment_id", rzpPayment);
        b.put("razorpay_signature", sig);
        return b;
    }

    private static String sign(String rzpOrder, String rzpPayment) {
        return RazorpaySignature.hmacSha256Hex(SECRET, rzpOrder + "|" + rzpPayment);
    }

    // ------------------------------------------------------------ verify

    @Test
    void verifyRejectsMissingInternalKey() {
        ResponseEntity<?> r = controller.verify(null, verifyBody(1, "order_1", "pay_1", "x"));
        assertEquals(403, r.getStatusCode().value());
        verifyNoInteractions(repo);
    }

    @Test
    void verifyRejectsWrongInternalKey() {
        ResponseEntity<?> r = controller.verify("nope", verifyBody(1, "order_1", "pay_1", "x"));
        assertEquals(403, r.getStatusCode().value());
    }

    @Test
    void verifyMarksPaymentSuccessWhenSignatureIsValid() {
        PaymentEntity p = payment(1, "order_1", "CREATED", 500);
        when(repo.findByTransactionRef("order_1")).thenReturn(Optional.of(p));

        ResponseEntity<?> r = controller.verify(KEY, verifyBody(1, "order_1", "pay_1", sign("order_1", "pay_1")));

        assertEquals(200, r.getStatusCode().value());
        assertEquals("SUCCESS", p.getStatus());
        assertEquals("pay_1", p.getGatewayPaymentId());
        verify(repo).save(p);
    }

    @Test
    void verifyWithInvalidSignatureKeepsPaymentCreatedAndSavesNothing() {
        PaymentEntity p = payment(1, "order_1", "CREATED", 500);
        when(repo.findByTransactionRef("order_1")).thenReturn(Optional.of(p));

        ResponseEntity<?> r = controller.verify(KEY, verifyBody(1, "order_1", "pay_1", "deadbeef"));

        assertEquals(400, r.getStatusCode().value());
        assertEquals("CREATED", p.getStatus());
        assertNull(p.getGatewayPaymentId());
        verify(repo, never()).save(any());
    }

    @Test
    void verifyRejectsPaymentBelongingToAnotherOrder() {
        PaymentEntity p = payment(2, "order_1", "CREATED", 500);   // belongs to order 2
        when(repo.findByTransactionRef("order_1")).thenReturn(Optional.of(p));

        ResponseEntity<?> r = controller.verify(KEY, verifyBody(1, "order_1", "pay_1", sign("order_1", "pay_1")));

        assertEquals(400, r.getStatusCode().value());
        assertEquals("CREATED", p.getStatus());
    }

    @Test
    void verifyRejectsUnknownRazorpayOrder() {
        when(repo.findByTransactionRef("order_x")).thenReturn(Optional.empty());
        ResponseEntity<?> r = controller.verify(KEY, verifyBody(1, "order_x", "pay_1", "x"));
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void verifyIsIdempotentForTheSamePaymentId() {
        PaymentEntity p = payment(1, "order_1", "SUCCESS", 500);
        p.setGatewayPaymentId("pay_1");
        when(repo.findByTransactionRef("order_1")).thenReturn(Optional.of(p));

        ResponseEntity<?> r = controller.verify(KEY, verifyBody(1, "order_1", "pay_1", sign("order_1", "pay_1")));

        assertEquals(200, r.getStatusCode().value());
        verify(repo, never()).save(any());
    }

    @Test
    void verifyRejectsAlreadyRefundedPayment() {
        PaymentEntity p = payment(1, "order_1", "REFUNDED", 500);
        p.setGatewayPaymentId("pay_1");
        when(repo.findByTransactionRef("order_1")).thenReturn(Optional.of(p));

        ResponseEntity<?> r = controller.verify(KEY, verifyBody(1, "order_1", "pay_1", sign("order_1", "pay_1")));

        assertEquals(409, r.getStatusCode().value());
        assertEquals("REFUNDED", p.getStatus());
    }

    @Test
    void verifyRejectsMissingFields() {
        ResponseEntity<?> r = controller.verify(KEY, verifyBody(1, "order_1", "", "sig"));
        assertEquals(400, r.getStatusCode().value());
    }

    // ------------------------------------------------------------ create order

    @Test
    void createOrderRejectsAmountBelowOneRupee() {
        ResponseEntity<?> r = controller.createOrder(KEY, Map.of("orderId", 1, "amount", 0.5));
        assertEquals(400, r.getStatusCode().value());
        verify(client, never()).createOrder(any());
    }

    @Test
    void createOrderStoresNewRazorpayOrderAndReturnsCheckoutPayload() {
        when(repo.findByOrderId(7L)).thenReturn(Optional.empty());
        when(client.createOrder(any())).thenReturn(Map.of("id", "order_new"));

        ResponseEntity<?> r = controller.createOrder(KEY, Map.of("orderId", 7, "amount", 525.5));

        assertEquals(201, r.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) r.getBody();
        assertEquals("order_new", body.get("razorpayOrderId"));
        assertEquals(52550L, body.get("amount"));                 // rupees -> paise
        assertEquals("rzp_test_key", body.get("keyId"));
        assertFalse(body.containsKey("keySecret"));               // secret must never leave the server
        verify(repo).save(any(PaymentEntity.class));
    }

    @Test
    void createOrderReusesExistingUnpaidRazorpayOrder() {
        PaymentEntity existing = payment(7, "order_old", "CREATED", 500);
        when(repo.findByOrderId(7L)).thenReturn(Optional.of(existing));

        ResponseEntity<?> r = controller.createOrder(KEY, Map.of("orderId", 7, "amount", 500));

        assertEquals(200, r.getStatusCode().value());
        assertEquals("order_old", ((Map<?, ?>) r.getBody()).get("razorpayOrderId"));
        verify(client, never()).createOrder(any());
    }

    @Test
    void createOrderRefusesAlreadyPaidOrder() {
        when(repo.findByOrderId(7L)).thenReturn(Optional.of(payment(7, "order_old", "SUCCESS", 500)));
        ResponseEntity<?> r = controller.createOrder(KEY, Map.of("orderId", 7, "amount", 500));
        assertEquals(409, r.getStatusCode().value());
    }

    @Test
    void createOrderReturns502WhenRazorpayRejects() {
        when(repo.findByOrderId(7L)).thenReturn(Optional.empty());
        when(client.createOrder(any())).thenThrow(
                new RestClientResponseException("bad", 401, "Unauthorized", null, null, null));

        ResponseEntity<?> r = controller.createOrder(KEY, Map.of("orderId", 7, "amount", 500));

        assertEquals(502, r.getStatusCode().value());
        verify(repo, never()).save(any());
    }

    @Test
    void createOrderReturns503WhenNotConfigured() {
        when(client.configured()).thenReturn(false);
        ResponseEntity<?> r = controller.createOrder(KEY, Map.of("orderId", 7, "amount", 500));
        assertEquals(503, r.getStatusCode().value());
    }
}
