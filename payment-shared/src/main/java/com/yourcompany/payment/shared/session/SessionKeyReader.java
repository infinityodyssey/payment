package com.yourcompany.payment.shared.session;

import com.yourcompany.payment.shared.exception.SessionException;
import com.yourcompany.payment.shared.exception.StateStoreUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Read-only access to the session AES key, for services that are not auth-service.
 *
 * This exists so that fetch, payment and any future service can decrypt an envelope
 * without touching session state. It reads exactly one key and never mutates data.
 *
 * touch() is the one exception, and it is deliberately not a data write: it extends the
 * TTL on both session keys so the 5-minute window is idle time rather than total time.
 * Without it a sliding session would be impossible, because auth-service does not see
 * the requests that fetch and payment serve.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionKeyReader {

    private final StringRedisTemplate redis;

    /**
     * Returns the session AES key, or throws if the session is gone.
     *
     * The caller must zero the array when finished: it is live key material.
     */
    public byte[] sessionKey(String sessionId) {
        if (!SessionKeys.isWellFormedId(sessionId)) {
            throw SessionException.notFound();
        }
        String hex;
        try {
            hex = redis.opsForValue().get(SessionKeys.key(sessionId));
        } catch (RuntimeException e) {
            log.error("Session store unavailable: {}", e.getClass().getSimpleName());
            throw new StateStoreUnavailableException();
        }
        if (hex == null || hex.isBlank()) {
            throw SessionException.notFound();
        }
        return Hex.decode(hex);
    }

    public boolean exists(String sessionId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(SessionKeys.key(sessionId)));
        } catch (RuntimeException e) {
            throw new StateStoreUnavailableException();
        }
    }

    /** Extends both session keys so the TTL measures idle time, not total time. */
    public void touch(String sessionId, Duration ttl) {
        try {
            redis.expire(SessionKeys.key(sessionId), ttl);
            redis.expire(SessionKeys.state(sessionId), ttl);
        } catch (RuntimeException e) {
            // A failed slide is not worth failing the request for: the session simply
            // expires at its original time.
            log.warn("Could not extend session TTL");
        }
    }
}
