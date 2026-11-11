package com.papazone.ai.web;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window limiter (per key, per minute). Protects the paid LLM calls
 * from abuse. In-memory: with several ai-service instances each has its own
 * window - move to Redis / the gateway if you scale out.
 */
@Component
public class RateLimiter {

    private static final long WINDOW_MS = 60_000;
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Clock clock;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** @throws AiException 429 when the key made too many requests in the last minute */
    public void check(String key, int perMinute) {
        long now = clock.millis();

        if (hits.size() > CLEANUP_THRESHOLD) {
            hits.values().removeIf(d -> {
                synchronized (d) {
                    return d.isEmpty() || now - d.peekLast() > WINDOW_MS;
                }
            });
        }

        Deque<Long> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MS) {
                window.pollFirst();
            }
            if (window.size() >= perMinute) {
                throw new AiException(429, "Too many requests. Please wait a moment and try again.");
            }
            window.addLast(now);
        }
    }
}
