package com.papazone.order;

/** What has to happen once an order is confirmed (event, delivery, cart clear). An interface so it can be mocked in tests. */
public interface OrderFulfillment {
    void onConfirmed(OrderEntity order);
}
