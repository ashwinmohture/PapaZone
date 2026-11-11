package com.papazone.ai.recommend;

/** JSON returned by GET /ai/recommendations. kind: REORDER | SIMILAR | POPULAR | MENU */
public record RecommendationView(
        Long id,
        String name,
        String description,
        String category,
        double price,
        String imageUrl,
        String kind,
        String reason
) {

    public static RecommendationView from(RecommendationEngine.Recommendation r) {
        return new RecommendationView(
                r.item().id(),
                r.item().name(),
                r.item().description(),
                r.item().category(),
                r.item().price(),
                r.item().imageUrl(),
                r.kind().name(),
                r.reason());
    }
}
