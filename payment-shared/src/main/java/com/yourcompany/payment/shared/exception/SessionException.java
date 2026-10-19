package com.yourcompany.payment.shared.exception;

import org.springframework.http.HttpStatus;

public class SessionException extends ApiException {

    public SessionException(String errorCode, String message) {
        super(errorCode, message, HttpStatus.UNAUTHORIZED);
    }

    public static SessionException notFound() {
        return new SessionException("SESSION_INVALID", "Your session has ended. Please reload the page.");
    }

    public static SessionException wrongState() {
        return new SessionException("SESSION_STATE_INVALID", "This step is not available yet. Please start again.");
    }

    public static SessionException required() {
        return new SessionException("SESSION_REQUIRED", "Your session has ended. Please reload the page.");
    }
}
