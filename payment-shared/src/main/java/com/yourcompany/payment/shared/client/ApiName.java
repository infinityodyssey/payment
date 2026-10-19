package com.yourcompany.payment.shared.client;

/** Values for LMS_API_LOG.API_NAME. One list, so log queries are stable. */
public final class ApiName {

    private ApiName() {}

    public static final String LMS_TOKEN = "LMS_TOKEN";
    public static final String AGREEMENT_FETCH = "AGREEMENT_FETCH";
    public static final String OTS_AMOUNT = "OTS_AMOUNT";
    public static final String STATUS_CHECK = "STATUS_CHECK";
    public static final String POST_TXN = "POST_TXN";

    public static final String SMS_TOKEN = "SMS_TOKEN";
    public static final String SMS_SEND = "SMS_SEND";
}
