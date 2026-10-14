package com.yourcompany.payment.shared.client;

import com.yourcompany.payment.shared.exception.StateStoreUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Caches upstream bearer tokens in Redis, shared across instances.
 *
 * Per-instance caching would multiply token requests by the pod count and, on
 * providers that invalidate the previous token on issue, cause instances to knock each
 * other offline. Redis keeps one token per upstream per cluster.
 *
 * Tokens are cached at a TTL shorter than their real lifetime so a token is never used
 * in the last moments before expiry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpstreamTokenCache {

    private static final String PREFIX = "pay:upstream:token:";
    private static final long EARLY_EXPIRY_SECONDS = 60;

    private final StringRedisTemplate redis;

    public String get(String name, long ttlSeconds, Supplier<String> loader) {
        String key = PREFIX + name;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                return cached;
            }
        } catch (RuntimeException e) {
            log.error("Token cache unavailable: {}", e.getClass().getSimpleName());
            throw new StateStoreUnavailableException();
        }

        String fresh = loader.get();
        long effective = Math.max(30, ttlSeconds - EARLY_EXPIRY_SECONDS);
        try {
            redis.opsForValue().set(key, fresh, Duration.ofSeconds(effective));
        } catch (RuntimeException e) {
            // Caching is an optimisation; a write failure must not fail the call.
            log.warn("Could not cache upstream token for {}", name);
        }
        return fresh;
    }

    /** Called when an upstream rejects the token, so the next call fetches a fresh one. */
    public void evict(String name) {
        try {
            redis.delete(PREFIX + name);
        } catch (RuntimeException e) {
            log.warn("Could not evict upstream token for {}", name);
        }
    }
}
