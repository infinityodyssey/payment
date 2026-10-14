package com.yourcompany.payment.auth.client;

/**
 * Dispatch boundary for the OTP.
 *
 * Implementations must not log, persist or forward the code to anything other than the
 * SMS provider. In particular the code never goes on a Kafka topic: a durable,
 * replicated, replayable log is the worst possible home for a single-use secret.
 */
public interface OtpSender {
    void send(String mobileNumber, String otpCode, String agreementNumber);
}
