package com.papazone.ai.rag;

import com.papazone.ai.security.JwtVerifier;
import com.papazone.ai.web.AiException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/ai/rag")
public class RagController {
    private final RagMenuService ragMenuService;
    private final JwtVerifier jwtVerifier;

    public RagController(RagMenuService ragMenuService, JwtVerifier jwtVerifier) {
        this.ragMenuService = ragMenuService;
        this.jwtVerifier = jwtVerifier;
    }

    /** Admin-only: creates/refreshes embeddings from the current Menu Service response. */
    @PostMapping("/index-menu")
    public Map<String, Object> indexMenu(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        int count = ragMenuService.indexMenu();
        return Map.of("status", "indexed", "documents", count);
    }

    /** Diagnostic semantic search endpoint. */
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam("q") String question) {
        if (question == null || question.isBlank() || question.length() > 500) {
            throw new AiException(400, "Provide a question between 1 and 500 characters.");
        }
        return ragMenuService.search(question).stream().map(document -> Map.<String, Object>of(
                "content", document.getText(),
                "metadata", document.getMetadata()
        )).toList();
    }

    private void requireAdmin(String authorization) {
        Optional<JwtVerifier.Claims> claims = jwtVerifier.verifyAuthorizationHeader(authorization);
        if (claims.isEmpty()) throw new AiException(401, "Please log in again.");
        if (!claims.get().isAdmin()) throw new AiException(403, "Only admins can index menu data.");
    }
}
