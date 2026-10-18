package com.yourcompany.payment.shared.exception;

import org.springframework.http.HttpStatus;

public class CryptoException extends ApiException {
    public CryptoException(String message) {
        super("CRYPTO_ERROR", message, HttpStatus.BAD_REQUEST);
    }
}
