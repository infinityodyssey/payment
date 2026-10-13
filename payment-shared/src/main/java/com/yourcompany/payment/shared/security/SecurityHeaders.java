package com.yourcompany.payment.shared.security;

public final class SecurityHeaders {

    private SecurityHeaders() {}

    public static final String SESSION_COOKIE = "__Host-PAY_SESSION";
    public static final String SESSION_ID = "X-Session-Id";
    public static final String NONCE = "X-Nonce";
    public static final String TIMESTAMP = "X-Timestamp";
    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String AUTHORIZATION = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";
}
