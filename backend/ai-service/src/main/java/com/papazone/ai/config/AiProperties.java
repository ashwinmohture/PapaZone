package com.papazone.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ai")
public record AiProperties(
        String timezone,
        Llm llm,
        Services services,
        Limits limits,
        Security security
) {

    public record Llm(
            String baseUrl,
            String apiKey,
            String model,
            int maxTokens,
            Duration timeout
    ) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Services(String menuUrl, String orderUrl) {
    }

    public record Limits(
            int chatPerMinute,
            int adminPerMinute,
            int maxMessageChars,
            int maxHistory
    ) {
    }

    public record Security(String jwtSecret, String internalKey) {
    }
}
