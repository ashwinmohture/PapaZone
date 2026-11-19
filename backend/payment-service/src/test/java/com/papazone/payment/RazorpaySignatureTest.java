package com.papazone.payment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RazorpaySignatureTest {

    // Independent vectors, computed outside Java with Python's hmac/hashlib (SHA-256).
    private static final String SECRET = "test_secret";
    private static final String ORDER = "order_Abc123";
    private static final String PAYMENT = "pay_Xyz789";
    private static final String VALID_SIG = "b6c247d87924c16697d8cd4d0dcc5beda20c415a770157e8292db099de365613";

    private static final String WEBHOOK_SECRET = "whsec_test";
    private static final String WEBHOOK_BODY =
            "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_1\",\"order_id\":\"order_1\",\"amount\":50000}}}}";
    private static final String WEBHOOK_SIG = "b83d71aaee62525571e5d820325208edd312efcf0da4869c0b89716667328ab8";

    @Test
    void hmacMatchesKnownVector() {
        assertEquals(VALID_SIG, RazorpaySignature.hmacSha256Hex(SECRET, ORDER + "|" + PAYMENT));
    }

    @Test
    void validPaymentSignatureIsAccepted() {
        assertTrue(RazorpaySignature.isValidPayment(SECRET, ORDER, PAYMENT, VALID_SIG));
    }

    @Test
    void uppercaseOrPaddedSignatureIsStillAccepted() {
        assertTrue(RazorpaySignature.isValidPayment(SECRET, ORDER, PAYMENT, "  " + VALID_SIG.toUpperCase() + " "));
    }

    @Test
    void tamperedSignatureIsRejected() {
        String tampered = "0" + VALID_SIG.substring(1);
        assertFalse(RazorpaySignature.isValidPayment(SECRET, ORDER, PAYMENT, tampered));
    }

    @Test
    void signatureForAnotherPaymentIsRejected() {
        assertFalse(RazorpaySignature.isValidPayment(SECRET, ORDER, "pay_Other", VALID_SIG));
    }

    @Test
    void signatureForAnotherOrderIsRejected() {
        assertFalse(RazorpaySignature.isValidPayment(SECRET, "order_Other", PAYMENT, VALID_SIG));
    }

    @Test
    void wrongSecretIsRejected() {
        assertFalse(RazorpaySignature.isValidPayment("another_secret", ORDER, PAYMENT, VALID_SIG));
    }

    @Test
    void missingValuesAreRejectedNotThrown() {
        assertFalse(RazorpaySignature.isValidPayment(null, ORDER, PAYMENT, VALID_SIG));
        assertFalse(RazorpaySignature.isValidPayment("", ORDER, PAYMENT, VALID_SIG));
        assertFalse(RazorpaySignature.isValidPayment(SECRET, null, PAYMENT, VALID_SIG));
        assertFalse(RazorpaySignature.isValidPayment(SECRET, ORDER, "", VALID_SIG));
        assertFalse(RazorpaySignature.isValidPayment(SECRET, ORDER, PAYMENT, null));
        assertFalse(RazorpaySignature.isValidPayment(SECRET, ORDER, PAYMENT, " "));
    }

    @Test
    void validWebhookSignatureIsAccepted() {
        assertTrue(RazorpaySignature.isValidWebhook(WEBHOOK_SECRET, WEBHOOK_BODY, WEBHOOK_SIG));
    }

    @Test
    void webhookBodyChangedByOneCharacterIsRejected() {
        assertFalse(RazorpaySignature.isValidWebhook(WEBHOOK_SECRET, WEBHOOK_BODY.replace("50000", "50001"), WEBHOOK_SIG));
    }

    @Test
    void webhookWithoutSecretOrSignatureIsRejected() {
        assertFalse(RazorpaySignature.isValidWebhook("", WEBHOOK_BODY, WEBHOOK_SIG));
        assertFalse(RazorpaySignature.isValidWebhook(WEBHOOK_SECRET, null, WEBHOOK_SIG));
        assertFalse(RazorpaySignature.isValidWebhook(WEBHOOK_SECRET, WEBHOOK_BODY, null));
    }
}
