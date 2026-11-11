package com.papazone.ai.web;

import com.papazone.ai.admin.AdminAssistantService;
import com.papazone.ai.admin.AdminDtos;
import com.papazone.ai.chat.ChatDtos;
import com.papazone.ai.chat.FoodAssistantService;
import com.papazone.ai.config.AiProperties;
import com.papazone.ai.recommend.RecommendationService;
import com.papazone.ai.recommend.RecommendationView;
import com.papazone.ai.security.JwtVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * Reached through the gateway as /api/ai/** (StripPrefix=1 -> /ai/**).
 *
 * Identity always comes from the JWT issued by customer-service:
 *  - chat / recommendations: token optional (visitors are anonymous)
 *  - admin assistant:        token required, role must be ADMIN
 *
 * NOTE: parameter names are written out explicitly (e.g. @RequestParam("limit"))
 * because this project is not built with the -parameters compiler flag.
 */
@RestController
@RequestMapping("/ai")
public class AiController {

    private final FoodAssistantService food;
    private final RecommendationService recommendations;
    private final AdminAssistantService admin;
    private final RateLimiter limiter;
    private final JwtVerifier jwt;
    private final AiProperties props;

    public AiController(
            FoodAssistantService food,
            RecommendationService recommendations,
            AdminAssistantService admin,
            RateLimiter limiter,
            JwtVerifier jwt,
            AiProperties props
    ) {
        this.food = food;
        this.recommendations = recommendations;
        this.admin = admin;
        this.limiter = limiter;
        this.jwt = jwt;
        this.props = props;
    }

    // Feature 1: chat with the food ordering assistant
    @PostMapping("/chat")
    public ChatDtos.Response chat(
            @RequestBody ChatDtos.Request request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            HttpServletRequest http
    ) {
        limiter.check("chat:" + clientIp(http), props.limits().chatPerMinute());
        return food.chat(request, customerId(authorization));
    }

    // Feature 2: personalised recommendations (no LLM involved)
    @GetMapping("/recommendations")
    public List<RecommendationView> recommendations(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "limit", defaultValue = "6") int limit
    ) {
        return recommendations.forCustomer(customerId(authorization), limit);
    }

    // Feature 6: admin analytics assistant (ADMIN role only)
    @PostMapping("/admin/ask")
    public AdminDtos.Response ask(
            @RequestBody AdminDtos.Request request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            HttpServletRequest http
    ) {
        limiter.check("admin:" + clientIp(http), props.limits().adminPerMinute());

        Optional<JwtVerifier.Claims> claims = jwt.verifyAuthorizationHeader(authorization);

        if (claims.isEmpty()) {
            throw new AiException(401, "Please log in again.");
        }
        if (!claims.get().isAdmin()) {
            throw new AiException(403, "Only admins can use the admin assistant.");
        }
        return admin.ask(request.question());
    }

    /** Verified customer id, or null for visitors / invalid / expired tokens. */
    private Long customerId(String authorization) {
        return jwt.verifyAuthorizationHeader(authorization)
                .map(JwtVerifier.Claims::customerId)
                .orElse(null);
    }

    private static String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
