
package com.papazone.order;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long customerId;

    @Column(length = 5000)
    private String itemsJson;

    private double subtotal;

    private double deliveryFee;

    private double tax;

    private double total;

    private String address;

    private String status = "PLACED";

    private String paymentStatus = "PENDING";

    private Instant createdAt = Instant.now();

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long v) {
        customerId = v;
    }

    public String getItemsJson() {
        return itemsJson;
    }

    public void setItemsJson(String v) {
        itemsJson = v;
    }

    public double getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(double v) {
        subtotal = v;
    }

    public double getDeliveryFee() {
        return deliveryFee;
    }

    public void setDeliveryFee(double v) {
        deliveryFee = v;
    }

    public double getTax() {
        return tax;
    }

    public void setTax(double v) {
        tax = v;
    }

    public double getTotal() {
        return total;
    }

    public void setTotal(double v) {
        total = v;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String v) {
        address = v;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        status = v;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(String v) {
        paymentStatus = v;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
