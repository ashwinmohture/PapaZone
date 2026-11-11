package com.papazone.ai.client;

import com.fasterxml.jackson.databind.JsonNode;

/** Small helpers for reading Anthropic Messages API responses. */
public final class LlmJson {

    private LlmJson() {
    }

    /** Input object of the first tool_use block with this name, or null. */
    public static JsonNode firstToolUse(JsonNode response, String toolName) {
        if (response == null) return null;

        for (JsonNode block : response.path("content")) {
            if ("tool_use".equals(block.path("type").asText())
                    && toolName.equals(block.path("name").asText())) {
                return block.path("input");
            }
        }
        return null;
    }

    /** All text blocks joined together. */
    public static String text(JsonNode response) {
        if (response == null) return "";

        StringBuilder sb = new StringBuilder();
        for (JsonNode block : response.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(block.path("text").asText(""));
            }
        }
        return sb.toString().trim();
    }
}
