package com.papazone.ai;

import com.papazone.ai.analytics.AnalyticsEngine;
import com.papazone.ai.analytics.AnalyticsEngine.Query;
import com.papazone.ai.analytics.Metric;
import com.papazone.ai.analytics.Period;
import com.papazone.ai.model.MenuEntry;
import com.papazone.ai.model.OrderRow;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsEngineTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // "Now" = 6 Nov 2026, 15:30 IST
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC);
    private final AnalyticsEngine engine = new AnalyticsEngine(clock, IST);

    private final List<MenuEntry> menu = List.of(
            new MenuEntry(1L, "Margherita Pizza", "", "Pizza", 299, null, true),
            new MenuEntry(2L, "Farmhouse Pizza", "", "Pizza", 449, null, true),
            new MenuEntry(6L, "Coke", "", "Beverages", 59, null, true));

    private static OrderRow order(long id, String createdAt, String status, double total, String items) {
        return new OrderRow(id, 1L, Instant.parse(createdAt), status, total, total, items);
    }

    private static String line(int menuId, String name, double price, int qty) {
        return "{id=1, customerId=1, menuItemId=" + menuId + ", name=" + name
                + ", unitPrice=" + price + ", quantity=" + qty + "}";
    }

    private final List<OrderRow> orders = List.of(
            // 00:30 IST on 6 Nov -> TODAY (it is still 5 Nov in UTC!)
            order(1, "2026-11-05T19:00:00Z", "PLACED", 700,
                    "[" + line(1, "Margherita Pizza", 299, 2) + ", " + line(6, "Coke", 59, 2) + "]"),
            order(2, "2026-11-06T06:00:00Z", "DELIVERED", 500,
                    "[" + line(2, "Farmhouse Pizza", 449, 1) + "]"),
            order(3, "2026-11-06T07:00:00Z", "CANCELLED", 999,
                    "[" + line(1, "Margherita Pizza", 299, 9) + "]"),
            // 23:30 IST on 5 Nov -> YESTERDAY
            order(4, "2026-11-05T18:00:00Z", "DELIVERED", 300,
                    "[" + line(1, "Margherita Pizza", 299, 1) + "]"),
            // October -> LAST_MONTH
            order(5, "2026-10-15T10:00:00Z", "DELIVERED", 1000, "[]")
    );

    private Map<String, Object> run(Metric m, Period p, String cat, Integer limit) {
        return engine.run(new Query(m, p, cat, limit), orders, menu);
    }

    @Test
    void countsTodayUsingTheBusinessTimeZone() {
        Map<String, Object> r = run(Metric.ORDERS_COUNT, Period.TODAY, null, null);

        assertEquals(3, r.get("totalReceived"));
        assertEquals(1, r.get("cancelled"));
        assertEquals(2, r.get("excludingCancelled"));
        assertEquals("2026-11-06", r.get("fromDate"));
    }

    @Test
    void yesterdayIsSeparateFromToday() {
        assertEquals(1, run(Metric.ORDERS_COUNT, Period.YESTERDAY, null, null).get("totalReceived"));
    }

    @Test
    void revenueExcludesCancelledOrders() {
        assertEquals(1200.0, (double) run(Metric.REVENUE, Period.TODAY, null, null).get("revenue"), 0.001);
    }

    @Test
    void thisMonthRevenueAndAverage() {
        assertEquals(1500.0, (double) run(Metric.REVENUE, Period.THIS_MONTH, null, null).get("revenue"), 0.001);
        assertEquals(500.0, (double) run(Metric.AVERAGE_ORDER_VALUE, Period.THIS_MONTH, null, null)
                .get("averageOrderValue"), 0.001);
    }

    @Test
    void lastMonthOnlyContainsOctober() {
        assertEquals(1000.0, (double) run(Metric.REVENUE, Period.LAST_MONTH, null, null).get("revenue"), 0.001);
    }

    @Test
    @SuppressWarnings("unchecked")
    void topItemsCanBeRestrictedToACategory() {
        Map<String, Object> r = run(Metric.TOP_ITEMS, Period.THIS_MONTH, "pizza", 5);
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.get("items");

        // cancelled order (9x Margherita) must not count: 2 (today) + 1 (yesterday) = 3
        assertEquals("Margherita Pizza", items.get(0).get("name"));
        assertEquals(3, items.get(0).get("unitsSold"));
        assertEquals("Farmhouse Pizza", items.get(1).get("name"));
        assertEquals(2, items.size()); // Coke is not a Pizza
    }

    @Test
    void unknownCategoryIsReportedNotExecuted() {
        Map<String, Object> r = run(Metric.TOP_ITEMS, Period.TODAY, "Sushi", null);

        assertTrue(r.get("error").toString().contains("Unknown category"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ordersByStatusGroupsCorrectly() {
        Map<String, Integer> by = (Map<String, Integer>) run(Metric.ORDERS_BY_STATUS, Period.THIS_MONTH, null, null)
                .get("byStatus");

        assertEquals(1, by.get("PLACED"));
        assertEquals(2, by.get("DELIVERED"));
        assertEquals(1, by.get("CANCELLED"));
    }

    @Test
    void wireNamesRoundTripAndRejectUnknownValues() {
        assertEquals(Metric.TOP_ITEMS, Metric.fromWire("TOP_ITEMS").orElseThrow());
        assertEquals(Period.LAST_7_DAYS, Period.fromWire(" last_7_days ").orElseThrow());
        assertTrue(Metric.fromWire("drop table orders").isEmpty());
        assertTrue(Period.fromWire(null).isEmpty());
    }
}
