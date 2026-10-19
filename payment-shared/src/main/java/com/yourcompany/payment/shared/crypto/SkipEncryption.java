package com.yourcompany.payment.shared.crypto;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the handshake, which by definition runs before a session key exists, and the
 * exception handler, whose bodies must be readable without a key.
 *
 * There is no runtime header or query parameter that turns encryption off. The legacy
 * backend honoured an X-Accept-Plaintext request header that its own CORS allow-list
 * advertised, so any caller could ask for borrower data in the clear.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SkipEncryption {
}
