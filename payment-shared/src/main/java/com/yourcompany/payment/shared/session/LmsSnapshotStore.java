package com.yourcompany.payment.shared.session;

import com.yourcompany.payment.shared.config.SharedProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

/**
 * Session-scoped store for the LMS agreement-fetch response.
 *
 * Written by auth-service at OTP dispatch, read by fetch-service. Keyed by session, so it
 * expires with the session and cannot leak between borrowers.
 *
 * Every operation degrades rather than fails. A snapshot miss costs one LMS call, which
 * is the behaviour without the cache at all — never a reason to fail a borrower's
 * request. This is the one place in the platform where falling back is correct, and it is
 * correct precisely because the fallback is the authoritative source.
 */
@Slf4j
@Component
public class LmsSnapshotStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final SharedProperties props;

    public LmsSnapshotStore(StringRedisTemplate redis,
                            @Qualifier("redisObjectMapper") ObjectMapper mapper,
                            SharedProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.props = props;
    }

    public void put(String sessionId, LmsSnapshot snapshot) {
        try {
            redis.opsForValue().set(key(sessionId), mapper.writeValueAsString(snapshot),
                    Duration.ofSeconds(props.getSecurity().getSessionTtlSeconds()));
        } catch (Exception e) {
            // A failed write costs one LMS call later. Not worth failing OTP dispatch.
            log.warn("Could not store LMS snapshot: {}", e.getClass().getSimpleName());
        }
    }

    public Optional<LmsSnapshot> get(String sessionId) {
        try {
            String json = redis.opsForValue().get(key(sessionId));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(json, LmsSnapshot.class));
        } catch (Exception e) {
            log.warn("Could not read LMS snapshot, falling through to the LMS: {}",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Extends the snapshot alongside the session, on resend or any downstream request. */
    public void touch(String sessionId) {
        try {
            redis.expire(key(sessionId), Duration.ofSeconds(props.getSecurity().getSessionTtlSeconds()));
        } catch (Exception e) {
            log.warn("Could not extend LMS snapshot TTL");
        }
    }

    public void evict(String sessionId) {
        try {
            redis.delete(key(sessionId));
        } catch (Exception e) {
            log.warn("Could not evict LMS snapshot");
        }
    }

    private String key(String sessionId) {
        return SessionKeys.lms(sessionId);
    }
}
