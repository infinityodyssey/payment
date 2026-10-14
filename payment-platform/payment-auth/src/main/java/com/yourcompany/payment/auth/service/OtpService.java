package com.yourcompany.payment.auth.service;

import com.yourcompany.payment.auth.client.OtpSender;
import com.yourcompany.payment.auth.config.AuthProperties;
import com.yourcompany.payment.shared.exception.OtpException;
import com.yourcompany.payment.shared.exception.StateStoreUnavailableException;
import com.yourcompany.payment.shared.util.Hashing;
import com.yourcompany.payment.shared.util.Mask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * OTP issuance and verification.
 *
 * Storage: only a peppered, salted HMAC, under a key that expires with the code. Be
 * clear about what that buys. A 5-digit code has a 100,000-value space, so a fast hash is
 * exhaustible instantly by anyone who also holds the pepper. The pepper is the real
 * control and it lives in the application secret store, not in Redis — someone who dumps
 * Redis alone learns nothing.
 *
 * THE ATTEMPT CAP IS LOAD-BEARING. Three guesses against 100,000 values is roughly 1 in
 * 33,000 per session, which is what makes a 5-digit code acceptable at all. Raise the
 * cap, remove the lockout, or let the counter reset on resend and the scheme degrades
 * ten times faster than a 6-digit one would. Treat changes to max-attempts,
 * lockout-seconds and the counter reset below as security changes.
 *
 * Counting uses Redis INCR, atomic cluster-wide. The legacy code read a counter off a
 * session object, incremented it in the JVM and wrote it back, so N parallel requests all
 * read the same value and the cap never bound.
 *
 * These limits cannot move to the API gateway: they key on the agreement number, which is
 * inside an AES-GCM envelope the gateway cannot read.
 */
@Slf4j
@Service
public class OtpService {

    private static final String OTP_KEY = "otp:code:";
    private static final String ATTEMPT_KEY = "otp:attempts:";
    private static final String COOLDOWN_KEY = "otp:cooldown:";
    private static final String SEND_COUNT_KEY = "otp:sends:";
    private static final String LOCK_KEY = "otp:lock:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final AuthProperties props;
    private final OtpSender sender;
    private final SecureRandom secureRandom = new SecureRandom();

    public OtpService(StringRedisTemplate redis,
                      @Qualifier("redisObjectMapper") ObjectMapper mapper,
                      AuthProperties props,
                      OtpSender sender) {
        this.redis = redis;
        this.mapper = mapper;
        this.props = props;
        this.sender = sender;
    }

    /**
     * Generates and dispatches a code.
     *
     * Order matters: lockout, then cooldown, then the per-agreement send budget. A
     * locked-out session must not be able to burn another SMS from the agreement's hourly
     * allowance.
     *
     * The code is stored before dispatch and cleared if dispatch fails, so a borrower is
     * never left holding a code the server forgot, nor the reverse.
     */
    public void issue(String sessionId, String agreementNumber, String mobileNumber) {
        assertNotLocked(sessionId);
        enforceCooldown(sessionId);
        enforceSendBudget(agreementNumber);

        String code = generateCode();
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        String saltHex = HexFormat.of().formatHex(salt);

        Map<String, Object> record = Map.of(
                "saltHex", saltHex,
                "hashHex", hash(saltHex, code),
                "issuedAt", Instant.now().toEpochMilli());

        try {
            redis.opsForValue().set(OTP_KEY + sessionId, mapper.writeValueAsString(record),
                    Duration.ofSeconds(props.getOtp().getTtlSeconds()));
            // Fresh code, fresh attempt budget. See the class note: this reset is part of
            // why the cap holds.
            redis.delete(ATTEMPT_KEY + sessionId);
        } catch (RuntimeException e) {
            log.error("OTP store unavailable: {}", e.getClass().getSimpleName());
            throw new StateStoreUnavailableException();
        }

        try {
            sender.send(mobileNumber, code, agreementNumber);
        } catch (RuntimeException e) {
            // Dispatch failed, so the stored hash is unusable. Clearing it lets the
            // borrower retry immediately instead of waiting out a TTL for a code that was
            // never delivered.
            try {
                redis.delete(OTP_KEY + sessionId);
                redis.delete(COOLDOWN_KEY + sessionId);
            } catch (RuntimeException ignored) {
                log.warn("Could not clear OTP keys after failed dispatch");
            }
            throw e;
        }

        log.info("OTP issued for agreement {}", Mask.agreement(agreementNumber));
    }

    /**
     * Verifies a submitted code.
     *
     * The attempt counter increments BEFORE the comparison. Incrementing only on failure
     * leaves a race where an attacker cancels the connection after sending a guess;
     * incrementing first means every guess costs an attempt whatever the client does.
     *
     * Consumption decides the winner of a race. Two concurrent requests with the same
     * correct code can both pass the cap and both match the hash — read and compare
     * cannot be made atomic without serialising every verification. DEL is atomic and
     * returns true to exactly one caller; only that caller proceeds to a token.
     */
    public void verify(String sessionId, String submitted) {
        assertNotLocked(sessionId);

        long attempts = incrementAttempts(sessionId);
        if (attempts > props.getOtp().getMaxAttempts()) {
            lockout(sessionId);
            throw new OtpException("OTP_MAX_ATTEMPTS",
                    "Too many incorrect attempts. Please start again in 15 minutes.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }

        String json;
        try {
            json = redis.opsForValue().get(OTP_KEY + sessionId);
        } catch (RuntimeException e) {
            throw new StateStoreUnavailableException();
        }
        if (json == null) {
            throw new OtpException("OTP_EXPIRED",
                    "That code has expired. Please request a new one.", HttpStatus.BAD_REQUEST);
        }

        String saltHex;
        String hashHex;
        try {
            Map<?, ?> record = mapper.readValue(json, Map.class);
            saltHex = String.valueOf(record.get("saltHex"));
            hashHex = String.valueOf(record.get("hashHex"));
        } catch (Exception e) {
            throw new OtpException("OTP_EXPIRED",
                    "That code has expired. Please request a new one.", HttpStatus.BAD_REQUEST);
        }

        boolean matches = MessageDigest.isEqual(
                hash(saltHex, submitted.trim()).getBytes(StandardCharsets.UTF_8),
                hashHex.getBytes(StandardCharsets.UTF_8));

        if (!matches) {
            long remaining = Math.max(0, props.getOtp().getMaxAttempts() - attempts);
            throw new OtpException("OTP_INVALID",
                    "That code is not correct. " + remaining + " attempt(s) remaining.",
                    HttpStatus.UNAUTHORIZED);
        }

        boolean consumed;
        try {
            consumed = Boolean.TRUE.equals(redis.delete(OTP_KEY + sessionId));
        } catch (RuntimeException e) {
            throw new StateStoreUnavailableException();
        }
        if (!consumed) {
            throw new OtpException("OTP_ALREADY_USED",
                    "That code has already been used. Please request a new one.", HttpStatus.CONFLICT);
        }

        try {
            redis.delete(ATTEMPT_KEY + sessionId);
        } catch (RuntimeException e) {
            log.warn("Could not clear OTP attempt counter after verification");
        }
    }

    public long ttlSeconds() {
        return props.getOtp().getTtlSeconds();
    }

    public long resendCooldownSeconds() {
        return props.getOtp().getResendCooldownSeconds();
    }

    /**
     * nextInt(bound) covers the full space including codes with leading zeros. The common
     * "10000 + nextInt(90000)" idiom silently discards 10% of it.
     */
    private String generateCode() {
        int digits = props.getOtp().getLength();
        int bound = (int) Math.pow(10, digits);
        return String.format("%0" + digits + "d", secureRandom.nextInt(bound));
    }

    private String hash(String saltHex, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    props.getOtpPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(HexFormat.of().parseHex(saltHex));
            mac.update(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException("OTP hashing unavailable", e);
        }
    }

    private long incrementAttempts(String sessionId) {
        try {
            Long attempts = redis.opsForValue().increment(ATTEMPT_KEY + sessionId);
            if (attempts == null) {
                throw new StateStoreUnavailableException();
            }
            if (attempts == 1L) {
                redis.expire(ATTEMPT_KEY + sessionId,
                        Duration.ofSeconds(props.getOtp().getTtlSeconds() + 10));
            }
            return attempts;
        } catch (StateStoreUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new StateStoreUnavailableException();
        }
    }

    private void enforceCooldown(String sessionId) {
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(COOLDOWN_KEY + sessionId, "1",
                    Duration.ofSeconds(props.getOtp().getResendCooldownSeconds()));
        } catch (RuntimeException e) {
            throw new StateStoreUnavailableException();
        }
        if (acquired == null) {
            throw new StateStoreUnavailableException();
        }
        if (!acquired) {
            throw new OtpException("OTP_RESEND_COOLDOWN",
                    "Please wait before requesting another code.", HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    /**
     * Caps codes per agreement per hour. This is what stops an attacker flooding a
     * borrower's phone, and stops anyone burning the SMS budget by iterating agreement
     * numbers. Keyed on a hash so a Redis keyspace listing does not enumerate the book.
     */
    private void enforceSendBudget(String agreementNumber) {
        String key = SEND_COUNT_KEY + Hashing.keyHash(agreementNumber);
        try {
            Long sends = redis.opsForValue().increment(key);
            if (sends == null) {
                throw new StateStoreUnavailableException();
            }
            if (sends == 1L) {
                redis.expire(key, Duration.ofHours(1));
            }
            if (sends > props.getOtp().getMaxSendsPerAgreementPerHour()) {
                log.warn("OTP send budget exhausted for agreement {}", Mask.agreement(agreementNumber));
                throw new OtpException("OTP_SEND_LIMIT",
                        "Too many code requests for this account. Please try again later.",
                        HttpStatus.TOO_MANY_REQUESTS);
            }
        } catch (OtpException | StateStoreUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new StateStoreUnavailableException();
        }
    }

    private void assertNotLocked(String sessionId) {
        try {
            if (Boolean.TRUE.equals(redis.hasKey(LOCK_KEY + sessionId))) {
                throw new OtpException("OTP_LOCKED",
                        "This session is locked. Please start again in 15 minutes.",
                        HttpStatus.TOO_MANY_REQUESTS);
            }
        } catch (OtpException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new StateStoreUnavailableException();
        }
    }

    private void lockout(String sessionId) {
        try {
            redis.opsForValue().set(LOCK_KEY + sessionId, "1",
                    Duration.ofSeconds(props.getOtp().getLockoutSeconds()));
            redis.delete(OTP_KEY + sessionId);
        } catch (RuntimeException e) {
            log.error("Could not apply OTP lockout");
        }
    }
}
