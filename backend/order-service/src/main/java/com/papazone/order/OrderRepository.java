
package com.papazone.order;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    // Get orders for a specific customer
    List<OrderEntity> findByCustomerIdOrderByCreatedAtDesc(Long id);

    // Orders created in [from, to) - used by the internal export
    List<OrderEntity> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);

    // Get all orders for Admin Dashboard
    List<OrderEntity> findAllByOrderByCreatedAtDesc();

}
