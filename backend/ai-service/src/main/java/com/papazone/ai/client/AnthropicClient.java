
package com.papazone.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.papazone.ai.config.AiProperties;
import com.papazone.ai.web.AiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Iterator;
import java.util.Map;

@Component
public class AnthropicClient {

    private static final Logger log =
            LoggerFactory.getLogger(AnthropicClient.class);

    private final RestClient http;
    private final AiProperties props;
    private final ObjectMapper mapper;

    public AnthropicClient(
            @Qualifier("llmRestClient") RestClient http,
            AiProperties props,
            ObjectMapper mapper
    ) {
        this.http = http;
        this.props = props;
        this.mapper = mapper;
    }

    public ObjectNode newRequest(String system) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.llm().model());
        body.put("max_tokens", props.llm().maxTokens());
        body.put("system", system);
        return body;
    }

    public JsonNode send(ObjectNode body) {
        if (!props.llm().configured()) {
            throw new AiException(
                    503, "The AI assistant is not configured yet.");
        }

        try {
            ObjectNode geminiRequest = convertRequest(body);

            JsonNode response = http.post()
                    .uri("/v1beta/models/{model}:generateContent",
                            props.llm().model())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("x-goog-api-key", props.llm().apiKey())
                    .body(geminiRequest)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) {
                throw new AiException(
                        502, "The AI assistant returned an empty response.");
            }

            return convertResponse(response);

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();

            // Do not log request headers or API keys.
            log.error("Gemini provider error {}: {}",
                    status, abbreviate(e.getResponseBodyAsString()));

            if (status == 429 || status == 503) {
                throw new AiException(
                        503,
                        "The AI assistant is busy right now. Please try again shortly.");
            }

            throw new AiException(
                    502,
                    "The AI assistant is temporarily unavailable.");

        } catch (ResourceAccessException e) {
            log.error("Gemini provider unreachable or timed out: {}",
                    e.getMessage());

            throw new AiException(
                    504,
                    "The AI assistant took too long to respond. Please try again.");
        }
    }

    private ObjectNode convertRequest(ObjectNode source) {
        ObjectNode target = mapper.createObjectNode();

        // System instruction
        if (source.hasNonNull("system")) {
            target.putObject("systemInstruction")
                    .putArray("parts")
                    .addObject()
                    .put("text", source.path("system").asText());
        }

        // Conversation history
        ArrayNode contents = target.putArray("contents");

        for (JsonNode message : source.path("messages")) {
            String role = message.path("role").asText("user");
            JsonNode content = message.path("content");

            ObjectNode geminiContent = contents.addObject();
            geminiContent.put(
                    "role",
                    "assistant".equals(role) ? "model" : "user"
            );

            ArrayNode parts = geminiContent.putArray("parts");

            if (content.isTextual()) {
                parts.addObject().put("text", content.asText());
                continue;
            }

            if (!content.isArray()) {
                continue;
            }

            for (JsonNode block : content) {
                String type = block.path("type").asText();

                if ("tool_use".equals(type)) {
                    ObjectNode functionCall =
                            parts.addObject().putObject("functionCall");

                    functionCall.put(
                            "name", block.path("name").asText());

                    JsonNode input = block.path("input");
                    functionCall.set(
                            "args",
                            input.isObject()
                                    ? input.deepCopy()
                                    : mapper.createObjectNode()
                    );

                } else if ("tool_result".equals(type)) {
                    ObjectNode functionResponse =
                            parts.addObject().putObject("functionResponse");

                    // The adapter uses the function name as tool_use_id.
                    functionResponse.put(
                            "name", block.path("tool_use_id").asText());

                    functionResponse.set(
                            "response",
                            parseToolResult(block.path("content").asText("{}"))
                    );
                }
            }
        }

        // Function declarations
        JsonNode tools = source.path("tools");

        if (tools.isArray() && !tools.isEmpty()) {
            ArrayNode declarations = mapper.createArrayNode();

            for (JsonNode tool : tools) {
                ObjectNode declaration = mapper.createObjectNode();

                declaration.put("name", tool.path("name").asText());
                declaration.put(
                        "description",
                        tool.path("description").asText("")
                );

                JsonNode schema = tool.path("input_schema");
                declaration.set(
                        "parameters",
                        convertSchema(schema)
                );

                declarations.add(declaration);
            }

            ObjectNode geminiTool = mapper.createObjectNode();
            geminiTool.set("functionDeclarations", declarations);

            target.putArray("tools").add(geminiTool);
        }

        // Forced function calling or automatic function calling
        JsonNode toolChoice = source.path("tool_choice");

        if ("tool".equals(toolChoice.path("type").asText())) {
            ObjectNode functionConfig = target
                    .putObject("toolConfig")
                    .putObject("functionCallingConfig");

            functionConfig.put("mode", "ANY");
            functionConfig.putArray("allowedFunctionNames")
                    .add(toolChoice.path("name").asText());

        } else if (tools.isArray() && !tools.isEmpty()) {
            target.putObject("toolConfig")
                    .putObject("functionCallingConfig")
                    .put("mode", "AUTO");
        }

        // Generation settings
        target.putObject("generationConfig")
                .put("maxOutputTokens", props.llm().maxTokens());

        return target;
    }

    private ObjectNode convertSchema(JsonNode schema) {
        ObjectNode result = mapper.createObjectNode();

        String type = schema.path("type").asText("object");
        result.put("type", type.toUpperCase());

        if (schema.hasNonNull("description")) {
            result.put(
                    "description",
                    schema.path("description").asText()
            );
        }

        JsonNode properties = schema.path("properties");

        if (properties.isObject()) {
            ObjectNode convertedProperties =
                    result.putObject("properties");

            Iterator<Map.Entry<String, JsonNode>> fields =
                    properties.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();

                convertedProperties.set(
                        field.getKey(),
                        convertSchema(field.getValue())
                );
            }
        }

        JsonNode items = schema.path("items");

        if (items.isObject()) {
            result.set("items", convertSchema(items));
        }

        if (schema.path("required").isArray()) {
            result.set(
                    "required",
                    schema.path("required").deepCopy()
            );
        }

        if (schema.path("enum").isArray()) {
            result.set(
                    "enum",
                    schema.path("enum").deepCopy()
            );
        }

        return result;
    }

    private JsonNode parseToolResult(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            ObjectNode fallback = mapper.createObjectNode();
            fallback.put("result", value);
            return fallback;
        }
    }

    private JsonNode convertResponse(JsonNode response) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode blocks = result.putArray("content");

        JsonNode parts = response
                .path("candidates")
                .path(0)
                .path("content")
                .path("parts");

        boolean hasFunctionCall = false;

        for (JsonNode part : parts) {
            if (part.hasNonNull("text")) {
                blocks.addObject()
                        .put("type", "text")
                        .put("text", part.path("text").asText());
            }

            JsonNode call = part.path("functionCall");

            if (call.isObject()) {
                hasFunctionCall = true;

                ObjectNode toolUse = blocks.addObject();
                toolUse.put("type", "tool_use");

                String name = call.path("name").asText();
                toolUse.put("name", name);

                // Function name is used as the stable tool_use_id.
                toolUse.put("id", name);

                JsonNode args = call.path("args");

                toolUse.set(
                        "input",
                        args.isObject()
                                ? args.deepCopy()
                                : mapper.createObjectNode()
                );
            }
        }

        result.put(
                "stop_reason",
                hasFunctionCall ? "tool_use" : "end_turn"
        );

        return result;
    }

    private static String abbreviate(String value) {
        if (value == null) return "";

        return value.length() > 500
                ? value.substring(0, 500) + "..."
                : value;
    }
}
