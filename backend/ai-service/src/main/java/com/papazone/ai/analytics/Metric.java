package com.papazone.ai.analytics;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The ONLY analytics the AI may run. Each one is implemented in AnalyticsEngine. */
public enum Metric {
    ORDERS_COUNT, REVENUE, AVERAGE_ORDER_VALUE, TOP_ITEMS, ORDERS_BY_STATUS;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Metric> fromWire(String s) {
        if (s == null) return Optional.empty();
        return Arrays.stream(values()).filter(m -> m.wire().equals(s.trim().toLowerCase(Locale.ROOT))).findFirst();
    }

    public static List<String> wireValues() {
        return Arrays.stream(values()).map(Metric::wire).toList();
    }
}
