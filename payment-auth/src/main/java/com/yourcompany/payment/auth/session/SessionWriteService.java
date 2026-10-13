package com.yourcompany.payment.auth.session;

import com.yourcompany.payment.shared.config.SharedProperties;
import com.yourcompany.payment.shared.exception.SessionConflictException;
import com.yourcompany.payment.shared.exception.SessionException;
import com.yourcompany.payment.shared.exception.StateStoreUnavailableException;
import com.yourcompany.payment.shared.session.PaymentSession;
import com.yourcompany.payment.shared.session.SessionKeys;
import com.yourcompany.payment.shared.session.SessionState;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The only writer of session state in the entire platform.
 *
 * Writes are compare-and-swap, not blind overwrites. Without it, two requests could each
 * load a session, each mutate it, and the second save would erase the first request's
 * transition — for OTP verification that meant two tokens where the first silently
 * stopped working.
 *
 * Optimistic concurrency rather than a distributed lock, deliberately. A lock service
 * adds stale locks after a pod dies and "lock store unreachable" as a second way to be
 * down, for a contention pattern that is rare. A version compare costs one round trip
 * and fails loudly exactly when it matters.
 *
 * Storage is a Redis hash with `data` and `version` so the compare can read the version
 * without parsing JSON inside Lua. Redis executes the script atomically, so the compare
 * and the write cannot interleave.
 *
 * The AES key is written to a SEPARATE key so fetch-service and payment-service can read
 * it without any ability to see or modify the state machine.
 */
@Slf4j
@Service
public class SessionWriteService {

    private static final int ID_BYTES = 32;

    /**
     * 1 on success, 0 if the version moved, -1 if the session is gone.
     *
     * Distinguishing the last two matters: a moved version is a concurrent request, a
     * missing key is an expired session, and the caller sees different messages.
     */
    private static final RedisScript<Long> CAS_SAVE = new DefaultRedisScript<>("""
            local stored = redis.call('HGET', KEYS[1], 'version')
            if stored == false then
                return -1
            end
            if stored ~= ARGV[1] then
                return 0
            end
            redis.call('HSET', KEYS[1], 'data', ARGV[2], 'version', ARGV[3])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            redis.call('EXPIRE', KEYS[2], ARGV[4])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final SharedProperties props;
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionWriteService(StringRedisTemplate redis,
                               @Qualifier("redisObjectMapper") ObjectMapper mapper,
                               SharedProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * Mints an id without persisting anything.
     *
     * Needed before the session exists because the id is the HKDF salt for key
     * derivation. Generating it separately avoids writing a placeholder session and
     * immediately overwriting it.
     */
    public String mintSessionId() {
        byte[] raw = new byte[ID_BYTES];
        secureRandom.nextBytes(raw);
        String id = "SES_" + Hex.toHexString(raw);
        Arrays.fill(raw, (byte) 0);
        return id;
    }

    /** Writes both session keys. A freshly minted id cannot collide, so no compare. */
    public PaymentSession create(String sessionId, byte[] sessionKey, String clientFingerprint) {
        Instant now = Instant.now();
        Duration ttl = Duration.ofSeconds(props.getSecurity().getSessionTtlSeconds());

        PaymentSession session = PaymentSession.builder()
                .sessionId(sessionId)
                .state(SessionState.HANDSHAKE_COMPLETE)
                .version(0L)
                .clientFingerprint(clientFingerprint)
                .createdAt(now)
                .expiresAt(now.plus(ttl))
                .build();

        try {
            redis.opsForHash().putAll(SessionKeys.state(sessionId), Map.of(
                    SessionKeys.FIELD_DATA, mapper.writeValueAsString(session),
                    SessionKeys.FIELD_VERSION, "0"));
            redis.expire(SessionKeys.state(sessionId), ttl);

            redis.opsForValue().set(SessionKeys.key(sessionId), Hex.toHexString(sessionKey), ttl);
        } catch (RuntimeException e) {
            log.error("Session store unavailable on create: {}", e.getClass().getSimpleName());
            throw new StateStoreUnavailableException();
        }
        return session;
    }

    public PaymentSession get(String sessionId) {
        if (!SessionKeys.isWellFormedId(sessionId)) {
            throw SessionException.notFound();
        }

        List<Object> fields;
        try {
            fields = redis.opsForHash().multiGet(SessionKeys.state(sessionId),
                    List.of(SessionKeys.FIELD_DATA, SessionKeys.FIELD_VERSION));
        } catch (RuntimeException e) {
            log.error("Session store unavailable: {}", e.getClass().getSimpleName());
            throw new StateStoreUnavailableException();
        }

        Object data = fields.get(0);
        Object version = fields.get(1);
        if (data == null || version == null) {
            throw SessionException.notFound();
        }

        PaymentSession session;
        try {
            session = mapper.readValue(String.valueOf(data), PaymentSession.class);
            // The hash field is authoritative, not whatever was serialised into the JSON.
            session.setVersion(Long.parseLong(String.valueOf(version)));
        } catch (Exception e) {
            log.error("Corrupt session payload; discarding");
            invalidate(sessionId);
            throw SessionException.notFound();
        }

        if (session.isExpired() || session.getState() == SessionState.LOCKED) {
            invalidate(sessionId);
            throw SessionException.notFound();
        }
        return session;
    }

    /**
     * Compare-and-swap write, extending the TTL on both session keys.
     *
     * Throws SessionConflictException if another request advanced the session since this
     * one loaded it. Deliberately not retried: re-applying a transition against state the
     * caller never read is how a session ends up bound to two agreements.
     */
    public void save(PaymentSession session) {
        Duration ttl = Duration.ofSeconds(props.getSecurity().getSessionTtlSeconds());
        // Sliding window: every successful write pushes the expiry out.
        session.setExpiresAt(Instant.now().plus(ttl));

        long expected = session.getVersion();
        long next = expected + 1;
        session.setVersion(next);

        Long result;
        try {
            result = redis.execute(CAS_SAVE,
                    List.of(SessionKeys.state(session.getSessionId()),
                            SessionKeys.key(session.getSessionId())),
                    Long.toString(expected),
                    mapper.writeValueAsString(session),
                    Long.toString(next),
                    Long.toString(ttl.getSeconds()));
        } catch (RuntimeException e) {
            session.setVersion(expected);
            log.error("Session store unavailable on write: {}", e.getClass().getSimpleName());
            throw new StateStoreUnavailableException();
        }

        if (result == null || result == -1L) {
            session.setVersion(expected);
            throw SessionException.notFound();
        }
        if (result == 0L) {
            session.setVersion(expected);
            log.warn("Concurrent session update rejected at version {}", expected);
            throw new SessionConflictException();
        }
    }

    /**
     * Claims a one-shot operation for this session.
     *
     * Returns true exactly once per (sessionId, operation) within the TTL, however many
     * requests race. Used for token issuance, where two winners would be expensive.
     *
     * The TTL matches the session lifetime, so a claim cannot outlive what it protects
     * and cannot leak if a pod dies mid-request — the failure mode a lock would have had.
     */
    public boolean claimOnce(String sessionId, String operation) {
        try {
            Boolean claimed = redis.opsForValue().setIfAbsent(
                    SessionKeys.claim(sessionId, operation), "1",
                    Duration.ofSeconds(props.getSecurity().getSessionTtlSeconds()));
            if (claimed == null) {
                throw new StateStoreUnavailableException();
            }
            return claimed;
        } catch (StateStoreUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Claim store unavailable: {}", e.getClass().getSimpleName());
            throw new StateStoreUnavailableException();
        }
    }

    public void releaseClaim(String sessionId, String operation) {
        try {
            redis.delete(SessionKeys.claim(sessionId, operation));
        } catch (RuntimeException e) {
            log.warn("Could not release claim {}", operation);
        }
    }

    /**
     * Deletes every key belonging to the session together, so a downstream key read is a
     * reliable liveness check and no snapshot outlives the session it was captured for.
     */
    public void invalidate(String sessionId) {
        try {
            redis.delete(List.of(
                    SessionKeys.state(sessionId),
                    SessionKeys.key(sessionId),
                    SessionKeys.lms(sessionId)));
        } catch (RuntimeException e) {
            log.warn("Could not delete session keys: {}", e.getClass().getSimpleName());
        }
    }

    public void requireState(PaymentSession session, SessionState... allowed) {
        if (!session.isInState(allowed)) {
            throw SessionException.wrongState();
        }
    }
}
