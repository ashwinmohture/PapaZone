
package com.papazone.notification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class NotificationService {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationService.class);

    private static final String ORDER_DELIVERED = "ORDER_DELIVERED";
    private static final int MAX_ATTEMPTS = 5;

    // Retry delays after failed attempts: 1, 2, 4 and 8 minutes.
    private static final int[] RETRY_DELAYS_MINUTES = {1, 2, 4, 8};

    // A PROCESSING notification can be recovered after this lease expires.
    private static final int PROCESSING_LEASE_MINUTES = 2;

    private final NotificationRepository repository;
    private final JavaMailSender mailSender;
    private final RestClient http = RestClient.create();

    @Value("${app.internal-api-key:}")
    private String internalApiKey;

    @Value("${app.customer-service-url}")
    private String customerServiceUrl;

    @Value("${app.mail.from}")
    private String fromAddress;

    public NotificationService(
            NotificationRepository repository,
            JavaMailSender mailSender) {
        this.repository = repository;
        this.mailSender = mailSender;
    }

    /**
     * Creates one notification per order and event type.
     * Repeated requests return the existing notification.
     */
    public Notification enqueueOrderDeliveredEmail(
            NotificationController.OrderDeliveredRequest request) {

        var existing = repository.findByOrderIdAndEventType(
                request.orderId(), ORDER_DELIVERED);

        if (existing.isPresent()) {
            return existing.get();
        }

        Notification notification = new Notification();
        notification.setOrderId(request.orderId());
        notification.setCustomerId(request.customerId());
        notification.setTrackingCode(request.trackingCode());
        notification.setEventType(ORDER_DELIVERED);
        notification.setStatus("PENDING");
        notification.setAttemptCount(0);
        notification.setNextRetryAt(null);

        try {
            return repository.saveAndFlush(notification);
        } catch (DataIntegrityViolationException ex) {
            // The database unique constraint protects against concurrent duplicates.
            return repository.findByOrderIdAndEventType(
                            request.orderId(), ORDER_DELIVERED)
                    .orElseThrow(() -> ex);
        }
    }

    /**
     * Called periodically by NotificationScheduler.
     */
    public void processPendingNotifications() {

        List<Notification> notifications =
                repository.findNotificationsReadyForRetry(
                        LocalDateTime.now(), MAX_ATTEMPTS);

        for (Notification notification : notifications) {
            processNotification(notification);
        }
    }

    private void processNotification(Notification notification) {

        // Claim the notification before attempting to send.
        notification.setStatus("PROCESSING");
        notification.setAttemptCount(notification.getAttemptCount() + 1);
        notification.setNextRetryAt(
                LocalDateTime.now().plusMinutes(PROCESSING_LEASE_MINUTES));

        repository.save(notification);

        try {
            sendEmail(notification);

            notification.setStatus("SENT");
            notification.setSentAt(LocalDateTime.now());
            notification.setNextRetryAt(null);
            notification.setLastError(null);

            repository.save(notification);

            log.info("Delivery email sent for order {}",
                    notification.getOrderId());

        } catch (Exception ex) {

            int attempts = notification.getAttemptCount();

            notification.setStatus("FAILED");
            notification.setLastError(truncateError(ex.getMessage()));

            if (attempts < MAX_ATTEMPTS) {
                int delay = RETRY_DELAYS_MINUTES[attempts - 1];

                notification.setNextRetryAt(
                        LocalDateTime.now().plusMinutes(delay));

                log.warn(
                        "Email failed for order {}. Attempt {} of {}. Retrying in {} minute(s).",
                        notification.getOrderId(),
                        attempts,
                        MAX_ATTEMPTS,
                        delay,
                        ex
                );
            } else {
                notification.setNextRetryAt(null);

                log.error(
                        "Email permanently failed for order {} after {} attempts.",
                        notification.getOrderId(),
                        attempts,
                        ex
                );
            }

            repository.save(notification);
        }
    }

    private void sendEmail(Notification notification) {

        CustomerResponse customer = http.get()
                .uri(customerServiceUrl + "/customers/internal/"
                        + notification.getCustomerId())
                .header("X-Internal-Key", internalApiKey)
                .retrieve()
                .body(CustomerResponse.class);

        if (customer == null
                || customer.email() == null
                || customer.email().isBlank()) {
            throw new IllegalStateException(
                    "Customer email was not found");
        }

        String customerName =
                customer.name() == null ? "Customer" : customer.name();

        String tracking = notification.getTrackingCode() == null
                || notification.getTrackingCode().isBlank()
                ? "Not available"
                : notification.getTrackingCode();

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(customer.email());
        message.setSubject(
                "Your PapaZone order has been delivered!");

        message.setText(
                "Hi " + customerName + ",\n\n"
                        + "Great news! Your PapaZone order has been successfully delivered.\n\n"
                        + "Order ID: #" + notification.getOrderId() + "\n"
                        + "Tracking code: " + tracking + "\n"
                        + "Delivery status: DELIVERED\n\n"
                        + "Thank you for ordering with PapaZone. "
                        + "We hope you enjoy your meal!\n\n"
                        + "Regards,\nPapaZone Team"
        );

        mailSender.send(message);
    }

    private String truncateError(String error) {
        if (error == null || error.isBlank()) {
            return "Unknown email delivery error";
        }

        return error.length() > 1900
                ? error.substring(0, 1900)
                : error;
    }

    private record CustomerResponse(
            Long id,
            String name,
            String email) {
    }
}
