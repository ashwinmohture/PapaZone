
package com.papazone.order;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    // Get orders for a specific customer
    List<OrderEntity> findByCustomerIdOrderByCreatedAtDesc(Long id);

    // Get all orders for Admin Dashboard
    List<OrderEntity> findAllByOrderByCreatedAtDesc();

}
