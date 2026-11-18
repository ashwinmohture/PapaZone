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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
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
                    503,
                    "The AI assistant is not configured yet."
            );
        }

        ObjectNode geminiRequest = convertRequest(body);

        try {
            try {
                return sendWithRetry(
                        geminiRequest,
                        props.llm().model(),
                        3
                );

            } catch (RestClientResponseException primaryError) {
                // Provider answered with an HTTP error status.
                int status = primaryError.getStatusCode().value();

                if (status != 503 && status != 429) {
                    throw primaryError;
                }

                String fallbackModel = usableFallbackModel();

                if (fallbackModel == null) {
                    throw primaryError;
                }

                log.warn(
                        "Primary Gemini model returned HTTP {}. Trying fallback model '{}'.",
                        status,
                        fallbackModel
                );

                return sendWithRetry(geminiRequest, fallbackModel, 2);

            } catch (RestClientException primaryError) {
                // No usable HTTP response: read timeout, connection problem,
                // or an unreadable body. (Must stay AFTER the
                // RestClientResponseException catch, which is a subclass.)
                String fallbackModel = usableFallbackModel();

                if (fallbackModel == null) {
                    throw primaryError;
                }

                log.warn(
                        "Primary Gemini model failed ({}). Trying fallback model '{}'.",
                        rootMessage(primaryError),
                        fallbackModel
                );

                return sendWithRetry(geminiRequest, fallbackModel, 2);
            }

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();

            log.error(
                    "Gemini provider returned HTTP {}: {}",
                    status,
                    abbreviate(e.getResponseBodyAsString())
            );

            if (status == 429 || status == 503) {
                throw new AiException(
                        503,
                        "The AI assistant is temporarily busy. Please try again shortly."
                );
            }

            throw new AiException(
                    502,
                    "The AI assistant is temporarily unavailable."
            );

        } catch (RestClientException e) {
            // Includes ResourceAccessException (timeouts / unreachable) and
            // any other client-side failure, so none of them become a 500.
            log.error(
                    "Gemini call failed or timed out: {}",
                    rootMessage(e),
                    e
            );

            throw new AiException(
                    504,
                    "The AI assistant took too long to respond. Please try again."
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new AiException(
                    503,
                    "The AI request was interrupted. Please try again."
            );
        }
    }

    /** Fallback model name, or null if none is configured / same as primary. */
    private String usableFallbackModel() {
        String fallback = props.llm().fallbackModel();

        if (fallback == null
                || fallback.isBlank()
                || fallback.equals(props.llm().model())) {
            return null;
        }

        return fallback;
    }

    private JsonNode sendWithRetry(
            ObjectNode request,
            String model,
            int maxAttempts
    ) throws InterruptedException {

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                JsonNode response = http.post()
                        .uri(
                                "/v1beta/models/{model}:generateContent",
                                model
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-goog-api-key", props.llm().apiKey())
                        .body(request)
                        .retrieve()
                        .body(JsonNode.class);

                if (response == null) {
                    throw new AiException(
                            502,
                            "The AI assistant returned an empty response."
                    );
                }

                return convertResponse(response);

            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean retryable = status == 503 || status == 429;

                if (!retryable || attempt == maxAttempts) {
                    throw e;
                }

                long delay = getRetryDelay(e, attempt);

                log.warn(
                        "Gemini returned HTTP {}. Retry attempt {}/{} after {} ms.",
                        status,
                        attempt,
                        maxAttempts,
                        delay
                );

                Thread.sleep(delay);
            }
        }

        throw new IllegalStateException(
                "Gemini retry attempts exhausted."
        );
    }

    private long getRetryDelay(
            RestClientResponseException exception,
            int attempt
    ) {
        HttpHeaders headers = exception.getResponseHeaders();

        if (headers != null) {
            String retryAfter = headers.getFirst("Retry-After");

            if (retryAfter != null) {
                try {
                    long seconds = Long.parseLong(retryAfter.trim());

                    if (seconds >= 0) {
                        return Math.min(seconds * 1000, 5000);
                    }
                } catch (NumberFormatException ignored) {
                    // Use exponential backoff if Retry-After is not numeric.
                }
            }
        }

        return Math.min(1000L * (1L << (attempt - 1)), 5000);
    }

    /**
     * Converts the internal Anthropic-like message format used by the
     * assistant services into Gemini generateContent format.
     *
     * The internal "id" identifies a tool result within our conversation.
     * "gemini_call_id" stores Gemini's original call ID, if Gemini supplied
     * one. These values must not be confused with the function name.
     */
    private ObjectNode convertRequest(ObjectNode source) {
        ObjectNode target = mapper.createObjectNode();

        if (source.hasNonNull("system")) {
            target.putObject("systemInstruction")
                    .putArray("parts")
                    .addObject()
                    .put("text", source.path("system").asText());
        }

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

            boolean firstCallInMessage = true;

            for (JsonNode block : content) {
                String type = block.path("type").asText();

                if ("tool_use".equals(type)) {
                    ObjectNode functionCallPart = parts.addObject();
                    ObjectNode functionCall =
                            functionCallPart.putObject("functionCall");

                    functionCall.put(
                            "name",
                            block.path("name").asText()
                    );

                    JsonNode geminiCallId = block.path("gemini_call_id");
                    if (geminiCallId.isTextual()
                            && !geminiCallId.asText().isBlank()) {
                        functionCall.put("id", geminiCallId.asText());
                    }

                    JsonNode input = block.path("input");
                    functionCall.set(
                            "args",
                            input.isObject()
                                    ? input.deepCopy()
                                    : mapper.createObjectNode()
                    );

                    JsonNode thoughtSignature =
                            block.path("thought_signature");

                    if (thoughtSignature.isTextual()
                            && !thoughtSignature.asText().isBlank()) {
                        functionCallPart.put(
                                "thoughtSignature",
                                thoughtSignature.asText()
                        );
                    } else if (firstCallInMessage) {
                        // Gemini 3 rejects a replayed functionCall that has no
                        // signature (HTTP 400). Some models (e.g. lite) don't
                        // return one, so use Google's documented placeholder.
                        functionCallPart.put(
                                "thoughtSignature",
                                "skip_thought_signature_validator"
                        );
                    }
                    firstCallInMessage = false;

                } else if ("tool_result".equals(type)) {
                    ObjectNode functionResponse =
                            parts.addObject().putObject("functionResponse");

                    // Gemini requires the declared function name here,
                    // not the internal tool_use_id.
                    functionResponse.put(
                            "name",
                            block.path("name").asText()
                    );

                    // Echo Gemini's ID only when the original call supplied it.
                    JsonNode geminiCallId =
                            block.path("gemini_call_id");

                    if (geminiCallId.isTextual()
                            && !geminiCallId.asText().isBlank()) {
                        functionResponse.put("id", geminiCallId.asText());
                    }

                    functionResponse.set(
                            "response",
                            parseToolResult(
                                    block.path("content").asText("{}")
                            )
                    );
                }
            }
        }

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

                declaration.set(
                        "parameters",
                        convertSchema(tool.path("input_schema"))
                );

                declarations.add(declaration);
            }

            ObjectNode geminiTool = mapper.createObjectNode();
            geminiTool.set("functionDeclarations", declarations);
            target.putArray("tools").add(geminiTool);
        }

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

        JsonNode candidates = response.path("candidates");
        JsonNode parts = candidates.path(0)
                .path("content")
                .path("parts");

        if (!parts.isArray()) {
            String finishReason = candidates.path(0)
                    .path("finishReason")
                    .asText("UNKNOWN");

            log.error(
                    "Gemini response did not contain candidate parts. finishReason={}",
                    finishReason
            );

            throw new AiException(
                    502,
                    "The AI provider returned no usable response."
            );
        }

        boolean hasFunctionCall = false;
        int functionCallIndex = 0;

        for (JsonNode part : parts) {
            if (part.hasNonNull("text")) {
                blocks.addObject()
                        .put("type", "text")
                        .put("text", part.path("text").asText());
            }

            JsonNode call = part.path("functionCall");

            if (call.isObject()) {
                hasFunctionCall = true;

                String name = call.path("name").asText();
                String providerCallId = call.path("id").asText("");

                ObjectNode toolUse = blocks.addObject();
                toolUse.put("type", "tool_use");
                toolUse.put("name", name);

                // Always provide an internal ID so the assistant service can
                // match each tool result to the correct tool call.
                String internalId = !providerCallId.isBlank()
                        ? providerCallId
                        : name + "_" + functionCallIndex;

                toolUse.put("id", internalId);

                // Keep Gemini's ID separate. If Gemini did not provide one,
                // do not invent one when sending functionResponse back.
                if (!providerCallId.isBlank()) {
                    toolUse.put("gemini_call_id", providerCallId);
                }

                JsonNode args = call.path("args");
                toolUse.set(
                        "input",
                        args.isObject()
                                ? args.deepCopy()
                                : mapper.createObjectNode()
                );

                JsonNode thoughtSignature =
                        part.path("thoughtSignature");

                if (thoughtSignature.isTextual()
                        && !thoughtSignature.asText().isBlank()) {
                    toolUse.put(
                            "thought_signature",
                            thoughtSignature.asText()
                    );
                }

                functionCallIndex++;
            }
        }

        result.put(
                "stop_reason",
                hasFunctionCall ? "tool_use" : "end_turn"
        );

        return result;
    }

    /** Message of the deepest cause, e.g. "java.net.SocketTimeoutException: Read timed out". */
    private static String rootMessage(Throwable t) {
        Throwable root = t;

        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        return root.toString();
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }

        return value.length() > 500
                ? value.substring(0, 500) + "..."
                : value;
    }
}
