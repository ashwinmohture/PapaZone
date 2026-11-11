package com.papazone.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.papazone.ai.admin.AdminLogic;
import com.papazone.ai.analytics.AnalyticsEngine;
import com.papazone.ai.model.MenuEntry;
import com.papazone.ai.model.OrderRow;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminLogicTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AnalyticsEngine engine = new AnalyticsEngine(
            Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC), ZoneId.of("Asia/Kolkata"));

    private final List<MenuEntry> menu = List.of(new MenuEntry(1L, "Margherita Pizza", "", "Pizza", 299, null, true));

    private static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void validCallRunsThePredefinedQuery() {
        List<OrderRow> orders = List.of(
                new OrderRow(1L, 1L, Instant.parse("2026-11-06T06:00:00Z"), "PLACED", 500, 500, "[]"));
        AtomicInteger loads = new AtomicInteger();

        AdminLogic.Outcome o = AdminLogic.runTool(
                json("{\"metric\":\"orders_count\",\"period\":\"today\"}"),
                engine, range -> {
                    loads.incrementAndGet();
                    return orders;
                }, menu);

        assertFalse(o.error());
        assertEquals(1, o.result().get("totalReceived"));
        assertEquals(1, loads.get());
    }

    @Test
    void unknownMetricOrPeriodIsRejectedWithoutTouchingTheDatabase() {
        AtomicInteger loads = new AtomicInteger();

        AdminLogic.Outcome badMetric = AdminLogic.runTool(
                json("{\"metric\":\"delete_all_orders\",\"period\":\"today\"}"),
                engine, r -> {
                    loads.incrementAndGet();
                    return List.of();
                }, menu);
        AdminLogic.Outcome badPeriod = AdminLogic.runTool(
                json("{\"metric\":\"revenue\",\"period\":\"2020-01-01; DROP TABLE orders\"}"),
                engine, r -> {
                    loads.incrementAndGet();
                    return List.of();
                }, menu);
        AdminLogic.Outcome missing = AdminLogic.runTool(json("{}"), engine, r -> List.of(), menu);

        assertTrue(badMetric.error());
        assertTrue(badPeriod.error());
        assertTrue(missing.error());
        assertEquals(0, loads.get());
    }

    @Test
    void toolSchemaOnlyOffersTheWhitelistedValues() {
        JsonNode tool = AdminLogic.toolDefinition(List.of("Pizza", "Sides"));
        JsonNode props = tool.get("input_schema").get("properties");

        assertEquals("orders_count", props.get("metric").get("enum").get(0).asText());
        assertEquals(5, props.get("metric").get("enum").size());
        assertEquals(7, props.get("period").get("enum").size());
        assertTrue(props.get("category").get("description").asText().contains("Pizza, Sides"));
    }

    @Test
    void systemPromptContainsTodayAndForbidsInventingNumbers() {
        String p = AdminLogic.systemPrompt(java.time.LocalDate.of(2026, 11, 6), ZoneId.of("Asia/Kolkata"), List.of("Pizza"));

        assertTrue(p.contains("2026-11-06"));
        assertTrue(p.contains("Never estimate or invent"));
    }
}
