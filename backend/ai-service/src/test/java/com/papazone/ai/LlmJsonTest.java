package com.papazone.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.papazone.ai.client.LlmJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmJsonTest {

    private static JsonNode json(String s) {
        try {
            return new ObjectMapper().readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void findsTheNamedToolUseBlock() {
        JsonNode resp = json("{\"content\":[{\"type\":\"text\",\"text\":\"thinking\"},"
                + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"recommend_items\",\"input\":{\"reply\":\"hi\"}}]}");

        assertEquals("hi", LlmJson.firstToolUse(resp, "recommend_items").get("reply").asText());
        assertEquals(null, LlmJson.firstToolUse(resp, "other_tool"));
        assertEquals(null, LlmJson.firstToolUse(null, "x"));
    }

    @Test
    void joinsTextBlocks() {
        JsonNode resp = json("{\"content\":[{\"type\":\"text\",\"text\":\"Line one\"},"
                + "{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"n\",\"input\":{}},"
                + "{\"type\":\"text\",\"text\":\"Line two\"}]}");

        assertEquals("Line one\nLine two", LlmJson.text(resp));
        assertTrue(LlmJson.text(null).isEmpty());
    }
}
