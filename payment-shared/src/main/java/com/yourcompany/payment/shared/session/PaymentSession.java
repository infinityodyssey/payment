package com.yourcompany.payment.shared.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Session state. Lives only in Redis, only under session:state:{sid}.
 *
 * Deliberately absent: the server ECDH private key (discarded immediately after key
 * derivation) and the OTP in any form (its own key, its own TTL). There is no JPA
 * mapping, so no key material is ever written to Oracle and no database backup can
 * decrypt captured traffic.
 *
 * The AES key is NOT here either. It lives under session:key:{sid} so that downstream
 * services can read it without being able to see or modify the state machine.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSession {

    private String sessionId;
    private SessionState state;

    /**
     * Optimistic-concurrency version, incremented on every successful save.
     *
     * A session loaded at version n can only be written back if the stored version is
     * still n. This is what stops one request silently overwriting a transition made by
     * another.
     */
    private long version;

    private String agreementNumber;
    private String customerName;
    private String maskedMobile;

    private String clientFingerprint;

    /** The single token issued for this session. Reusable until the session expires. */
    private String tokenId;
    private Long tokenIssuedAt;

    private Instant createdAt;
    private Instant expiresAt;

    public boolean isExpired() {
        return expiresAt == null || Instant.now().isAfter(expiresAt);
    }

    public boolean isInState(SessionState... allowed) {
        for (SessionState s : allowed) {
            if (s == state) {
                return true;
            }
        }
        return false;
    }
}
