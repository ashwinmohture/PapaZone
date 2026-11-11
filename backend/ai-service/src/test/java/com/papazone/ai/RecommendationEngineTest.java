package com.papazone.ai;

import com.papazone.ai.model.MenuEntry;
import com.papazone.ai.model.OrderLine;
import com.papazone.ai.recommend.RecommendationEngine;
import com.papazone.ai.recommend.RecommendationEngine.Kind;
import com.papazone.ai.recommend.RecommendationEngine.Recommendation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationEngineTest {

    private static MenuEntry item(long id, String name, String desc, String cat, double price, boolean available) {
        return new MenuEntry(id, name, desc, cat, price, null, available);
    }

    private final List<MenuEntry> menu = List.of(
            item(1, "Paneer Pizza", "Paneer and onion", "Pizza", 349, true),
            item(2, "Paneer Tikka Pizza", "Spicy paneer tikka topping", "Pizza", 399, true),
            item(3, "Pepperoni Pizza", "Pepperoni and mozzarella", "Pizza", 499, true),
            item(4, "Garlic Breadsticks", "Oven baked garlic bread", "Sides", 149, true),
            item(5, "Choco Lava Cake", "Warm chocolate dessert", "Dessert", 129, true),
            item(6, "Coke", "Chilled soft drink", "Beverages", 59, true),
            item(7, "Retired Pizza", "No longer sold", "Pizza", 299, false)
    );

    private final RecommendationEngine engine = new RecommendationEngine();

    @Test
    void suggestsSimilarDishAfterAPreviousOrder() {
        List<OrderLine> history = List.of(new OrderLine(1L, "Paneer Pizza", 349, 1));

        List<Recommendation> recs = engine.recommend(menu, history, List.of(), 6);

        Recommendation first = recs.get(0);
        assertEquals(Kind.REORDER, first.kind());
        assertEquals(1L, first.item().id());

        Recommendation similar = recs.stream().filter(r -> r.kind() == Kind.SIMILAR).findFirst().orElseThrow();
        assertEquals("Paneer Tikka Pizza", similar.item().name());
        assertEquals("You previously ordered Paneer Pizza. Try Paneer Tikka Pizza.", similar.reason());
    }

    @Test
    void neverRecommendsUnavailableItems() {
        List<Recommendation> recs = engine.recommend(menu, List.of(), List.of(), 12);

        assertFalse(recs.stream().anyMatch(r -> r.item().id() == 7L));
    }

    @Test
    void coldStartFallsBackToPopularThenMenu() {
        List<OrderLine> everyone = List.of(
                new OrderLine(6L, "Coke", 59, 10),
                new OrderLine(5L, "Choco Lava Cake", 129, 4));

        List<Recommendation> recs = engine.recommend(menu, List.of(), everyone, 4);

        assertEquals(6L, recs.get(0).item().id());
        assertEquals(Kind.POPULAR, recs.get(0).kind());
        assertEquals(5L, recs.get(1).item().id());
        assertEquals(Kind.MENU, recs.get(2).kind());
        assertEquals(4, recs.size());
    }

    @Test
    void respectsLimitAndMatchesLegacyRowsByName() {
        // legacy row without menuItemId must still be matched by name
        List<OrderLine> history = List.of(new OrderLine(null, "coke", 59, 2));

        List<Recommendation> recs = engine.recommend(menu, history, List.of(), 2);

        assertEquals(2, recs.size());
        assertEquals(6L, recs.get(0).item().id());
    }

    @Test
    void emptyMenuGivesNoRecommendations() {
        assertTrue(engine.recommend(List.of(), List.of(), List.of(), 6).isEmpty());
    }
}
