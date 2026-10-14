package com.yourcompany.payment.shared.util;

public final class AuditEventType {

    private AuditEventType() {}

    public static final String HANDSHAKE = "AUTH_HANDSHAKE";
    public static final String AGREEMENT_LOOKUP = "AUTH_AGREEMENT_LOOKUP";
    public static final String OTP_ISSUED = "AUTH_OTP_ISSUED";
    public static final String OTP_RESEND = "AUTH_OTP_RESEND";
    public static final String OTP_VERIFY = "AUTH_OTP_VERIFY";
    public static final String TOKEN_ISSUED = "AUTH_TOKEN_ISSUED";
    public static final String PARTNER_TOKEN_ISSUED = "PARTNER_TOKEN_ISSUED";

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";
}
