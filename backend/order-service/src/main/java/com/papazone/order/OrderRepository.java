
package com.papazone.order;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {
    java.util.Optional<OrderEntity> findByIdAndCustomerId(Long id, Long customerId);

    // Get orders for a specific customer
    List<OrderEntity> findByCustomerIdOrderByCreatedAtDesc(Long id);

    // Orders created in [from, to) - used by the internal export
    List<OrderEntity> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);

    // Unpaid online-payment orders older than a cutoff (used by the cleanup job)
    List<OrderEntity> findByStatusAndCreatedAtBefore(String status, Instant cutoff);

    // Get all orders for Admin Dashboard
    List<OrderEntity> findAllByOrderByCreatedAtDesc();

}
