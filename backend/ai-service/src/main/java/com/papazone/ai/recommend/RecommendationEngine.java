package com.papazone.ai.recommend;

import com.papazone.ai.model.MenuEntry;
import com.papazone.ai.model.OrderLine;

import java.util.*;

/**
 * Deterministic, explainable recommendations (no LLM, no hallucinations).
 *
 * Order of results:
 *   1. REORDER  - items this customer already ordered (max 2)
 *   2. SIMILAR  - unseen items similar to what they ordered (max 3)
 *   3. POPULAR  - unseen items ordered most by everyone
 *   4. MENU     - cold-start filler so the list is never empty
 */
public final class RecommendationEngine {

    public enum Kind { REORDER, SIMILAR, POPULAR, MENU }

    public record Recommendation(MenuEntry item, Kind kind, String reason) {
    }

    private static final Set<String> STOPWORDS =
            Set.of("a", "an", "and", "with", "the", "of", "in", "on", "for", "to", "our", "served");

    private static final double SIMILARITY_THRESHOLD = 0.25;
    private static final double NAME_WEIGHT = 0.65;
    private static final double CATEGORY_WEIGHT = 0.35;
    private static final int MAX_REORDER = 2;
    private static final int MAX_SIMILAR = 3;

    /** What we know about one dish the customer ordered. */
    private static final class Profile {
        final String name;
        final Set<String> tokens;
        final String category;
        int units;

        Profile(String name, Set<String> tokens, String category) {
            this.name = name;
            this.tokens = tokens;
            this.category = category;
        }
    }

    /**
     * @param menu         full menu (available or not)
     * @param history      line items from this customer's non-cancelled orders
     * @param popularLines line items from recent orders of all customers
     */
    public List<Recommendation> recommend(
            List<MenuEntry> menu,
            List<OrderLine> history,
            List<OrderLine> popularLines,
            int limit
    ) {
        int max = Math.max(1, Math.min(limit, 12));

        List<MenuEntry> available = menu.stream().filter(MenuEntry::available).toList();
        if (available.isEmpty()) {
            return List.of();
        }

        Map<Long, MenuEntry> byId = new HashMap<>();
        Map<String, MenuEntry> byName = new HashMap<>();
        for (MenuEntry m : menu) {
            if (m.id() != null) byId.put(m.id(), m);
            if (m.name() != null) byName.put(norm(m.name()), m);
        }

        // ---- customer profile ----
        Map<String, Profile> profiles = new LinkedHashMap<>();
        Map<Long, Integer> ownedUnits = new HashMap<>();

        for (OrderLine line : history) {
            if (line.quantity() <= 0) continue;

            MenuEntry m = resolve(line, byId, byName);
            String key = m != null ? "id:" + m.id() : "name:" + norm(line.name());

            Set<String> tokens = tokens(line.name());
            if (m != null) tokens.addAll(tokens(m.description()));

            Profile p = profiles.computeIfAbsent(key, k -> new Profile(
                    m != null ? m.name() : line.name(),
                    tokens,
                    m != null ? m.category() : null));
            p.units += line.quantity();

            if (m != null) ownedUnits.merge(m.id(), line.quantity(), Integer::sum);
        }

        // ---- global popularity ----
        Map<Long, Integer> popularity = new HashMap<>();
        for (OrderLine line : popularLines) {
            if (line.quantity() <= 0) continue;
            MenuEntry m = resolve(line, byId, byName);
            if (m != null) popularity.merge(m.id(), line.quantity(), Integer::sum);
        }

        Map<Long, Recommendation> picked = new LinkedHashMap<>();

        // 1. REORDER
        ownedUnits.entrySet().stream()
                .map(e -> Map.entry(byId.get(e.getKey()), e.getValue()))
                .filter(e -> e.getKey() != null && e.getKey().available())
                .sorted(Comparator
                        .comparing((Map.Entry<MenuEntry, Integer> e) -> e.getValue()).reversed()
                        .thenComparing(e -> e.getKey().name()))
                .limit(MAX_REORDER)
                .forEach(e -> picked.put(e.getKey().id(), new Recommendation(
                        e.getKey(), Kind.REORDER,
                        "You ordered " + e.getKey().name() + " before. Fancy it again?")));

        // 2. SIMILAR
        record Scored(MenuEntry item, double score, Profile basedOn) {
        }

        List<Scored> similar = new ArrayList<>();
        for (MenuEntry candidate : available) {
            if (ownedUnits.containsKey(candidate.id())) continue;

            Set<String> cTokens = tokens(candidate.name());
            cTokens.addAll(tokens(candidate.description()));

            Scored best = null;
            for (Profile p : profiles.values()) {
                double s = NAME_WEIGHT * jaccard(cTokens, p.tokens)
                        + (sameCategory(candidate.category(), p.category) ? CATEGORY_WEIGHT : 0);

                if (best == null || s > best.score()
                        || (s == best.score() && p.units > best.basedOn().units)) {
                    best = new Scored(candidate, s, p);
                }
            }
            if (best != null && best.score() >= SIMILARITY_THRESHOLD) {
                similar.add(best);
            }
        }

        similar.sort(Comparator
                .comparingDouble(Scored::score).reversed()
                .thenComparing(s -> -popularity.getOrDefault(s.item().id(), 0))
                .thenComparing(s -> s.item().name()));

        similar.stream().limit(MAX_SIMILAR).forEach(s -> picked.putIfAbsent(s.item().id(),
                new Recommendation(s.item(), Kind.SIMILAR,
                        "You previously ordered " + s.basedOn().name + ". Try " + s.item().name() + ".")));

        // 3. POPULAR
        available.stream()
                .filter(m -> !picked.containsKey(m.id()) && !ownedUnits.containsKey(m.id()))
                .filter(m -> popularity.getOrDefault(m.id(), 0) > 0)
                .sorted(Comparator
                        .comparing((MenuEntry m) -> popularity.get(m.id())).reversed()
                        .thenComparing(MenuEntry::name))
                .forEach(m -> picked.putIfAbsent(m.id(),
                        new Recommendation(m, Kind.POPULAR, "Popular with PapaZone customers.")));

        // 4. MENU filler (cold start)
        available.stream()
                .filter(m -> !picked.containsKey(m.id()) && !ownedUnits.containsKey(m.id()))
                .forEach(m -> picked.putIfAbsent(m.id(),
                        new Recommendation(m, Kind.MENU, "From our menu.")));

        return picked.values().stream().limit(max).toList();
    }

    // ---------- helpers ----------

    private static MenuEntry resolve(OrderLine line, Map<Long, MenuEntry> byId, Map<String, MenuEntry> byName) {
        if (line.menuItemId() != null && byId.containsKey(line.menuItemId())) {
            return byId.get(line.menuItemId());
        }
        return line.name() == null ? null : byName.get(norm(line.name()));
    }

    private static boolean sameCategory(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;

        for (String t : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() > 1 && !STOPWORDS.contains(t)) out.add(t);
        }
        return out;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;

        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);

        Set<String> union = new HashSet<>(a);
        union.addAll(b);

        return (double) inter.size() / union.size();
    }
}
