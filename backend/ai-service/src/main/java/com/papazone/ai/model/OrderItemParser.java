package com.papazone.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses OrderEntity.itemsJson.
 *
 * order-service currently stores {@code List.toString()} (Java format) instead of JSON:
 * <pre>[{id=3, customerId=1, menuItemId=6, name=Coke, unitPrice=59.0, quantity=1}]</pre>
 * so this parser accepts both real JSON and the legacy Java format
 * (same approach as parseOrderItems() in the React app).
 */
public final class OrderItemParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern BLOCK = Pattern.compile("\\{([^{}]+)}");
    private static final Pattern MENU_ID = Pattern.compile("menuItemId=(\\d+)");
    private static final Pattern NAME = Pattern.compile("name=(.*?),\\s*unitPrice=");
    private static final Pattern PRICE = Pattern.compile("unitPrice=([\\d.]+)");
    private static final Pattern QTY = Pattern.compile("quantity=(\\d+)");

    private OrderItemParser() {
    }

    public static List<OrderLine> parse(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) {
            return List.of();
        }

        List<OrderLine> fromJson = tryJson(itemsJson);
        return fromJson != null ? fromJson : parseJavaFormat(itemsJson);
    }

    private static List<OrderLine> tryJson(String text) {
        try {
            JsonNode root = MAPPER.readTree(text);
            if (root == null || !root.isArray()) {
                return null;
            }

            List<OrderLine> lines = new ArrayList<>();
            for (JsonNode n : root) {
                lines.add(new OrderLine(
                        n.hasNonNull("menuItemId") ? n.get("menuItemId").asLong() : null,
                        n.path("name").asText("Unknown item"),
                        n.path("unitPrice").asDouble(0),
                        n.path("quantity").asInt(0)
                ));
            }
            return lines;
        } catch (Exception notJson) {
            return null;
        }
    }

    private static List<OrderLine> parseJavaFormat(String text) {
        List<OrderLine> lines = new ArrayList<>();
        Matcher blocks = BLOCK.matcher(text);

        while (blocks.find()) {
            String b = blocks.group(1);

            Matcher id = MENU_ID.matcher(b);
            Matcher name = NAME.matcher(b);
            Matcher price = PRICE.matcher(b);
            Matcher qty = QTY.matcher(b);

            lines.add(new OrderLine(
                    id.find() ? Long.valueOf(id.group(1)) : null,
                    name.find() ? name.group(1).trim() : "Unknown item",
                    price.find() ? Double.parseDouble(price.group(1)) : 0,
                    qty.find() ? Integer.parseInt(qty.group(1)) : 0
            ));
        }
        return lines;
    }
}
