package com.papazone.ai.analytics;

import com.papazone.ai.model.MenuEntry;
import com.papazone.ai.model.OrderItemParser;
import com.papazone.ai.model.OrderLine;
import com.papazone.ai.model.OrderRow;

import java.time.Clock;
import java.time.ZoneId;
import java.util.*;

/**
 * Safe, predefined, read-only analytics. The LLM never writes queries:
 * it can only choose a Metric + Period (+ optional category / limit),
 * every value of which is validated here.
 */
public final class AnalyticsEngine {

    public static final int DEFAULT_LIMIT = 5;
    public static final int MAX_LIMIT = 10;

    public record Query(Metric metric, Period period, String category, Integer limit) {
    }

    private final Clock clock;
    private final ZoneId zone;

    public AnalyticsEngine(Clock clock, ZoneId zone) {
        this.clock = clock;
        this.zone = zone;
    }

    /** The concrete date range a period covers (used to fetch only the needed orders). */
    public PeriodResolver.Range rangeFor(Period period) {
        return PeriodResolver.resolve(period, clock, zone);
    }

    public Map<String, Object> run(Query q, List<OrderRow> orders, List<MenuEntry> menu) {
        PeriodResolver.Range range = PeriodResolver.resolve(q.period(), clock, zone);

        List<OrderRow> inRange = orders.stream()
                .filter(o -> o.createdAt() != null
                        && !o.createdAt().isBefore(range.from())
                        && o.createdAt().isBefore(range.to()))
                .toList();

        List<OrderRow> valid = inRange.stream().filter(o -> !o.cancelled()).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metric", q.metric().wire());
        out.put("period", q.period().wire());
        out.put("fromDate", range.firstDay().toString());
        out.put("toDate", range.lastDay().toString());
        out.put("currency", "INR");

        switch (q.metric()) {
            case ORDERS_COUNT -> {
                out.put("totalReceived", inRange.size());
                out.put("cancelled", inRange.size() - valid.size());
                out.put("excludingCancelled", valid.size());
            }
            case REVENUE -> {
                out.put("revenue", round2(valid.stream().mapToDouble(OrderRow::total).sum()));
                out.put("ordersCounted", valid.size());
                out.put("note", "Cancelled orders are excluded. Includes delivery fee and tax.");
            }
            case AVERAGE_ORDER_VALUE -> {
                double sum = valid.stream().mapToDouble(OrderRow::total).sum();
                out.put("averageOrderValue", valid.isEmpty() ? 0.0 : round2(sum / valid.size()));
                out.put("ordersCounted", valid.size());
            }
            case ORDERS_BY_STATUS -> {
                Map<String, Integer> byStatus = new TreeMap<>();
                for (OrderRow o : inRange) {
                    byStatus.merge(o.status() == null ? "UNKNOWN" : o.status().toUpperCase(Locale.ROOT), 1, Integer::sum);
                }
                out.put("byStatus", byStatus);
            }
            case TOP_ITEMS -> topItems(q, valid, menu, out);
        }
        return out;
    }

    private void topItems(Query q, List<OrderRow> valid, List<MenuEntry> menu, Map<String, Object> out) {
        int limit = q.limit() == null ? DEFAULT_LIMIT : Math.max(1, Math.min(q.limit(), MAX_LIMIT));

        Map<Long, MenuEntry> byId = new HashMap<>();
        Map<String, MenuEntry> byName = new HashMap<>();
        Set<String> categories = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (MenuEntry m : menu) {
            if (m.id() != null) byId.put(m.id(), m);
            if (m.name() != null) byName.put(m.name().trim().toLowerCase(Locale.ROOT), m);
            if (m.category() != null) categories.add(m.category());
        }

        String wanted = q.category() == null || q.category().isBlank() ? null : q.category().trim();
        if (wanted != null && !categories.contains(wanted)) {
            out.put("error", "Unknown category '" + wanted + "'. Valid categories: " + categories);
            return;
        }
        if (wanted != null) {
            out.put("category", wanted);
        }

        record Agg(String name, int[] units, double[] revenue) {
        }
        Map<String, Agg> totals = new HashMap<>();

        for (OrderRow o : valid) {
            for (OrderLine line : OrderItemParser.parse(o.itemsJson())) {
                if (line.quantity() <= 0) continue;

                MenuEntry m = line.menuItemId() != null ? byId.get(line.menuItemId()) : null;
                if (m == null && line.name() != null) {
                    m = byName.get(line.name().trim().toLowerCase(Locale.ROOT));
                }

                if (wanted != null && (m == null || m.category() == null || !m.category().equalsIgnoreCase(wanted))) {
                    continue;
                }

                String name = m != null ? m.name() : line.name();
                Agg agg = totals.computeIfAbsent(name.toLowerCase(Locale.ROOT),
                        k -> new Agg(name, new int[1], new double[1]));
                agg.units()[0] += line.quantity();
                agg.revenue()[0] += line.unitPrice() * line.quantity();
            }
        }

        List<Map<String, Object>> items = totals.values().stream()
                .sorted(Comparator
                        .comparingInt((Agg a) -> a.units()[0]).reversed()
                        .thenComparing(Comparator.comparingDouble((Agg a) -> a.revenue()[0]).reversed())
                        .thenComparing(Agg::name))
                .limit(limit)
                .map(a -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", a.name());
                    row.put("unitsSold", a.units()[0]);
                    row.put("revenue", round2(a.revenue()[0]));
                    return row;
                })
                .toList();

        out.put("items", items);
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
