package com.yourcompany.payment.shared.exception;

import org.springframework.http.HttpStatus;

public class OtpException extends ApiException {
    public OtpException(String errorCode, String message, HttpStatus status) {
        super(errorCode, message, status);
    }
}
