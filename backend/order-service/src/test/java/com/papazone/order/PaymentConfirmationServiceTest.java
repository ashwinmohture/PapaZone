package com.papazone.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentConfirmationServiceTest {

    private OrderRepository repo;
    private OrderFulfillment fulfillment;
    private PaymentConfirmationService service;

    @BeforeEach
    void setUp() {
        repo = mock(OrderRepository.class);
        fulfillment = mock(OrderFulfillment.class);
        // Simulate JPA: save() returns the same instance.
        when(repo.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new PaymentConfirmationService(repo, fulfillment);
    }

    private OrderEntity order(long id, String status, String paymentStatus) {
        OrderEntity o = new OrderEntity();
        ReflectionTestUtils.setField(o, "id", id);
        o.setStatus(status);
        o.setPaymentStatus(paymentStatus);
        when(repo.findById(id)).thenReturn(Optional.of(o));
        return o;
    }

    // ------------------------------------------------------------ confirmPaid

    @Test
    void pendingOrderBecomesPlacedAndPaidAndIsFulfilled() {
        OrderEntity o = order(1, "PAYMENT_PENDING", "PENDING");

        OrderEntity result = service.confirmPaid(1L);

        assertEquals("PLACED", result.getStatus());
        assertEquals("PAID", result.getPaymentStatus());
        verify(repo).save(o);
        verify(fulfillment).onConfirmed(o);
    }

    @Test
    void secondConfirmationDoesNotFulfilTwice() {
        order(1, "PAYMENT_PENDING", "PENDING");

        service.confirmPaid(1L);   // browser verify
        service.confirmPaid(1L);   // webhook arrives afterwards

        verify(fulfillment, times(1)).onConfirmed(any());
        verify(repo, times(1)).save(any());
    }

    @Test
    void unknownOrderReturnsNull() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertNull(service.confirmPaid(99L));
        verifyNoInteractions(fulfillment);
    }

    @Test
    void alreadyPlacedOrderIsLeftAlone() {
        OrderEntity o = order(2, "PREPARING", "PAID");
        service.confirmPaid(2L);
        assertEquals("PREPARING", o.getStatus());
        verifyNoInteractions(fulfillment);
    }

    @Test
    void paymentAfterAutoCancellationIsFlaggedForRefundAndNotFulfilled() {
        OrderEntity o = order(3, "CANCELLED", "FAILED");

        service.confirmPaid(3L);

        assertEquals("CANCELLED", o.getStatus());
        assertEquals("REFUND_REQUIRED", o.getPaymentStatus());
        verifyNoInteractions(fulfillment);
    }

    @Test
    void refundedOrderIsNotFlaggedAgain() {
        OrderEntity o = order(4, "CANCELLED", "REFUNDED");
        service.confirmPaid(4L);
        assertEquals("REFUNDED", o.getPaymentStatus());
        verify(repo, never()).save(any());
    }

    // ------------------------------------------------------------ cancelAbandonedOrders

    @Test
    void staleUnpaidOrdersAreCancelled() {
        OrderEntity stale = order(10, "PAYMENT_PENDING", "PENDING");
        when(repo.findByStatusAndCreatedAtBefore(eq("PAYMENT_PENDING"), any(Instant.class)))
                .thenReturn(List.of(stale));

        int cancelled = service.cancelAbandonedOrders();

        assertEquals(1, cancelled);
        assertEquals("CANCELLED", stale.getStatus());
        assertEquals("FAILED", stale.getPaymentStatus());
    }

    @Test
    void orderPaidJustBeforeCleanupIsNotCancelled() {
        // The query still returned it, but it was confirmed in the meantime.
        OrderEntity stale = new OrderEntity();
        ReflectionTestUtils.setField(stale, "id", 11L);
        stale.setStatus("PAYMENT_PENDING");

        OrderEntity fresh = new OrderEntity();
        ReflectionTestUtils.setField(fresh, "id", 11L);
        fresh.setStatus("PLACED");
        fresh.setPaymentStatus("PAID");

        when(repo.findByStatusAndCreatedAtBefore(eq("PAYMENT_PENDING"), any(Instant.class)))
                .thenReturn(List.of(stale));
        when(repo.findById(11L)).thenReturn(Optional.of(fresh));

        assertEquals(0, service.cancelAbandonedOrders());
        assertEquals("PLACED", fresh.getStatus());
        verify(repo, never()).save(any());
    }

    @Test
    void cutoffUsesTheConfiguredTtl() {
        ReflectionTestUtils.setField(service, "unpaidOrderTtlMinutes", 30L);
        when(repo.findByStatusAndCreatedAtBefore(any(), any())).thenReturn(List.of());

        Instant before = Instant.now().minusSeconds(30 * 60);
        service.cancelAbandonedOrders();
        Instant after = Instant.now().minusSeconds(30 * 60);

        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(repo).findByStatusAndCreatedAtBefore(eq("PAYMENT_PENDING"), captor.capture());
        assertFalse(captor.getValue().isBefore(before));
        assertFalse(captor.getValue().isAfter(after));
    }
}
