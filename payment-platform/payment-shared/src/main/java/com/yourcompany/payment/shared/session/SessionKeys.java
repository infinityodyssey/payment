package com.yourcompany.payment.shared.session;

/**
 * Redis key layout. Single source of truth for every service.
 *
 * The split between state and key is the contract that makes the service boundaries
 * safe:
 *
 *   session:state:{sid}   the state machine, CAS version, agreement, token id.
 *                         WRITTEN AND READ BY auth-service ONLY.
 *
 *   session:lms:{sid}     the LMS agreement-fetch response for this session.
 *                         Written by auth at OTP dispatch, read by fetch-service so the
 *                         customer-details screen costs no second LMS call. Scoped to one
 *                         session, never to an agreement.
 *
 *   session:key:{sid}     the derived AES-256 key, hex.
 *                         Written by auth, READ by any service that must decrypt an
 *                         envelope. Read-only everywhere else.
 *
 * Both share a TTL and are deleted together, so a service that cannot read the key knows
 * the session is gone without needing to interpret session state.
 *
 * Changing anything here is a coordinated release across every service.
 */
public final class SessionKeys {

    private SessionKeys() {}

    public static final String STATE_PREFIX = "session:state:";
    public static final String KEY_PREFIX = "session:key:";
    public static final String LMS_PREFIX = "session:lms:";
    public static final String CLAIM_PREFIX = "claim:";
    public static final String NONCE_PREFIX = "nonce:";

    public static final String FIELD_DATA = "data";
    public static final String FIELD_VERSION = "version";

    public static final int ID_LENGTH = 68;   // "SES_" + 64 hex characters

    public static String state(String sessionId) {
        return STATE_PREFIX + sessionId;
    }

    public static String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    public static String lms(String sessionId) {
        return LMS_PREFIX + sessionId;
    }

    public static String claim(String sessionId, String operation) {
        return CLAIM_PREFIX + operation + ":" + sessionId;
    }

    public static String nonce(String nonce) {
        return NONCE_PREFIX + nonce;
    }

    /** Cheap structural check before any store round trip. */
    public static boolean isWellFormedId(String sessionId) {
        return sessionId != null
                && sessionId.length() == ID_LENGTH
                && sessionId.startsWith("SES_")
                && sessionId.chars().skip(4)
                    .allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }
}
