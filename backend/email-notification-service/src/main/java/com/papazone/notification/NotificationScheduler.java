
package com.papazone.notification;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationScheduler {

    private final NotificationService notificationService;

    public NotificationScheduler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Scheduled(fixedDelay = 5000)
    public void processPendingNotifications() {
        notificationService.processPendingNotifications();
    }
}
