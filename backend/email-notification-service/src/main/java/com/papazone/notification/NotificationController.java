
package com.papazone.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    @Value("${app.internal-api-key:}")
    private String internalApiKey;

    public NotificationController(
            NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public record OrderDeliveredRequest(
            Long orderId,
            Long customerId,
            String trackingCode) {
    }

    @PostMapping("/order-delivered")
    public ResponseEntity<?> orderDelivered(
            @RequestHeader(
                    value = "X-Internal-Key",
                    required = false)
            String suppliedKey,
            @RequestBody(required = false)
            OrderDeliveredRequest request) {

        if (!isValidInternalKey(suppliedKey)) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                            "message",
                            "Unauthorized internal request"));
        }

        if (request == null
                || request.orderId() == null
                || request.customerId() == null) {
            return ResponseEntity
                    .badRequest()
                    .body(Map.of(
                            "message",
                            "orderId and customerId are required"));
        }

        try {
            Notification notification =
                    notificationService.enqueueOrderDeliveredEmail(
                            request);

            return ResponseEntity
                    .accepted()
                    .body(Map.of(
                            "message",
                            "Delivery notification queued",
                            "notificationId",
                            notification.getId(),
                            "orderId",
                            notification.getOrderId(),
                            "status",
                            notification.getStatus()));

        } catch (Exception ex) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "message",
                            "Unable to queue delivery notification",
                            "orderId",
                            request.orderId()));
        }
    }

    private boolean isValidInternalKey(String suppliedKey) {

        if (internalApiKey == null
                || internalApiKey.isBlank()
                || suppliedKey == null
                || suppliedKey.isBlank()) {
            return false;
        }

        return MessageDigest.isEqual(
                internalApiKey.getBytes(StandardCharsets.UTF_8),
                suppliedKey.getBytes(StandardCharsets.UTF_8));
    }
}
