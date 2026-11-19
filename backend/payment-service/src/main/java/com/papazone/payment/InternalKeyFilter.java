package com.papazone.payment;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The API gateway exposes /api/payments/** publicly, so every payment endpoint
 * (create payment, refund, read payment) now requires the internal service key.
 * Only the Razorpay webhook (HMAC-protected), health checks and API docs stay open.
 */
@Component
public class InternalKeyFilter extends OncePerRequestFilter {

    @Value("${INTERNAL_API_KEY:}")
    private String internalKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();

        boolean open = "OPTIONS".equalsIgnoreCase(request.getMethod())
                || uri.equals("/payments/razorpay/webhook")
                || uri.startsWith("/actuator")
                || uri.startsWith("/swagger-ui")
                || uri.startsWith("/v3/api-docs");

        if (open) {
            chain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader("X-Internal-Key");
        boolean ok = internalKey != null && !internalKey.isBlank() && provided != null
                && MessageDigest.isEqual(
                internalKey.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));

        if (!ok) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Forbidden\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}
