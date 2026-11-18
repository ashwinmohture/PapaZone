package com.papazone.ai.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.papazone.ai.analytics.AnalyticsEngine;
import com.papazone.ai.client.AnthropicClient;
import com.papazone.ai.client.LlmJson;
import com.papazone.ai.client.MenuClient;
import com.papazone.ai.client.OrderClient;
import com.papazone.ai.config.AiProperties;
import com.papazone.ai.model.MenuEntry;
import com.papazone.ai.web.AiException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Feature 6 - AI admin assistant.
 *
 * Natural-language question -> the model picks a whitelisted metric + period
 * -> we run the predefined query -> the model words the answer.
 * The model never sees raw orders and never writes SQL.
 */
@Service
public class AdminAssistantService {

    private static final int MAX_ROUNDS = 4;

    private final AnthropicClient llm;
    private final MenuClient menuClient;
    private final OrderClient orderClient;
    private final AiProperties props;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ZoneId zone;

    public AdminAssistantService(
            AnthropicClient llm,
            MenuClient menuClient,
            OrderClient orderClient,
            AiProperties props,
            ObjectMapper mapper,
            Clock clock,
            ZoneId zone
    ) {
        this.llm = llm;
        this.menuClient = menuClient;
        this.orderClient = orderClient;
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
        this.zone = zone;
    }

    public AdminDtos.Response ask(String question) {
        String q = question == null ? "" : question.trim();

        if (q.isEmpty()) {
            throw new AiException(400, "Please type a question.");
        }

        if (q.length() > props.limits().maxMessageChars()) {
            q = q.substring(0, props.limits().maxMessageChars());
        }

        List<MenuEntry> menu = menuClient.allItems();

        Set<String> categories =
                new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        for (MenuEntry menuEntry : menu) {
            if (menuEntry.category() != null
                    && !menuEntry.category().isBlank()) {
                categories.add(menuEntry.category());
            }
        }

        AnalyticsEngine engine = new AnalyticsEngine(clock, zone);

        ObjectNode body = llm.newRequest(
                AdminLogic.systemPrompt(
                        LocalDate.now(clock.withZone(zone)),
                        zone,
                        categories
                )
        );

        body.putArray("tools")
                .add(AdminLogic.toolDefinition(categories));

        ArrayNode messages = body.putArray("messages");
        messages.addObject()
                .put("role", "user")
                .put("content", q);

        List<Map<String, Object>> results = new ArrayList<>();

        for (int round = 0; round < MAX_ROUNDS; round++) {
            JsonNode response = llm.send(body);

            if (!"tool_use".equals(
                    response.path("stop_reason").asText()
            )) {
                String text = LlmJson.text(response);

                return new AdminDtos.Response(
                        text.isBlank()
                                ? "I could not produce an answer for that question."
                                : text,
                        results
                );
            }

            // Echo the complete assistant tool-call blocks. This retains
            // Gemini's original call ID and thought signature.
            messages.addObject()
                    .put("role", "assistant")
                    .set("content", response.get("content"));

            // Gemini expects all function responses for this assistant turn
            // together in one following user message.
            ArrayNode toolResults = mapper.createArrayNode();

            for (JsonNode block : response.path("content")) {
                if (!"tool_use".equals(block.path("type").asText())) {
                    continue;
                }

                String toolName = block.path("name").asText();

                AdminLogic.Outcome outcome =
                        AdminLogic.TOOL.equals(toolName)
                                ? AdminLogic.runTool(
                                        block.path("input"),
                                        engine,
                                        range -> orderClient.export(
                                                range.from(),
                                                range.to()
                                        ),
                                        menu
                                )
                                : new AdminLogic.Outcome(
                                        true,
                                        Map.of("error", "Unknown tool")
                                );

                if (!outcome.error()) {
                    results.add(outcome.result());
                }

                ObjectNode toolResult = toolResults.addObject();
                toolResult.put("type", "tool_result");

                // Keep the function name separate from the internal ID.
                toolResult.put("name", toolName);

                // Internal ID is used to match the result with this tool call
                // in the assistant conversation.
                toolResult.put(
                        "tool_use_id",
                        block.path("id").asText()
                );

                // Forward Gemini's provider ID only if it was present in
                // the original functionCall. AnthropicClient will use it
                // for Gemini functionResponse.id.
                JsonNode geminiCallId = block.path("gemini_call_id");

                if (geminiCallId.isTextual()
                        && !geminiCallId.asText().isBlank()) {
                    toolResult.set(
                            "gemini_call_id",
                            geminiCallId.deepCopy()
                    );
                }

                toolResult.put("content", toJson(outcome.result()));

                if (outcome.error()) {
                    toolResult.put("is_error", true);
                }
            }

            messages.addObject()
                    .put("role", "user")
                    .set("content", toolResults);
        }

        throw new AiException(
                502,
                "The assistant could not finish the analysis. Try a simpler question."
        );
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"could not serialise result\"}";
        }
    }
}
