
package com.papazone.order;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {
    java.util.Optional<OrderEntity> findByIdAndCustomerId(Long id, Long customerId);

    // Get orders for a specific customer
    List<OrderEntity> findByCustomerIdOrderByCreatedAtDesc(Long id);

    Page<OrderEntity> findByCustomerIdOrderByCreatedAtDesc(Long id, Pageable pageable);

    Page<OrderEntity> findByStatusNotOrderByCreatedAtDesc(String status, Pageable pageable);

    // Orders created in [from, to) - used by the internal export
    List<OrderEntity> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);

    // Unpaid online-payment orders older than a cutoff (used by the cleanup job)
    List<OrderEntity> findByStatusAndCreatedAtBefore(String status, Instant cutoff);

    // Get all orders for Admin Dashboard
    List<OrderEntity> findAllByOrderByCreatedAtDesc();

    Page<OrderEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

}
