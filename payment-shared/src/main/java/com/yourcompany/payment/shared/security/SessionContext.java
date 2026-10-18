package com.yourcompany.payment.shared.security;

/**
 * What a downstream service knows about the caller after the session filter has run.
 *
 * Note what is absent: the state machine, the agreement, the CAS version. A service that
 * is not auth-service never sees session state, only the id and the key needed to
 * decrypt the envelope.
 *
 * The caller must zero sessionKey() when finished — it is live key material.
 */
public record SessionContext(String sessionId, byte[] sessionKey) {

    public static final String ATTRIBUTE = "PAYMENT_SESSION_CONTEXT";
}
