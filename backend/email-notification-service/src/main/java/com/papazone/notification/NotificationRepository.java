
package com.papazone.notification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationRepository
        extends JpaRepository<Notification, Long> {

    Optional<Notification> findByOrderIdAndEventType(
            Long orderId,
            String eventType
    );

    @Query("""
        SELECT n
        FROM Notification n
        WHERE (
            n.status IN ('PENDING', 'FAILED')
            OR n.status = 'PROCESSING'
        )
        AND n.attemptCount < :maxAttempts
        AND (n.nextRetryAt IS NULL OR n.nextRetryAt <= :now)
        ORDER BY n.createdAt ASC
        """)
    List<Notification> findNotificationsReadyForRetry(
            @Param("now") LocalDateTime now,
            @Param("maxAttempts") int maxAttempts
    );
}
