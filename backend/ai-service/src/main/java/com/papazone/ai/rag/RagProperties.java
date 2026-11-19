package com.papazone.ai.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(boolean enabled, int embeddingDimensions, int topK) {
    public RagProperties {
        if (embeddingDimensions <= 0) embeddingDimensions = 768;
        if (topK <= 0) topK = 5;
    }
}
