package com.papazone.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.papazone.ai.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AnthropicClientTest {

    private MockRestServiceServer server;
    private AnthropicClient client;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();

        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com");

        server = MockRestServiceServer.bindTo(builder).build();

        RestClient restClient = builder.build();

        AiProperties properties = new AiProperties(
                "Asia/Kolkata",
                new AiProperties.Llm(
                        "https://generativelanguage.googleapis.com",
                        "test-gemini-key",
                        "gemini-2.5-flash",
                        1024,
                        Duration.ofSeconds(30)
                ),
                new AiProperties.Services(
                        "http://menu-service:8082",
                        "http://order-service:8084"
                ),
                new AiProperties.Limits(12, 20, 500, 10),
                new AiProperties.Security("test-jwt-secret", "test-internal-key")
        );

        client = new AnthropicClient(restClient, properties, mapper);
    }

    @Test
    void shouldConvertGeminiRequestAndResponse() throws Exception {

        String geminiResponse = """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {
                            "text": "Hello! Welcome to PapaZone."
                          }
                        ]
                      },
                      "finishReason": "STOP"
                    }
                  ]
                }
                """;

        server.expect(requestTo(
                        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-gemini-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.systemInstruction.parts[0].text")
                        .value("You are PapaZone assistant."))
                .andExpect(jsonPath("$.contents[0].role").value("user"))
                .andExpect(jsonPath("$.contents[0].parts[0].text")
                        .value("Hello"))
                .andRespond(withSuccess(
                        geminiResponse,
                        MediaType.APPLICATION_JSON
                ));

        ObjectNode request = client.newRequest(
                "You are PapaZone assistant."
        );

        request.putArray("messages")
                .addObject()
                .put("role", "user")
                .put("content", "Hello");

        JsonNode response = client.send(request);

        assertNotNull(response);
        assertEquals(
                "Hello! Welcome to PapaZone.",
                LlmJson.text(response)
        );

        server.verify();
    }

    @Test
    void shouldConvertGeminiFunctionCallResponse() {

        String geminiResponse = """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {
                            "functionCall": {
                              "name": "recommend_items",
                              "args": {
                                "reply": "Here are some recommendations.",
                                "itemIds": [1, 2]
                              }
                            }
                          }
                        ]
                      },
                      "finishReason": "STOP"
                    }
                  ]
                }
                """;

        server.expect(requestTo(
                        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-gemini-key"))
                .andRespond(withSuccess(
                        geminiResponse,
                        MediaType.APPLICATION_JSON
                ));

        ObjectNode request = client.newRequest("Recommend food.");

        request.putArray("messages")
                .addObject()
                .put("role", "user")
                .put("content", "Suggest some food.");

        JsonNode response = client.send(request);

        JsonNode toolInput = LlmJson.firstToolUse(
                response,
                "recommend_items"
        );

        assertNotNull(toolInput);
        assertEquals(
                "Here are some recommendations.",
                toolInput.path("reply").asText()
        );
        assertEquals(2, toolInput.path("itemIds").size());

        server.verify();
    }


    @Test
    void shouldPreserveThoughtSignatureAcrossFunctionCallRoundTrip() throws Exception {

        String thoughtSignature = "test-thought-signature-123";

        String geminiResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [
                      {
                        "functionCall": {
                          "name": "run_analytics",
                          "args": {
                            "query": "total_orders"
                          }
                        },
                        "thoughtSignature": "test-thought-signature-123"
                      }
                    ]
                  },
                  "finishReason": "STOP"
                }
              ]
            }
            """;

        // First request: Gemini returns a function call.
        server.expect(requestTo(
                        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        geminiResponse,
                        MediaType.APPLICATION_JSON
                ));

        // Register the second request expectation before sending the first request.
        server.expect(requestTo(
                        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath(
                        "$.contents[0].parts[0].thoughtSignature"
                ).value(thoughtSignature))
                .andExpect(jsonPath(
                        "$.contents[0].parts[0].functionCall.name"
                ).value("run_analytics"))
                .andRespond(withSuccess("""
                    {
                      "candidates": [
                        {
                          "content": {
                            "role": "model",
                            "parts": [
                              {
                                "text": "You received 6 orders."
                              }
                            ]
                          },
                          "finishReason": "STOP"
                        }
                      ]
                    }
                    """, MediaType.APPLICATION_JSON));

        ObjectNode firstRequest = client.newRequest("PapaZone analytics assistant.");

        firstRequest.putArray("messages")
                .addObject()
                .put("role", "user")
                .put("content", "How many orders did we receive?");

        JsonNode firstResponse = client.send(firstRequest);

        JsonNode toolUse = firstResponse.path("content")
                .get(0);

        assertEquals("tool_use", toolUse.path("type").asText());
        assertEquals("run_analytics", toolUse.path("name").asText());
        assertEquals(
                thoughtSignature,
                toolUse.path("thought_signature").asText()
        );

        ObjectNode secondRequest = client.newRequest("PapaZone analytics assistant.");

        ArrayNode messages = secondRequest.putArray("messages");

        ObjectNode assistantMessage = messages.addObject();
        assistantMessage.put("role", "assistant");

        ArrayNode assistantContent = assistantMessage.putArray("content");

        assistantContent.add(toolUse.deepCopy());

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");

        ArrayNode userContent = userMessage.putArray("content");

        ObjectNode toolResult = userContent.addObject();
        toolResult.put("type", "tool_result");
        toolResult.put("tool_use_id", "run_analytics");
        toolResult.put("content", "{\"total_orders\":6}");

        JsonNode secondResponse = client.send(secondRequest);

        assertEquals(
                "You received 6 orders.",
                LlmJson.text(secondResponse)
        );

        server.verify();
    }

}
