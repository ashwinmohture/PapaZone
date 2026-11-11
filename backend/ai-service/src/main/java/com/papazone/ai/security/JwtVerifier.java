package com.papazone.ai.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;

/**
 * Verifies the HS256/384/512 JWTs issued by customer-service (JwtService).
 * Same secret (JWT_SECRET, base64) - ai-service can verify but never issue tokens.
 *
 * Rules: signature must match, token must carry an "exp" in the future,
 * "alg":"none" and any non-HMAC algorithm are rejected.
 */
public final class JwtVerifier {

    public record Claims(Long customerId, String email, String role) {

        public boolean isAdmin() {
            return "ADMIN".equalsIgnoreCase(role);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final byte[] key;
    private final Clock clock;

    /** @param base64Secret same value customer-service uses; blank = every token is rejected */
    public JwtVerifier(String base64Secret, Clock clock) {
        this.key = decode(base64Secret);
        this.clock = clock;
    }

    public boolean enabled() {
        return key.length > 0;
    }

    /** Returns the claims of a valid token, or empty (never throws). */
    public Optional<Claims> verify(String token) {
        if (!enabled() || token == null) return Optional.empty();

        try {
            String[] parts = token.trim().split("\\.", -1);
            if (parts.length != 3) return Optional.empty();

            JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
            String javaAlg = switch (header.path("alg").asText("")) {
                case "HS256" -> "HmacSHA256";
                case "HS384" -> "HmacSHA384";
                case "HS512" -> "HmacSHA512";
                default -> null; // includes "none"
            };
            if (javaAlg == null) return Optional.empty();

            Mac mac = Mac.getInstance(javaAlg);
            mac.init(new SecretKeySpec(key, javaAlg));
            byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) return Optional.empty();

            JsonNode payload = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));

            if (!payload.path("exp").isNumber()
                    || payload.get("exp").asLong() <= clock.millis() / 1000) {
                return Optional.empty();
            }

            Long customerId = payload.path("customerId").canConvertToLong()
                    ? payload.get("customerId").asLong()
                    : parseLong(payload.path("sub").asText(null));

            String role = payload.path("role").asText("CUSTOMER");

            return Optional.of(new Claims(customerId, payload.path("email").asText(null), role));

        } catch (Exception malformed) {
            return Optional.empty();
        }
    }

    /** Extracts the token from an "Authorization: Bearer xxx" header value. */
    public Optional<Claims> verifyAuthorizationHeader(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return Optional.empty();
        return verify(header.substring(7));
    }

    private static Long parseLong(String s) {
        try {
            return s == null ? null : Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] decode(String secret) {
        if (secret == null || secret.isBlank()) return new byte[0];
        try {
            return Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException notStandard) {
            try {
                return Base64.getUrlDecoder().decode(secret.trim());
            } catch (IllegalArgumentException e) {
                return new byte[0];
            }
        }
    }
}
