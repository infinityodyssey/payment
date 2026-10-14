package com.yourcompany.payment.shared.exception;

import org.springframework.http.HttpStatus;

/** An upstream failed. The cause is logged and audited, never returned to the caller. */
public class UpstreamException extends ApiException {

    public UpstreamException(String errorCode, String message) {
        super(errorCode, message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    public static UpstreamException lms() {
        return new UpstreamException("LMS_UNAVAILABLE",
                "We could not reach the loan system. Please try again in a few minutes.");
    }

    public static UpstreamException sms() {
        return new UpstreamException("OTP_DISPATCH_FAILED",
                "We could not send the code. Please try again in a few minutes.");
    }
}
