package com.papazone.ai.chat;

import java.util.List;

/** Request / response shapes for POST /ai/chat. */
public final class ChatDtos {

    private ChatDtos() {
    }

    /** role is "user" or "assistant". */
    public record Message(String role, String content) {
    }

    /** The customer is identified by the JWT, never by a field in the body. */
    public record Request(List<Message> messages) {
    }

    public record Suggestion(
            Long id,
            String name,
            String description,
            String category,
            double price,
            String imageUrl
    ) {
    }

    public record Response(String reply, List<Suggestion> items) {
    }
}
