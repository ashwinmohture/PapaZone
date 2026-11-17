
package com.papazone.order.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(
                Decoders.BASE64.decode(jwtSecret)
        );
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {

        String requestUri = request.getRequestURI();

        // These endpoints perform their own authentication
        // or do not require JWT authentication.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())
                || "/orders/export".equals(requestUri)
                || requestUri.startsWith("/orders/internal/")
                || "/actuator/health".equals(requestUri)
                || requestUri.startsWith("/swagger-ui")
                || requestUri.startsWith("/v3/api-docs")) {

            chain.doFilter(request, response);
            return;
        }

        // Validate JWT for all other protected endpoints.
        String header = request.getHeader("Authorization");

        if (header == null
                || !header.startsWith("Bearer ")
                || header.length() <= 7) {

            unauthorized(response, "Bearer token is required");
            return;
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .build()
                    .parseSignedClaims(header.substring(7).trim())
                    .getPayload();

            Object idClaim = claims.get("customerId");

            Long customerId = idClaim instanceof Number n
                    ? n.longValue()
                    : Long.valueOf(
                    String.valueOf(
                            idClaim == null
                                    ? claims.getSubject()
                                    : idClaim
                    )
            );

            String role = claims.get("role", String.class);

            if (customerId == null
                    || customerId <= 0
                    || role == null
                    || role.isBlank()) {

                unauthorized(response, "Token identity is invalid");
                return;
            }

            request.setAttribute(
                    AuthenticatedUser.class.getName(),
                    new AuthenticatedUser(
                            customerId,
                            role.trim().toUpperCase()
                    )
            );

            chain.doFilter(request, response);

        } catch (JwtException | IllegalArgumentException ex) {
            unauthorized(response, "Token is invalid or expired");
        }
    }

    private void unauthorized(
            HttpServletResponse response,
            String message
    ) throws IOException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");

        response.getWriter().write(
                "{\"message\":\"" + message + "\"}"
        );
    }
}
