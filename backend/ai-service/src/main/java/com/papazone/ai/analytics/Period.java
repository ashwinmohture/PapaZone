package com.papazone.ai.analytics;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The ONLY time windows the AI may ask for. Wire format is lower-case. */
public enum Period {
    TODAY, YESTERDAY, LAST_7_DAYS, LAST_30_DAYS, THIS_MONTH, LAST_MONTH, ALL_TIME;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Period> fromWire(String s) {
        if (s == null) return Optional.empty();
        return Arrays.stream(values()).filter(p -> p.wire().equals(s.trim().toLowerCase(Locale.ROOT))).findFirst();
    }

    public static List<String> wireValues() {
        return Arrays.stream(values()).map(Period::wire).toList();
    }
}
