package com.papazone.ai.recommend;

import com.papazone.ai.client.MenuClient;
import com.papazone.ai.client.OrderClient;
import com.papazone.ai.model.OrderItemParser;
import com.papazone.ai.model.OrderLine;
import com.papazone.ai.model.OrderRow;
import com.papazone.ai.web.AiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
    private static final Duration POPULAR_WINDOW = Duration.ofDays(30);
    private static final Duration POPULAR_TTL = Duration.ofMinutes(5);

    private record Cached(List<OrderLine> lines, Instant at) {
    }

    private final MenuClient menu;
    private final OrderClient orders;
    private final Clock clock;
    private final RecommendationEngine engine = new RecommendationEngine();

    private volatile Cached popular = new Cached(List.of(), Instant.EPOCH);

    public RecommendationService(MenuClient menu, OrderClient orders, Clock clock) {
        this.menu = menu;
        this.orders = orders;
        this.clock = clock;
    }

    /** customerId may be null (anonymous visitor): they get popular items. */
    public List<RecommendationView> forCustomer(Long customerId, int limit) {
        return engine
                .recommend(menu.allItems(), historyOf(customerId), popularLines(), limit)
                .stream()
                .map(RecommendationView::from)
                .toList();
    }

    /** Names of the dishes a customer ordered most (used to personalise the chat). */
    public List<String> topOrderedNames(Long customerId, int n) {
        Map<String, Integer> units = new HashMap<>();
        for (OrderLine l : historyOf(customerId)) {
            if (l.quantity() > 0 && l.name() != null) units.merge(l.name(), l.quantity(), Integer::sum);
        }
        return units.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }

    // ---------- data ----------

    private List<OrderLine> historyOf(Long customerId) {
        if (customerId == null || customerId <= 0) return List.of();

        try {
            return orders.customerOrders(customerId).stream()
                    .filter(o -> !o.cancelled())
                    .flatMap(o -> OrderItemParser.parse(o.itemsJson()).stream())
                    .toList();
        } catch (AiException e) {
            return List.of(); // personalisation is best-effort
        }
    }

    private List<OrderLine> popularLines() {
        Cached current = popular;
        Instant now = clock.instant();

        if (Duration.between(current.at(), now).compareTo(POPULAR_TTL) < 0) {
            return current.lines();
        }

        try {
            List<OrderLine> lines = orders.export(now.minus(POPULAR_WINDOW), now.plusSeconds(60)).stream()
                    .filter(o -> !o.cancelled())
                    .map(OrderRow::itemsJson)
                    .flatMap(json -> OrderItemParser.parse(json).stream())
                    .toList();

            popular = new Cached(lines, now);
            return lines;

        } catch (AiException e) {
            log.warn("Popularity data unavailable: {}", e.getMessage());
            popular = new Cached(current.lines(), now.minus(POPULAR_TTL).plusSeconds(30)); // retry in 30 s
            return current.lines();
        }
    }
}
