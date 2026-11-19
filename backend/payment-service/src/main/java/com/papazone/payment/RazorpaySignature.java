package com.papazone.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** HMAC-SHA256 helpers for Razorpay payment and webhook signatures (kept separate so they are unit-testable). */
public final class RazorpaySignature {

    private RazorpaySignature() {
    }

    public static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    /** Checkout signature = HMAC_SHA256(razorpay_order_id + "|" + razorpay_payment_id, key_secret). */
    public static boolean isValidPayment(String secret, String orderId, String paymentId, String signature) {
        if (blank(secret) || blank(orderId) || blank(paymentId) || blank(signature)) return false;
        return constantTimeEquals(hmacSha256Hex(secret, orderId + "|" + paymentId), signature);
    }

    /** Webhook signature = HMAC_SHA256(raw request body, webhook_secret). */
    public static boolean isValidWebhook(String webhookSecret, String rawBody, String signature) {
        if (blank(webhookSecret) || rawBody == null || blank(signature)) return false;
        return constantTimeEquals(hmacSha256Hex(webhookSecret, rawBody), signature);
    }

    private static boolean constantTimeEquals(String expectedHex, String provided) {
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8),
                provided.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
