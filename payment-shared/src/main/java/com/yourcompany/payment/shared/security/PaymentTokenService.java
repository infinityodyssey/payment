package com.yourcompany.payment.shared.security;

import com.yourcompany.payment.shared.config.SharedProperties;
import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.util.Mask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues and verifies the payment authorisation token.
 *
 *   PAY1.{tokenId}.{issuedAtEpochMillis}.{base64url(HMAC-SHA256)}
 *
 * The signature covers a length-prefixed encoding of
 *   version | sessionId | agreementNumber | tokenId | issuedAt
 *
 * sessionId and agreementNumber are SIGNED, NOT CARRIED. Verification recomputes the
 * HMAC from the session the request actually arrived on and the agreement the caller is
 * operating on. A token from another session, or presented against another agreement,
 * fails the signature. The binding is cryptographic rather than a value someone reads
 * and compares, which is why downstream services need no session-state access to
 * enforce it.
 *
 * Length-prefixed encoding because plain ":"-separated concatenation is forgeable the
 * moment any field can contain the separator.
 *
 * issue() is called only by auth-service. Every other service calls verify().
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTokenService {

    private static final String VERSION = "PAY1";
    private static final String HMAC_ALG = "HmacSHA256";

    private final SharedProperties props;
    private final SecureRandom secureRandom = new SecureRandom();

    private static final String PARTNER_PREFIX = "PARTNER:";

    /** One token per session. auth-service enforces that with a claim before calling this. */
    public IssuedToken issue(String sessionId, String agreementNumber) {
        byte[] raw = new byte[16];
        secureRandom.nextBytes(raw);
        String tokenId = HexFormat.of().formatHex(raw);
        long issuedAt = Instant.now().toEpochMilli();

        String signature = sign(sessionId, agreementNumber, tokenId, issuedAt);
        String token = String.join(".", VERSION, tokenId, Long.toString(issuedAt), signature);

        return new IssuedToken(token, tokenId, issuedAt, props.getSecurity().getTokenTtlSeconds());
    }

    /**
     * Verifies a presented token against the session it arrived on and the agreement
     * being operated on.
     *
     * Cheap structural checks first, then expiry, then the HMAC. Every failure returns
     * the same error code so response differences do not reveal which check failed.
     *
     * Returns the token id so the caller can correlate; throws on any failure.
     */
    public String verify(String presented, String sessionId, String agreementNumber) {
        if (presented == null || presented.isBlank()) {
            throw invalid();
        }

        String[] parts = presented.split("\\.");
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw invalid();
        }

        String tokenId = parts[1];
        if (!tokenId.matches("[0-9a-f]{32}")) {
            throw invalid();
        }

        long issuedAt;
        try {
            issuedAt = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            throw invalid();
        }

        long ageMs = Instant.now().toEpochMilli() - issuedAt;
        long skewMs = props.getSecurity().getClockSkewToleranceSeconds() * 1000L;
        long ttlMs = props.getSecurity().getTokenTtlSeconds() * 1000L;
        if (ageMs > ttlMs || ageMs < -skewMs) {
            throw invalid();
        }

        String expected = sign(sessionId, agreementNumber, tokenId, issuedAt);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                parts[3].getBytes(StandardCharsets.UTF_8))) {
            log.warn("Payment token rejected for session {}", Mask.sessionTail(sessionId));
            throw invalid();
        }

        return tokenId;
    }

    /**
     * Partner tokens use the same format with the partner code in the session-id position
     * and no agreement bound.
     *
     * These two methods exist so the "no agreement" convention is enforced by the method
     * signature rather than by every caller remembering to pass null. One service passing
     * an empty string instead would produce a silent verification failure that looks like
     * a token bug.
     */
    public IssuedToken issuePartnerToken(String partnerCode) {
        return issue(PARTNER_PREFIX + partnerCode, null);
    }

    public String verifyPartnerToken(String presented, String partnerCode) {
        return verify(presented, PARTNER_PREFIX + partnerCode, null);
    }

    private String sign(String sessionId, String agreementNumber, String tokenId, long issuedAt) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(
                    props.getSecurity().getTokenHmacSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            mac.update(lengthPrefixed(VERSION));
            mac.update(lengthPrefixed(sessionId));
            mac.update(lengthPrefixed(agreementNumber == null ? "" : agreementNumber));
            mac.update(lengthPrefixed(tokenId));
            mac.update(lengthPrefixed(Long.toString(issuedAt)));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        } catch (Exception e) {
            throw new ApiException("INTERNAL_ERROR", "Unable to process request",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** 4-byte big-endian length then UTF-8 bytes: unambiguous concatenation. */
    private byte[] lengthPrefixed(String value) {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[4 + body.length];
        out[0] = (byte) (body.length >>> 24);
        out[1] = (byte) (body.length >>> 16);
        out[2] = (byte) (body.length >>> 8);
        out[3] = (byte) body.length;
        System.arraycopy(body, 0, out, 4, body.length);
        return out;
    }

    private ApiException invalid() {
        return new ApiException("PAYMENT_TOKEN_INVALID",
                "Your authorisation has expired. Please verify the code again.", HttpStatus.UNAUTHORIZED);
    }

    public record IssuedToken(String token, String tokenId, long issuedAt, long expiresInSeconds) {}
}
