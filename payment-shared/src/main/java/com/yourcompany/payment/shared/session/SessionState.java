package com.yourcompany.payment.shared.session;

/**
 * Explicit state machine. Only auth-service transitions it; other services never read it.
 *
 * HANDSHAKE_COMPLETE -> OTP_SENT -> OTP_VERIFIED
 *
 * There is no TOKEN_CONSUMED state: one token is issued per session and is reusable for
 * the life of the session. Single-use enforcement was deliberately replaced by
 * idempotent payment initiation in payment-service.
 */
public enum SessionState {
    HANDSHAKE_COMPLETE,
    OTP_SENT,
    OTP_VERIFIED,
    LOCKED
}
