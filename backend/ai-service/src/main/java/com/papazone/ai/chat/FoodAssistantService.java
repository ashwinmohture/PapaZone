package com.papazone.ai.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.papazone.ai.client.AnthropicClient;
import com.papazone.ai.client.LlmJson;
import com.papazone.ai.client.MenuClient;
import com.papazone.ai.config.AiProperties;
import com.papazone.ai.model.MenuEntry;
import com.papazone.ai.recommend.RecommendationService;
import com.papazone.ai.web.AiException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Feature 1 - AI food ordering assistant.
 *
 * The model can only RECOMMEND (via a forced tool call). It never touches the
 * cart: the customer confirms by pressing "Add" in the UI, which calls the
 * normal cart API.
 */
@Service
public class FoodAssistantService {

    private final AnthropicClient llm;
    private final MenuClient menuClient;
    private final RecommendationService recommendations;
    private final AiProperties props;

    public FoodAssistantService(
            AnthropicClient llm,
            MenuClient menuClient,
            RecommendationService recommendations,
            AiProperties props
    ) {
        this.llm = llm;
        this.menuClient = menuClient;
        this.recommendations = recommendations;
        this.props = props;
    }

    /** customerId comes from the verified JWT (null for visitors). */
    public ChatDtos.Response chat(ChatDtos.Request request, Long customerId) {
        List<ChatDtos.Message> history;
        try {
            history = ChatLogic.sanitize(
                    request == null ? null : request.messages(),
                    props.limits().maxMessageChars(),
                    props.limits().maxHistory());
        } catch (IllegalArgumentException e) {
            throw new AiException(400, e.getMessage());
        }

        List<MenuEntry> menu = menuClient.availableItems();
        if (menu.isEmpty()) {
            throw new AiException(503, "The menu is not available right now.");
        }

        List<String> previouslyOrdered = recommendations.topOrderedNames(customerId, 5);

        ObjectNode body = llm.newRequest(ChatLogic.systemPrompt(menu, previouslyOrdered));

        ArrayNode messages = body.putArray("messages");
        for (ChatDtos.Message m : history) {
            messages.addObject().put("role", m.role()).put("content", m.content());
        }

        body.putArray("tools").add(ChatLogic.toolDefinition());
        body.set("tool_choice", ChatLogic.forcedToolChoice());

        JsonNode response = llm.send(body);

        JsonNode input = LlmJson.firstToolUse(response, ChatLogic.TOOL);
        if (input == null) {
            throw new AiException(502, "The AI assistant returned an unexpected answer. Please try again.");
        }

        return ChatLogic.toResponse(input, menu);
    }
}
