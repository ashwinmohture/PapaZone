package com.papazone.ai.admin;

import java.util.List;
import java.util.Map;

/** Request / response shapes for POST /ai/admin/ask. */
public final class AdminDtos {

    private AdminDtos() {
    }

    public record Request(String question) {
    }

    /**
     * answer  - the model's wording
     * results - the exact numbers the answer is based on (shown in the UI so
     *           the admin can verify the wording against the data)
     */
    public record Response(String answer, List<Map<String, Object>> results) {
    }
}
